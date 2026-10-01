package io.movieclaw.android.core.playback.mpv

import android.view.Surface

/**
 * MPV JNI 桥(libmovieclaw_jni,源码 cpp/mpv_bridge.cpp)。
 * libmp2.so 为 lanplayer 验证过的 libmpv 全量构建(静态编入 libass/fontconfig/ffmpeg),
 * 经 dlopen 加载、dlsym 解析符号。加载失败置 available=false,上层回退 Exo 而非闪退。
 */
object MpvNative {
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("movieclaw_jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** 创建 mpv 核心并绑定 Surface(wid = Surface 全局引用,官方契约) */
    external fun nativeCreate(surface: Surface): Long

    /** 销毁核心(释放 Surface 前必须调用) */
    external fun nativeDestroy(handle: Long)

    /** Surface 被系统重建后重新挂载并恢复 VO */
    external fun nativeAttachSurface(handle: Long, surface: Surface): Boolean

    /** wid=0 + 释放全局引用;必须在 Surface 真正失效前调用 */
    external fun nativeDetachSurface(handle: Long)

    /** 发送 mpv 命令(loadfile/seek/set …) */
    external fun nativeCommand(handle: Long, cmd: String): Boolean

    /** 设置字符串属性 */
    external fun nativeSetProperty(handle: Long, name: String, value: String): Boolean

    /** 读取字符串属性 */
    external fun nativeGetProperty(handle: Long, name: String): String?
}
