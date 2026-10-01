package io.movieclaw.android.core.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 把 MPV 内核包成 Media3 Player(SimpleBasePlayer 的官方用途:非 Media3 引擎接入
 * MediaSession)。这样锁屏/通知/媒体键对 MPV 播放同样有效,引擎切换时服务只需
 * mediaSession.player = 本代理 / ExoPlayer。
 *
 * 状态由 500ms 轮询 mpv 属性驱动(SimpleBasePlayer 要求主动 invalidateState)。
 */
class MpvPlayerProxy(
    private val engine: PlayerEngine,
    scope: CoroutineScope,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var playWhenReady = true
    private var speed = 1.0f
    private var mediaItem: MediaItem = MediaItem.Builder().build()

    init {
        scope.launch {
            while (isActive) {
                invalidateState()
                delay(500)
            }
        }
    }

    fun updateMediaItem(item: MediaItem) {
        mediaItem = item
        invalidateState()
    }

    override fun getState(): State {
        val durationMs = engine.durationMs()
        val itemData = MediaItemData.Builder(UID)
            .setMediaItem(mediaItem)
            .setDurationUs(if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
            .setIsSeekable(durationMs > 0)
            .build()
        val playing = engine.isPlaying()
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(listOf(itemData))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(engine.positionMs())
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when {
                    durationMs > 0 -> Player.STATE_READY
                    playing -> Player.STATE_BUFFERING
                    else -> Player.STATE_IDLE
                }
            )
            .setPlaybackParameters(PlaybackParameters(speed))
            .setIsLoading(false)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        engine.setPlaying(playWhenReady)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        engine.seekTo(positionMs)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        engine.setSpeed(speed)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playWhenReady = false
        engine.setPlaying(false)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val UID = "movieclaw-mpv"

        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
