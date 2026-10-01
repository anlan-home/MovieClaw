package io.movieclaw.android.core.playback

import android.content.Context
import io.movieclaw.android.core.api.McApi
import androidx.media3.common.MimeTypes
import io.movieclaw.android.core.model.PlaybackPolicyPatch
import io.movieclaw.android.core.model.PlaybackProgressRequest
import io.movieclaw.android.core.model.PlaybackSessionRequest
import io.movieclaw.android.core.model.PlaybackSessionView
import io.movieclaw.android.core.network.dataOrThrow
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.movieclaw.android.core.playback.mpv.MpvEngine
import io.movieclaw.android.core.playback.mpv.MpvNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

enum class EngineKind { EXO, MPV }

/**
 * 播放会话编排(双内核):
 *   协商(POST /playback/sessions)→ 引擎开播 → start + 10s progress 上报(互斥串行)
 *   → 15s 会话心跳(404 原位重开)→ 退出 stop + 关会话。
 * 引擎策略(设计方案 §6.1):默认 Exo 直连;解码失败且未在 HLS 上 → 自动切 MPV;
 * 两内核都失败才走服务端转码;用户可随时手动切换。引擎所有读写在主线程
 * (ExoPlayer 线程约束),网络调用经 suspend(main-safe)。
 */
class PlaybackController(
    context: Context,
    private val endpoint: PlaybackEndpoint,
    private val deviceId: String,
    val target: PlayTarget,
    private val origin: String = "",
    private val qoe: PlaybackQoe? = null,
    private val trickplay: TrickplayProvider? = null,
) {
    private var attempt: PlaybackQoe.Attempt? = null

    private fun positionMs(): Long = runCatching { engine().positionMs() }.getOrDefault(0L)

    /** 光盘镜像的本机代理地址（协商阶段备好，见 negotiateInternal） */
    private var isoLocalUrl: String? = null

    /** 暴露给 trickplay 的 McApi(成员域/访客域都用同一个实例) */
    private var apiRef: McApi? = null

    fun attachApi(api: McApi) {
        apiRef = api
    }

    private fun endpointSessionApi(): McApi = apiRef ?: error("未注入 McApi")
    private val appContext: Context = context.applicationContext
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engines = mutableMapOf<EngineKind, PlayerEngine>()

    // 默认 **Exo**：硬解、省电；只有 Exo 解不了的容器（ISO/蓝光原盘等）
    // 才切到 mpv 内核。mpv 同时承担解码失败兜底。
    private val _engineKind = MutableStateFlow(EngineKind.EXO)
    val engineKind: StateFlow<EngineKind> = _engineKind.asStateFlow()

    val mpvAvailable: Boolean get() = MpvNative.available

    /** 轨道菜单项:ref = 服务端中性引用(embedded:N / external:<文件> / off) */
    /** [unavailableReason] 非空 = 这条轨本机渲染不了，菜单里置灰并写明原因 */
    data class TrackOption(val ref: String, val label: String, val unavailableReason: String? = null)

    private val _selectedAudioRef = MutableStateFlow<String?>(null)
    val selectedAudioRef: StateFlow<String?> = _selectedAudioRef.asStateFlow()

    private val _selectedSubtitleRef = MutableStateFlow<String?>(null)
    val selectedSubtitleRef: StateFlow<String?> = _selectedSubtitleRef.asStateFlow()

    /** 引擎读到的盘内轨（镜像用）：服务端读不了盘内结构，只能由 mpv 在本机读 */
    private val _engineAudio = MutableStateFlow<List<TrackOption>>(emptyList())
    private val _engineSubtitle = MutableStateFlow<List<TrackOption>>(emptyList())

    fun audioOptions(): List<TrackOption> {
        val tracks = session?.decision?.audioTracks ?: return _engineAudio.value
        // 服务端一条音轨都没给（光盘镜像）：整份用引擎读到的（iOS `engineOptions` 同款）
        if (tracks.isEmpty()) return _engineAudio.value
        return tracks.mapIndexed { index, track ->
            val ref = track.trackRef ?: "embedded:$index"
            TrackOption(ref, TrackLabels.audio(track.language, track.codec, track.channels, ref))
        }
    }

    fun subtitleOptions(): List<TrackOption> {
        val tracks = session?.decision?.subtitles ?: return _engineSubtitle.value
        if (tracks.isEmpty()) return _engineSubtitle.value
        return tracks.mapIndexed { index, subtitle ->
            val ref = subtitle.trackRef ?: "embedded:$index"
            // 两种"别给用户点"的情况：格式本机渲染不了、服务端没给这条轨的地址
            // （subtitle_urls 与 subsutitles 按下标一一对应，缺一个就是那条没地址）
            val hasUrl = session?.subtitleUrls?.getOrNull(index) != null
            TrackOption(
                ref = ref,
                label = TrackLabels.subtitle(subtitle.language, subtitle.kind, ref, subtitle.isAi),
                unavailableReason = TrackLabels.subtitleUnsupportedReason(subtitle.kind)
                    ?: if (hasUrl) null else "服务端没有给出这条轨的地址",
            )
        }
    }

    fun selectAudio(ref: String) {
        _selectedAudioRef.value = ref
        val index = audioOptions().indexOfFirst { it.ref == ref }
        if (index >= 0) engine().selectAudioIndex(index)
        mainScope.launch {
            reportMutex.withLock { runCatching { endpoint.reportProgress(progressRequest("progress", audioTrack = ref)) } }
        }
    }

    fun selectSubtitle(ref: String) {
        _selectedSubtitleRef.value = ref
        val index = subtitleOptions().indexOfFirst { it.ref == ref }
        engine().selectTextIndex(if (ref == "off" || index < 0) null else index)
        mainScope.launch {
            reportMutex.withLock { runCatching { endpoint.reportProgress(progressRequest("progress", subtitleTrack = ref)) } }
        }
    }

    sealed interface Negotiation {
        data class Ready(val session: PlaybackSessionView) : Negotiation
        data class Consent(val reason: String, val costHint: String?) : Negotiation
        data class Rejected(val reason: String, val suggestion: String?) : Negotiation
    }

    private val reportMutex = Mutex()
    private val failedTiers = mutableSetOf<Int>()
    private var droppedSubtitle = false

    private var session: PlaybackSessionView? = null
    private var lastStartMs: Long? = null
    private var progressJob: Job? = null
    private var pingJob: Job? = null
    private var watchJob: Job? = null

    private var currentUrl: String? = null
    private var currentHls = false
    private var currentOffsetMs = 0L
    private var currentSpeed = 1.0f
    private var playCommanded = true
    private var autoSwitchedToMpv = false
    private var mpvProxy: MpvPlayerProxy? = null

    /** 画质上限(用户选择或画质记忆回填;null = 自动) */
    private var qualityCapHeight: Int? = null

    /** 降质建议(iOS QualitySuggestion):一片只提一次,绝不自动切换 */
    data class QualityOffer(val measuredBps: Double?, val requiredBps: Long, val recommendedHeight: Int)

    private val _qualityOffer = MutableStateFlow<QualityOffer?>(null)
    val qualityOffer: StateFlow<QualityOffer?> = _qualityOffer.asStateFlow()

    private var offerProduced = false
    private var startedAtMs = 0L
    private var lastSeekAtMs = 0L
    private var audioGraceUntilMs = 0L

    /** 每秒一条样本:(时刻, 是否卡在中转, 实测 bps) */
    private val stallSamples = ArrayDeque<Triple<Long, Boolean, Double?>>()

    private val _qualityCap = MutableStateFlow<Int?>(null)
    val qualityCap: StateFlow<Int?> = _qualityCap.asStateFlow()

    fun setQualityCap(height: Int?) {
        qualityCapHeight = height
        _qualityCap.value = height
    }

    /** MediaSession 的 player 变化通知(引擎创建/切换时):服务据此 setPlayer */
    var onPlayerChanged: ((androidx.media3.common.Player?) -> Unit)? = null

    /** 当前引擎对应的 Media3 Player(Exo 实体 / MPV 代理),供 MediaSession 使用 */
    fun media3Player(): androidx.media3.common.Player? = when (_engineKind.value) {
        EngineKind.EXO -> (engines[EngineKind.EXO] as? ExoEngine)?.player
        EngineKind.MPV -> mpvProxy
    }

    private fun engine(kind: EngineKind = _engineKind.value): PlayerEngine =
        engines.getOrPut(kind) {
            when (kind) {
                EngineKind.EXO -> ExoEngine(appContext).also { exo ->
                    exo.onDecodeError = { autoSwitchToMpv() }
                }
                EngineKind.MPV -> MpvEngine(appContext).also { mpv ->
                    mpvProxy = MpvPlayerProxy(mpv, mainScope)
                }
            }
        }

    fun exoPlayer(): androidx.media3.exoplayer.ExoPlayer? =
        (engines[EngineKind.EXO] as? ExoEngine)?.player

    fun mpvSurfaceView(): android.view.SurfaceView? =
        (engines[EngineKind.MPV] as? MpvEngine)?.surfaceView

    /** 解码失败自动降级:仅直连场景切 MPV(HLS 由 Exo 原生处理);网络错误不切 */
    private fun autoSwitchToMpv() {
        if (autoSwitchedToMpv || currentHls || !MpvNative.available) return
        autoSwitchedToMpv = true
        mainScope.launch { switchEngine(EngineKind.MPV) }
    }

    /** 播放中切内核:只迁移 position / playing / speed(内核切换契约);轨道重挂在 M1c 轨道管线接管 */
    /**
     * 起播前选内核：此时还没 open 过任何东西，只改状态、不动播放器。
     *
     * 别用 [switchEngine] 做这件事：它会先 release 再 `open(currentUrl)`，
     * 而调用点随后还会自己 open 一次 —— 同一路流被打开两遍（画面会重来一次）。
     */
    /**
     * 外挂 libass 叠层接管字幕时，关掉当前内核自己的字幕渲染（见 PlayerEngine.setSubtitleRendering）。
     * 两个内核的关法不同，UI 不该知道这件事。
     */
    fun setEngineSubtitleRendering(enabled: Boolean) {
        runCatching { engine().setSubtitleRendering(enabled) }
    }

    fun preselectEngine(kind: EngineKind) {
        if (kind == _engineKind.value) return
        if (kind == EngineKind.MPV && !MpvNative.available) return
        engines.remove(_engineKind.value)?.release()
        _engineKind.value = kind
    }

    fun switchEngine(kind: EngineKind) {
        if (kind == _engineKind.value) return
        if (kind == EngineKind.MPV && !MpvNative.available) return
        val previous = engine()
        val position = filePositionMs()
        val playing = previous.isPlaying()
        previous.release()
        engines.remove(_engineKind.value)
        _engineKind.value = kind
        val url = currentUrl ?: return
        val playerMs = (position - currentOffsetMs).coerceAtLeast(0L)
        val next = engine(kind)
        next.open(currentSource(url, playerMs))
        next.setPlaying(playing)
        next.setSpeed(currentSpeed)
        playCommanded = playing
        onPlayerChanged?.invoke(media3Player())
    }

    private fun currentSource(url: String, playerMs: Long) = EngineSource(
        url = url,
        hls = currentHls,
        startPositionMs = playerMs,
        title = target.title,
        subtitle = target.subtitle,
    )

    suspend fun negotiate(): Negotiation = negotiateInternal(startMs = null)

    suspend fun fromBeginning(): Negotiation = negotiateInternal(startMs = 0L)

    private suspend fun negotiateInternal(startMs: Long?): Negotiation {
        lastStartMs = startMs
        val request = PlaybackSessionRequest(
            mediaItemId = target.mediaItemId,
            seasonNumber = target.seasonNumber,
            episodeNumber = target.episodeNumber,
            // 两种情况不报 universal：
            //  · 限了画质上限——报了服务端会直通原文件，上限形同虚设；
            //  · 片源是原盘——本机引擎读不了盘内结构（见 DeviceCapability.probe），
            //    报了会拿到读不了的原始字节，不报则服务端要么明确拒绝、要么拼成 HLS。
            capability = DeviceCapability.probe(
                appContext,
                universal = qualityCapHeight == null && !target.discSource,
            ),
            failedTiers = failedTiers.toList(),
            maxHeight = qualityCapHeight,
            // 详情页选好的起播轨直接随请求带上：服务端 apply 后返回的 plan 就是那两条轨，
            // 播放器起播即按它走（PGS 烧录弹窗那条路不变，被丢字幕时仍报 off）
            audioTrack = target.preferredAudio,
            subtitleTrack = if (droppedSubtitle) "off" else target.preferredSubtitle,
            deviceId = deviceId,
            startMs = startMs,
            client = "android",
        )
        val view = endpoint.startSession(request)
        session = view
        // 光盘镜像：**在协商阶段就把盘内结构读出来**（开卷 + 扫目录是几十次远端小读，
        // 真实耗时几百毫秒到秒级），跑在 IO 线程；start() 直接用结果，不阻塞主线程。
        isoLocalUrl = if (view.decision.disc == "image") {
            val raw = view.streamUrl
            if (raw.isNullOrEmpty()) {
                null
            } else {
                withContext(Dispatchers.IO) { IsoBridge.open(normalizeStreamUrl(raw)) }
                    .also { if (it == null) android.util.Log.w("McPlayer", "ISO 直连失败：盘内结构读不出来") }
            }
        } else {
            IsoBridge.close()
            null
        }
        // 把服务端的判据打出来：档 1=换壳直通、2=换壳+转音轨 都不是"转码"，
        // 但用户看到播放页的档位标签只会以为"又在转码"，这一行能直接指认是哪个输入
        // 顶上去的（视频编码/容器/HDR/音轨/画质上限），排查"为什么全是转码"靠它
        android.util.Log.i(
            "McPlayer",
            "决策 档=${view.decision.tier} 直通=${view.decision.video?.action ?: "-"}" +
                " 音轨=${view.decision.audio?.action ?: "-"} 原因=${view.decision.reason}",
        )
        return when (view.decision.outcome) {
            "rejected" -> Negotiation.Rejected(view.decision.reason, view.decision.suggestion)
            "consent" -> Negotiation.Consent(view.decision.reason, view.decision.costHint)
            else -> Negotiation.Ready(view)
        }
    }

    /** consent「开启并播放」:保存软件转码开关后同一请求重来(failedTiers 保留) */
    suspend fun grantConsent(): Negotiation {
        endpoint.enableSoftwareTranscode()
        return negotiateInternal(startMs = lastStartMs)
    }

    /** 换画质:iOS 语义是「上限」而非目标;从当前位置重开会话让服务端按新档位出流 */
    suspend fun changeQuality(maxHeight: Int?): Negotiation {
        qualityCapHeight = maxHeight
        _qualityCap.value = maxHeight
        val resumeAt = filePositionMs()
        session?.sessionId?.let { runCatching { endpoint.stop(it) } }
        stopLoops()
        return negotiateInternal(startMs = resumeAt)
    }

    /** 起播时回填记忆里的画质(不触发重开,只影响本次协商) */
    fun applyRememberedQuality(height: Int?) {
        qualityCapHeight = height
        _qualityCap.value = height
    }

    /** 当前片源高度(用于判断「限制性选择」) */
    fun sourceHeight(): Int? = session?.decision?.video?.height

    /** PGS 烧录 consent 的自动降级:丢字幕重试一次(对齐 iOS) */
    suspend fun retryWithoutSubtitle(): Negotiation {
        droppedSubtitle = true
        return negotiateInternal(startMs = lastStartMs)
    }

    /**
     * 流地址归一化（起播与 ISO 开卷都要用同一份）：
     *  · 相对路径补上当前服务器地址——会话可能返回 `/api/v1/...`，没有 scheme/host，
     *    ExoPlayer 会直接报 MalformedURLException: no protocol（实机日志抓到过）；
     *  · 主机是 localhost / 127.0.0.1 / 容器内主机名（服务器自己视角）时换成 App 连的那台，
     *    否则手机连不上 → ERROR_CODE_IO_NETWORK_CONNECTION_FAILED（实机日志 2001）。
     */
    private fun normalizeStreamUrl(raw: String): String = runCatching {
        if (!raw.startsWith("http")) {
            val abs = origin.trimEnd('/') + raw
            android.util.Log.i("McPlayer", "流地址补全（相对路径）: " + abs.substringBefore("token=") + "token=…")
            return@runCatching abs
        }
        val u = java.net.URI(raw)
        val o = java.net.URI(origin.trimEnd('/'))
        val localHosts = setOf("localhost", "127.0.0.1", "0.0.0.0")
        val host = u.host
        if (host != null && o.host != null && (host in localHosts || !host.contains('.'))) {
            val port = if (o.port > 0) o.port else u.port
            java.net.URI(o.scheme, null, o.host, port, u.path, u.query, u.fragment).toString()
        } else raw
    }.getOrDefault(raw)

    fun start(view: PlaybackSessionView) {
        val raw = if (view.timeline == "file") (view.masterUrl ?: view.streamUrl) else view.streamUrl
        if (raw.isNullOrEmpty()) return
        val url = normalizeStreamUrl(raw)
        // 档 0 直出（原文件 / 原盘目录）**没有 session_id**：流就是文件本身的字节，
        // 续播点要 seek 过去、时间轴就是文件时间——服务端原话「直出没有会话时间轴，
        // 续播位置由前端 seek 到 watch.position_ms」。有 session_id 的才是转码/换壳会话：
        // 流从 0 起，文件时间 = startMs + 播放器位置。
        // 旧代码只看 timeline，而直出**根本不带这个字段**（默认 "session"），于是把直出
        // 当成"流从 0 起"：续播从头播、时钟与进度上报整体偏移 startMs、seek 也差一个续播点。
        val direct = view.sessionId == null
        val fileTimeline = view.timeline == "file" || direct
        // HLS 只认「会话的 HLS 流」与真 .m3u8 地址：**档 0 直出是原文件的字节流
        // （/playback/files/{id}/stream），是渐进式流而不是 HLS** —— 当成 HLS 交给
        // HlsMediaSource 会解析失败（Exo 报的 "Top bit not zero" 就是这个类别的错）
        val hls = view.timeline == "file" || url.substringBefore('?').endsWith(".m3u8")
        val startPosition = if (fileTimeline) view.startMs else 0L
        // 光盘镜像（ISO）：本机读 UDF 卷、找正片 m2ts、用本地服务暴露成普通流，
        // 再把**本地地址**交给 mpv（Exo 与远端 mpv 都读不了 ISO 原始字节）。
        // 与内核那边的原盘代理判断同路：拿到 /stream.m2ts 就走 mpv。
        val playUrl = isoLocalUrl ?: url
        android.util.Log.i(
            "McPlayer",
            "起播 timeline=${view.timeline} disc=${view.decision.disc ?: "-"}" +
                " start=${view.startMs}ms url=" +
                playUrl.substringBefore('?').take(120) + (if (playUrl.contains('?')) "?…" else ""),
        )
        currentUrl = playUrl
        currentHls = false      // 本地 m2ts 是普通字节流，不是 HLS
        currentOffsetMs = if (fileTimeline) 0L else view.startMs
        playCommanded = true
        // 起播选中轨：
        //  · 音轨以 **decision.audio.trackRef** 为准——那是这份计划真要放的那条
        //    （详情页选的轨随请求发上去，服务端 apply 后就体现在这里）；
        //  · 字幕以**本次请求指定的**为准，没指定才用观看记忆（watch 快照已含"沿用上一集"的结果）。
        // 旧版两个都只看 watch 快照，于是详情页选好的轨"没加载"：计划放的是新的，
        // 界面和叠加层抓的却是记忆里那条。
        _selectedAudioRef.value = view.decision.audio?.trackRef
            ?: target.preferredAudio
            ?: view.watch?.audioTrack
        _selectedSubtitleRef.value = target.preferredSubtitle ?: view.watch?.subtitleTrack
        // 切核策略照搬已验证的 auto：网络流/HLS 走 Exo（硬解最优）；
        // **原盘代理流（.m2ts/.iso/bdmv）与 HDR 内容切 mpv**（格式兼容 + tone-mapping）。
        // 真机实证（2026-09-27）：Exo 播原盘 m2ts 会白等 5 秒才失败回退。
        if (MpvNative.available) {
            val raw = (view.streamUrl ?: view.masterUrl ?: "").lowercase()
            // 原盘判定**不能挂 timeline=file**：直出的原盘没有 timeline 字段，
            // 但它恰恰是最需要 mpv 的那一类（Exo 读不了盘内结构）
            val isIsoLike = view.decision.disc != null || (
                view.timeline == "file" && (
                    raw.contains(".m2ts") || raw.contains(".iso") ||
                        raw.contains("bdmv") || raw.contains("/stream.m2ts")
                    )
                )
            val hdrTag = view.source?.hdr
            val isHdr = hdrTag != null && hdrTag.isNotBlank() && !hdrTag.equals("SDR", true)
            if (isIsoLike || isHdr) {
                android.util.Log.i("McPlayer", "起播选 mpv 内核：isoLike=$isIsoLike hdr=$hdrTag")
                preselectEngine(EngineKind.MPV)
            }
        }
        // 外挂字幕旁挂(仅 Exo 合流;kind→mime 映射,PGS 烧录由服务端决策处理)
        val sidecars = if (!fileTimeline) {
            emptyList()
        } else {
            view.decision.subtitles.mapIndexedNotNull { index, subtitle ->
                if (!subtitle.trackRef?.startsWith("external:").let { it == true }) return@mapIndexedNotNull null
                val sidecarUrl = view.subtitleUrls.getOrNull(index) ?: return@mapIndexedNotNull null
                val mime = when (subtitle.kind) {
                    "vtt" -> MimeTypes.TEXT_VTT
                    "ass", "ssa" -> MimeTypes.TEXT_SSA
                    "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
                    else -> return@mapIndexedNotNull null
                }
                Sidecar(url = sidecarUrl, mime = mime, language = subtitle.language)
            }
        }
        // **必须用 playUrl**：镜像时它是本机 m2ts 代理地址。之前这里仍传原变量 `url`，
        // 于是引擎读的还是 ISO 原始字节（本地服务一个请求都收不到），
        // 表现就是 mpv 反复 mpegts 重同步失败 —— 一度以为是字节或 Range 的问题。
        engine().open(currentSource(playUrl, startPosition), sidecars)
        engine().setSpeed(currentSpeed)
        mpvProxy?.updateMediaItem(
            androidx.media3.common.MediaItem.Builder()
                .setUri(playUrl)
                .setMediaMetadata(mediaMetadataOf(EngineSource(playUrl, hls, startPosition, target.title, target.subtitle)))
                .build()
        )
        onPlayerChanged?.invoke(media3Player())
        trickplay?.let { provider ->
            provider.setOrigin(origin)
            // 文件号**从流地址里取**（与 Web `fetchTrickplay` 同款）：签名 token 是按流地址里
            // 那个文件签的，拿 decision.fileId 有可能不是同一个（多版本 / 原盘），
            // 服务端会按「地址无效」直接 404（实机日志抓到 1523 vs 1658）。
            val fileIdFromUrl = Regex("""/files/(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
            mainScope.launch {
                runCatching {
                    provider.prepare(
                        api = endpointSessionApi(),
                        fileId = fileIdFromUrl ?: view.decision.fileId,
                        streamUrl = url,
                    )
                }
            }
        }
        attempt = qoe?.begin(
            origin = origin,
            mediaItemId = target.mediaItemId,
            seasonNumber = target.seasonNumber,
            episodeNumber = target.episodeNumber,
            engine = _engineKind.value.name.lowercase(),
            tier = view.decision.tier ?: 0,
            degradedFrom = view.decision.degradedFrom,
            fileId = view.decision.fileId,
            hwBackend = view.hwBackend,
        )
        startLoops(view)
        if (view.decision.disc == "image") loadEngineTracks()
    }

    /**
     * 光盘镜像：等 mpv 解析完盘内结构，把它读到的音轨/字幕补成菜单项。
     * 进程内轮询（1.5 秒一次、最多 12 次）：镜像开卷要几秒，太早问就是空。
     */
    private fun loadEngineTracks() {
        mainScope.launch {
            repeat(12) { attempt ->
                val tracks = runCatching { engine().embeddedTracks() }.getOrDefault(EngineTracks())
                if (!tracks.isEmpty) {
                    // 下标即菜单里的 embedded:N——与 selectAudioIndex/selectTextIndex 同一口径
                    _engineAudio.value = tracks.audio.mapIndexed { i, t ->
                        TrackOption("embedded:$i", TrackLabels.audio(t.language, t.codec, t.channels, "embedded:$i"))
                    }
                    _engineSubtitle.value = tracks.subtitle.mapIndexed { i, t ->
                        val kind = kindOfCodec(t.codec)
                        TrackOption("embedded:$i", TrackLabels.subtitle(t.language, kind, "embedded:$i", false))
                    }
                    // mpv 已经选中的那条，同步成界面上的"当前选中"
                    tracks.audio.indexOfFirst { it.selected }.takeIf { it >= 0 }
                        ?.let { _selectedAudioRef.value = "embedded:$it" }
                    tracks.subtitle.indexOfFirst { it.selected }.takeIf { it >= 0 }
                        ?.let { _selectedSubtitleRef.value = "embedded:$it" }
                    android.util.Log.i(
                        "McPlayer",
                        "盘内轨（引擎读出）：音轨 ${tracks.audio.size} 条、字幕 ${tracks.subtitle.size} 条" +
                            "（第 ${attempt + 1} 次查询）",
                    )
                    return@launch
                }
                delay(1500)
            }
            android.util.Log.w("McPlayer", "盘内轨：12 次查询仍未读到")
        }
    }

    /** 字幕编码名 → 服务端那套 kind（vtt/ass/pgs），只用于显示"文本/特效/图形" */
    private fun kindOfCodec(codec: String?): String = when (codec?.lowercase()) {
        "ass", "ssa" -> "ass"
        "hdmv_pgs_subtitle", "pgs", "sup", "dvd_subtitle", "dvb_subtitle" -> "pgs"
        else -> "vtt"
    }

    /**
     * 文件绝对时间位置:上报与「继续观看」的唯一口径。
     *
     * 用 [currentOffsetMs]（起播时按「是不是会话相对流」算好的），**不要再看 `timeline`**：
     * 档 0 直出的响应根本不带这个字段（默认就是 "session"），照它判断会把续播点又加一遍——
     * 时钟、进度条、以及字幕按位置找 cue 全部整体偏移一个"上次看到哪儿"
     * （实机表现：字幕切了却不显示、拖动落点对不上）。
     */
    fun filePositionMs(): Long {
        session ?: return 0L
        val position = runCatching { engine().positionMs() }.getOrDefault(0L).coerceAtLeast(0L)
        return position + currentOffsetMs
    }

    fun fileDurationMs(): Long {
        val current = session ?: return 0L
        val duration = runCatching { engine().durationMs() }.getOrDefault(0L)
        if (duration <= 0L) return current.watch?.durationMs ?: 0L
        // 与 filePositionMs 同一口径：会话相对流才加续播点
        return duration + currentOffsetMs
    }

    fun isPlaying(): Boolean = runCatching { engine().isPlaying() }.getOrDefault(false)

    /** 自然播完判定(Exo 的 STATE_ENDED / MPV 的到达片长) */
    fun isEnded(): Boolean = runCatching {
        when (_engineKind.value) {
            EngineKind.EXO ->
                (engines[EngineKind.EXO] as? ExoEngine)?.player?.playbackState ==
                    androidx.media3.common.Player.STATE_ENDED
            EngineKind.MPV -> {
                val duration = engine().durationMs()
                duration > 0 && engine().positionMs() >= duration - 500
            }
        }
    }.getOrDefault(false)

    /** 自然播完:按片长上报 stop(iOS 在剩余 ≤5s 时把位置吸附到片长,服务端据此标已看) */
    fun reportEnded() {
        attempt?.watchedEnding = true
        val duration = fileDurationMs()
        mainScope.launch {
            reportMutex.withLock {
                runCatching { endpoint.reportProgress(progressRequest("stop", positionMs = duration)) }
            }
        }
    }

    fun setPlaying(playing: Boolean) {
        playCommanded = playing
        engine().setPlaying(playing)
    }

    fun seekToFileMs(fileMs: Long) {
        // 换画质/换引擎后不重置:那是用户主动选择,不是播放异常
        lastSeekAtMs = System.currentTimeMillis()
        attempt?.let { qoe?.noteSeek(it) }
        engine().seekTo((fileMs - currentOffsetMs).coerceAtLeast(0L))
    }

    fun seekBy(deltaMs: Long) {
        lastSeekAtMs = System.currentTimeMillis()
        attempt?.let { qoe?.noteSeek(it) }
        engine().seekBy(deltaMs)
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        engine().setSpeed(speed)
    }

    fun currentSpeed(): Float = currentSpeed

    private fun progressRequest(
        event: String,
        paused: Boolean? = null,
        positionMs: Long = filePositionMs(),
        audioTrack: String? = null,
        subtitleTrack: String? = null,
    ) =
        PlaybackProgressRequest(
            mediaItemId = target.mediaItemId,
            seasonNumber = target.seasonNumber,
            episodeNumber = target.episodeNumber,
            event = event,
            positionMs = positionMs,
            audioTrack = audioTrack,
            subtitleTrack = subtitleTrack,
            fileId = session?.decision?.fileId,
            deviceId = deviceId,
            paused = paused,
        )

    private fun startLoops(view: PlaybackSessionView) {
        stopLoops()
        mainScope.launch {
            reportMutex.withLock { runCatching { endpoint.reportProgress(progressRequest("start")) } }
        }
        progressJob = mainScope.launch {
            while (isActive) {
                delay(10_000)
                if (isPlaying() || playCommanded) {
                    reportMutex.withLock {
                        runCatching { endpoint.reportProgress(progressRequest("progress", paused = !isPlaying())) }
                    }
                }
            }
        }
        startedAtMs = System.currentTimeMillis()
        watchJob = mainScope.launch {
            while (isActive) {
                delay(1_000)
                feedQualityWatchdog()
                attempt?.let { active ->
                    if (active.firstFrameMs == null && positionMs() > 0) qoe?.noteFirstFrame(active)
                    qoe?.noteBuffering(active, engine().isBuffering())
                    qoe?.noteProgress(active, filePositionMs(), fileDurationMs())
                }
            }
        }
        val sessionId = view.sessionId ?: return
        pingJob = mainScope.launch {
            while (isActive) {
                delay(15_000)
                val failure = runCatching { endpoint.ping(sessionId) }.exceptionOrNull()
                if ((failure as? HttpException)?.code() == 404 && isPlaying()) {
                    reopenSession()
                }
            }
        }
    }

    /** 心跳发现会话死亡:从当前文件位置原位重开 */
    private suspend fun reopenSession() {
        val current = filePositionMs()
        stopLoops()
        runCatching { negotiateInternal(startMs = current) }
            .onSuccess { negotiation -> if (negotiation is Negotiation.Ready) start(negotiation.session) }
    }

    private fun stopLoops() {
        progressJob?.cancel()
        pingJob?.cancel()
        watchJob?.cancel()
        progressJob = null
        pingJob = null
        watchJob = null
    }

    /**
     * 降质看门狗(iOS 门限):
     *   开播/seek 后 10 秒宽限不计;5 分钟滚动窗口内 ≥3 次卡顿或累计 ≥45 秒;
     *   实测带宽 < 所需码率 × 0.9 才提;一片只提一次;建议档位取「当前档位以下、
     *   留 20% 余量」的最高档,没有则取最低档。
     */
    private fun feedQualityWatchdog() {
        if (offerProduced) return
        val now = System.currentTimeMillis()
        if (now - startedAtMs < GRACE_MS || now - lastSeekAtMs < GRACE_MS) return
        if (!isPlaying() && !playCommanded) return
        val stalled = runCatching { engine().isBuffering() }.getOrDefault(false)
        val measured = runCatching { (engines[EngineKind.EXO] as? ExoEngine)?.measuredBps() }.getOrNull()
        stallSamples.addLast(Triple(now, stalled, measured))
        while (stallSamples.isNotEmpty() && now - stallSamples.first().first > WINDOW_MS) stallSamples.removeFirst()

        val inWindow = stallSamples.filter { now - it.first <= WINDOW_MS }
        val stallSeconds = inWindow.count { it.second }
        var episodes = 0
        var previous = false
        inWindow.forEach { sample ->
            if (sample.second && !previous) episodes++
            previous = sample.second
        }
        val required = requiredBps() ?: return

        val measuredMedian = median(inWindow.mapNotNull { it.third })
        if (measuredMedian == null) {
            // 没有实测带宽(如 MPV 内核)时没有余量门限可依,两个条件都必须满足才敢提
            if (episodes < MIN_STALLS || stallSeconds < MIN_STALL_SECONDS) return
        } else {
            if (episodes < MIN_STALLS && stallSeconds < MIN_STALL_SECONDS) return
            if (measuredMedian >= required * 0.9) return
        }

        val recommended = recommendHeight(required, measuredMedian) ?: return
        offerProduced = true
        _qualityOffer.value = QualityOffer(measuredMedian, required, recommended)
    }

    /** 需要的码率:优先源文件总码率,其次按当前档位的阶梯估算 */
    private fun requiredBps(): Long? {
        val current = session ?: return null
        current.source?.bitRate?.takeIf { it > 0 }?.let { return it }
        return rungFor(current.decision.video?.height ?: current.source?.height())?.second
    }

    private fun recommendHeight(required: Long, measured: Double?): Int? {
        val current = session ?: return null
        val currentHeight = current.decision.video?.height ?: current.source?.height() ?: return null
        val below = RUNGS.filter { it.first < currentHeight }
        if (below.isEmpty()) return null
        if (measured != null) {
            val sustainable = below.firstOrNull { measured >= it.second * 1.2 }
            if (sustainable != null) return sustainable.first
        }
        return below.last().first
    }

    private fun rungFor(height: Int?): Pair<Int, Long>? =
        height?.let { h -> RUNGS.firstOrNull { it.first == h } ?: RUNGS.lastOrNull { it.first <= h } }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /** 拖动预览取雪碧图切片(未就绪返回 null,界面只显示时间) */
    suspend fun trickplayTile(positionMs: Long): android.graphics.Bitmap? =
        trickplay?.takeIf { it.ready }?.tileAt(positionMs)

    fun dismissQualityOffer() {
        _qualityOffer.value = null
    }

    /** 退出播放:同步取位后异步冲刷 stop + 关会话(scope 不随 ViewModel 死亡,冲刷可完成) */
    fun dispose() {
        val position = filePositionMs()
        val snapshot = session
        stopLoops()
        mainScope.launch {
            reportMutex.withLock { runCatching { endpoint.reportProgress(progressRequest("stop", positionMs = position)) } }
            snapshot?.sessionId?.let { runCatching { endpoint.stop(it) } }
        }
        attempt?.let { qoe?.finish(it) }
        attempt = null
        engines.values.forEach { it.release() }
        engines.clear()
        mpvProxy = null
        onPlayerChanged?.invoke(null)
    }
}

/** 降质建议的档位阶梯(码率取 iOS 提示里的口径) */
private val RUNGS = listOf(
    1080 to 6_000_000L,
    720 to 3_000_000L,
    480 to 1_500_000L,
)
private const val GRACE_MS = 10_000L
private const val WINDOW_MS = 300_000L
private const val MIN_STALLS = 3
private const val MIN_STALL_SECONDS = 45
