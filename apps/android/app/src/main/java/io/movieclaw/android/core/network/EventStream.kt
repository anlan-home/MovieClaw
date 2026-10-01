package io.movieclaw.android.core.network

import io.movieclaw.android.core.session.SessionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

data class SseEvent(val name: String, val data: String, val id: String?)

/**
 * SSE 客户端(实时通道,独立连接池防被图片请求饿死):
 * 断线自动重连并回传 Last-Event-ID(服务端据此续传),终止事件出现后正常收尾不重连。
 * 消费方:/search/torrents/stream(站点搜索)、后续的 /jobs/stream、/sessions/{id}/events。
 */
@Singleton
class EventStream @Inject constructor(
    @LiveChannel private val client: okhttp3.OkHttpClient,
    private val sessionRepository: SessionRepository,
    private val apiFactory: ApiFactory,
) {

    fun reliableEvents(
        path: String,
        terminalEvents: Set<String> = DEFAULT_TERMINAL,
    ): Flow<SseEvent> = flow {
        var lastEventId: String? = null
        var attempt = 0
        while (coroutineContext.isActive) {
            var finished = false
            try {
                streamOnce(path, lastEventId).collect { event ->
                    event.id?.let { lastEventId = it }
                    emit(event)
                    if (event.name in terminalEvents) finished = true
                }
                // 连接自然结束:终止事件则收尾,否则视为断流重连
                if (finished) return@flow
            } catch (t: Throwable) {
                if (finished) return@flow
                attempt++
                if (attempt > MAX_ATTEMPTS) throw t
                delay(backoffMs(attempt))
                continue
            }
            attempt++
            if (attempt > MAX_ATTEMPTS) return@flow
            delay(backoffMs(attempt))
        }
    }

    /** 单次连接:URL/鉴权取自当前活跃服务器 */
    private fun streamOnce(path: String, lastEventId: String?): Flow<SseEvent> = callbackFlow {
        val origin = sessionRepository.ui.value.origin
        val base = origin?.let { apiFactory.apiBaseOf(it) ?: "$it/api/v1" }
        if (base == null) {
            close(ApiException("NO_SERVER", "尚未连接服务器"))
            return@callbackFlow
        }
        val request = Request.Builder()
            .url(base.trimEnd('/') + "/" + path.trimStart('/'))
            .header("Accept", "text/event-stream")
            .apply { lastEventId?.let { header("Last-Event-ID", it) } }
            .build()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                trySend(SseEvent(name = type ?: "message", data = data, id = id))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: ApiException("SSE_FAILED", "实时连接中断(HTTP ${response?.code})"))
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    private fun backoffMs(attempt: Int): Long =
        min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L shl min(attempt, 5)))

    private companion object {
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_ATTEMPTS = 5
        val DEFAULT_TERMINAL = setOf("done", "agent_done", "agent_error", "agent_cancelled")
    }
}
