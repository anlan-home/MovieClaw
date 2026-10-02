package io.movieclaw.android.feature.library

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.network.dataOrThrow
import io.movieclaw.android.core.network.friendlyMessage
import io.movieclaw.android.core.session.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/* ══════════ 我的收藏（iOS FavoritesView） ══════════ */

data class FavoritesState(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<io.movieclaw.android.core.model.FavoriteItemView> = emptyList(),
    /** 去重后的收藏作品总数（行首那句「N 部作品」用它，不是当前加载到的条数） */
    val total: Int = 0,
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val apiFactory: ApiFactory,
    private val repository: SessionRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(FavoritesState())
    val ui = _ui.asStateFlow()
    val origin: String? get() = repository.ui.value.origin

    init { load() }

    fun load() {
        viewModelScope.launch {
            val origin = origin ?: run { _ui.update { it.copy(loading = false, error = "尚未连接服务器") }; return@launch }
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val page = apiFactory.forOrigin(origin).favorites(limit = 60, offset = 0).dataOrThrow()
                _ui.update { it.copy(loading = false, items = page.items, total = page.total) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = friendlyMessage(e)) }
            }
        }
    }
}

@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenItem: (Long, Long) -> Unit,
    vm: FavoritesViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("我的收藏", onBack)
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TextMuted) }
            state.error != null -> EmptyHint(state.error!!)
            state.items.isEmpty() -> EmptyHint("还没有收藏。在影片页点心，或在 Jellyfin 客户端里收藏，都会出现在这里。")
            else -> {
                Text(
                    "${if (state.total > 0) state.total else state.items.size} 部作品 · 与 Jellyfin 客户端里点的心同一份",
                    fontSize = 15.sp, color = TextMuted,
                    modifier = Modifier.padding(horizontal = McMetrics.pagePadding, vertical = 8.dp),
                )
                // iOS：排序档（默认「最近收藏」）+ 图床浏览入口
                Row(
                    Modifier.padding(horizontal = McMetrics.pagePadding, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "最近收藏 ⌄",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(GlassCapsule)
                            .border(1.dp, LineSoft, RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    Text(
                        "图床浏览",
                        fontSize = 13.sp, color = TextMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, LineSoft, RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = McMetrics.pagePadding, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.items, key = { it.mediaItemId }) { item ->
                        val libraryName = ""
                        Column(Modifier.clickable { onOpenItem(item.libraryId ?: -1L, item.mediaItemId) }) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(McMetrics.posterRadius))
                                    .border(1.dp, LineSoft, RoundedCornerShape(McMetrics.posterRadius))
                                    .background(Color(0xFF101219)),
                            ) {
                                RemoteImage(item.posterUrl, vm.origin, Modifier.fillMaxSize(), contentDescription = item.title)
                            }
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                            }
                            Text(
                                // iOS 收藏格副行是「库名 · 年份」
                                listOfNotNull(
                                    if ((item.libraryId ?: 0L) > 0) "库 · $libraryName" else null,
                                    item.year?.toString(),
                                ).joinToString(" · "),
                                fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ══════════ 全部合集（iOS AllCollectionsView） ══════════ */

data class CollectionCard(
    val id: Long,
    val name: String,
    val itemCount: Int,
    val kind: String?,          // series / manual / rules
    val libraryId: Long?,
    val posters: List<String?>,
    val isHidden: Boolean,
    val isPrivate: Boolean,
)

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val apiFactory: ApiFactory,
    private val repository: SessionRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow<Pair<Boolean, List<CollectionCard>>>(true to emptyList())
    val ui = _ui.asStateFlow()
    val origin: String? get() = repository.ui.value.origin
    init { load() }

    fun load() {
        viewModelScope.launch {
            val origin = origin ?: return@launch
            _ui.value = true to _ui.value.second
            try {
                val raw = apiFactory.forOrigin(origin).collections().dataOrThrow()
                val arr = raw.jsonObject["items"]?.jsonArray ?: (raw as? kotlinx.serialization.json.JsonArray) ?: return@launch
                val list = arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val id = (o["id"] ?: return@mapNotNull null).jsonPrimitive.longOrNull ?: return@mapNotNull null
                    CollectionCard(
                        id = id,
                        name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        itemCount = o["item_count"]?.jsonPrimitive?.intOrNull ?: 0,
                        kind = o["kind"]?.jsonPrimitive?.contentOrNull,
                        libraryId = o["library_id"]?.jsonPrimitive?.longOrNull,
                        posters = (o["posters"]?.jsonArray ?: emptyList()).mapNotNull { it.jsonPrimitive.contentOrNull },
                        isHidden = o["is_hidden"]?.jsonPrimitive?.contentOrNull == "true",
                        isPrivate = o["visibility"]?.jsonPrimitive?.contentOrNull == "private",
                    )
                }
                _ui.value = false to list
            } catch (_: Exception) {
                _ui.value = false to _ui.value.second
            }
        }
    }
}

@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    onOpenCollection: (Long, String) -> Unit,
    vm: CollectionsViewModel = hiltViewModel(),
) {
    val (loading, list) = vm.ui.collectAsStateWithLifecycle().value
    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("全部合集", onBack)
        if (loading && list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TextMuted) }
        } else if (list.isEmpty()) {
            EmptyHint("还没有合集。在库详情里把筛选存为合集，系列会自动出现。")
        } else {
            Text("${list.size} 个合集 · 按库分组", fontSize = 15.sp, color = TextMuted, modifier = Modifier.padding(horizontal = McMetrics.pagePadding, vertical = 8.dp))
            // iOS：两组计数芯片（类型 / 来源），0 置灰
            Row(Modifier.padding(horizontal = McMetrics.pagePadding, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("类型：全部")
                Chip("剧集")
                Chip("电影")
                Chip(if (list.none { it.kind == "rules" }) "来源：自动 (0)" else "来源：自动 ${list.count { it.kind == "rules" }}", dim = list.none { it.kind == "rules" })
                Chip("来源：自建")
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = McMetrics.pagePadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 跨库分组（libraryId == null）排最前，标题「跨库」
                val crossLib = list.filter { it.libraryId == null }
                if (crossLib.isNotEmpty()) {
                    item(key = "grp-cross") {
                        Column {
                            Text("跨库", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("不属于任何一个库的手动名单", fontSize = 12.sp, color = TextFaint, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                        }
                    }
                    items(crossLib, key = { "cross-${it.id}" }) { c -> CollectionRow(c, vm.origin, onOpenCollection) }
                }
                items(list.filter { it.libraryId != null }, key = { it.id }) { c ->
                    CollectionRow(c, vm.origin, onOpenCollection)

                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, dim: Boolean = false) {
    Text(
        text,
        fontSize = 12.sp, color = if (dim) TextFaint else TextMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = if (dim) 0.03f else 0.06f))
            .border(1.dp, LineSoft, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun CollectionRow(c: CollectionCard, origin: String?, onOpen: (Long, String) -> Unit) {
    Row(Modifier.clickable { onOpen(c.id, c.name) }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(150.dp).height(96.dp)) {
            listOf(0, 1, 2).forEach { i ->
                val url = c.posters.getOrNull(i)
                Box(
                    Modifier
                        .padding(start = (i * 14).dp)
                        .width(64.dp).height(96.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, LineSoft, RoundedCornerShape(8.dp))
                        .background(Color(0xFF101219)),
                ) { RemoteImage(url, origin, Modifier.fillMaxSize(), contentDescription = null) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    "${c.itemCount} 部",
                    when (c.kind) { "series" -> "系列"; "rules" -> "自动收录"; "manual" -> "只有我"; else -> null },
                    if (c.isHidden) "已隐藏" else null,
                ).joinToString(" · "),
                fontSize = 12.sp, color = TextFaint, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = TextFaint, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun SubTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = TextMuted,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(999.dp)).clickable { onBack() }.padding(6.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = McType.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 15.sp, color = TextMuted, modifier = Modifier.padding(horizontal = 40.dp), lineHeight = 22.sp)
    }
}

/* ══════════ 媒体库管理（iOS LibraryManageView：四页签） ══════════ */

data class ManageState(
    val loading: Boolean = true,
    val tab: Int = 0,
    val libraries: List<io.movieclaw.android.core.model.LibraryView> = emptyList(),
    val trashed: List<Triple<String, String, String>> = emptyList(),   // 文件名 / 库名 / 原因
    val duplicates: List<Pair<String, Int>> = emptyList(),             // 标题 / 重复数
    val busy: Boolean = false,
)

@HiltViewModel
class LibraryManageViewModel @Inject constructor(
    private val apiFactory: ApiFactory,
    private val repository: SessionRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(ManageState())
    val ui = _ui.asStateFlow()
    val origin: String? get() = repository.ui.value.origin
    init { load() }

    fun setTab(i: Int) {
        _ui.update { it.copy(tab = i) }
        if (i == 1 && _ui.value.trashed.isEmpty()) loadTrash()
        if (i == 2) loadDuplicates()
    }

    fun load() {
        viewModelScope.launch {
            val origin = origin ?: return@launch
            try {
                val libs = runCatching { apiFactory.forOrigin(origin).libraries().dataOrThrow() }.getOrDefault(emptyList())
                _ui.update { it.copy(loading = false, libraries = libs) }
            } catch (_: Exception) { _ui.update { it.copy(loading = false) } }
        }
    }

    private fun loadTrash() {
        viewModelScope.launch {
            val origin = origin ?: return@launch
            try {
                val raw = apiFactory.forOrigin(origin).trashedFiles().dataOrThrow()
                val arr = raw.jsonObject["items"]?.jsonArray ?: (raw as? kotlinx.serialization.json.JsonArray) ?: return@launch
                val rows = arr.map { el ->
                    val o = el.jsonObject
                    Triple(
                        o["file_name"]?.jsonPrimitive?.contentOrNull ?: o["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        o["library_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        o["reason"]?.jsonPrimitive?.contentOrNull ?: "手动删除",
                    )
                }
                _ui.update { it.copy(trashed = rows) }
            } catch (_: Exception) { }
        }
    }

    private fun loadDuplicates() {
        viewModelScope.launch {
            val origin = origin ?: return@launch
            _ui.update { it.copy(busy = true) }
            try {
                val raw = apiFactory.forOrigin(origin).duplicateFiles().dataOrThrow()
                val arr = raw.jsonObject["groups"]?.jsonArray ?: (raw as? kotlinx.serialization.json.JsonArray) ?: return@launch
                val rows = arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val title = o["title"]?.jsonPrimitive?.contentOrNull ?: o["media_title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    title to (o["files"]?.jsonArray?.size ?: o["file_count"]?.jsonPrimitive?.intOrNull ?: 0)
                }
                _ui.update { it.copy(duplicates = rows, busy = false) }
            } catch (_: Exception) { _ui.update { it.copy(busy = false) } }
        }
    }

    fun scanDuplicates() {
        viewModelScope.launch {
            val origin = origin ?: return@launch
            _ui.update { it.copy(busy = true) }
            runCatching { apiFactory.forOrigin(origin).scanDuplicates() }
            loadDuplicates()
        }
    }
}

@Composable
fun LibraryManageScreen(
    onBack: () -> Unit,
    onOpenLibrary: (Long, String) -> Unit,
    vm: LibraryManageViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("媒体库管理", onBack)
        Text(
            "${state.libraries.size} 个媒体库",
            fontSize = 15.sp, color = TextMuted,
            modifier = Modifier.padding(horizontal = McMetrics.pagePadding, vertical = 4.dp),
        )
        // 四页签
        Row(Modifier.padding(horizontal = McMetrics.pagePadding, vertical = 10.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF3A3A3E)).padding(2.dp)) {
            listOf("媒体库", "回收站", "重复文件", "分享").forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                        .background(if (state.tab == i) Color(0xFF636667) else Color.Transparent)
                        .clickable { vm.setTab(i) }.padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (state.tab == i) Color.White else TextMuted) }
            }
        }
        when (state.tab) {
            0 -> LazyColumn(
                contentPadding = PaddingValues(horizontal = McMetrics.pagePadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.libraries, key = { it.id }) { lib ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1E212B).copy(alpha = 0.74f))
                            .border(1.dp, LineSoft, RoundedCornerShape(16.dp))
                            .clickable { onOpenLibrary(lib.id, lib.name) }.padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(lib.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (lib.kind == "tv") "剧集" else if (lib.kind == "movie") "电影" else "其他",
                                fontSize = 11.sp, color = TextMuted,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            lib.rootPaths.firstOrNull().orEmpty(),
                            fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = McMetrics.pagePadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.trashed.isEmpty()) item { EmptyHint("回收站是空的") }
                items(state.trashed) { (name, lib, reason) ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)).padding(12.dp)) {
                        Text(name, fontSize = 14.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$lib · $reason", fontSize = 12.sp, color = TextFaint, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            2 -> Column(Modifier.fillMaxSize().padding(horizontal = McMetrics.pagePadding)) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.busy) "扫描中…" else "一共 ${state.duplicates.size} 个单元有重复",
                        fontSize = 15.sp, color = TextMuted,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("重新扫描", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.clickable { vm.scanDuplicates() })
                }
                state.duplicates.forEach { (title, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontSize = 15.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("$n 个文件", fontSize = 13.sp, color = TextFaint)
                    }
                }
            }
            else -> EmptyHint("分享管理只在管理员网页端提供")
        }
    }
}

private fun formatBytesLocal(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble(); var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "${bytes} B" else "%.2f %s".format(v, units[i])
}
