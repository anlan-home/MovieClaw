package io.movieclaw.android.core.playback

import org.robolectric.RuntimeEnvironment
import io.movieclaw.android.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class PlaybackControllerTest {
    private class Engine : PlayerEngine {
        val opens = mutableListOf<EngineSource>()
        var released = false
        override fun open(source: EngineSource, sidecars: List<Sidecar>) { opens += source }
        override fun positionMs() = 42_000L
        override fun durationMs() = 100_000L
        override fun isPlaying() = !released
        override fun setPlaying(playing: Boolean) {}
        override fun seekTo(playerMs: Long) {}
        override fun seekBy(deltaMs: Long) {}
        override fun setSpeed(speed: Float) {}
        override fun release() { released = true }
    }
    private class Endpoint : PlaybackEndpoint {
        val starts = mutableListOf<PlaybackSessionRequest>()
        val stops = mutableListOf<String>()
        val reports = mutableListOf<PlaybackProgressRequest>()
        override suspend fun startSession(request: PlaybackSessionRequest): PlaybackSessionView {
            starts += request
            return PlaybackSessionView(PlaybackDecisionView("ready", fileId = 5),
                sessionId = "s${starts.size}", streamUrl = "/index.m3u8", startMs = request.startMs ?: 0)
        }
        override suspend fun reportProgress(request: PlaybackProgressRequest): PlaybackStateView? {
            reports += request; return null
        }
        override suspend fun ping(sessionId: String) { if (sessionId == "s1") throw HttpException(Response.error<Any>(404, "expired".toResponseBody())) }
        override suspend fun stop(sessionId: String) { stops += sessionId }
        override suspend fun enableSoftwareTranscode() = true
    }
    @Test fun expiredHeartbeatRecoversAndExitStopsPollingWithoutRecreatingEngine() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val endpoint = Endpoint()
        val engine = Engine()
        val controller = PlaybackController(RuntimeEnvironment.getApplication(), endpoint, "device",
            PlayTarget(1, 1, "movie", "Movie"), "https://example.com", engineFactory = { engine })
        try {
            val ready = controller.negotiate() as PlaybackController.Negotiation.Ready
            controller.start(ready.session)
            assertTrue(engine.opens.single().hls)
            assertFalse(endpoint.starts.single().capability.universal)
            advanceTimeBy(15_001); runCurrent()
            assertEquals(2, endpoint.starts.size)
            assertEquals(42_000L, endpoint.starts[1].startMs)
            assertEquals(2, engine.opens.size)
            controller.dispose(); runCurrent()
            assertTrue(engine.released)
            assertEquals("s2", endpoint.stops.last())
            val requests = endpoint.starts.size
            advanceTimeBy(60_000); runCurrent()
            assertEquals(requests, endpoint.starts.size)
            controller.start(ready.session); runCurrent()
            assertEquals(2, engine.opens.size)
            assertFalse(controller.isPlaying())
        } finally { controller.dispose(); runCurrent(); Dispatchers.resetMain() }
    }
}
