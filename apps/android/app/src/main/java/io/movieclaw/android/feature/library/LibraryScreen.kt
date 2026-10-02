package io.movieclaw.android.feature.library

import androidx.compose.foundation.layout.statusBarsPadding
import io.movieclaw.android.core.designsystem.MenuSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.movieclaw.android.core.designsystem.Bg
import io.movieclaw.android.core.designsystem.ContinueWatchingCard
import io.movieclaw.android.core.designsystem.ErrorPane
import io.movieclaw.android.core.designsystem.Loadable
import io.movieclaw.android.core.designsystem.McFormat
import io.movieclaw.android.core.designsystem.McMetrics
import io.movieclaw.android.core.designsystem.LineSoft
import io.movieclaw.android.core.designsystem.McNavButton
import io.movieclaw.android.core.designsystem.McTabBarContentPadding
import io.movieclaw.android.core.designsystem.McTopBar
import io.movieclaw.android.core.designsystem.McTopBarVariant
import io.movieclaw.android.core.designsystem.McType
import io.movieclaw.android.core.designsystem.Placeholder
import io.movieclaw.android.core.designsystem.PosterCard
import io.movieclaw.android.core.designsystem.RemoteImage
import io.movieclaw.android.core.designsystem.SectionHeader
import io.movieclaw.android.core.designsystem.TextMuted
import io.movieclaw.android.core.designsystem.TextPrimary
import io.movieclaw.android.core.model.FavoriteItemView
import io.movieclaw.android.core.model.LibraryItemView
import io.movieclaw.android.core.model.LibraryView
import io.movieclaw.android.core.model.favoriteLevelLabel
import io.movieclaw.android.core.model.UpNextItem
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.playback.PlayTargetFactory
import io.movieclaw.android.core.network.dataOrThrow
import io.movieclaw.android.core.network.friendlyMessage
import io.movieclaw.android.core.session.SessionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 媒体库首屏的数据（统计行 / 接下来继续 / 我的收藏 / 每个库的最近添加 / 按类型的跨库行） */
data class LibraryHome(
    val libraries: List<LibraryView> = emptyList(),
    val upNext: List<UpNextItem> = emptyList(),
    /** 「我的收藏」横滚行（前 20 条、未看完的提前）；空 = 这一行不出现 */
    val favorites: List<FavoriteItemView> = emptyList(),
    val favoriteTotal: Int = 0,
    val recentByLibrary: Map<Long, List<LibraryItemView>> = emptyMap(),
    /** 「全部电影 · 最近添加」这类跨库行：同类型可见库 ≥2 个且有作品时才有 */
    val kindRows: List<KindRow> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: SessionRepository,
    private val apiFactory: ApiFactory,
) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<LibraryHome>>(Loadable.Loading)
    val state = _state.asStateFlow()

    /** 图片基址（RemoteImage 用） */
    val origin: String? get() = repository.ui.value.origin

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = Loadable.Loading
            val origin = repository.ui.value.origin
            if (origin == null) {
                _state.value = Loadable.Failed("尚未连接服务器")
                return@launch
            }
            try {
                val api = apiFactory.forOrigin(origin)
                val libraries = api.libraries().dataOrThrow()
                val upNext = runCatching { api.upNext(limit = 24).dataOrThrow().items }.getOrDefault(emptyList())
                // 「我的收藏」横滚行：前 20 条、没看完的提前（服务端 /playback/favorites 的
                // 首页口径，与 iOS LibraryHomeStore / 网页首页同一组参数）。取不到就当空行，
                // 不挡住首屏其它内容。
                val favorites = runCatching {
                    api.favorites(limit = 20, offset = 0, unwatchedFirst = true).dataOrThrow()
                }.getOrNull()
                val recent = buildMap {
                    libraries.forEach { lib ->
                        val items = runCatching {
                            api.libraryItems(libraryId = lib.id, limit = 12).dataOrThrow()
                        }.getOrDefault(emptyList())
                        if (items.isNotEmpty()) put(lib.id, items)
                    }
                }
                // ── 按类型的跨库行（「全部电影 · 最近添加」）──
                // 口径同网页 `mediaKindGroups` + 默认行 hidden = 参与库 < 2：可见 ∩ 该类型 ∩
                // 没被排除出首页的库至少两个才出这一行；再由服务端概况确认有作品（跨库去重）。
                // 照片库不做（另一种形态，见 library-home-perspective.md §8）。
                val kindRows = buildList {
                    listOf("movie" to "电影", "tv" to "剧集", "video" to "其他视频").forEach { (kind, label) ->
                        val members = libraries.filter {
                            it.viewerAccess && !it.excludeFromHome && it.kind == kind
                        }
                        if (members.size < 2) return@forEach
                        val summary = runCatching { api.libraryKindSummary(kind).dataOrThrow() }.getOrNull() ?: return@forEach
                        if (summary.itemCount <= 0) return@forEach
                        val items = runCatching {
                            api.libraryKindItems(kind = kind, sort = "added_at", limit = 12).dataOrThrow()
                        }.getOrDefault(emptyList())
                        if (items.isNotEmpty()) {
                            add(KindRow(kind = kind, label = label, total = summary.itemCount, libraryCount = members.size, items = items))
                        }
                    }
                }
                _state.value = Loadable.Ready(
                    LibraryHome(
                        libraries = libraries,
                        upNext = upNext,
                        favorites = favorites?.items.orEmpty(),
                        favoriteTotal = favorites?.total ?: 0,
                        recentByLibrary = recent,
                        kindRows = kindRows,
                    )
                )
            } catch (e: Exception) {
                _state.value = Loadable.Failed(friendlyMessage(e))
            }
        }
    }
}

/** 首页的一条按类型跨库行（「全部电影 · 最近添加」） */
data class KindRow(
    val kind: String,
    val label: String,
    val total: Int,
    val libraryCount: Int,
    val items: List<LibraryItemView>,
)

/**
 * 媒体库 —— 按移动端网页实测重排（此前是一个库列表网格，与网页完全不是一页）：
 *   统计行（14px 62%）→「接下来继续」（200×113 卡）→「我的媒体库」（230×110 拼贴卡 +
 *   居中库名 + 徽标）→ 每个库的「最近添加的<库名>」（126×189 海报行）。
 *   分区标题 17/600，行尾动作 14px 62%，箭头只在能滚时出现。
 */
@Composable
fun LibraryScreen(
    onOpenLibrary: (Long, String) -> Unit,
    onOpenItem: (Long, Long) -> Unit,
    onPlay: (io.movieclaw.android.core.playback.PlayTarget) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenManage: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenCollections: () -> Unit = {},
    /** 「全部电影」类型行点「查看全部」进跨库墙 */
    onOpenKind: (String) -> Unit = {},
    vm: LibraryViewModel = hiltViewModel(),
) {
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var moreOpen by remember { mutableStateOf(false) }
    val libScroll = androidx.compose.foundation.rememberScrollState()
    io.movieclaw.android.core.designsystem.TrackTabBarMinimize(libScroll)
    val origin = vm.origin

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (val s = state) {
            Loadable.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TextMuted)
            }
            is Loadable.Failed -> ErrorPane(message = s.message, onRetry = vm::load)
            is Loadable.Ready -> {
                val home = s.value
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(libScroll)
                        // iOS 根页是 inlineLarge 大标题：标题占 52dp 之后统计行再往下 4dp
                        .padding(top = McMetrics.topBarHeight + 2.dp, bottom = McTabBarContentPadding),
                ) {
                    // 大标题（iOS inlineLarge：34/700，左对齐 16）
                    Text(
                        "媒体库",
                        fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = McMetrics.pagePadding, vertical = 2.dp),
                    )
                    StorageStatsLine(home)

                    if (home.upNext.isNotEmpty()) {
                        Spacer(Modifier.height(McMetrics.sectionTop))
                        SectionHeader("接下来继续")
                        Spacer(Modifier.height(McMetrics.sectionBottom))
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = McMetrics.pagePadding),
                            horizontalArrangement = Arrangement.spacedBy(McMetrics.rowSpacing),
                        ) {
                            items(home.upNext, key = { it.mediaItemId }) { item ->
                                // 分集卡：第二行是「S1E3 · 集名」，电影才是年份（iOS UpNextCard 同款）
                                val isEpisode = item.kind == "tv"
                                val context = if (isEpisode) {
                                    listOfNotNull(
                                        "S${item.seasonNumber}E${item.episodeNumber}",
                                        item.episodeTitle?.takeIf { it.isNotBlank() },
                                    ).joinToString(" · ")
                                } else {
                                    item.year?.toString()
                                }
                                val clockText = if (item.positionMs > 0 && item.durationMs > 0) {
                                    "${McFormat.clock(item.positionMs)} / ${McFormat.clock(item.durationMs)}"
                                } else {
                                    null
                                }
                                val ago = McFormat.relativeFromNow(item.lastPlayedAt).orEmpty()
                                val stateLabel = when {
                                    item.advanced -> "${ago}看完上一集"
                                    // 有「已播/总时长」时那一行已经说明了进度，第三行只留时间
                                    item.positionMs > 0 && item.progressPercent > 0 && clockText == null ->
                                        "${ago}看到 ${item.progressPercent}%"
                                    item.positionMs > 0 -> "${ago}看过一段"
                                    else -> "${ago}打开过"
                                }
                                ContinueWatchingCard(
                                    imageUrl = item.episodeStillUrl ?: item.backdropUrl ?: item.posterUrl,
                                    origin = origin,
                                    title = item.title,
                                    context = context,
                                    stateLabel = stateLabel,
                                    clockText = clockText,
                                    progressPercent = item.progressPercent.takeIf { it > 0 && item.positionMs > 0 },
                                    remainingEpisodes = item.unwatchedAheadCount,
                                    onClick = {
                                        onPlay(
                                            PlayTargetFactory.fromUpNext(
                                                item = item,
                                                subtitle = context,
                                            )
                                        )
                                    },
                                )
                            }
                        }
                    }

                    // ── 我的收藏（iOS LibraryHomeView `.favorites`：排在「接下来继续」之后、
                    // 「我的媒体库」之前）──
                    // 每格是海报 + 收藏层级小字（整季 / 单集才解释，整剧与电影不说）；
                    // 行尾动作「查看全部 N 部」进收藏墙（N 是去重后的作品总数，不是这一行的条数）。
                    if (home.favorites.isNotEmpty()) {
                        Spacer(Modifier.height(McMetrics.sectionTop))
                        SectionHeader(
                            title = "我的收藏",
                            actionText = if (home.favoriteTotal > 0) "查看全部 ${home.favoriteTotal} 部" else "查看全部",
                            onAction = onOpenFavorites,
                        )
                        Spacer(Modifier.height(McMetrics.sectionBottom))
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = McMetrics.pagePadding),
                            horizontalArrangement = Arrangement.spacedBy(McMetrics.rowSpacing),
                        ) {
                            items(home.favorites, key = { it.mediaItemId }) { fav ->
                                val level = favoriteLevelLabel(
                                    fav.kind,
                                    fav.favoriteSeasonNumber,
                                    fav.favoriteEpisodeNumber,
                                )
                                PosterCard(
                                    imageUrl = fav.posterUrl ?: fav.backdropUrl,
                                    origin = origin,
                                    title = fav.title,
                                    meta = listOfNotNull(fav.year?.toString(), level).joinToString(" · ")
                                        .takeIf { it.isNotBlank() },
                                    rating = fav.rating?.takeIf { it > 0f },
                                    modifier = Modifier.width(McMetrics.rowCardWidth),
                                    onClick = { onOpenItem(fav.libraryId ?: -1L, fav.mediaItemId) },
                                )
                            }
                        }
                    }

                    if (home.libraries.isNotEmpty()) {
                        Spacer(Modifier.height(McMetrics.sectionTop))
                        SectionHeader("我的媒体库")
                        Spacer(Modifier.height(McMetrics.sectionBottom))
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = McMetrics.pagePadding),
                            horizontalArrangement = Arrangement.spacedBy(McMetrics.rowSpacing),
                        ) {
                            items(home.libraries, key = { it.id }) { lib ->
                                LibraryCollageCard(
                                    library = lib,
                                    // iOS 用服务端合成的「氛围光货架」封面（与 Jellyfin 兼容层同一张图）
                                    coverUrl = origin?.trimEnd('/')?.let { "$it/api/v1/libraries/${lib.id}/cover" },
                                    origin = origin,
                                    onClick = { onOpenLibrary(lib.id, lib.name) },
                                )
                            }
                        }
                    }

                    // 按类型的跨库行（「全部电影 · 最近添加」）：同类型可见库 ≥2 个且
                    // 有作品时才有（网页 `kind:<类型>` 默认行同口径）
                    home.kindRows.forEach { row ->
                        Spacer(Modifier.height(McMetrics.sectionTop))
                        SectionHeader(
                            title = "全部${row.label} · 最近添加",
                            actionText = "查看全部",
                            onAction = { onOpenKind(row.kind) },
                        )
                        Spacer(Modifier.height(McMetrics.sectionBottom))
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = McMetrics.pagePadding),
                            horizontalArrangement = Arrangement.spacedBy(McMetrics.rowSpacing),
                        ) {
                            items(row.items, key = { "kind-${row.kind}-${it.mediaItemId}" }) { item ->
                                PosterCard(
                                    imageUrl = item.posterUrl ?: item.backdropUrl,
                                    origin = origin,
                                    title = item.title,
                                    meta = item.year?.toString(),
                                    rating = item.rating?.takeIf { it > 0f },
                                    modifier = Modifier.width(McMetrics.rowCardWidth),
                                    onClick = { onOpenItem(item.libraryId ?: -1L, item.mediaItemId) },
                                )
                            }
                        }
                    }

                    home.libraries.forEach { lib ->
                        val items = home.recentByLibrary[lib.id].orEmpty()
                        if (items.isEmpty()) return@forEach
                        Spacer(Modifier.height(McMetrics.sectionTop))
                        SectionHeader(
                            title = "最近添加的${lib.name}",
                            actionText = "查看全部",
                            onAction = { onOpenLibrary(lib.id, lib.name) },
                        )
                        Spacer(Modifier.height(McMetrics.sectionBottom))
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = McMetrics.pagePadding),
                            horizontalArrangement = Arrangement.spacedBy(McMetrics.rowSpacing),
                        ) {
                            items(items, key = { it.mediaItemId }) { item ->
                                PosterCard(
                                    imageUrl = item.posterUrl ?: item.backdropUrl,
                                    origin = origin,
                                    title = item.title,
                                    meta = item.year?.toString(),
                                    rating = item.rating?.takeIf { it > 0f },
                                    modifier = Modifier.width(McMetrics.rowCardWidth),
                                    onClick = { onOpenItem(item.libraryId ?: lib.id, item.mediaItemId) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        McTopBar(
            variant = McTopBarVariant.Root,
            title = "",   // 大标题在内容区（iOS inlineLarge），顶栏只留右侧动作钮
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            McNavButton(Icons.Rounded.MoreHoriz, contentDescription = "媒体库操作", onClick = { moreOpen = true })
            Spacer(Modifier.width(8.dp))
            // 入口口径是「任一搜索分区可用」（影视 / 资源 / 媒体库，见 SearchAccess.canOpenSearch）
            if (io.movieclaw.android.core.session.LocalSearchAccess.current.canOpenSearch) {
                McNavButton(Icons.Rounded.Search, contentDescription = "搜索", onClick = onOpenSearch)
            }
        }

        // ⋯ 菜单：自定义首页 / 全部合集 / 管理媒体库（iOS library-more 三项）
        if (moreOpen) {
            Box(Modifier.fillMaxSize().clickable { moreOpen = false })
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = McMetrics.pagePadding, top = 44.dp)
                    .width(190.dp)
                    .clip(RoundedCornerShape(McMetrics.menuRadius))
                    .background(MenuSurface)
                    .border(1.dp, LineSoft, RoundedCornerShape(McMetrics.menuRadius))
                    .padding(4.dp),
            ) {
                MenuItem("自定义首页") {
                    moreOpen = false
                    // 自定义首页（行的显隐 / 排序 / 改名）目前只有网页端有这套编辑器；
                    // 原生这层不摆一个点了没反应的入口，直接开网页那一页（脚本、拖拽都现成）
                    val base = origin?.trimEnd('/')
                    if (base != null) {
                        runCatching {
                            appContext.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("$base/library/customize"),
                                )
                            )
                        }
                    }
                }
                MenuItem("全部合集") { moreOpen = false; onOpenCollections() }
                // 媒体库管理是超管页面（Web accessiblePathFor / iOS .libraryManage 同口径）
                if (io.movieclaw.android.core.session.LocalPermissions.current.canManageLibraries) {
                    MenuItem("管理媒体库") { moreOpen = false; onOpenManage() }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = McType.body,
        color = TextPrimary,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    )
}

/**
 * 统计行 —— iOS `libraryStatsSummary`：只聚合服务端随库返回的**预算快照**，
 * 不拿「最近添加那几条」去算（那样电影/剧集数与占用空间会全错）。
 * 文案：`N 个媒体库 · X 部电影 · Y 部剧集[ · Z 个其他视频] · 共占用 S 存储空间`
 */
@Composable
private fun StorageStatsLine(home: LibraryHome) {
    val movies = home.libraries.filter { it.kind == "movie" }.sumOf { it.stats.itemCount }
    val series = home.libraries.filter { it.kind == "tv" }.sumOf { it.stats.itemCount }
    val others = home.libraries.filter { it.kind != "movie" && it.kind != "tv" }.sumOf { it.stats.itemCount }
    val totalBytes = home.libraries.sumOf { it.stats.totalSizeBytes }
    val text = buildString {
        if (home.libraries.isEmpty()) {
            append("还没有媒体库，创建后会在这里显示库存统计")
            return@buildString
        }
        append("${home.libraries.size} 个媒体库")
        append(" · ").append("$movies 部电影")
        append(" · ").append("$series 部剧集")
        if (others > 0) append(" · ").append("$others 个其他视频")
        append(" · 共占用 ").append(formatBytes(totalBytes)).append(" 存储空间")
    }
    Text(
        text,
        style = McType.sub,
        color = TextMuted,
        lineHeight = androidx.compose.ui.unit.TextUnit(21f, androidx.compose.ui.unit.TextUnitType.Sp),
        modifier = Modifier.padding(horizontal = McMetrics.pagePadding, vertical = 8.dp),
    )
}

/**
 * 库拼贴卡（实测 230×110 整图 + 居中库名 + 徽标）。
 * 封面直接用服务端合成的那张（`GET /libraries/{id}/cover`，与网页/Jellyfin 同一张图，
 * ETag 走素材指纹所以库没变就 304）。
 * 该库**还没有海报资产**时服务端返回 404，这时退到按类型画的功能占位——
 * 不接的话就是一块空黑，看着像加载失败。
 */
@Composable
private fun LibraryCollageCard(
    library: LibraryView,
    coverUrl: String?,
    origin: String?,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(230.dp).clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(McMetrics.cardRadius))
                .background(Placeholder),
        ) {
            RemoteImage(
                url = coverUrl,
                origin = origin,
                contentDescription = library.name,
                modifier = Modifier.fillMaxSize().aspectRatio(2.1f),
                fallback = { LibraryCoverPlaceholder(library) },
            )
        }
        Spacer(Modifier.height(11.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(library.name, style = McType.bodySemibold, color = io.movieclaw.android.core.designsystem.TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text(
                "默认",
                style = McType.micro,
                color = TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** 库封面取不到时的占位：按库类型给图标 + 库名，比空白有用 */
@Composable
private fun LibraryCoverPlaceholder(library: LibraryView) {
    val icon = when (library.kind) {
        "tv" -> androidx.compose.material.icons.Icons.Rounded.Tv
        "music" -> androidx.compose.material.icons.Icons.Rounded.LibraryMusic
        else -> androidx.compose.material.icons.Icons.Rounded.Movie
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.02f)),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(library.name, style = McType.caption2, color = TextMuted, maxLines = 1)
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (value >= 100 || i == 0) "%.0f %s".format(value, units[i]) else "%.2f %s".format(value, units[i])
}
