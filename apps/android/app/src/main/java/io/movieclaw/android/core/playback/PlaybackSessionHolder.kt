package io.movieclaw.android.core.playback

import io.movieclaw.android.core.designsystem.FeedbackBus
import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import io.movieclaw.android.core.model.PlaybackSessionView
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.session.SessionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用级播放会话宿主(M1d):播放不再绑定在播放页 ViewModel 上——
 * 页面退出/重建不影响播放,后台播放由 MediaSessionService 保活。
 * 连接 MediaController 是 Media3 的约定入口:服务随之创建,播放中
 * Media3 自行 startForegroundService 并维护通知。
 */
@Singleton
class PlaybackSessionHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiFactory: ApiFactory,
    private val sessionRepository: SessionRepository,
    private val qualityMemory: QualityMemory,
    private val feedback: FeedbackBus,
    private val qoe: PlaybackQoe,
    private val trickplay: TrickplayProvider,
) {
    sealed interface State {
        data object Idle : State
        data object Preparing : State
        data class Playing(val controller: PlaybackController, val session: PlaybackSessionView) : State
        data class Consent(val reason: String, val costHint: String?) : State
        data class Failed(val message: String, val suggestion: String? = null) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _sessionPlayer = MutableStateFlow<Player?>(null)

    /** 当前 Media3 Player(Exo 实体或 MPV 代理),PlaybackService 据此挂载 MediaSession */
    val sessionPlayer: StateFlow<Player?> = _sessionPlayer.asStateFlow()

    private var controller: PlaybackController? = null
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var rememberedQualityNotice: String? = null

    fun open(target: PlayTarget) {
        val origin = sessionRepository.ui.value.origin
        if (origin == null) {
            _state.value = State.Failed("尚未连接服务器")
            return
        }
        // 新点播先收掉旧会话(与网页端「同文件重开有 stop 兜底」同语义)
        controller?.dispose()
        controller = null
        _state.value = State.Preparing
        scope.launch {
            val api = apiFactory.forOrigin(origin)
            val created = PlaybackController(
                context = context.applicationContext,
                endpoint = MemberPlaybackEndpoint(api),
                deviceId = sessionRepository.deviceId(),
                target = target,
                origin = origin,
                qoe = qoe,
                trickplay = trickplay,
            )
            created.attachApi(api)
            created.onPlayerChanged = { _sessionPlayer.value = it }
            controller = created
            // 画质记忆:同一片名 + 同一网络环境沿上次的选择(iOS QualityMemory)
            val remembered = qualityMemory.remembered(target.mediaItemId, networkOf())
            if (remembered != null) {
                created.applyRememberedQuality(remembered.takeIf { it > 0 })
                // iOS:第一帧后提示「已沿用上次的选择」(只提限制性的选择)
                rememberedQualityNotice = remembered
                    .takeIf { it > 0 }
                    ?.let { "${it}p(${networkOf().label})" }
            }
            handle(created.negotiate())
        }
    }

    private suspend fun handle(negotiation: PlaybackController.Negotiation) {
        val active = controller ?: return
        when (negotiation) {
            is PlaybackController.Negotiation.Rejected ->
                _state.value = State.Failed(negotiation.reason.ifEmpty { "无法播放" }, negotiation.suggestion)
            is PlaybackController.Negotiation.Consent ->
                _state.value = State.Consent(negotiation.reason, negotiation.costHint)
            is PlaybackController.Negotiation.Ready -> {
                active.start(negotiation.session)
                _state.value = State.Playing(active, negotiation.session)
                connectMediaController()
                rememberedQualityNotice?.let { choice ->
                    rememberedQualityNotice = null
                    feedback.info("已沿用上次的选择:画质 $choice")
                }
            }
        }
    }

    /** 换画质走的是「重开会话」,结果由调用方回填(与内部 handle 同构) */
    fun publishPlaying(controller: PlaybackController, session: PlaybackSessionView) {
        _state.value = State.Playing(controller, session)
    }

    fun publishConsent(reason: String, costHint: String?) {
        _state.value = State.Consent(reason, costHint)
    }

    fun publishFailed(message: String, suggestion: String?) {
        _state.value = State.Failed(message, suggestion)
    }

    /**
     * 访客播放:显式指定服务器与分享 slug,走按 slug 收窄的公开播放通道;
     * 不读写任何成员态(进度由服务端访客通道保存)。
     */
    fun openGuest(origin: String, slug: String, target: PlayTarget) {
        controller?.dispose()
        controller = null
        _state.value = State.Preparing
        scope.launch {
            val api = apiFactory.forOrigin(origin)
            val created = PlaybackController(
                context = context.applicationContext,
                endpoint = GuestPlaybackEndpoint(api, slug),
                deviceId = "guest",
                target = target,
                origin = origin,
                qoe = qoe,
                trickplay = trickplay,
            )
            created.attachApi(api)
            created.onPlayerChanged = { _sessionPlayer.value = it }
            controller = created
            handle(created.negotiate())
        }
    }

    fun grantConsent() {
        scope.launch { controller?.let { handle(it.grantConsent()) } }
    }

    fun retryWithoutSubtitle() {
        scope.launch { controller?.let { handle(it.retryWithoutSubtitle()) } }
    }

    /** 关闭播放:先冲刷进度,再断开控制器(服务随之后台停用) */
    fun exit() {
        controller?.dispose()
        controller = null
        _sessionPlayer.value = null
        _state.value = State.Idle
        mediaControllerFuture?.let { MediaController.releaseFuture(it) }
        mediaControllerFuture = null
    }

    /** 记录用户主动选择的画质(只记限制性选择;等于自动时记 0) */
    fun rememberQuality(mediaItemId: Long, capHeight: Int?) {
        scope.launch { qualityMemory.remember(mediaItemId, networkOf(), capHeight) }
    }

    fun networkOf(): PlaybackNetwork {
        val origin = sessionRepository.ui.value.origin ?: return PlaybackNetwork.UNKNOWN
        return PlaybackNetwork.of(android.net.Uri.parse(origin).host)
    }

    fun isPlaying(): Boolean = when (val current = _state.value) {
        is State.Playing -> current.controller.isPlaying()
        else -> false
    }

    private fun connectMediaController() {
        if (mediaControllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        mediaControllerFuture = MediaController.Builder(context, token).buildAsync()
    }
}
