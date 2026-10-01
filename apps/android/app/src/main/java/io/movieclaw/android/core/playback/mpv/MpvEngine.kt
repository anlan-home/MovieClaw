package io.movieclaw.android.core.playback.mpv

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.movieclaw.android.core.playback.EngineSource
import io.movieclaw.android.core.playback.EngineTrack
import io.movieclaw.android.core.playback.EngineTracks
import io.movieclaw.android.core.playback.PlayerEngine
import io.movieclaw.android.core.playback.Sidecar
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MPV 万能内核(AetherEngine 的 Android 对位):
 *   libmpv(vo=gpu-next + gpu-context=android)直渲 SurfaceView,不经过任何纹理管线;
 *   VC-1/MPEG-2/TrueHD/PGS 本地全解,ASS 特效字幕由内核内置 libass 渲染进视频。
 * Surface 契约与 mpv-android 官方一致(照抄 lanplayer 实证版本):
 *   attach = 全局引用 + wid;detach = wid=0 → vo=null;尺寸唯一入口 = android-surface-size。
 */
class MpvEngine(private val context: Context) : PlayerEngine {

    val surfaceView: SurfaceView = SurfaceView(context)

    private var handle = 0L
    private var surfaceReady = false
    private val pending = ArrayDeque<(Surface) -> Unit>()
    private val cacheDir = File(context.cacheDir, "mpv_stream_cache").apply { mkdirs() }
    private val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val hadHandle = handle != 0L
            surfaceReady = true
            holder.surface?.let { surface ->
                while (pending.isNotEmpty()) pending.removeFirst().invoke(surface)
            }
            if (hadHandle && handle != 0L) {
                MpvNative.nativeAttachSurface(handle, holder.surface)
                MpvNative.nativeSetProperty(handle, "vo", "gpu-next")
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (handle != 0L) {
                MpvNative.nativeSetProperty(handle, "android-surface-size", "${width}x$height")
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            if (handle != 0L) {
                MpvNative.nativeSetProperty(handle, "vo", "null")
                MpvNative.nativeDetachSurface(handle)
            }
        }
    }

    init {
        surfaceView.holder.addCallback(holderCallback)
    }

    private fun setProperty(name: String, value: String) {
        if (handle != 0L) MpvNative.nativeSetProperty(handle, name, value)
    }

    /** 内核选项配方:全部来自 lanplayer 真机实证(每条理由见设计方案 §6.2) */
    private fun applyCoreOptions() {
        setProperty("vo", "gpu-next")
        setProperty("gpu-context", "android")
        setProperty("hwdec", "mediacodec,mediacodec-copy")
        setProperty("osc", "no")
        setProperty("tone-mapping", "auto")
        setProperty("hdr-compute-peak", "no")
        setProperty("scale", "bilinear")
        setProperty("cscale", "bilinear")
        setProperty("dscale", "bilinear")
        setProperty("dither-depth", "no")
        setProperty("video-sync", "audio")
        setProperty("vd", "-magicyuv")
        // 刻意不开 reconnect_on_http_error:302/4xx 签名过期必须上抛换流
        setProperty(
            "stream-lavf-o",
            "timeout=10000000,reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=30",
        )
        setProperty("cache", "yes")
        setProperty("cache-on-disk", "yes")
        setProperty("cache-dir", cacheDir.absolutePath)
        setProperty("demuxer-max-bytes", "67108864")
        setProperty("demuxer-max-back-bytes", "16777216")
        setProperty("demuxer-readahead-secs", "60")
        setProperty("cache-secs", "300")
        setProperty("cache-pause-wait", "3")
        setProperty("cache-pause-initial", "no")
        setProperty("ao", "audiotrack,opensles")
        setProperty("audio-channels", "stereo")
        setProperty("ad", "lavc")
        setProperty("sub-fonts-dir", fontsDir.absolutePath)
    }

    override fun open(source: EngineSource, sidecars: List<Sidecar>) {
        // sidecars:MPV 侧外挂字幕走 sub-add,在 M1c 轨道管线统一接;当前忽略
        runWhenSurface { surface ->
            if (handle == 0L) {
                handle = MpvNative.nativeCreate(surface)
                if (handle != 0L) applyCoreOptions()
            }
            if (handle != 0L) {
                if (surfaceView.width > 0 && surfaceView.height > 0) {
                    MpvNative.nativeSetProperty(
                        handle,
                        "android-surface-size",
                        "${surfaceView.width}x${surfaceView.height}",
                    )
                }
                MpvNative.nativeSetProperty(
                    handle,
                    "start",
                    if (source.startPositionMs > 0) "${source.startPositionMs / 1000.0}" else "none",
                )
                MpvNative.nativeCommand(handle, "loadfile \"${source.url}\" replace")
            }
        }
    }

    private fun runWhenSurface(block: (Surface) -> Unit) {
        val surface = if (surfaceReady) surfaceView.holder.surface else null
        if (surface != null) {
            block(surface)
        } else {
            pending.add(block)
        }
    }

    private fun getSecondsMs(name: String): Long =
        if (handle == 0L) {
            0L
        } else {
            val seconds = MpvNative.nativeGetProperty(handle, name)?.toDoubleOrNull() ?: 0.0
            (seconds * 1000).toLong()
        }

    override fun positionMs(): Long = getSecondsMs("time-pos")

    override fun durationMs(): Long = getSecondsMs("duration")

    override fun isPlaying(): Boolean {
        if (handle == 0L) return false
        return MpvNative.nativeGetProperty(handle, "pause")?.let { it == "no" } ?: false
    }

    override fun setPlaying(playing: Boolean) = setProperty("pause", if (playing) "no" else "yes")

    override fun seekTo(playerMs: Long) {
        if (handle != 0L) {
            MpvNative.nativeCommand(handle, "seek ${(playerMs.coerceAtLeast(0)) / 1000.0} absolute")
        }
    }

    override fun seekBy(deltaMs: Long) {
        if (handle != 0L) {
            MpvNative.nativeCommand(handle, "seek ${deltaMs / 1000.0}")
        }
    }

    override fun setSpeed(speed: Float) = setProperty("speed", "$speed")

    /** track-list JSON 中按类型取轨道 id 序(升序),与服务端枚举序对齐 */
    private fun trackIds(type: String): List<Int> {
        if (handle == 0L) return emptyList()
        val raw = MpvNative.nativeGetProperty(handle, "track-list") ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                if (obj["type"]?.jsonPrimitive?.content == type) obj["id"]?.jsonPrimitive?.intOrNull else null
            }.sorted()
        }.getOrDefault(emptyList())
    }

    override fun embeddedTracks(): EngineTracks {
        if (handle == 0L) return EngineTracks()
        val raw = MpvNative.nativeGetProperty(handle, "track-list") ?: return EngineTracks()
        return runCatching {
            val all = Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val o = element.jsonObject
                val type = o["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (type != "audio" && type != "sub") return@mapNotNull null
                EngineTrack(
                    id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    language = o["lang"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    codec = o["codec"]?.jsonPrimitive?.contentOrNull,
                    channels = o["demux-channel-count"]?.jsonPrimitive?.intOrNull ?: 0,
                    selected = o["selected"]?.jsonPrimitive?.booleanOrNull ?: false,
                ).let { it to type }
            }
            EngineTracks(
                audio = all.filter { it.second == "audio" }.map { it.first }.sortedBy { it.id },
                subtitle = all.filter { it.second == "sub" }.map { it.first }.sortedBy { it.id },
            )
        }.getOrDefault(EngineTracks())
    }

    override fun setSubtitleRendering(enabled: Boolean) {
        if (handle != 0L) {
            MpvNative.nativeSetProperty(handle, "sub-visibility", if (enabled) "yes" else "no")
        }
    }

    override fun selectAudioIndex(index: Int) {
        trackIds("audio").getOrNull(index)?.let { setProperty("aid", "$it") }
    }

    override fun selectTextIndex(index: Int?) {
        if (index == null) {
            setProperty("sid", "no")
            return
        }
        trackIds("sub").getOrNull(index)?.let { setProperty("sid", "$it") }
    }

    /** mpv 的缓冲停顿状态(对应 Exo 的 STATE_BUFFERING) */
    override fun isBuffering(): Boolean =
        handle != 0L && MpvNative.nativeGetProperty(handle, "paused-for-cache") == "yes"

    override fun release() {
        if (handle != 0L) {
            MpvNative.nativeDestroy(handle)
            handle = 0L
        }
        pending.clear()
    }
}
