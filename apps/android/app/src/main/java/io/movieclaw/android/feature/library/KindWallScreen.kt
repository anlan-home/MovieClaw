package io.movieclaw.android.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.movieclaw.android.core.designsystem.Bg
import io.movieclaw.android.core.designsystem.ErrorPane
import io.movieclaw.android.core.designsystem.LineSoft
import io.movieclaw.android.core.designsystem.LibraryItemCard
import io.movieclaw.android.core.designsystem.McMetrics
import io.movieclaw.android.core.designsystem.McTopBar
import io.movieclaw.android.core.designsystem.McTopBarVariant
import io.movieclaw.android.core.designsystem.McType
import io.movieclaw.android.core.designsystem.TextFaint
import io.movieclaw.android.core.designsystem.TextMuted
import io.movieclaw.android.core.designsystem.TextPrimary
import io.movieclaw.android.core.model.LibraryItemView
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.network.dataOrThrow
import io.movieclaw.android.core.network.friendlyMessage
import io.movieclaw.android.core.session.SessionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 按类型的跨库海报墙（`/library/kind/{movie|tv|video}`，网页 `/library/kind/{类型}` 的对应物）：
 * 首页「全部电影 · 最近添加」那一行点「查看全部」进来的地方。
 *
 * 成员 = 当前身份可见、没被管理员排除出首页的同类型库，同一部片在 4K 库和普通库里
 * 各有一份时只出现一格；每格点进它自己的落点库。口径全在服务端（`/libraries/kinds/{kind}`）。
 *
 * 刻意比单库页薄（与网页同）：没有筛选条、没有索引条，只有排序。
 */

/** 一档排序：服务端 sort 键 + 展示名（不反转，一律走自然方向；方向文案见下） */
private data class KindSortOption(val sort: String, val label: String)

/** 其他视频没有评分与上映时间（列出来只会排出一面按 id 的墙，按类型裁掉） */
private fun sortOptionsFor(kind: String): List<KindSortOption> = listOf(
    // 默认档就是「最近添加」：从首页那一行点进来，顺序不该变
    KindSortOption("added_at", "最近添加"),
    KindSortOption("title", "按标题"),
) + if (kind == "video") {
    listOf(
        KindSortOption("runtime", "按片长"),
        KindSortOption("size", "按体积"),
        KindSortOption("last_played", "最近观看"),
    )
} else {
    listOf(
        KindSortOption("release_date", "按上映时间"),
        KindSortOption("rating", "按评分"),
        KindSortOption("runtime", "按片长"),
        KindSortOption("size", "按体积"),
        KindSortOption("last_played", "最近观看"),
    )
}

/** 「全部电影」这类行的名字（网页 `MEDIA_KIND_LABELS` 同口径） */
internal fun mediaKindLabel(kind: String): String = when (kind) {
    "movie" -> "电影"
    "tv" -> "剧集"
    "video" -> "其他视频"
    else -> "作品"
}

@HiltViewModel
class KindWallViewModel @Inject constructor(
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val apiFactory: ApiFactory,
    private val repository: SessionRepository,
) : ViewModel() {

    val kind: String = savedStateHandle.get<String>("kind").orEmpty().ifBlank { "movie" }

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val items: List<LibraryItemView> = emptyList(),
        val total: Int = 0,
        val libraryCount: Int = 0,
        val sort: String = "added_at",
        val loadingMore: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    val origin: String? get() = repository.ui.value.origin

    init { load() }

    fun load() {
        viewModelScope.launch {
            val origin = origin
            if (origin == null) { _ui.update { it.copy(loading = false, error = "尚未连接服务器") }; return@launch }
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val api = apiFactory.forOrigin(origin)
                val summary = api.libraryKindSummary(kind).dataOrThrow()
                val items = api.libraryKindItems(
                    kind = kind,
                    sort = _ui.value.sort,
                    limit = PAGE_SIZE,
                    offset = 0,
                ).dataOrThrow()
                _ui.update {
                    it.copy(
                        loading = false,
                        items = items,
                        total = summary.itemCount,
                        libraryCount = summary.libraryIds.size,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = friendlyMessage(e)) }
            }
        }
    }

    fun setSort(sort: String) {
        if (sort == _ui.value.sort) return
        _ui.update { it.copy(sort = sort) }
        load()
    }

    fun loadMore() {
        val state = _ui.value
        if (state.loading || state.loadingMore || state.items.size >= state.total) return
        viewModelScope.launch {
            val origin = origin ?: return@launch
            _ui.update { it.copy(loadingMore = true) }
            runCatching {
                apiFactory.forOrigin(origin).libraryKindItems(
                    kind = kind,
                    sort = state.sort,
                    limit = PAGE_SIZE,
                    offset = state.items.size,
                ).dataOrThrow()
            }
                .onSuccess { more -> _ui.update { it.copy(loadingMore = false, items = it.items + more) } }
                .onFailure { _ui.update { it.copy(loadingMore = false) } }
        }
    }

    private companion object {
        /** 每次向服务端要的格数，与单库海报墙同一页长 */
        const val PAGE_SIZE = 60
    }
}

@Composable
fun KindWallScreen(
    onBack: () -> Unit,
    onOpenItem: (Long, Long) -> Unit,
    vm: KindWallViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val origin = vm.origin
    val label = "全部${mediaKindLabel(vm.kind)}"
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    androidx.compose.runtime.LaunchedEffect(gridState, state.items.size) {
        androidx.compose.runtime.snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { last ->
            val total = gridState.layoutInfo.totalItemsCount
            if (total > 0 && last >= total - 8) vm.loadMore()
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(top = McMetrics.topBarHeight)) {
            // 页头：名字 20/700 + 一行统计（N 部作品 · M 个库）
            Column(Modifier.padding(horizontal = McMetrics.pagePadding, vertical = 14.dp)) {
                Text(label, style = McType.title3, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.total > 0) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${state.total} 部作品" + if (state.libraryCount > 0) " · ${state.libraryCount} 个库" else "",
                        style = McType.body,
                        color = TextMuted,
                    )
                }
            }

            // 排序档（横向 chips）：只有排序，没有筛选条与索引条（网页这面墙刻意做薄）
            LazyRow(
                contentPadding = PaddingValues(horizontal = McMetrics.pagePadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sortOptionsFor(vm.kind), key = { it.sort }) { option ->
                    val on = option.sort == state.sort
                    Text(
                        option.label,
                        style = McType.caption,
                        color = if (on) TextPrimary else TextMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (on) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, if (on) Color.Transparent else LineSoft, RoundedCornerShape(999.dp))
                            .clickable { vm.setSort(option.sort) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TextMuted)
                }
                state.error != null -> ErrorPane(message = state.error!!, onRetry = vm::load)
                state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这个类型下还没有作品", style = McType.body, color = TextFaint)
                }
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = McMetrics.pagePadding,
                        end = McMetrics.pagePadding,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.items, key = { it.mediaItemId }) { item ->
                        LibraryItemCard(
                            item = item,
                            origin = origin,
                            onClick = { onOpenItem(item.libraryId ?: -1L, item.mediaItemId) },
                        )
                    }
                    if (state.loadingMore) {
                        item(span = { GridItemSpan(3) }) {
                            Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = TextMuted, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }

        McTopBar(
            variant = McTopBarVariant.Sub,
            title = label,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
