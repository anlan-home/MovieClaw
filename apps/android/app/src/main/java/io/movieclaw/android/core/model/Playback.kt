package io.movieclaw.android.core.model

import kotlinx.serialization.Serializable

/**
 * 播放协议模型,字段与服务端 schemas/playback.py 一一对应。
 * capability.codec 用归一化编码家族名(h264/hevc/aac…),服务端与 ffprobe codec_name 比对。
 */

@Serializable
data class VideoSupportIn(
    val codec: String,
    val maxHeight: Int = 2160,
    val smooth: Boolean = true,
    val powerEfficient: Boolean = true,
)

@Serializable
data class AudioSupportIn(
    val codec: String,
    val maxChannels: Int = 8,
)

@Serializable
data class ClientCapabilityIn(
    val video: List<VideoSupportIn> = emptyList(),
    val audio: List<AudioSupportIn> = emptyList(),
    val containers: List<String> = emptyList(),
    val hdrPassthrough: Boolean = false,
    val mse: String = "full",
    val isMobile: Boolean = true,
    val nativeHls: Boolean = true,
    /** 全解码引擎(MPV 内核就位后 = true);Exo 单内核如实报 false 走逐项比对 */
    val universal: Boolean = false,
    val discImage: Boolean = false,
    val discFolder: Boolean = false,
)

@Serializable
data class PlaybackDecideRequest(
    val fileId: Long? = null,
    val mediaItemId: Long? = null,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val capability: ClientCapabilityIn,
    val failedTiers: List<Int> = emptyList(),
    val audioTrack: String? = null,
    val subtitleTrack: String? = null,
    val maxHeight: Int? = null,
    val deviceId: String? = null,
    val downlinkBps: Long? = null,
)

/** 服务端 PlaybackSessionRequest 继承 DecideRequest;客户端发扁平 JSON */
@Serializable
data class PlaybackSessionRequest(
    val fileId: Long? = null,
    val mediaItemId: Long? = null,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val capability: ClientCapabilityIn,
    val failedTiers: List<Int> = emptyList(),
    val audioTrack: String? = null,
    val subtitleTrack: String? = null,
    val maxHeight: Int? = null,
    val deviceId: String? = null,
    val downlinkBps: Long? = null,
    /** null = 服务端按观看状态定续播点;显式 0 = 从头播 */
    val startMs: Long? = null,
    val attemptId: String? = null,
    val client: String = "android",
)

@Serializable
data class VideoPlanView(
    val action: String? = null,
    val codec: String? = null,
    val height: Int? = null,
    val toneMap: Boolean = false,
    val bitrateCapBps: Long? = null,
    val burnSubtitle: String? = null,
)

@Serializable
data class AudioPlanView(
    val action: String? = null,
    val trackRef: String? = null,
    val codec: String? = null,
    val channels: Int? = null,
    val downmix: Boolean? = null,
)

@Serializable
data class AudioTrackView(
    // 服务端这条是 `ref`（不是 `track_ref`，与字幕的 SubtitlePlanView 故意不同）：
    // 不加这一行，track_ref 永远解不出来 → 音轨只能退回"按序号猜"的 embedded:N
    @kotlinx.serialization.SerialName("ref")
    val trackRef: String? = null,
    val language: String? = null,
    /** 标签要显示编码（H264 · 5.1 这类），服务端这条字段是真的 */
    val codec: String? = null,
    val channels: Int? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class SubtitlePlanView(
    val trackRef: String? = null,
    val kind: String? = null,
    val language: String? = null,
    val isDefault: Boolean = false,
    val isAi: Boolean = false,
)

@Serializable
data class PlaybackDecisionView(
    val outcome: String,
    val tier: Int? = null,
    val fileId: Long? = null,
    val container: String? = null,
    val video: VideoPlanView? = null,
    val audio: AudioPlanView? = null,
    val audioTracks: List<AudioTrackView> = emptyList(),
    val subtitles: List<SubtitlePlanView> = emptyList(),
    val degradedFrom: Int? = null,
    val disc: String? = null,
    val discPlaylist: String? = null,
    val costHint: String? = null,
    val canSelfEnable: Boolean? = null,
    val reason: String = "",
    val suggestion: String? = null,
)

@Serializable
data class PlaybackStateView(
    val positionMs: Long = 0,
    val played: Boolean = false,
    val playCount: Int = 0,
    val durationMs: Long? = null,
    val audioTrack: String? = null,
    val subtitleTrack: String? = null,
    val endedByAdmin: Boolean = false,
)

@Serializable
data class PlaybackSourceView(
    val container: String? = null,
    val resolution: String? = null,
    val videoCodec: String? = null,
    val hdr: String? = null,
    /** 总码率(bps);探测不出为 null */
    val bitRate: Long? = null,
    val frameRate: Float? = null,
    val sizeBytes: Long? = null,
) {
    /** "3840x2160" → 2160 */
    fun height(): Int? = resolution?.substringAfterLast('x')?.toIntOrNull()
}

@Serializable
data class PlaybackSessionView(
    val decision: PlaybackDecisionView,
    val sessionId: String? = null,
    val streamUrl: String? = null,
    val startMs: Long = 0,
    /** session = 流从 0 起(文件时间 = startMs + position);file = 分片即文件绝对时间 */
    val timeline: String = "session",
    val subtitleUrls: List<String> = emptyList(),
    val masterUrl: String? = null,
    val hwBackend: String? = null,
    val watch: PlaybackStateView? = null,
    /** 源文件客观规格(诊断与降质建议的「需要的码率」来源) */
    val source: PlaybackSourceView? = null,
)

@Serializable
data class PlaybackProgressRequest(
    val mediaItemId: Long,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val event: String = "progress",
    /** null = 没报(视同播完);与报 0(拖回开头)语义不同 */
    val positionMs: Long? = null,
    val audioTrack: String? = null,
    val subtitleTrack: String? = null,
    val fileId: Long? = null,
    val deviceId: String? = null,
    val paused: Boolean? = null,
)

@Serializable
data class PlaybackPolicyPatch(
    val softwareTranscodeEnabled: Boolean,
)

/** QoE 上报载荷(字段与服务端 PlaybackMetricPayload 对齐;未采集项留空/0) */
@Serializable
data class PlaybackMetricPayload(
    val tier: Int = 0,
    val degradedFrom: Int? = null,
    val engine: String = "",
    val hwBackend: String = "",
    val ttffMs: Int? = null,
    val rebufferMs: Long = 0,
    val rebufferCount: Int = 0,
    val seekCount: Int = 0,
    val droppedFrames: Int? = null,
    val totalFrames: Int? = null,
    val watchedMs: Long = 0,
    val attemptId: String? = null,
    val outcome: String = "",
    val mediaItemId: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val origin: String = "",
    val client: String = "android",
    val networkClass: String = "",
    val appVersion: String = "",
    val firstFrameMs: Int? = null,
    val playingMs: Int? = null,
    val userWaitMs: Long = 0,
    val errorKind: String = "",
    val errorCategory: String = "",
    val errorStage: String = "",
)

/** trickplay 雪碧图信息 */
@Serializable
data class TrickplayView(
    val ready: Boolean = false,
    val intervalMs: Int = 0,
    val tileWidth: Int = 0,
    val tileHeight: Int = 0,
    val columns: Int = 0,
    val rows: Int = 0,
    val count: Int = 0,
    val sheets: List<String> = emptyList(),
)

/** GET/POST /playback/marks */
@Serializable
data class PlaybackMarks(
    val played: Boolean = false,
    val isFavorite: Boolean = false,
    /** 整剧 / 整季尚未看完的集数；电影与单集为 null */
    val unplayedCount: Int? = null,
)

@Serializable
data class PlaybackMarksRequest(
    val mediaItemId: Long,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val played: Boolean? = null,
    val favorite: Boolean? = null,
    val deviceId: String? = null,
)
