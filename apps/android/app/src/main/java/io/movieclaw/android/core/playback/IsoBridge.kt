package io.movieclaw.android.core.playback

import android.util.Log

/**
 * ISO 原盘直连（对应 `cpp/iso_native.cpp`，源头是 lanplayer 同一套实现）。
 *
 * 服务端对光盘镜像只按 Range 供原字节（`disc-direct-play.md`），盘内结构由播放器在本机读。
 * 这里做四件事：远端 ISO 按 HTTP Range 读（带预读窗口）→ libudfread 打开 UDF 卷 →
 * 扫 `BDMV/STREAM` 找正片 m2ts → 用本机 127.0.0.1 服务把它以 Range/206 暴露成
 * `http://127.0.0.1:PORT/stream.m2ts`。解码交给 mpv（它是唯一能放这种流的引擎，
 * 与 lanplayer 的 `isIsoProxyStreamUrl` 判断一致）。
 *
 * 用法：`open(isoUrl)?.let { 用返回的本地地址起播 }`；换片/退出时 `close()`。
 */
object IsoBridge {

    private const val TAG = "McIso"

    private val loaded: Boolean = runCatching {
        System.loadLibrary("movieclaw_jni"); true
    }.onFailure { Log.w(TAG, "loadLibrary 失败: ${it.message}") }.getOrDefault(false)

    val available: Boolean get() = loaded

    /** 上次成功探测到的正片（换片时先关再开；同一部片重进会命中原生侧的扫描缓存） */
    @Volatile
    private var currentUrl: String? = null

    /**
     * 打开 ISO 并起本地流服务，返回可交给播放引擎的地址（`http://127.0.0.1:PORT/stream.m2ts`）。
     * 失败返回 null（调用方按"无法播放该镜像"处理）。
     *
     * **会阻塞**：开卷与扫描目录是几十次远端小读（原生侧有 4MB 预读窗口兜着），
     * 真机实测几百毫秒到两秒级，所以调用方要放到 IO 线程。
     */
    fun open(isoUrl: String): String? {
        if (!loaded) return null
        return runCatching {
            nativeOpenIso(isoUrl).also { currentUrl = it }
        }.onFailure { Log.w(TAG, "打开 ISO 失败: ${it.message}") }.getOrNull()
    }

    fun close() {
        if (!loaded) return
        currentUrl = null
        runCatching { nativeCloseIso() }
    }

    private external fun nativeOpenIso(url: String): String?
    private external fun nativeCloseIso()
}
