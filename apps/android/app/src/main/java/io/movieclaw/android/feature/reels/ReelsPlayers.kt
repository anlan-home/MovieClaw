package io.movieclaw.android.feature.reels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.network.dataOrThrow
import io.movieclaw.android.core.playback.PlaybackNetwork
import io.movieclaw.android.core.model.PlaybackSessionRequest
import io.movieclaw.android.core.model.ReelItemView
import io.movieclaw.android.core.playback.DeviceCapability
import io.movieclaw.android.core.playback.EngineSource
import io.movieclaw.android.core.playback.ExoEngine
import io.movieclaw.android.core.playback.ReelsQuality
import io.movieclaw.android.core.playback.SourceByteCache
import io.movieclaw.android.core.playback.SubtitleCues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一条片段怎么放：直出与转码两条路统一成同一个形状。
 *
 * - **直出**（局域网默认）：服务端按文件签发的令牌，播原片字节；引擎起点就是片段起点，
 *   终点就是片段终点，播放器位置 = 原片时间。
 * - **转码**（外网 / 显式限了画质）：借正片的播放会话说一份 720p 流；会话从起点开始，
 *   流时间 0 = 原片 `segment.startMs`。播放器位置 + [fileOffsetMs] = 原片时间。
 */
data class ReelSource(
    val url: String,
    val hls: Boolean,
    /** 引擎起播位置（播放器时间） */
    val playerStartMs: Long,
    /** 引擎停下的位置（播放器时间） */
    val playerEndMs: Long,
    /** 播放器时间 → 原片时间 的偏移（用于进度、字幕、事件） */
    val fileOffsetMs: Long,
    val cacheKey: String?,
    val audioRef: String?,
    /** 转码会话 id；直出为 null。离开这条时要停掉，播放中每 15 秒续命 */
    val sessionId: String? = null,
)

/**
 * 刷片的播放器池 —— iOS `ReelsStore`（player / standby / prefetchTasks）的对应物。
 *
 * 三件事：
 *  1. **字节预取**：当前条出画后，把后几条的 `play.prefetch` 范围下进片源字节缓存
 *     （非计费网络 3 条，计费 1 条；第一条全量，后面的只取文件头与索引）；
 *  2. **预起下一条**：局域网直出时，当前条出画 1 秒且下一条字节就绪后，另建一个引擎
 *     把下一条装载到片段起点停着，滑过去只需「播放」（省掉建引擎、探测、出第一帧）；
 *  3. **画质**：按「网络环境 × 用户选的档位」决定直出还是借转码会话，会话播放中续命。
 *
 * 为什么播放器由这里（而不是每一页）持有：预起的那条属于**下一条**，而它的引擎是在
 * 当前这条还活着的时候建的——放在页面里就会随当前页被滑走而一起销毁。这里按节点换手，
 * 页面只负责把画面挂上去。
 */
class ReelsPlayers(
    private val context: Context,
    private val apiFactory: ApiFactory,
    private val scope: CoroutineScope,
    private val originProvider: () -> String?,
    /** 本机 device id（会话归属与活动页的「正在播放」用，同正片） */
    private val deviceIdProvider: suspend () -> String? = { null },
    /** 事件上报（impression / first_frame / complete / leave / fail …） */
    private val onEvent: (ReelItemView, String, Long?, Long?, Long?, String?) -> Unit,
) {

    /* ---------------- 界面要读的状态（Compose state） ---------------- */

    /** 当前条已出画（它的画面可以直接显示；没出画时页面垫封面） */
    var frameReadyId by mutableStateOf<String?>(null)
        private set

    /** 预起的下一条已经装载好（滑过去直接播） */
    var standbyReadyId by mutableStateOf<String?>(null)
        private set

    var playing by mutableStateOf(false)
        private set

    /** 放到片段终点停下了（页面上给重播键） */
    var ended by mutableStateOf(false)
        private set

    /** 播放器位置（**原片时间**，含 fileOffsetMs） */
    var positionMs by mutableLongStateOf(0L)
        private set

    var failMessage by mutableStateOf<String?>(null)
        private set

    /** 这一条的字幕（服务端给的窗口文件，文件时间轴） */
    var cues by mutableStateOf<List<SubtitleCues.Cue>>(emptyList())
        private set

    /** 画面该挂哪个引擎：当前条的引擎（页面用 state 读它，换引擎会重组重绑） */
    var currentEngine by mutableStateOf<ExoEngine?>(null)
        private set

    /* ---------------- 内部 ---------------- */

    private class Clip(
        val item: ReelItemView,
        val engine: ExoEngine,
        val source: ReelSource,
        var pollJob: Job? = null,
        var subtitleJob: Job? = null,
        var hasFirstFrame: Boolean = false,
        var startedAtMs: Long = 0L,
        var watchedMs: Long = 0L,
    )

    private var current: Clip? = null
    private var standby: Clip? = null
    private var standbyJob: Job? = null
    private var pingJob: Job? = null

    /** 预取任务：键 = "条目id#full|light"（同 iOS） */
    private val prefetchJobs = mutableMapOf<String, Job>()

    private var qualityCap: Int = ReelsQuality.AUTO
    private var network: PlaybackNetwork = PlaybackNetwork.UNKNOWN

    fun setQuality(cap: Int) {
        qualityCap = cap
    }

    fun setNetwork(n: PlaybackNetwork) {
        network = n
    }

    fun currentItemId(): String? = current?.item?.id

    fun engineFor(itemId: String): ExoEngine? {
        if (current?.item?.id == itemId) return current?.engine
        // 预起的那条：**装载好就把画面给它**——页面挂上 surface 它才出得了第一帧；
        // 等 standbyReadyId 再给就成了鸡生蛋（没 surface 永远不出帧）
        if (standby?.item?.id == itemId) return standby?.engine
        return null
    }

    fun isPlaying(): Boolean = playing

    /* ---------------- 换条（滑动停稳后调用） ---------------- */

    /**
     * 当前条换成 [item]：下一条预起好了就直接接着播，否则现起（必要时先协商转码会话）。
     * 旧引擎先停声、500ms 后再拆（拆引擎在主线程要几十毫秒，不能赶在滑动收尾那一刻）。
     */
    fun settle(item: ReelItemView, index: Int, items: List<ReelItemView>) {
        if (current?.item?.id == item.id) return
        leaveCurrent(deferTeardown = true)
        val impressionAt = android.os.SystemClock.elapsedRealtime()
        onEvent(item, "impression", null, null, item.segment.startMs, null)

        val ready = standby?.takeIf { it.item.id == item.id && it.source.playerStartMs >= 0 }
        if (ready != null) {
            standby = null
            standbyReadyId = null
            adopt(ready, item, impressionAt)
            ready.engine.setPlaying(true)
            playing = true
            ended = false
            if (ready.hasFirstFrame) {
                frameReadyId = item.id
                afterFirstFrame(item, index, items)
            }
        } else {
            dropStandby()
            scope.launch {
                val source = openSource(item) ?: run {
                    failMessage = "这一条缺少取流地址"
                    onEvent(item, "fail", null, null, null, "no_stream_url")
                    return@launch
                }
                if (current?.item?.id == item.id) return@launch    // 又滑走了
                val engine = ExoEngine(context)
                val clip = Clip(item = item, engine = engine, source = source)
                current = clip
                currentEngine = engine
                frameReadyId = null
                failMessage = null
                ended = false
                attach(clip, impressionAt)
                engine.open(engineSource(source, item), emptyList())
                startPing(source.sessionId)
            }
        }
        schedulePrefetch(index)
    }

    private fun engineSource(source: ReelSource, item: ReelItemView, autoplay: Boolean = true) = EngineSource(
        url = source.url,
        hls = source.hls,
        startPositionMs = source.playerStartMs,
        autoplay = autoplay,
        initialAudioRef = source.audioRef,
        cacheKey = source.cacheKey,
        title = item.title.name,
        subtitle = item.title.episode?.name,
        artworkUrl = item.title.posterUrl,
    )

    /**
     * 这一条要放什么：直出（局域网默认）还是转码（外网 / 限了画质）。
     * 转码走正片的 `POST /playback/sessions`，`startMs` 直接给片段起点——服务端从那儿切。
     */
    private suspend fun openSource(item: ReelItemView): ReelSource? {
        val origin = originProvider() ?: return null
        val cap = ReelsQuality.effectiveCap(qualityCap, network)
        if (cap == null) return directSource(item, origin)
        return runCatching {
            val api = apiFactory.forOrigin(origin)
            val view = api.startPlaybackSession(
                PlaybackSessionRequest(
                    fileId = item.segment.fileId.takeIf { it > 0 },
                    mediaItemId = item.title.mediaItemId.takeIf { it > 0 },
                    seasonNumber = item.title.episode?.season ?: 0,
                    episodeNumber = item.title.episode?.episode ?: 0,
                    // 限了上限就不报 universal：报了服务端会把原文件直通回来，上限形同虚设
                    capability = DeviceCapability.probe(context, universal = false),
                    maxHeight = cap,
                    deviceId = runCatching { deviceIdProvider() }.getOrNull(),
                    startMs = item.segment.startMs,
                    client = "android",
                )
            ).dataOrThrow().let { it }
            val raw = if (view.timeline == "file") (view.masterUrl ?: view.streamUrl) else view.streamUrl
            if (raw.isNullOrEmpty()) return@runCatching directSource(item, origin)
            val url = if (raw.startsWith("http")) raw else origin.trimEnd('/') + raw
            // 服务端也可能直出（源本来就在上限之内）：没有 session_id 就是直出语义
            val direct = view.sessionId == null
            val hls = view.timeline == "file" || url.substringBefore('?').endsWith(".m3u8")
            ReelSource(
                url = url,
                hls = hls,
                playerStartMs = if (direct) item.segment.startMs else 0L,
                playerEndMs = if (direct) item.segment.endMs else (item.segment.endMs - item.segment.startMs),
                fileOffsetMs = if (direct) 0L else item.segment.startMs,
                cacheKey = if (direct) SourceByteCache.key(item.segment.fileId, item.play.sizeBytes) else null,
                audioRef = item.play.audioOrdinal?.let { "embedded:$it" },
                sessionId = view.sessionId,
            )
        }.getOrElse { directSource(item, origin) }
    }

    private fun directSource(item: ReelItemView, origin: String): ReelSource? {
        val raw = item.play.streamUrl ?: return null
        val url = if (raw.startsWith("http")) raw else origin.trimEnd('/') + raw
        return ReelSource(
            url = url,
            hls = url.substringBefore('?').endsWith(".m3u8"),
            playerStartMs = item.segment.startMs,
            playerEndMs = item.segment.endMs,
            fileOffsetMs = 0L,
            cacheKey = SourceByteCache.key(item.segment.fileId, item.play.sizeBytes),
            audioRef = item.play.audioOrdinal?.let { "embedded:$it" },
        )
    }

    /** 把引擎的监听、位置轮询、字幕挂上（新建的与预起后接手的都走这里） */
    private fun attach(clip: Clip, impressionAt: Long) {
        val engine = clip.engine
        engine.player.addListener(object : Player.Listener {
            private fun firstFrame() {
                if (clip.hasFirstFrame) return
                clip.hasFirstFrame = true
                clip.startedAtMs = android.os.SystemClock.elapsedRealtime()
                frameReadyId = clip.item.id
                onEvent(
                    clip.item, "first_frame", null,
                    clip.startedAtMs - impressionAt, clip.item.segment.startMs, null,
                )
                val index = pendingItems.indexOfFirst { it.id == clip.item.id }
                if (index >= 0) afterFirstFrame(clip.item, index, pendingItems)
            }

            override fun onRenderedFirstFrame() = firstFrame()

            /** 首帧回调万一漏了（surface 刚挂上就追帧），画面尺寸一到也算出了画 */
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) firstFrame()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                failMessage = error.message ?: error.errorCodeName
                onEvent(clip.item, "fail", clip.watchedMs, null, positionMs, error.errorCodeName)
            }
        })

        clip.pollJob = scope.launch {
            while (isActive) {
                delay(250)
                val enginePos = engine.positionMs()
                positionMs = enginePos + clip.source.fileOffsetMs
                if (clip.hasFirstFrame && clip.startedAtMs > 0) {
                    clip.watchedMs = android.os.SystemClock.elapsedRealtime() - clip.startedAtMs
                }
                if (enginePos >= clip.source.playerEndMs) {
                    engine.setPlaying(false)
                    playing = false
                    ended = true
                    onEvent(clip.item, "complete", clip.watchedMs, null, clip.item.segment.endMs, null)
                    break
                }
            }
        }

        // 字幕：窗口抽取的那一小段（整轨要 NAS 通读整个文件，等不到）
        val sub = subtitleUrlFor(clip.item)
        if (sub != null) {
            clip.subtitleJob = scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { java.net.URL(sub).openStream().use { it.readBytes() } }.getOrNull()
                } ?: return@launch
                cues = SubtitleCues.parse(bytes)
            }
        }
    }

    /** 这一条的字幕窗口文件（服务端给的相对路径，含令牌） */
    private fun subtitleUrlFor(item: ReelItemView): String? {
        val raw = item.play.subtitle?.url ?: return null
        if (raw.startsWith("http")) return raw
        val origin = originProvider() ?: return null
        return origin.trimEnd('/') + raw
    }

    /** 换画质档位：当前这条按新档位重开（同正片播放器换档的语义） */
    fun reloadCurrent() {
        val clip = current ?: return
        val item = clip.item
        val index = pendingItems.indexOfFirst { it.id == item.id }
        leaveCurrent(deferTeardown = false)
        if (index >= 0) settle(item, index, pendingItems)
    }

    /** 出画之后：排字节预取，再排下一条的预起 */
    private fun afterFirstFrame(item: ReelItemView, index: Int, items: List<ReelItemView>) {
        schedulePrefetch(index)
        scheduleStandby(index, items)
    }

    // 当前信息流（预起与预取都按它算「下一条」）
    private var pendingItems: List<ReelItemView> = emptyList()

    fun setItems(items: List<ReelItemView>) {
        pendingItems = items
    }

    /* ---------------- 收藏播放控制 ---------------- */

    fun togglePause() {
        val clip = current ?: return
        if (ended) {
            replay()
            return
        }
        playing = !playing
        clip.engine.setPlaying(playing)
    }

    fun pause() {
        if (!playing) return
        playing = false
        current?.engine?.setPlaying(false)
    }

    /** 回到页面：当前这条从片段起点重新起播（iOS `resume`） */
    fun resume() {
        val clip = current ?: return
        if (playing) return
        clip.engine.seekTo(clip.source.playerStartMs)
        ended = false
        playing = true
        clip.engine.setPlaying(true)
    }

    fun replay() {
        val clip = current ?: return
        clip.engine.seekTo(clip.source.playerStartMs)
        ended = false
        playing = true
        clip.engine.setPlaying(true)
        clip.startedAtMs = android.os.SystemClock.elapsedRealtime()
        clip.watchedMs = 0L
    }

    fun seekBy(deltaMs: Long) {
        val clip = current ?: return
        clip.engine.seekBy(deltaMs)
    }

    fun setSpeed(speed: Float) {
        current?.engine?.setSpeed(speed)
    }

    // 从当前位置「看全片」：给正片播放器的起点（原片时间）
    fun filePositionMs(): Long = current?.let { it.engine.positionMs() + it.source.fileOffsetMs } ?: 0L

    /* ---------------- 收尾 ---------------- */

    /** 页面切走 / 退出：停声、拆引擎、停会话、取消预取 */
    fun release() {
        leaveCurrent(deferTeardown = false)
        dropStandby()
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
        pingJob?.cancel()
        pingJob = null
        stopSession()
    }

    private fun leaveCurrent(deferTeardown: Boolean) {
        val clip = current ?: return
        if (!ended && clip.hasFirstFrame) {
            onEvent(clip.item, "leave", clip.watchedMs, null, positionMs, null)
        }
        pingJob?.cancel()
        pingJob = null
        stopSession()
        current = null
        currentEngine = null
        playing = false
        ended = false
        frameReadyId = null
        cues = emptyList()
        clip.pollJob?.cancel()
        clip.subtitleJob?.cancel()
        val engine = clip.engine
        if (deferTeardown) {
            engine.setPlaying(false)
            scope.launch {
                delay(500)
                engine.release()
            }
        } else {
            engine.release()
        }
    }

    /* ---------------- 字节预取 ---------------- */

    private fun schedulePrefetch(index: Int) {
        // 转码档位下这些范围没用（服务端自己读原文件），跳过
        if (ReelsQuality.effectiveCap(qualityCap, network) != null) return
        val window = if (isMetered()) 1 else 3
        val targets = pendingItems.drop(index + 1).take(window)
        val keep = targets.map { it.id }.toSet()
        prefetchJobs.entries.filterNot { it.key.substringBefore('#') in keep }.forEach { (key, job) ->
            job.cancel()
            prefetchJobs.remove(key)
        }
        targets.forEachIndexed { position, item ->
            // 下一条全量；再往后只取文件头与索引（起点后几秒动辄几十 MB，滑不到就白下了）
            val full = position == 0
            val key = "${item.id}#${if (full) "full" else "light"}"
            if (prefetchJobs.containsKey(key) || prefetchJobs.containsKey("${item.id}#full")) return@forEachIndexed
            val raw = item.play.streamUrl ?: return@forEachIndexed
            val url = if (raw.startsWith("http")) raw else (originProvider()?.trimEnd('/') ?: return@forEachIndexed) + raw
            val cacheKey = SourceByteCache.key(item.segment.fileId, item.play.sizeBytes) ?: return@forEachIndexed
            val ranges = item.play.prefetch
                .filter { full || it.purpose != "start" }
                .map { it.offset to it.length }
            if (ranges.isEmpty()) return@forEachIndexed
            prefetchJobs[key] = scope.launch {
                SourceByteCache.prefetch(context, url, cacheKey, ranges)
            }
        }
    }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        return cm.isActiveNetworkMetered
    }

    /* ---------------- 预起下一条 ---------------- */

    /**
     * 局域网直出才预起（外网/转码档位下预起会白占一路带宽与解码器）。等 1 秒让开滑动收尾，
     * 且下一条的字节已预取完（本地读，装载很快）。
     */
    private fun scheduleStandby(index: Int, items: List<ReelItemView>) {
        standbyJob?.cancel()
        val next = items.getOrNull(index + 1) ?: return
        if (standby?.item?.id == next.id) return
        if (ReelsQuality.effectiveCap(qualityCap, network) != null) return
        if (network == PlaybackNetwork.AWAY) return
        val origin = originProvider() ?: return
        val fullKey = "${next.id}#full"
        standbyJob = scope.launch {
            delay(1000)
            prefetchJobs[fullKey]?.join()
            if (current?.item?.id == next.id) return@launch
            val source = directSource(next, origin) ?: return@launch
            val engine = ExoEngine(context)
            val clip = Clip(item = next, engine = engine, source = source)
            standby = clip
            standbyReadyId = null
            engine.player.addListener(object : Player.Listener {
                private fun ready() {
                    if (standby === clip) standbyReadyId = clip.item.id
                }
                override fun onRenderedFirstFrame() = ready()
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) ready()
                }
            })
            // 装载到片段起点停着：不播（滑过去才「播放」）
            engine.open(engineSource(source, next, autoplay = false), emptyList())
        }
    }

    private fun dropStandby() {
        standbyJob?.cancel()
        standbyJob = null
        val clip = standby ?: return
        standby = null
        standbyReadyId = null
        clip.pollJob?.cancel()
        clip.subtitleJob?.cancel()
        clip.engine.release()
    }

    /** 预起好的那条转正当前：把监听与轮询接上（iOS `adopt`） */
    private fun adopt(clip: Clip, item: ReelItemView, impressionAt: Long) {
        current = clip
        currentEngine = clip.engine
        frameReadyId = if (clip.hasFirstFrame) item.id else null
        failMessage = null
        cues = emptyList()
        positionMs = clip.engine.positionMs() + clip.source.fileOffsetMs
        if (clip.hasFirstFrame) {
            clip.startedAtMs = android.os.SystemClock.elapsedRealtime()
            clip.watchedMs = 0L
            onEvent(item, "first_frame", null, null, item.segment.startMs, null)
        }
        // 轮询与字幕在 adopt 之后才需要（预起阶段不产生事件）
        clip.pollJob?.cancel()
        clip.subtitleJob?.cancel()
        attach(clip, impressionAt)
    }

    /* ---------------- 转码会话续命 ---------------- */

    private var liveSessionId: String? = null

    private fun startPing(sessionId: String?) {
        pingJob?.cancel()
        if (sessionId == null) {
            liveSessionId = null
            return
        }
        liveSessionId = sessionId
        pingJob = scope.launch {
            while (isActive) {
                delay(15_000)
                val origin = originProvider() ?: continue
                // 三态同 iOS：只有服务端明确说没了才当没了，请求本身失败不算
                runCatching { apiFactory.forOrigin(origin).pingPlaybackSession(sessionId) }
            }
        }
    }

    private fun stopSession() {
        val id = liveSessionId ?: return
        liveSessionId = null
        val origin = originProvider() ?: return
        scope.launch { runCatching { apiFactory.forOrigin(origin).stopPlaybackSession(id) } }
    }
}
