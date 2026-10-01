package io.movieclaw.android.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
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
import io.movieclaw.android.core.designsystem.GlassCapsule
import io.movieclaw.android.core.designsystem.LineSoft
import io.movieclaw.android.core.designsystem.McMetrics
import io.movieclaw.android.core.designsystem.McType
import io.movieclaw.android.core.designsystem.RemoteImage
import io.movieclaw.android.core.designsystem.TextFaint
import io.movieclaw.android.core.designsystem.TextMuted
import io.movieclaw.android.core.designsystem.TextPrimary
import io.movieclaw.android.core.designsystem.Warn
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.network.dataOrThrow
import io.movieclaw.android.core.network.friendlyMessage
import io.movieclaw.android.core.session.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/* ══════════ 发现页筛选结果（iOS DiscoverFilteredGrid） ══════════ */

data class DiscTitle(
    val ref: String,
    val title: String,
    val year: Int?,
    val rating: Float?,
    val posterUrl: String?,
    val genres: List<String>,
    val owned: Boolean,
)

data class FilteredState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val titles: List<DiscTitle> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalResults: Int = 0,
)

@HiltViewModel
class FilteredResultsViewModel @Inject constructor(
    private val apiFactory: ApiFactory,
    private val repository: SessionRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(FilteredState())
    val ui = _ui.asStateFlow()
    val origin: String? get() = repository.ui.value.origin

    var filter: DiscoveryFilter = DiscoveryFilter()
        private set
    var mediaType: String = "movie"
        private set

    init { load() }

    fun apply(newFilter: DiscoveryFilter) {
        filter = newFilter
        load()
    }

    fun load() {
        viewModelScope.launch {
            val origin = origin ?: run { _ui.update { it.copy(loading = false, error = "尚未连接服务器") }; return@launch }
            _ui.update { it.copy(loading = true, error = null, page = 1) }
            try {
                val raw = apiFactory.forOrigin(origin).discoverTitles(
                    mediaType = mediaType,
                    genres = filter.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                    country = filter.country, year = filter.year, rating = filter.rating,
                    runtime = filter.runtime, sort = filter.sort, page = 1,
                ).dataOrThrow().jsonObject
                _ui.update {
                    it.copy(
                        loading = false,
                        titles = parseTitles(raw),
                        page = raw.jsonObject["page"]?.jsonPrimitive?.intOrNull ?: 1,
                        totalPages = raw.jsonObject["total_pages"]?.jsonPrimitive?.intOrNull ?: 1,
                        totalResults = raw.jsonObject["total_results"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = friendlyMessage(e)) }
            }
        }
    }

    fun loadMore() {
        val s = _ui.value
        if (s.loadingMore || s.loading || s.page >= s.totalPages) return
        viewModelScope.launch {
            val origin = origin ?: return@launch
            _ui.update { it.copy(loadingMore = true) }
            try {
                val next = s.page + 1
                val raw = apiFactory.forOrigin(origin).discoverTitles(
                    mediaType = mediaType,
                    genres = filter.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                    country = filter.country, year = filter.year, rating = filter.rating,
                    runtime = filter.runtime, sort = filter.sort, page = next,
                ).dataOrThrow().jsonObject
                _ui.update { it.copy(loadingMore = false, page = next, titles = it.titles + parseTitles(raw)) }
            } catch (_: Exception) {
                _ui.update { it.copy(loadingMore = false) }
            }
        }
    }
}

internal fun parseTitles(raw: JsonObject): List<DiscTitle> {
    val arr = raw["titles"]?.jsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el.jsonObject
        val ref = o["title_ref"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        DiscTitle(
            ref = ref,
            title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            year = o["release_year"]?.jsonPrimitive?.intOrNull,
            rating = o["provider_rating"]?.jsonPrimitive?.floatOrNull,
            posterUrl = o["poster_url"]?.jsonPrimitive?.contentOrNull,
            genres = (o["genres"]?.jsonArray ?: emptyList()).mapNotNull { it.jsonPrimitive.contentOrNull },
            // library_status 是**对象**（MediaLibraryStatus：media_item_id / library_count / file_count），
            // 不是字符串。以前拿它当 primitive 读，服务端一返回对象就抛
            // "Element class kotlinx.serialization.json.JsonObject is not a JsonPrimitive"，
            // 整页变成一串异常文字（用户报的"更多按钮点了报错"）。
            owned = (o["library_status"] as? JsonObject)?.let {
                (it["file_count"]?.jsonPrimitive?.intOrNull ?: 0) > 0
            } ?: false,
        )
    }
}

@Composable
fun FilteredResultsScreen(
    onBack: () -> Unit,
    onOpenTitle: (String) -> Unit,
    vm: FilteredResultsViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()

    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (grid.layoutInfo.totalItemsCount > 0 && last >= grid.layoutInfo.totalItemsCount - 8) vm.loadMore()
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        io.movieclaw.android.feature.library.SubTopBar("筛选结果", onBack)
        Column(Modifier.padding(horizontal = McMetrics.pagePadding)) {
            Text("TMDB DISCOVER", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = Color(0xFF9FB0C9))
            Text(
                if (state.totalResults > 0) "找到 ${state.totalResults} 部，已加载 ${state.titles.size} 部" else "已启用筛选",
                fontSize = 15.sp, color = TextMuted, modifier = Modifier.padding(top = 4.dp),
            )
        }
        // 当前条件 chips
        val chips = buildList {
            if (vm.filter.genreIds.isNotEmpty()) add("类型 ${vm.filter.genreIds.size} 项")
            vm.filter.country?.let { add(it) }
            vm.filter.year?.let { add("$it 年") }
            vm.filter.rating?.let { add("$it 分以上") }
            vm.filter.runtime?.let { add("$it 分钟以内") }
            vm.filter.sort?.let { add("排序") }
        }
        if (chips.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = McMetrics.pagePadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                item {
                    Pill("清空条件", danger = true) { vm.apply(DiscoveryFilter()) }
                }
                items(chips.size) { i -> Pill(chips[i]) { } }
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TextMuted) }
            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(state.error!!, color = TextMuted) }
            state.titles.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有符合条件的影片", fontSize = 15.sp, color = TextMuted)
            }
            else -> LazyVerticalGrid(
                state = grid,
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = McMetrics.pagePadding, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.titles, key = { it.ref }) { t -> TitleCell(t, vm.origin) { onOpenTitle(t.ref) } }
                if (state.loadingMore) {
                    item(span = { GridItemSpan(2) }) {
                        Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TitleCell(t: DiscTitle, origin: String?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(McMetrics.posterRadius))
                .border(1.dp, LineSoft, RoundedCornerShape(McMetrics.posterRadius))
                .background(Color(0xFF101219)),
        ) {
            RemoteImage(t.posterUrl, origin, Modifier.fillMaxSize(), contentDescription = t.title)
            t.rating?.takeIf { it > 0f }?.let {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Star, contentDescription = null, tint = Warn, modifier = Modifier.size(9.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("%.1f".format(it), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
        Text(t.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        val meta = listOfNotNull(t.year?.toString(), t.genres.take(2).joinToString(" / ").takeIf { it.isNotBlank() }).joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, fontSize = 13.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Pill(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = if (danger) Color(0xFFFF6B6B) else TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (danger) Color(0x1AFF6B6B) else GlassCapsule)
            .border(1.dp, LineSoft, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/* ══════════ 影人页（iOS DiscoveredPersonView） ══════════ */

data class PersonState(
    val loading: Boolean = true,
    val error: String? = null,
    val name: String = "",
    val avatarUrl: String? = null,
    val titles: List<DiscTitle> = emptyList(),
)

@HiltViewModel
class PersonViewModel @Inject constructor(
    private val apiFactory: ApiFactory,
    private val repository: SessionRepository,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {
    private val personId: Int = savedStateHandle.get<String>("tmdbPersonId")?.toIntOrNull() ?: 0
    private val _ui = MutableStateFlow(PersonState())
    val ui = _ui.asStateFlow()
    val origin: String? get() = repository.ui.value.origin

    init { load() }

    fun load() {
        viewModelScope.launch {
            val origin = origin ?: run { _ui.update { it.copy(loading = false, error = "尚未连接服务器") }; return@launch }
            try {
                val raw = apiFactory.forOrigin(origin).discoveredPerson(personId).dataOrThrow().jsonObject
                _ui.update {
                    it.copy(
                        loading = false,
                        name = raw["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        avatarUrl = raw["avatar_url"]?.jsonPrimitive?.contentOrNull,
                        titles = parseTitles(raw),
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = friendlyMessage(e)) }
            }
        }
    }
}

@Composable
fun PersonScreen(
    onBack: () -> Unit,
    onOpenTitle: (String) -> Unit,
    vm: PersonViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Bg)) {
        io.movieclaw.android.feature.library.SubTopBar(state.name.ifBlank { "影人" }, onBack)
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TextMuted) }
        } else {
            Row(Modifier.padding(horizontal = McMetrics.pagePadding, vertical = 6.dp)) {
                Box(
                    Modifier.width(92.dp).height(138.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Color(0xFF101219)),
                ) { RemoteImage(state.avatarUrl, vm.origin, Modifier.fillMaxSize(), contentDescription = state.name) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("TMDB 影人", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.5.sp, color = Color(0xFF9FB0C9))
                    Text(state.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(top = 4.dp))
                    Text("共 ${state.titles.size} 部影视作品", fontSize = 13.sp, color = TextMuted, modifier = Modifier.padding(top = 6.dp))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = McMetrics.pagePadding, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.titles, key = { it.ref }) { t -> TitleCell(t, vm.origin) { onOpenTitle(t.ref) } }
            }
        }
    }
}
