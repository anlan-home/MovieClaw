package io.movieclaw.android.feature.discover

import androidx.compose.ui.unit.Dp
import io.movieclaw.android.core.designsystem.SkeletonBlock
import io.movieclaw.android.core.designsystem.PosterRowSkeleton
import io.movieclaw.android.core.designsystem.PosterRibbon
import io.movieclaw.android.core.designsystem.McType
import io.movieclaw.android.core.designsystem.McRow
import io.movieclaw.android.core.designsystem.McMetrics
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.movieclaw.android.core.designsystem.statusLabel
import io.movieclaw.android.feature.subscriptions.SubscriptionIndex
import io.movieclaw.android.core.model.SubscriptionView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
import io.movieclaw.android.core.designsystem.Accent
import io.movieclaw.android.core.designsystem.ErrorPane
import io.movieclaw.android.core.designsystem.GlassCard
import io.movieclaw.android.core.designsystem.ContinueWatchingCard
import io.movieclaw.android.core.designsystem.Loadable
import io.movieclaw.android.core.designsystem.PosterCard
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.movieclaw.android.core.designsystem.GlassCapsule
import io.movieclaw.android.core.designsystem.HeroAmbientBackdrop
import io.movieclaw.android.core.designsystem.HeroCarousel
import io.movieclaw.android.core.designsystem.HeroSlide
import io.movieclaw.android.core.designsystem.LineSoft
import io.movieclaw.android.core.designsystem.McNavButton
import io.movieclaw.android.core.designsystem.McTabBarContentPadding
import io.movieclaw.android.core.designsystem.McTopBar
import io.movieclaw.android.core.designsystem.McTopBarVariant
import io.movieclaw.android.core.discovery.rememberAmbientColor
import io.movieclaw.android.core.designsystem.RemoteImage
import io.movieclaw.android.core.designsystem.SectionHeader
import io.movieclaw.android.core.designsystem.TextFaint
import io.movieclaw.android.core.designsystem.TextMuted
import io.movieclaw.android.core.designsystem.Warning
import io.movieclaw.android.core.model.DiscoveredTitle
import io.movieclaw.android.core.model.DiscoverySection
import io.movieclaw.android.core.model.LibraryView
import io.movieclaw.android.core.model.UpNextItem
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.network.dataOrThrow
import io.movieclaw.android.core.network.friendlyMessage
import io.movieclaw.android.core.playback.PlayTarget
import io.movieclaw.android.core.session.SessionRepository
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import io.movieclaw.android.core.designsystem.LocalPosterReveal

data class DiscoverRow(val section: DiscoverySection, val titles: List<DiscoveredTitle>)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val repository: SessionRepository,
    private val apiFactory: ApiFactory,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val mediaType: String = "movie",
        val source: String = "tmdb",
        val filters: DiscoveryFilter = DiscoveryFilter(),
        val upNext: List<UpNextItem> = emptyList(),
        val libraries: List<LibraryView> = emptyList(),
        val rows: List<DiscoverRow> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    val origin: String? get() = repository.ui.value.origin

    init {
        load()
    }

    val source: String get() = _ui.value.source
    val filters: DiscoveryFilter get() = _ui.value.filters

    fun switchSource(source: String) {
        if (_ui.value.source == source) return
        _ui.update { it.copy(source = source, rows = emptyList()) }
        load()
    }

    fun applyFilters(filters: DiscoveryFilter) {
        _ui.update { it.copy(filters = filters) }
        load()
    }

    fun switchMediaType(mediaType: String) {
        if (_ui.value.mediaType == mediaType) return
        _ui.update { it.copy(mediaType = mediaType, rows = emptyList()) }
        load()
    }

    fun load() {
        viewModelScope.launch {
            val origin = origin
            if (origin == null) {
                _ui.update { it.copy(loading = false, error = "尚未连接服务器") }
                return@launch
            }
            _ui.update { it.copy(loading = true, error = null) }
            val api = apiFactory.forOrigin(origin)
            try {
                // 首页数据并行拉取:继续观看、资料库、服务端编排的发现页板块
                val pageDeferred = async { runCatching { api.discoveryPage(
                        mediaType = _ui.value.mediaType,
                        source = _ui.value.source,
                        genres = _ui.value.filters.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                        country = _ui.value.filters.country,
                        year = _ui.value.filters.year,
                        rating = _ui.value.filters.rating,
                        runtime = _ui.value.filters.runtime,
                        sort = _ui.value.filters.sort,
                    ).dataOrThrow() }.getOrNull() }

                val rows = pageDeferred.await()?.sections?.let { sections ->
                    coroutineScope {
                        sections
                            .filter { it.collectionRef.isNotEmpty() }
                            .map { section ->
                                async {
                                    val titles = runCatching {
                                        api.collectionTitles(
                                            section.collectionRef,
                                            limit = section.previewLimit.coerceIn(6, 30),
                                        ).dataOrThrow().titles
                                    }.getOrDefault(emptyList())
                                    DiscoverRow(section, titles)
                                }
                            }
                            .awaitAll()
                            .filter { it.titles.isNotEmpty() }
                    }
                }.orEmpty()

                _ui.update {
                    it.copy(
                        loading = false,
                        rows = rows,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = friendlyMessage(e)) }
            }
        }
    }
}

/** 发现页:搜索入口 + 电影/剧集 + 服务端板块 + 继续观看 + 资料库 */
@Composable
fun DiscoverScreen(
    onOpenLibrary: (Long, String) -> Unit,
    onOpenItem: (Long, Long) -> Unit,
    onPlay: (PlayTarget) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTitle: (String) -> Unit,
    onOpenCollection: (String, String) -> Unit,
    /** 未订阅 → 订阅弹层；已订阅 → 订阅管理（与网页「订阅影片 / 已订阅」同语义） */
    onSubscribeTitle: (String, io.movieclaw.android.feature.subscriptions.SubscribeSheetHost.Seed?) -> Unit,
    onOpenFiltered: () -> Unit = {},
    subscriptions: SubscriptionIndex,
    vm: DiscoverViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val origin = vm.origin
    // 全站订阅索引：hero 与每张卡片的「订阅影片 / 已订阅 · 状态」都由它判断，
    // 不再拿"是否在库里"冒充"是否已订阅"
    val subscriptionIndex by subscriptions.byKey.collectAsStateWithLifecycle()
    LaunchedEffect(origin, subscriptionIndex) { subscriptions.ensureLoaded() }

    val scroll = rememberScrollState()
    io.movieclaw.android.core.designsystem.TrackTabBarMinimize(scroll)
    // 英雄轮播的数据来自 presentation == "hero" 那一行（六屏）
    val heroRow = remember(state.rows) { state.rows.firstOrNull { it.section.presentation == "hero" } }
    val heroSlides = remember(heroRow, origin, subscriptionIndex) {
        heroRow?.titles.orEmpty().map { title ->
            title.toHeroSlide(
                origin,
                subscriptions.findIn(subscriptionIndex, title.provider, title.externalId, title.mediaType),
            )
        }
    }
    var heroPage by remember { mutableIntStateOf(0) }
    // 氛围底色 = 当前这一屏剧照的主色（同一套取色算法）
    val ambient = rememberAmbientColor(heroSlides.getOrNull(heroPage)?.backdropUrl, origin)
    var showSourceMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }

    // 展开层宿主：同一时刻只展开一张海报卡（网页 hover 层在触摸端的等价物）
    val posterReveal = remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.CompositionLocalProvider(LocalPosterReveal provides posterReveal) {
    Box(Modifier.fillMaxSize()) {
        // 氛围底在页面根、滚动容器之外（实测如此），随滚动退淡
        HeroAmbientBackdrop(
            color = ambient,
            scrollPx = scroll.value.toFloat(),
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bottom = McTabBarContentPadding),
        ) {
            if (heroSlides.isNotEmpty()) {
                HeroCarousel(
                    slides = heroSlides,
                    currentPage = heroPage,
                    onPageChange = { heroPage = it },
                    onOpenSlide = { onOpenTitle(it.id) },
                    onAction = { slide ->
                        onSubscribeTitle(
                            slide.id,
                            io.movieclaw.android.feature.subscriptions.SubscribeSheetHost.Seed(
                                title = slide.title,
                                posterUrl = slide.backdropUrl,
                                year = slide.year?.toIntOrNull(),
                                kind = if (state.mediaType == "tv") "tv" else "movie",
                            ),
                        )
                    },
                    // iOS ImmersiveHero：图随滚动 0.4× 下移（只下不上）
                    parallaxPx = maxOf(0f, scroll.value.toFloat()) * 0.4f,
                    // 文字与指示器在 260pt 内淡尽
                    contentFade = (1f - scroll.value / 260f).coerceIn(0f, 1f),
                )
            }

        when {
            state.loading && state.rows.isEmpty() -> Column {
                SkeletonBlock(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(190.dp),
                    radius = 18.dp,
                )
                Spacer(Modifier.height(20.dp))
                PosterRowSkeleton("正在加载")
                Spacer(Modifier.height(20.dp))
                PosterRowSkeleton(" ")
            }
            state.error != null -> ErrorPane(
                message = state.error!!,
                onRetry = vm::load,
                modifier = Modifier.fillMaxWidth().height(360.dp),
            )
            else -> {
                // iOS 发现页只有「英雄 + 服务端板块」：继续观看属于媒体库首页，这里不再重复

                state.rows.forEach { row ->
                    if (row.section.presentation == "hero") return@forEach
                    Spacer(Modifier.height(if (row === state.rows.first { it.section.presentation != "hero" }) 0.dp else McMetrics.sectionTop))
                    SectionHeader(
                        title = row.section.title,
                        // iOS：行右侧是「查看完整榜单」（subheadline/600、textMuted），不是「全部」
                        actionText = if (row.section.supportsFullListing) "查看完整榜单" else null,
                        onAction = if (row.section.supportsFullListing) {
                            { onOpenCollection(row.section.collectionRef, row.section.title) }
                        } else {
                            null
                        },
                    )
                    Spacer(Modifier.height(McMetrics.sectionBottom))
                    run {
                        McRow {
                            itemsIndexed(row.titles, key = { idx, it -> "${row.section.title}#$idx#" + it.titleRef.ifEmpty { it.title } }) { idx, item ->
                                // 用**收集成 Compose 状态**的 map 查（见 SubscriptionIndex.findIn 的说明）
                                val subscribed = subscriptions.findIn(
                                    subscriptionIndex,
                                    item.provider,
                                    item.externalId,
                                    item.mediaType,
                                ) != null
                                PosterCard(
                                    imageUrl = item.posterUrl,
                                    origin = origin,
                                    title = item.title,
                                    meta = posterMeta(item),
                                    rating = item.providerRating.takeIf { it > 0f },
                                    // 常显斜标：已入库优先（绿），否则已订阅（蓝）——网页同口径
                                    ribbon = when {
                                        item.libraryStatus != null -> PosterRibbon.OWNED
                                        subscribed -> PosterRibbon.SUBSCRIBED
                                        else -> null
                                    },
                                    modifier = Modifier.width(McMetrics.rowCardWidth),
                                    // 卡片动作键（移动端网页实测）：已订阅 = 中性底 + 绿勾 +「已订阅」；
                                    // 未订阅 = 强调色 +「订阅影片/订阅剧集」（**在库但未订阅照样给订阅键**，
                                    // 卡片右上另有「在库」徽标）。两种状态的点击都打开订阅弹层。
                                    actionLabel = if (subscribed) {
                                        "已订阅"
                                    } else if (state.mediaType == "tv") {
                                        "订阅剧集"
                                    } else {
                                        "订阅影片"
                                    },
                                    actionIcon = if (subscribed) Icons.Rounded.Check else Icons.Rounded.Add,
                                    actionNeutral = subscribed,
                                    onAction = {
                                        onSubscribeTitle(
                                            item.titleRef,
                                            io.movieclaw.android.feature.subscriptions.SubscribeSheetHost.Seed(
                                                title = item.title,
                                                posterUrl = item.posterUrl,
                                                year = item.releaseYear,
                                                kind = item.mediaType,
                                            ),
                                        )
                                    },
                                    onClick = { onOpenTitle(item.titleRef) },
                                    // 展开层的唯一标识：同一部片可能同时出现在「今日热榜」和「正在热映」，
                                    // 只按片名做 key 会让一处展开、处处展开（用户报的现象）
                                    instanceKey = "${row.section.title}#$idx#${item.titleRef}", 
                                )
                            }
                        }
                    }
                }

                if (state.libraries.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader("我的资料库")
                    Spacer(Modifier.height(11.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.libraries, key = { it.id }) { library ->
                            LibraryMiniCard(library = library) { onOpenLibrary(library.id, library.name) }
                        }
                    }
                }

                if (state.rows.isEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Text("发现页暂无板块", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "服务端未配置发现页板块(或未配置 TMDB)。可在服务器网页端「设置 → 发现页」检查。",
                            fontSize = 11.5.sp,
                            color = TextMuted,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }

    // 顶栏：大标题 30/700 + 数据源小字(可弹) + 右侧「全部」胶囊(可弹)与搜索圆钮。
    McTopBar(
        variant = McTopBarVariant.Discover,
        title = if (state.mediaType == "tv") "剧集" else "电影",
        sourceLabel = if (state.source.equals("douban", ignoreCase = true)) "豆瓣" else "TMDB",
        onSourceClick = { showFilterMenu = false; showSourceMenu = !showSourceMenu },
        mistStrength = 0.5f,
        mistHeight = 135.dp,
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        Text(
            state.filters.label(),
            style = McType.subSemibold,
            color = Color.White.copy(alpha = 0.92f),
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(GlassCapsule)
                .border(1.dp, LineSoft, RoundedCornerShape(999.dp))
                .clickable { showSourceMenu = false; onOpenFiltered() }
                .padding(horizontal = 14.dp)
                .wrapContentHeight(),
        )
        Spacer(Modifier.width(8.dp))
        McNavButton(Icons.Rounded.Search, contentDescription = "搜索", onClick = onOpenSearch)
    }

    // 菜单层。网页的 page-scrim 在 z-5、.app-shell 在 z-10，
    // 遮罩永远被盖住——所以弹出时背景不模糊也不变暗，
    // 只有一层透明的点外关闭区。
    if (showSourceMenu || showFilterMenu) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    showSourceMenu = false
                    showFilterMenu = false
                },
        )
    }

    if (showSourceMenu) {
        SourceMenu(
            mediaType = state.mediaType,
            source = state.source,
            onPickMediaType = { vm.switchMediaType(it); showSourceMenu = false },
            onPickSource = { vm.switchSource(it); showSourceMenu = false },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = McMetrics.topBarInsetRoot, top = McMetrics.topBarHeight),
        )
    }
    if (showFilterMenu) {
        DiscoveryFilterMenu(
            filter = state.filters,
            onApply = { vm.applyFilters(it); showFilterMenu = false },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = McMetrics.topBarInsetRoot, top = McMetrics.topBarHeight),
        )
    }
    }
}
}

/**
 * DiscoveredTitle → 轮播一屏。
 *
 * [sub] 是这部作品的**真实订阅**（来自全站订阅索引）。以前这里用 `libraryStatus != null`
 * 判断，等于把"已入库"当成"已订阅"——库里已有的片哪怕从没订阅过也显示「已订阅 ·
 * 追踪中」（用户报的正是这个）。入库看 library_status，订阅只能看订阅本身。
 */
private fun DiscoveredTitle.toHeroSlide(origin: String?, sub: SubscriptionView?) = HeroSlide(
    id = titleRef,
    backdropUrl = backdropUrl ?: posterUrl,
    origin = origin,
    label = "今日精选 · " + if (mediaType == "tv") "剧集" else "电影",
    title = title,
    originalTitle = originalTitle.takeIf { it.isNotBlank() && it != title },
    rating = providerRating.takeIf { it > 0f },
    year = releaseYear?.toString(),
    genres = genres.take(3).joinToString(" / ").takeIf { it.isNotBlank() },
    synopsis = overview.takeIf { it.isNotBlank() },
    // 用户指定：电影「订阅影片」、剧集「订阅剧集」（iOS 本身统一叫「订阅影片」，此处按用户要求切换）；
    // 已订阅时显示真实状态（追踪中 / 已暂停 / 已收齐），与详情页说的是同一套词
    actionLabel = if (sub != null) {
        "已订阅 · ${sub.statusLabel()}"
    } else if (mediaType == "tv") {
        "订阅剧集"
    } else {
        "订阅影片"
    },
    subscribed = sub != null,
)

@Composable
private fun HeroTitle(title: DiscoveredTitle, origin: String?, onClick: (DiscoveredTitle) -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick(title) },
    ) {
        RemoteImage(
            url = title.backdropUrl ?: title.posterUrl,
            origin = origin,
            contentDescription = title.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                    )
                ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(
                title.title,
                style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    title.releaseYear?.toString(),
                    title.genres.take(2).joinToString(" / ").takeIf { it.isNotEmpty() },
                    title.providerRating.takeIf { it > 0f }?.let { "★ ${"%.1f".format(it)}" },
                ).joinToString(" · "),
                fontSize = 11.5.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun MediaTypeSwitcher(current: String, onSelect: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        listOf("movie" to "电影", "tv" to "剧集").forEach { (id, label) ->
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (id == current) Color(0xFF0A0E12) else TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (id == current) Accent else Color.White.copy(alpha = 0.05f))
                    .clickable { onSelect(id) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}

internal fun upNextSubtitle(item: UpNextItem): String {
    val remaining = ((item.durationMs - item.positionMs).coerceAtLeast(0)) / 60000
    return when {
        item.kind == "tv" && item.seasonNumber > 0 ->
            "S%02dE%02d · 还剩 %d 分钟".format(item.seasonNumber, item.episodeNumber, remaining)
        else -> "还剩 $remaining 分钟"
    }
}

@Composable
private fun SearchBarHint(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = TextFaint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        Text("搜索标题、种子、媒体库…", fontSize = 13.5.sp, color = TextFaint)
    }
}

@Composable
private fun LibraryMiniCard(library: LibraryView, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(library.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(kindLabel(library.kind), fontSize = 11.sp, color = TextFaint)
        if (!library.viewerAccess) {
            Spacer(Modifier.height(6.dp))
            Text("仅管理权限", fontSize = 10.sp, color = Warning)
        }
    }
}

internal fun kindLabel(kind: String): String = when (kind) {
    "movie" -> "电影库"
    "tv" -> "剧集库"
    else -> "其他"
}

/** 海报卡元信息行:"2024 · 2 小时 8 分"(iOS 同款格式) */
internal fun posterMeta(item: DiscoveredTitle): String = listOfNotNull(
    item.releaseYear?.toString(),
    item.extentLabel.takeIf { it.isNotEmpty() },
).joinToString(" · ")
