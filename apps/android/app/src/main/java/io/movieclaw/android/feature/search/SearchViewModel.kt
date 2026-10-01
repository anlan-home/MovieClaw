package io.movieclaw.android.feature.search

import io.movieclaw.android.core.designsystem.FeedbackTone
import io.movieclaw.android.core.designsystem.McNotice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.movieclaw.android.core.model.DiscoveredTitle
import io.movieclaw.android.core.model.DownloadSubmitRequest
import io.movieclaw.android.core.model.LibraryItemView
import io.movieclaw.android.core.model.SearchHistoryItem
import io.movieclaw.android.core.model.SearchStreamDone
import io.movieclaw.android.core.model.SearchStreamStart
import io.movieclaw.android.core.model.SiteStreamError
import io.movieclaw.android.core.model.SiteStreamResult
import io.movieclaw.android.core.model.TorrentCategory
import io.movieclaw.android.core.model.TorrentHit
import io.movieclaw.android.core.model.TitleSearchRequest
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.network.EventStream
import io.movieclaw.android.core.network.dataOrThrow
import io.movieclaw.android.core.network.friendlyMessage
import io.movieclaw.android.core.session.SessionRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

enum class SearchMode(val label: String) {
    TITLES("标题"),
    TORRENTS("资源"),
    LIBRARY("库内"),
}

/** 一个站点的流式结果块(边收边渲染) */
data class SiteBlock(
    val siteId: String,
    val siteName: String,
    val items: List<TorrentHit> = emptyList(),
    val error: String? = null,
    val elapsedMs: Int = 0,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val apiFactory: ApiFactory,
    private val eventStream: EventStream,
    private val sessionRepository: SessionRepository,
    private val json: Json,
) : ViewModel() {

    data class UiState(
        val mode: SearchMode = SearchMode.TORRENTS,
        val query: String = "",
        val category: TorrentCategory? = null,
        val searching: Boolean = false,
        val error: String? = null,
        val notice: McNotice? = null,
        // 站点搜索(流式)
        val start: SearchStreamStart? = null,
        val blocks: List<SiteBlock> = emptyList(),
        val done: SearchStreamDone? = null,
        // 标题搜索
        val titles: List<DiscoveredTitle> = emptyList(),
        // 库内搜索
        val libraryGroups: List<Pair<String, List<LibraryItemView>>> = emptyList(),
        val history: List<SearchHistoryItem> = emptyList(),
        val submitting: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    val origin: String? get() = sessionRepository.ui.value.origin

    private var searchJob: Job? = null

    init {
        loadHistory()
    }

    fun onMode(mode: SearchMode) = _ui.update { it.copy(mode = mode, error = null) }
    fun onQuery(value: String) = _ui.update { it.copy(query = value) }
    fun onCategory(category: TorrentCategory?) = _ui.update { it.copy(category = category) }
    fun consumeNotice() = _ui.update { it.copy(notice = null) }

    fun loadHistory() {
        viewModelScope.launch {
            val origin = origin ?: return@launch
            runCatching { apiFactory.forOrigin(origin).searchHistory().dataOrThrow() }
                .onSuccess { list -> _ui.update { it.copy(history = list) } }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            val origin = origin ?: return@launch
            runCatching { apiFactory.forOrigin(origin).clearSearchHistory() }
            _ui.update { it.copy(history = emptyList()) }
        }
    }

    fun submit(keywordOverride: String? = null) {
        val state = _ui.value
        val keyword = (keywordOverride ?: state.query).trim()
        if (keyword.isEmpty() && state.mode != SearchMode.TORRENTS) {
            _ui.update { it.copy(error = "请输入关键词") }
            return
        }
        searchJob?.cancel()
        _ui.update {
            it.copy(
                query = keyword,
                searching = true,
                error = null,
                start = null,
                blocks = emptyList(),
                done = null,
                titles = emptyList(),
                libraryGroups = emptyList(),
            )
        }
        when (state.mode) {
            SearchMode.TORRENTS -> searchTorrents(keyword, state.category)
            SearchMode.TITLES -> searchTitles(keyword)
            SearchMode.LIBRARY -> searchLibrary(keyword)
        }
    }

    private fun searchTitles(keyword: String) {
        searchJob = viewModelScope.launch {
            val origin = origin ?: return@launch
            runCatching {
                apiFactory.forOrigin(origin).searchTitles(TitleSearchRequest(query = keyword)).dataOrThrow()
            }
                .onSuccess { view -> _ui.update { it.copy(titles = view.titles, searching = false) } }
                .onFailure { e -> _ui.update { it.copy(error = friendlyMessage(e), searching = false) } }
            loadHistory()
        }
    }

    private fun searchLibrary(keyword: String) {
        searchJob = viewModelScope.launch {
            val origin = origin ?: return@launch
            runCatching {
                apiFactory.forOrigin(origin).searchLibraryItems(keyword).dataOrThrow()
            }
                .onSuccess { groups ->
                    _ui.update {
                        it.copy(
                            libraryGroups = groups.map { group -> group.libraryName to group.items },
                            searching = false,
                        )
                    }
                }
                .onFailure { e -> _ui.update { it.copy(error = friendlyMessage(e), searching = false) } }
        }
    }

    /** 站点搜索:SSE 逐站点渲染(快的先出,单站失败不影响整体) */
    private fun searchTorrents(keyword: String, category: TorrentCategory?) {
        val params = buildList {
            add("keyword=" + java.net.URLEncoder.encode(keyword, "UTF-8"))
            category?.let { add("categories=${it.id}") }
        }.joinToString("&")
        searchJob = viewModelScope.launch {
            runCatching {
                eventStream.reliableEvents("search/torrents/stream?$params").collect { event ->
                    when (event.name) {
                        "start" -> {
                            val start = json.decodeFromString<SearchStreamStart>(event.data)
                            _ui.update {
                                it.copy(
                                    start = start,
                                    blocks = start.sites.map { site ->
                                        SiteBlock(siteId = site.siteId, siteName = site.siteName)
                                    },
                                )
                            }
                        }
                        "site_result" -> {
                            val result = json.decodeFromString<SiteStreamResult>(event.data)
                            _ui.update { state ->
                                state.copy(blocks = state.blocks.mergeResult(result))
                            }
                        }
                        "site_error" -> {
                            val error = json.decodeFromString<SiteStreamError>(event.data)
                            _ui.update { state ->
                                state.copy(
                                    blocks = state.blocks.map { block ->
                                        if (block.siteId == error.siteId) {
                                            block.copy(error = error.error, elapsedMs = error.elapsedMs)
                                        } else {
                                            block
                                        }
                                    },
                                )
                            }
                        }
                        "done" -> {
                            val done = json.decodeFromString<SearchStreamDone>(event.data)
                            _ui.update { it.copy(done = done, searching = false) }
                            loadHistory()
                        }
                    }
                }
            }.onFailure { e ->
                _ui.update { it.copy(error = friendlyMessage(e), searching = false) }
            }
        }
    }

    /** 下载提交:自动入库(服务端按种子名识别身份并投递) */
    fun submitDownload(hit: TorrentHit) {
        val origin = origin ?: return
        val url = hit.downloadUrl
        if (url.isNullOrEmpty()) {
            _ui.update { it.copy(notice = McNotice("该结果没有下载入口", FeedbackTone.Success)) }
            return
        }
        _ui.update { it.copy(submitting = hit.torrentId) }
        viewModelScope.launch {
            runCatching {
                apiFactory.forOrigin(origin).submitDownload(
                    DownloadSubmitRequest(
                        siteId = hit.siteId,
                        downloadUrl = url,
                        torrentId = hit.torrentId.ifEmpty { null },
                        title = hit.title,
                        subtitle = hit.subtitle.ifEmpty { null },
                        autoRoute = true,
                        category = hit.category,
                    )
                ).dataOrThrow()
            }
                .onSuccess { _ui.update { it.copy(submitting = null, notice = McNotice("已提交下载:${hit.title.take(40)}", FeedbackTone.Success)) } }
                .onFailure { e -> _ui.update { it.copy(submitting = null, notice = McNotice(friendlyMessage(e), FeedbackTone.Error)) } }
        }
    }
}

private fun List<SiteBlock>.mergeResult(result: SiteStreamResult): List<SiteBlock> {
    val existing = indexOfFirst { it.siteId == result.siteId }
    val merged = SiteBlock(
        siteId = result.siteId,
        siteName = result.siteName,
        items = result.items,
        elapsedMs = result.elapsedMs,
    )
    return if (existing >= 0) toMutableList().also { it[existing] = merged } else this + merged
}
