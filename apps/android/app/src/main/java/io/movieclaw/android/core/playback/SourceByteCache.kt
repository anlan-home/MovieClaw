package io.movieclaw.android.core.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import io.movieclaw.android.core.network.BuildInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 片源字节缓存 —— iOS `PlaybackController.sourceCacheKey` + `AetherPlayback.prefetchSource`
 * 的对应物。
 *
 * 两件事共用同一份缓存：
 *  · **播放时顺手下**：引擎的数据源链里挂一层 `CacheDataSource`，放过的字节落盘；
 *  · **刷片预取**：`/reels` 的 `play.prefetch` 给出该取的字节范围（文件头 / 索引 /
 *    起点后几秒），按范围下进去。滑到下一条时那一段已经在本地，起播不用等网络。
 *
 * 键与 iOS 同一口径（文件 id + 大小）：同一个文件在不同入口（正片页 / 刷片 /
 * 「接着看」）下过的字节彼此复用。缓存目录用 `cacheDir`（系统可回收），带 LRU 上限。
 */
@UnstableApi
object SourceByteCache {

    private const val DIR = "source-bytes"

    /** 上限 1.5 GB：一集 4K 大约 20~40 GB，缓存只为「刚看过的那点」服务，不做全片下载 */
    private const val MAX_BYTES = 1_500L * 1024 * 1024

    @Volatile private var instance: Cache? = null

    fun cache(context: Context): Cache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.applicationContext.cacheDir, DIR),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { instance = it }
    }

    /** 缓存键：文件 id + 大小（`fileId` 为 0 或大小未知时不下缓存） */
    fun key(fileId: Long, sizeBytes: Long?): String? {
        if (fileId <= 0L) return null
        return "file-$fileId-${sizeBytes ?: 0L}"
    }

    private fun httpFactory(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setUserAgent(BuildInfo.USER_AGENT)

    /** 引擎侧要挂的那层：读命中缓存、放过的字节顺手写进去 */
    fun playbackFactory(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(httpFactory())
        .setCacheWriteDataSinkFactory(
            androidx.media3.datasource.cache.CacheDataSink.Factory().setCache(cache(context)),
        )
        // 缓存目录写不进去（盘满 / 权限）时别影响播放：直接走网络
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * 按字节范围把一段下进缓存。已缓存的区段由 `CacheDataSource` 跳过，不会重下。
     *
     * @return 实际本次写入的字节数（失败的那几段不计）
     */
    suspend fun prefetch(
        context: Context,
        url: String,
        cacheKey: String,
        ranges: List<Pair<Long, Long>>,
    ): Long = withContext(Dispatchers.IO) {
        if (ranges.isEmpty()) return@withContext 0L
        val dataSource = CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(httpFactory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        var written = 0L
        try {
            for ((offset, length) in ranges) {
                if (length <= 0L) continue
                val spec = DataSpec.Builder()
                    .setUri(url)
                    .setKey(cacheKey)
                    .setPosition(offset)
                    .setLength(length)
                    .build()
                runCatching {
                    CacheWriter(
                        dataSource,
                        spec,
                        ByteArray(CacheWriter.DEFAULT_BUFFER_SIZE_BYTES),
                        null,
                    ).cache()
                }.onSuccess { written += length }
                // 失败（断网 / 令牌过期 / 这条被取消）就到此为止：只是预取，播放时还会再取一遍
                if (!cache(context).isCached(cacheKey, offset, length)) break
            }
        } finally {
            runCatching { dataSource.close() }
        }
        written
    }

    /** 这一段已经在本地了吗（等于「下载完成」，与 iOS `prefetch` 的 await 同义） */
    fun isCached(context: Context, cacheKey: String, ranges: List<Pair<Long, Long>>): Boolean =
        ranges.all { (offset, length) ->
            length <= 0L || cache(context).isCached(cacheKey, offset, length)
        }
}
