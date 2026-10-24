package io.movieclaw.android.core.offline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.network.dataOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线下载器：一个单元一个 Job（串行队列），OkHttp 流式落盘 + Range 续传。
 *
 * 原画档：GET /offline/files/{id}/content（Range 续传）；
 * 转码档：POST /offline/exports 建导出 → 轮询进度（503 + Retry-After = 排队/转码中）→
 * GET /offline/exports/{id}/content 下载；完成后 DELETE 通知服务端回收。
 * 海报与 manifest 在开始时一并落盘，文件夹即完整副本。
 */
@Singleton
class OfflineDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiFactory: ApiFactory,
    private val store: OfflineStore,
) {
    sealed interface TaskState {
        data object Queued : TaskState
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long, val speedBps: Long) : TaskState
        data object Paused : TaskState
        data class Failed(val message: String) : TaskState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _tasks = MutableStateFlow<Map<Long, TaskState>>(emptyMap())
    val tasks: StateFlow<Map<Long, TaskState>> = _tasks.asStateFlow()

    private val jobs = mutableMapOf<Long, Job>()
    @Volatile private var pausedIds = setOf<Long>()

    /** 已经开始过（含暂停/失败）的单元：详情页的下载状态条据此出现，不用回下载页找 */
    private val _known = MutableStateFlow<Map<Long, OfflineStore.Pack>>(emptyMap())
    val known: StateFlow<Map<Long, OfflineStore.Pack>> = _known.asStateFlow()

    /** 清单缓存：暂停后「继续」「重试」不必重新问服务端 */
    private val manifests = java.util.concurrent.ConcurrentHashMap<Long, io.movieclaw.android.core.model.OfflineManifest>()

    fun isBusy(fileId: Long): Boolean = jobs.containsKey(fileId)

    /** 原画：直接下原文件 */
    fun enqueueOriginal(origin: String, pack: OfflineStore.Pack, manifest: io.movieclaw.android.core.model.OfflineManifest) {
        start(origin, pack, manifest, profile = null)
    }

    /** 转码档：建导出 → 轮询 → 下载导出文件 */
    fun enqueueExport(origin: String, pack: OfflineStore.Pack, manifest: io.movieclaw.android.core.model.OfflineManifest, profile: String) {
        start(origin, pack, manifest, profile = profile)
    }

    fun pause(fileId: Long) {
        pausedIds = pausedIds + fileId
        jobs.remove(fileId)?.cancel()
        _tasks.update { it + (fileId to TaskState.Paused) }
    }

    /** 续传：从已落盘字节起 Range 续（转码档 503 排队时从头，服务端支持导出续传） */
    fun resume(origin: String, pack: OfflineStore.Pack, manifest: io.movieclaw.android.core.model.OfflineManifest, profile: String?) {
        pausedIds = pausedIds - pack.fileId
        start(origin, pack, manifest, profile)
    }

    /** 继续 / 重试（详情页状态条用）：清单有缓存就用缓存，没缓存现取一份 */
    fun resumePack(origin: String, pack: OfflineStore.Pack) {
        pausedIds = pausedIds - pack.fileId
        _tasks.update { it + (pack.fileId to TaskState.Queued) }
        scope.launch {
            val manifest = manifests[pack.fileId] ?: runCatching {
                apiFactory.forOrigin(origin).offlineManifest(pack.fileId).dataOrThrow()
            }.getOrNull()
            if (manifest == null) {
                _tasks.update { it + (pack.fileId to TaskState.Failed("拿不到下载信息")) }
                return@launch
            }
            start(origin, pack, manifest, pack.profile.takeIf { it != "original" })
        }
    }

    /** 删除本机副本 / 取消下载（详情页与下载页共用）：只动本机，观看进度保留在服务端 */
    fun deleteLocal(origin: String, pack: OfflineStore.Pack) {
        cancel(origin, pack, exportId = null)
        _known.update { it - pack.fileId }
        manifests.remove(pack.fileId)
    }

    fun cancel(origin: String, pack: OfflineStore.Pack, exportId: String?) {
        pausedIds = pausedIds - pack.fileId
        jobs.remove(pack.fileId)?.cancel()
        _tasks.update { it - pack.fileId }
        exportId?.let { id ->
            scope.launch { runCatching { apiFactory.forOrigin(origin).offlineExportRelease(id) } }
        }
        store.delete(pack.mediaItemId, pack.fileId)
    }

    private fun start(origin: String, pack: OfflineStore.Pack, manifest: io.movieclaw.android.core.model.OfflineManifest, profile: String?) {
        if (jobs.containsKey(pack.fileId)) return
        manifests[pack.fileId] = manifest
        _known.update { it + (pack.fileId to pack) }
        _tasks.update { it + (pack.fileId to TaskState.Queued) }
        jobs[pack.fileId] = scope.launch {
            runCatching { run(origin, pack, manifest, profile) }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure // 暂停：保留已落盘字节
                    android.util.Log.w("OfflineDownload", "下载失败 fileId=${pack.fileId}: ${e.message}", e)
                    _tasks.update { it + (pack.fileId to TaskState.Failed(e.message ?: "下载失败")) }
                }
        }
    }

    private suspend fun run(origin: String, pack: OfflineStore.Pack, manifest: io.movieclaw.android.core.model.OfflineManifest, profile: String?) {
        val api = apiFactory.forOrigin(origin)
        val client = apiFactory.downloadClient(origin)

        // 1) pack.json + 海报先落盘（文件夹即事实来源）
        store.savePack(pack)
        manifest.posterUrl()?.let { poster ->
            runCatching {
                val url = poster.let { raw ->
                    if (raw.startsWith("http")) raw
                    else origin.trimEnd('/') + "/api/v1/images/proxy?url=" + java.net.URLEncoder.encode(raw, "UTF-8") + "&w=600"
                }
                client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.isSuccessful) store.posterFile(pack.mediaItemId, pack.fileId).outputStream().use { out ->
                        resp.body.byteStream().copyTo(out)
                    }
                }
            }
        }

        // 2) 正片
        val video = when (profile) {
            null -> {
                store.videoFile(pack.mediaItemId, pack.fileId, manifest.container)
                downloadWithResume(client, origin, "/api/v1/offline/files/${pack.fileId}/content", pack)
            }
            else -> {
                val created = api.offlineExportCreate(io.movieclaw.android.core.model.OfflineExportRequest(pack.fileId, profile)).dataOrThrow()
                val video = store.videoFile(pack.mediaItemId, pack.fileId, "mp4")
                waitExport(client, origin, created.exportId, pack)
                // contentUrl 已含 /api/v1 前缀（服务端原样给），不要再拼
                downloadWithResume(client, origin, created.contentUrl, pack)
                // 下载完成：通知服务端回收这份导出（只摘本设备，别的设备还要时留着）
                runCatching { api.offlineExportRelease(created.exportId) }
                video
            }
        }

        // 3) 完成：登记副本；状态条随之消失（副本改由「文件」区的本机行代表）
        _tasks.update { it - pack.fileId }
        _known.update { it - pack.fileId }
        store.scan()
    }

    /** 轮询导出：queued/running 继续等（服务端 503 带 Retry-After），done/failed 落定 */
    private suspend fun waitExport(client: okhttp3.OkHttpClient, origin: String, exportId: String, pack: OfflineStore.Pack) {
        val api = apiFactory.forOrigin(origin)
        var polls = 0
        while (true) {
            val state = runCatching { api.offlineExportGet(exportId).dataOrThrow() }.getOrNull()
                ?: throw IllegalStateException("导出查询失败")
            polls++
            when (state.status) {
                "done" -> return
                "failed" -> throw IllegalStateException(state.error ?: "转码失败")
                else -> {
                    // 转码中：状态条写「等待转码」而不是假装 0%（iOS 的 hourglass 同义）
                    _tasks.update { it + (pack.fileId to TaskState.Queued) }
                    withContext(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.delay(2_000) }
                }
            }
        }
    }

    /** 流式落盘 + Range 续传 + 速度/进度发布；暂停 = cancel（保留半成品），下次 Range 续 */
    private fun downloadWithResume(client: okhttp3.OkHttpClient, origin: String, path: String, pack: OfflineStore.Pack): File {
        val target = store.videoFile(pack.mediaItemId, pack.fileId, null).let { f ->
            if (f.name == "video.") File(f.parentFile, "video.mp4") else f
        }
        target.parentFile?.mkdirs()
        var downloaded = if (target.isFile) target.length() else 0L
        val startedAt = System.currentTimeMillis()
        var lastTick = startedAt
        var lastBytes = downloaded

        val request = okhttp3.Request.Builder()
            .url(origin.trimEnd('/') + path)
            .apply { if (downloaded > 0) header("Range", "bytes=$downloaded-") }
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code != 200 && resp.code != 206) {
                throw IllegalStateException("下载失败(HTTP ${resp.code})")
            }
            val body = resp.body ?: throw IllegalStateException("下载失败：空响应")
            val total = if (resp.code == 206) {
                val range = resp.header("content-range") ?: ""
                range.substringAfter('/', "").substringBefore('/').toLongOrNull()?.plus(1) ?: (downloaded + body.contentLength())
            } else body.contentLength()
            target.outputStream().apply { if (downloaded > 0 && resp.code == 206) {} }.let { }
            java.io.FileOutputStream(target, resp.code == 206).use { out ->
                if (resp.code == 200) out.channel.truncate(0) // 服务端不支持 Range：从头重下
                val buf = ByteArray(256 * 1024)
                body.byteStream().let { input ->
                    while (true) {
                        if (Thread.currentThread().isInterrupted || pausedIds.contains(pack.fileId)) {
                            // 暂停：取消协程会到这里（CancellationException 在外层接住），字节留在盘上
                            throw CancellationException("paused")
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick >= 800) {
                            val speed = ((downloaded - lastBytes) * 1000 / (now - lastTick)).coerceAtLeast(0)
                            lastTick = now; lastBytes = downloaded
                            _tasks.update {
                                it + (pack.fileId to TaskState.Downloading(downloaded, total, speed))
                            }
                        }
                    }
                }
            }
        }
        return target
    }
}

/** manifest 里挖海报地址（item.poster_url 优先，其次 backdrop） */
private fun io.movieclaw.android.core.model.OfflineManifest.posterUrl(): String? {
    val item = item ?: return null
    val obj = item as? kotlinx.serialization.json.JsonObject ?: return null
    return (obj["poster_url"] ?: obj["backdrop_url"])
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        ?: (obj["poster_url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
