package io.movieclaw.android.core.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.movieclaw.android.core.network.BuildInfo

/**
 * Exo 内核:硬解主力(省电、低延迟)+ 服务端 HLS 的唯一消费者。
 * 直连走 ProgressiveMediaSource,HLS 走 HlsMediaSource;流 URL 带签名 token 免鉴权头。
 */
class ExoEngine(context: Context) : PlayerEngine {

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    /** 解码类错误回调:直连失败时 PlaybackController 据此自动切 MPV(网络错误不切) */
    var onDecodeError: ((Long) -> Unit)? = null

    /** 真实吞吐采样:数据源每传一段字节就记一次(降质建议的「实测带宽」来源) */
    private val transferMeter = TransferMeter()

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setTransferListener(transferMeter)
        .setUserAgent(BuildInfo.USER_AGENT)

    /** 近 windowMs 的实测吞吐(bps);样本不足返回 null */
    fun measuredBps(windowMs: Long = 30_000L): Double? = transferMeter.measuredBps(windowMs)

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // IO_UNSPECIFIED 既可能是传输问题（换内核没意义），也可能是**数据根本不是
                // Exo 解析器能吃的**（cause 链里是 IllegalStateException/ParserException，
                // 例如 `Top bit not zero: -1024` —— 实机在服务端换封装流上抓到过，表现是
                // 画面起不来、日志只说"不在兜底集合"）。后者换 mpv 就能放，所以按 cause 判定。
                val fallback = error.errorCode in DECODE_ERROR_CODES ||
                    (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED && isFormatLikeError(error))
                android.util.Log.w(
                    "McPlayer",
                    "player error code=${error.errorCode} name=${error.errorCodeName} " +
                        "msg=${error.message} -> ${if (fallback) "切换到 mpv 内核" else "仅记录（不在兜底集合）"}",
                )
                // 把 cause 链打出来：IO 类错误里通常写着失败的具体 URL 与底层异常
                var c: Throwable? = error.cause
                var depth = 0
                while (c != null && depth < 3) {
                    android.util.Log.w("McPlayer", "  cause[$depth] ${c::class.java.simpleName}: ${c.message}")
                    c = c.cause
                    depth++
                }
                if (fallback) onDecodeError?.invoke(error.errorCode.toLong())
            }
        })
    }

    override fun open(source: EngineSource, sidecars: List<Sidecar>) {
        val mediaItem = MediaItem.Builder()
            .setUri(source.url)
            .setMediaMetadata(mediaMetadataOf(source))
            .build()
        val factory: MediaSourceFactory = if (source.hls) {
            HlsMediaSource.Factory(httpFactory)
        } else {
            ProgressiveMediaSource.Factory(httpFactory)
        }
        val primary = factory.createMediaSource(mediaItem)
        val startPosition = source.startPositionMs.coerceAtLeast(0L)
        if (sidecars.isEmpty()) {
            player.setMediaSource(primary, startPosition)
        } else {
            val sources = listOf(primary) + sidecars.mapIndexed { index, sidecar ->
                val configuration = MediaItem.SubtitleConfiguration.Builder(Uri.parse(sidecar.url))
                    .setMimeType(sidecar.mime)
                    .setLanguage(sidecar.language)
                    .setId("sidecar:$index")
                    .build()
                SingleSampleMediaSource.Factory(httpFactory).createMediaSource(configuration, C.TIME_UNSET)
            }
            player.setMediaSource(MergingMediaSource(*sources.toTypedArray()), startPosition)
        }
        player.prepare()
        player.playWhenReady = true
    }

    override fun setSubtitleRendering(enabled: Boolean) {
        // Media3 没有 Player.setTrackTypeDisabled：走轨道选择参数
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
    }

    override fun selectAudioIndex(index: Int) {
        val group = player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .getOrNull(index)
            ?.mediaTrackGroup ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group, 0))
            .build()
    }

    override fun selectTextIndex(index: Int?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (index == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            player.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT }
                .getOrNull(index)
                ?.mediaTrackGroup
                ?.let { builder.setOverrideForType(TrackSelectionOverride(it, 0)) }
        }
        player.trackSelectionParameters = builder.build()
    }

    override fun positionMs(): Long = runCatching { player.currentPosition }.getOrDefault(0L)

    override fun durationMs(): Long = runCatching { player.duration }.getOrDefault(0L).takeIf { it > 0 } ?: 0L

    override fun isPlaying(): Boolean = runCatching { player.isPlaying }.getOrDefault(false)

    override fun setPlaying(playing: Boolean) {
        player.playWhenReady = playing
    }

    override fun seekTo(playerMs: Long) {
        player.seekTo(playerMs.coerceAtLeast(0L))
    }

    override fun seekBy(deltaMs: Long) {
        player.seekTo(player.currentPosition + deltaMs)
    }

    override fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun isBuffering(): Boolean =
        player.playbackState == androidx.media3.common.Player.STATE_BUFFERING

    override fun release() {
        player.release()
    }

    /**
     * cause 链里有没有"解析/格式"类的失败：`UnexpectedLoaderException` 只是外壳，
     * 真正的异常挂在它的 cause 上，所以要往下钻几层看类型名。
     */
    private fun isFormatLikeError(error: PlaybackException): Boolean {
        var c: Throwable? = error
        var depth = 0
        while (c != null && depth < 6) {
            val name = c::class.java.simpleName
            if (c is IllegalStateException ||
                name.contains("Parser") || name.contains("Format") || name.contains("Unsupported")
            ) {
                return true
            }
            c = c.cause
            depth++
        }
        return false
    }

    private companion object {
        val DECODE_ERROR_CODES = setOf(
            // 解码器层
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            // 解析/容器层：Exo 报的「Source error」多半落在这里（DV/HEVC/特殊封装），
            // 之前不在集合里 → 既不切 mpv 也不提示，表现成「点击播放没反应」（实机日志抓到过）
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
    }
}

/** 通知/锁屏展示用的媒体元数据(Exo 与 MPV 代理共用) */
internal fun mediaMetadataOf(source: EngineSource): MediaMetadata =
    MediaMetadata.Builder()
        .setTitle(source.title)
        .setSubtitle(source.subtitle)
        .setArtist(source.subtitle ?: source.title)
        .apply { source.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
        .build()

/** 累计数据源字节数与时间戳,给出一段窗口内的实测吞吐 */
internal class TransferMeter : TransferListener {
    private data class Sample(val at: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, byteCount: Int) {
        if (!isNetwork || byteCount <= 0) return
        val now = System.currentTimeMillis()
        samples.addLast(Sample(now, byteCount.toLong()))
        while (samples.isNotEmpty() && now - samples.first().at > WINDOW_MS) samples.removeFirst()
    }

    @Synchronized
    fun measuredBps(windowMs: Long): Double? {
        val now = System.currentTimeMillis()
        val recent = samples.filter { now - it.at <= windowMs }
        if (recent.size < 3) return null
        val bytes = recent.sumOf { it.bytes }
        val spanMs = (now - recent.first().at).coerceAtLeast(1_000L)
        return bytes * 8.0 * 1000.0 / spanMs
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}
