package io.movieclaw.android.feature.subscriptions

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.network.dataOrThrow
import io.movieclaw.android.core.network.friendlyMessage
import io.movieclaw.android.core.playback.PlayTarget
import io.movieclaw.android.core.api.McApi
import io.movieclaw.android.core.designsystem.Accent
import io.movieclaw.android.core.designsystem.Bg
import io.movieclaw.android.core.designsystem.LineSoft
import io.movieclaw.android.core.designsystem.McMetrics
import io.movieclaw.android.core.designsystem.McTabBarContentPadding
import io.movieclaw.android.core.designsystem.McType
import io.movieclaw.android.core.designsystem.Ok
import io.movieclaw.android.core.designsystem.RemoteImage
import io.movieclaw.android.core.designsystem.Success
import io.movieclaw.android.core.designsystem.TextFaint
import io.movieclaw.android.core.designsystem.TextMuted
import io.movieclaw.android.core.designsystem.TextPrimary
import io.movieclaw.android.core.designsystem.Warn
import io.movieclaw.android.core.discovery.rememberAmbientColor
import io.movieclaw.android.core.model.MediaBrief
import io.movieclaw.android.core.model.RecentArrivalView
import io.movieclaw.android.core.model.SubscriptionView
import io.movieclaw.android.core.model.TodayArrivalFull
import io.movieclaw.android.core.session.SessionRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/* ══════════ 数据模型（对齐 iOS SubsHomeModel） ══════════ */

enum class SubsStage { Downloading, Organizing, Arrived, Today, Upcoming, Resting }

data class SubsHeroSlide(
    val subscriptionId: Long,
    val media: MediaBrief,
    val stage: SubsStage,
    val eyebrow: String? = null,
    val eyebrowDot: Color? = null,
    val clockLabel: String? = null,
    val clock: String? = null,
    val detail: String? = null,
    val footnote: String? = null,
    val play: PlayTarget? = null,
    val resumePercent: Int? = null,
)

data class SubsDay(
    val date: LocalDate,
    val label: String,
    val dayNum: Int,
    val isToday: Boolean,
    val entries: List<TodayArrivalFull>,
)

data class SubsHomeState(
    val loading: Boolean = true,
    val error: String? = null,
    val slides: List<SubsHeroSlide> = emptyList(),
    val recent: List<RecentArrivalView> = emptyList(),
    val days: List<SubsDay> = emptyList(),
    val tv: List<SubscriptionView> = emptyList(),
    val movie: List<SubscriptionView> = emptyList(),
    val all: List<SubscriptionView> = emptyList(),
)

/* ══════════ ViewModel：订阅首页聚合 ══════════ */

@HiltViewModel
class SubsHomeViewModel @Inject constructor(
    private val apiFactory: ApiFactory,
    private val repository: SessionRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(SubsHomeState())
    val ui = _ui.asStateFlow()

    val origin: String? get() = repository.ui.value.origin

    init { load() }

    /** 取消/新增订阅后从别处回来时重新拉（详情页是独立导航目标，列表不会自己重建） */
    fun reloadIfChanged(revision: Int) {
        if (revision != lastSeenRevision) {
            lastSeenRevision = revision
            load()
        }
    }

    private var lastSeenRevision = SubscriptionEvents.revision

    fun load() {
        viewModelScope.launch {
            val origin = origin
            if (origin == null) { _ui.update { it.copy(loading = false, error = "尚未连接服务器") }; return@launch }
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val api = apiFactory.forOrigin(origin)
                val subs: List<SubscriptionView>
                val arrivals: List<TodayArrivalFull>
                val recent: List<RecentArrivalView>
                coroutineScope {
                    val a = async { runCatching { api.subscriptions().dataOrThrow() }.getOrDefault(emptyList()) }
                    val b = async { runCatching { api.todayArrivalsFull().dataOrThrow() }.getOrDefault(emptyList()) }
                    val c = async { runCatching { api.recentArrivals().dataOrThrow() }.getOrDefault(emptyList()) }
                    subs = a.await(); arrivals = b.await(); recent = c.await()
                }
                val now = LocalDate.now()
                _ui.update {
                    it.copy(
                        loading = false,
                        slides = buildSlides(subs, arrivals, recent),
                        recent = recent,
                        days = buildDays(arrivals, now),
                        tv = subs.filter { s -> s.media.kind == "tv" },
                        movie = subs.filter { s -> s.media.kind == "movie" },
                        all = subs,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = friendlyMessage(e)) }
            }
        }
    }

    /** 入库管线：下载中 → 整理中 → 刚入库 → 今天 → 排期；全空时回退「在追的前三」 */
    private fun buildSlides(
        subs: List<SubscriptionView>,
        arrivals: List<TodayArrivalFull>,
        recent: List<RecentArrivalView>,
    ): List<SubsHeroSlide> {
        val slides = mutableListOf<SubsHeroSlide>()
        val byId = subs.associateBy { it.id }
        val now = java.time.LocalDateTime.now()

        arrivals.forEach { arr ->
            val media = byId[arr.subscriptionId]?.media ?: MediaBrief(title = arr.mediaTitle, kind = arr.mediaKind)
            val label = if (arr.mediaKind == "tv") "S%02dE%02d".format(arr.seasonNumber, arr.episodeNumber) else null
            val downloading = arr.grabbedAt != null && arr.downloadedAt == null
            val organizing = arr.downloadedAt != null
            when {
                downloading -> {
                    val eta = (arr.estimatedDownloadToImportMinutes ?: arr.estimatedReleaseToImportMinutes)
                        ?.let { now.plusMinutes(it.toLong()) }
                    slides += if (eta != null) SubsHeroSlide(
                        arr.subscriptionId, media, SubsStage.Downloading,
                        eyebrow = "下载中", eyebrowDot = Color(0xFF7FB0FF),
                        clockLabel = if (arr.mediaKind == "movie") "预计可看" else "$label · 预计可看",
                        clock = clockText(eta),
                    ) else SubsHeroSlide(
                        arr.subscriptionId, media, SubsStage.Downloading,
                        eyebrow = "下载中", eyebrowDot = Color(0xFF7FB0FF),
                        detail = if (arr.mediaKind == "movie") "正在下载" else "$label · 正在下载",
                        footnote = "下载完成后自动整理入库",
                    )
                }
                organizing -> slides += SubsHeroSlide(
                    arr.subscriptionId, media, SubsStage.Organizing,
                    eyebrow = "整理中", eyebrowDot = Ok,
                    clockLabel = if (arr.mediaKind == "movie") "下载完成" else "$label · 下载完成",
                    clock = "马上就好",
                )
                arr.daysAhead > 0 -> slides += SubsHeroSlide(
                    arr.subscriptionId, media, SubsStage.Upcoming,
                    eyebrow = null,  // calm 态：状态文字只进读屏，不显示
                    clockLabel = listOfNotNull(label, formatCalendarDay(arr.expectedDay)).joinToString(" · "),
                    clock = if (arr.daysAhead == 1) "明天" else weekday(arr.expectedDay) ?: "${arr.daysAhead} 天后",
                )
                else -> slides += SubsHeroSlide(
                    arr.subscriptionId, media, SubsStage.Today,
                    eyebrow = "今天更新", eyebrowDot = Color(0xFFD9D6FF),
                    clockLabel = "$label · 预计入库",
                    clock = arr.estimatedReleaseToImportMinutes?.let { clockText(now.plusMinutes(it.toLong())) },
                )
            }
        }

        recent.forEach { card ->
            // RecentArrivalView 暂无 imported_at 字段：脚注显示「已入库」；接口补充后接相对时间
            val fresh = false
            val eyebrow = if (fresh) "刚刚入库" else if (card.media.kind == "tv") "新一集" else "新入库"
            val footnote = buildString {
                append("已入库")
                if (card.units.size > 1) append(" · 共 ${card.units.size} 集新内容")
            }
            slides += SubsHeroSlide(
                card.subscriptionId, card.media, SubsStage.Arrived,
                eyebrow = eyebrow, eyebrowDot = Ok,
                detail = recentDetail(card),
                footnote = footnote,
                play = PlayTarget(
                    mediaItemId = card.media.mediaItemId,
                    libraryId = 0,
                    kind = card.media.kind,
                    title = card.media.title,
                    seasonNumber = card.seasonNumber,
                    episodeNumber = card.episodeNumber,
                ),
                resumePercent = card.progressPercent,
            )
        }

        if (slides.isEmpty()) {
            subs.filter { it.status == "active" }.take(3).forEach { sub ->
                val movie = sub.media.kind == "movie"
                slides += SubsHeroSlide(
                    sub.id, sub.media, SubsStage.Resting,
                    eyebrow = null,  // 追踪中：calm，不显示
                    detail = if (movie) listOfNotNull(sub.media.year?.toString(), "电影").joinToString(" · ") else "剧集",
                    footnote = if (movie) (if (sub.progress.imported > 0) "已在媒体库里" else "上映后开始找资源") else "有新一集会自动下载入库",
                )
            }
        }
        return slides
    }

    private fun buildDays(arrivals: List<TodayArrivalFull>, today: LocalDate): List<SubsDay> {
        val days = (0..6L).map { today.plusDays(it) }
        val extra = arrivals.mapNotNull { a -> a.expectedDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }
            .filter { it.isAfter(today.plusDays(6)) }
        return (days + extra).map { date ->
            SubsDay(
                date = date,
                label = if (date == today) "今天" else chineseWeekday(date),
                dayNum = date.dayOfMonth,
                isToday = date == today,
                entries = arrivals.filter { it.expectedDay == date.toString() },
            )
        }
    }

    companion object {
        fun recentDetail(card: RecentArrivalView): String =
            if (card.media.kind != "tv") {
                listOfNotNull(card.media.year?.toString(), "电影").joinToString(" · ")
            } else {
                val code = "S%02dE%02d".format(card.seasonNumber, card.episodeNumber)
                if (card.episodeName.isNullOrBlank()) code else "$code · ${card.episodeName}"
            }

        fun clockText(eta: java.time.LocalDateTime, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String {
            val hm = eta.format(DateTimeFormatter.ofPattern("HH:mm"))
            return when {
                eta.toLocalDate() == now.toLocalDate() -> hm
                eta.toLocalDate() == now.toLocalDate().plusDays(1) -> "明天 $hm"
                else -> eta.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
            }
        }

        fun weekday(day: String?): String? {
            val d = day?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            return chineseWeekday(d)
        }

        fun formatCalendarDay(day: String?): String? {
            val d = day?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            return "${d.monthValue}月${d.dayOfMonth}日"
        }

        fun chineseWeekday(d: LocalDate): String =
            arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[d.dayOfWeek.value - 1]

        fun fromNow(dt: java.time.LocalDateTime, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String {
            val mins = java.time.Duration.between(dt, now).toMinutes()
            return when {
                mins < 60 -> "${mins} 分钟前"
                mins < 60 * 24 -> "${mins / 60} 小时前"
                else -> "${mins / 60 / 24} 天前"
            }
        }
    }
}

/* ══════════ 界面 ══════════ */

private val Dots = mapOf("ok" to Ok, "warn" to Warn, "live" to Color(0xFF7FB0FF), "today" to Color(0xFFD9D6FF))

@Composable
fun SubsHomeScreen(
    onOpenSubscription: (Long) -> Unit,
    onPlay: (PlayTarget) -> Unit,
    onOpenTitle: (String) -> Unit = {},
    /** 空态那颗「去发现剧集」：切到发现页 */
    onOpenDiscover: () -> Unit = {},
    vm: SubsHomeViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    // 订阅数据在别处被改动（详情页取消订阅 / 新建订阅）后，回到这里要能看到最新结果
    androidx.compose.runtime.LaunchedEffect(SubscriptionEvents.revision) {
        vm.reloadIfChanged(SubscriptionEvents.revision)
    }
    val scroll = rememberScrollState()
    io.movieclaw.android.core.designsystem.TrackTabBarMinimize(scroll)

    Box(Modifier.fillMaxSize().background(Bg)) {
        // 氛围底 = 当前英雄剧照主色，随滚动退淡（下限 0.35，900dp 淡尽）
        val slide = state.slides.getOrNull(0)
        val ambient = rememberAmbientColor(slide?.media?.backdropUrl, vm.origin)
        val ambAlpha = (1f - scroll.value / 900f).coerceIn(0.35f, 1f)
        Box(
            Modifier.fillMaxSize().alpha(ambAlpha).background(
                Brush.verticalGradient(
                    0f to (ambient ?: Color(0xFF20221C)).copy(alpha = 0.85f),
                    0.42f to (ambient ?: Color(0xFF20221C)).copy(alpha = 0.5f),
                    0.72f to (ambient ?: Color(0xFF20221C)).copy(alpha = 0.14f),
                    1f to Color.Transparent,
                )
            )
        )

        Text(
            "我的订阅",
            fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp,
            color = TextPrimary,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = McMetrics.pagePadding, top = 2.dp)
                .zIndex(3f),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bottom = McTabBarContentPadding),
        ) {
            if (state.loading && state.slides.isEmpty()) {
                Spacer(Modifier.height(300.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TextMuted)
                }
            } else if (state.all.isEmpty()) {
                // 一条订阅都没有：空数据不是故障，用与全站一致的银玻璃展台承接
                // （网页 `ContentEmptyState variant="subscription"`）
                Spacer(Modifier.height(McMetrics.topBarHeight + 40.dp))
                SubscriptionEmptyState(onOpenDiscover = onOpenDiscover)
            } else if (state.error != null && state.slides.isEmpty()) {
                Spacer(Modifier.height(200.dp))
                Text(state.error!!, color = TextMuted, modifier = Modifier.padding(horizontal = 24.dp))
            } else if (state.slides.isNotEmpty()) {
                SubsHero(
                    slides = state.slides,
                    scrollValue = scroll.value,
                    onPlay = onPlay,
                    onOpenSubscription = onOpenSubscription,
                )
            }

            Column(Modifier.padding(top = 22.dp)) {
                if (state.recent.isNotEmpty()) {
                    SectionHeader("刚刚入库", if (state.recent.size > 1) "${state.recent.size} 部" else null)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = McMetrics.pagePadding),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(state.recent, key = { it.subscriptionId.toString() + "-" + it.episodeNumber }) { card ->
                            ArrivalCard(
                                card = card, origin = vm.origin, onPlay = onPlay,
                                onOpenSubscription = onOpenSubscription, onOpenTitle = onOpenTitle,
                            )
                        }
                    }
                    Spacer(Modifier.height(36.dp))
                }

                val dayWithEntries = state.days.firstOrNull { it.entries.isNotEmpty() }
                if (dayWithEntries != null) {
                    ScheduleSection(days = state.days, default = dayWithEntries, onOpenSubscription = onOpenSubscription)
                    Spacer(Modifier.height(36.dp))
                }

                if (state.tv.isNotEmpty()) {
                    val active = state.tv.filter { it.status == "active" }
                    val resting = state.tv.filterNot { it.status == "active" }
                    ShelfHeader("剧集订阅", countSummary(state.tv, active.size))
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = McMetrics.pagePadding),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(active, key = { it.id }) { sub -> SubCard(sub, dim = false) { onOpenSubscription(sub.id) } }
                        if (resting.isNotEmpty()) {
                            item(key = "div-tv") { RestingDivider(restingLabel(resting), height = 189.dp) }
                            items(resting, key = { "r-${it.id}" }) { sub -> SubCard(sub, dim = true) { onOpenSubscription(sub.id) } }
                        }
                        item(key = "seeall-tv") { SeeAllCard(state.tv.size) { onOpenSubscription(-1) } }
                    }
                    Spacer(Modifier.height(36.dp))
                }

                if (state.movie.isNotEmpty()) {
                    val active = state.movie.filter { it.status == "active" }
                    val resting = state.movie.filterNot { it.status == "active" }
                    ShelfHeader("电影订阅", countSummary(state.movie, active.size))
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = McMetrics.pagePadding),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(active, key = { it.id }) { sub -> SubCard(sub, dim = false) { onOpenSubscription(sub.id) } }
                        if (resting.isNotEmpty()) {
                            item(key = "div-mv") { RestingDivider(restingLabel(resting), height = 189.dp) }
                            items(resting, key = { "rm-${it.id}" }) { sub -> SubCard(sub, dim = true) { onOpenSubscription(sub.id) } }
                        }
                        item(key = "seeall-mv") { SeeAllCard(state.movie.size) { onOpenSubscription(-1) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubsHero(
    slides: List<SubsHeroSlide>,
    scrollValue: Int,
    onPlay: (PlayTarget) -> Unit,
    onOpenSubscription: (Long) -> Unit,
) {
    val pager = rememberPagerState(pageCount = { slides.size })
    // 自动轮播：8s 前进一屏
    LaunchedEffect(pager, slides.size) {
        while (slides.size > 1) {
            kotlinx.coroutines.delay(8000)
            pager.animateScrollToPage((pager.currentPage + 1) % slides.size)
        }
    }
    val fade = (1f - scrollValue / 260f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(500.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val s = slides[page]
            val active = page == pager.currentPage
            val zoom = remember(page) { Animatable(1f) }
            LaunchedEffect(active) {
                if (active) {
                    zoom.snapTo(1f)
                    zoom.animateTo(1.1f, tween(12000, easing = LinearEasing))
                } else zoom.snapTo(1f)
            }
            Box(Modifier.fillMaxSize().graphicsLayer { translationY = scrollValue * 0.15f }) {
                RemoteImage(
                    url = s.media.backdropUrl ?: s.media.posterUrl,
                    origin = null,
                    contentDescription = s.media.title,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom.value; scaleY = zoom.value
                    },
                    contentScale = ContentScale.Crop,
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.5f), 0.26f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.36f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f))))
                Column(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = fade; translationY = scrollValue * 0.15f }
                        .padding(start = 28.dp, end = 28.dp, bottom = 44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    if (!s.media.logoUrl.isNullOrBlank()) {
                        RemoteImage(
                            url = s.media.logoUrl!!,
                            origin = null,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(88.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            s.media.title,
                            fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
                            color = Color.White, maxLines = 2,
                            modifier = Modifier.fillMaxWidth(0.8f),
                        )
                    }
                    if (!s.eyebrow.isNullOrBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            s.eyebrowDot?.let { Box(Modifier.size(7.dp).clip(CircleShape).background(it)) ; Spacer(Modifier.width(7.dp)) }
                            Text(s.eyebrow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f))
                        }
                    }
                    if (!s.detail.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(s.detail, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f))
                    }
                    if (!s.footnote.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(s.footnote, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                    }
                    if (!s.clockLabel.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(s.clockLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f))
                    }
                    if (!s.clock.isNullOrBlank()) {
                        val hasDigit = s.clock.any { it.isDigit() }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            s.clock,
                            fontSize = if (hasDigit) 48.sp else 36.sp,
                            fontWeight = if (hasDigit) FontWeight.Thin else FontWeight.Light,
                            color = Color.White,
                        )
                    }
                    if (s.stage == SubsStage.Downloading && s.play == null) {
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.width(168.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f)))
                    }
                    Spacer(Modifier.height(20.dp))
                    val play = s.play
                    if (play != null) {
                        Box(
                            Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFFF6F8FC), Color(0xFFCCD6E6))))
                                .clickable { onPlay(play) }
                                .padding(horizontal = 22.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color(0xFF141821), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (s.resumePercent == null || s.resumePercent == 0) "播放" else "继续播放",
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF141821),
                                )
                            }
                        }
                    } else {
                        Box(
                            Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.14f))
                                .border(1.dp, LineSoft, RoundedCornerShape(999.dp))
                                .clickable { onOpenSubscription(s.subscriptionId) }
                                .padding(horizontal = 22.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("查看订阅", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
                    }
                }
            }
        }
        // 指示器：底部居中；单屏不显示
        if (slides.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                slides.indices.forEach { i ->
                    if (i == pager.currentPage) {
                        Box(Modifier.width(26.dp).height(5.dp).clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.26f)))
                    } else {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.34f)))
                    }
                }
            }
        }
    }
}

/** 状态小签推导（iOS SubsHomeShelfItem.chip 的简化口径） */
private fun subChip(sub: SubscriptionView): Pair<String, Color> = when {
    sub.status == "paused" -> "已暂停" to TextMuted
    sub.status == "completed" -> (if (sub.media.kind == "movie") "已入库" else "已收齐") to Ok
    sub.progress.upgrading > 0 -> "洗版中" to Color(0xFF2DD4BF)
    sub.progress.grabbed > 0 -> "下载中" to Color(0xFF7FB0FF)
    sub.progress.imported > 0 && sub.progress.imported < sub.progress.total -> "缺 ${sub.progress.total - sub.progress.imported} 集" to Warn
    else -> "找资源中" to Warn
}

private fun restingLabel(resting: List<SubscriptionView>): String {
    val paused = resting.any { it.status == "paused" }
    val done = resting.any { it.status == "completed" }
    return when {
        paused && done -> "暂停·收齐"
        paused -> "已暂停"
        done -> if (resting.all { it.media.kind == "movie" }) "已入库" else "已收齐"
        else -> "已结束"
    }
}

@Composable
private fun SectionHeader(title: String, count: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = McMetrics.pagePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        if (count != null) Text(count, fontSize = 13.sp, color = TextFaint)
    }
}

@Composable
private fun ShelfHeader(title: String, count: String, onOpenWall: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = McMetrics.pagePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clickable(onClick = onOpenWall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
                tint = TextFaint, modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(count, fontSize = 13.sp, color = TextFaint)
    }
}

/** iOS countSummary：「N 部进行中 · 共 M 部」（全部进行中或没有时只说总数） */
private fun countSummary(all: List<SubscriptionView>, active: Int): String =
    if (active > 0 && active < all.size) "$active 部进行中 · 共 ${all.size} 部" else "共 ${all.size} 部"

@Composable
private fun ArrivalCard(
    card: RecentArrivalView,
    origin: String?,
    onPlay: (PlayTarget) -> Unit,
    onOpenSubscription: (Long) -> Unit = {},
    onOpenTitle: (String) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.width(264.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF101219))
                .combinedClickable(
                    onClick = {
                    onPlay(
                        PlayTarget(
                            mediaItemId = card.media.mediaItemId, libraryId = 0, kind = card.media.kind,
                            title = card.media.title, seasonNumber = card.seasonNumber, episodeNumber = card.episodeNumber,
                        )
                    )
                    },
                    onLongClick = { menuOpen = true },
                ),
        ) {
            RemoteImage(
                url = card.stillUrl ?: card.media.backdropUrl ?: card.media.posterUrl,
                origin = origin,
                contentDescription = card.media.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
            // iOS：多集才挂「新 N 集」小签
            if (card.units.size > 1) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(9.dp).clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF06281C)).padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Ok))
                    Spacer(Modifier.width(4.dp))
                    Text("新 ${card.units.size} 集", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Ok)
                }
            }
            if (!card.media.logoUrl.isNullOrBlank()) {
                RemoteImage(
                    url = card.media.logoUrl!!,
                    origin = origin,
                    contentDescription = null,
                    // iOS：maxWidth 118 × maxHeight 34，内距 12，左下
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).width(118.dp).height(34.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(18.dp)) }
            card.progressPercent?.takeIf { it > 0 }?.let { pct ->
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(pct / 100f).height(3.dp).background(Color.White))
                }
            }
        }
        Text(card.media.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
        Text(
            SubsHomeViewModel.recentDetail(card),
            fontSize = 12.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        // iOS note：X 入库 · 共 N 集新内容 · 看到 N%
        val note = buildList {
            add("已入库")
            if (card.units.size > 1) add("共 ${card.units.size} 集新内容")
            card.progressPercent?.takeIf { it > 0 }?.let { add("看到 $it%") }
        }.joinToString(" · ")
        Text(note, fontSize = 12.sp, color = TextFaint, modifier = Modifier.padding(top = 2.dp))

        // 长按菜单（iOS contextMenu：播放 / 查看订阅详情 / 查看影片详情）
        androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("播放") },
                onClick = {
                    menuOpen = false
                    onPlay(
                        PlayTarget(
                            mediaItemId = card.media.mediaItemId, libraryId = 0, kind = card.media.kind,
                            title = card.media.title, seasonNumber = card.seasonNumber, episodeNumber = card.episodeNumber,
                        )
                    )
                },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("查看订阅详情") },
                onClick = { menuOpen = false; onOpenSubscription(card.subscriptionId) },
            )
            val ref = card.media.tmdbId?.let { "tmdb:${card.media.kind}:$it" }
            if (ref != null) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("查看影片详情") },
                    onClick = { menuOpen = false; onOpenTitle(ref) },
                )
            }
        }
    }
}

@Composable
private fun ScheduleSection(days: List<SubsDay>, default: SubsDay, onOpenSubscription: (Long) -> Unit) {
    var selected by remember { mutableIntStateOf(days.indexOfFirst { it.date == default.date }.coerceAtLeast(0)) }
    Column {
        Row(Modifier.padding(horizontal = McMetrics.pagePadding), verticalAlignment = Alignment.CenterVertically) {
            Text("日程", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            Text("${default.date.monthValue}月${default.date.dayOfMonth}日 · ${default.entries.size} 部", fontSize = 13.sp, color = TextMuted)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = McMetrics.pagePadding), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { day ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (day == days[selected]) Color.White else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (day == days[selected]) Color.Transparent else Color.White.copy(alpha = 0.07f), RoundedCornerShape(15.dp))
                        .clickable(enabled = day.entries.isNotEmpty()) { selected = days.indexOf(day) }
                        .alpha(if (day.entries.isEmpty()) 0.36f else 1f)
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        day.label,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = if (day == days[selected]) Color.Black.copy(alpha = 0.55f) else TextMuted,
                    )
                    Text(
                        "${day.dayNum}",
                        fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                        color = if (day == days[selected]) Color(0xFF000000) else TextPrimary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(4.dp)) {
                        day.entries.take(3).forEach {
                            Box(
                                Modifier.size(4.dp).clip(CircleShape)
                                    .background(if (day == days[selected]) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.45f)),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .padding(horizontal = McMetrics.pagePadding)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = 0.045f))
                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(22.dp)),
        ) {
            val entries = days.getOrNull(selected)?.entries.orEmpty()
            if (entries.isEmpty()) {
                Text("这一天没有入库安排", fontSize = 13.sp, color = TextFaint, modifier = Modifier.padding(14.dp))
            }
            entries.forEachIndexed { i, e ->
                if (i > 0) Box(
                    Modifier.padding(start = 86.dp).fillMaxWidth().height(1.dp)
                        .background(Color.White.copy(alpha = 0.06f)),
                )
                Row(
                    Modifier.clickable { onOpenSubscription(e.subscriptionId) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左列 62 宽：上「待定/稍后」，下「● 状态」（iOS：状态在时间下方，不在右侧）
                    Column(Modifier.width(62.dp)) {
                        Text(
                            if (e.grabbedAt != null) "稍后" else "待定",
                            fontSize = 20.sp,
                            fontWeight = if (e.grabbedAt != null) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (e.grabbedAt != null) TextPrimary else TextFaint,
                        )
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(if (e.status == "wanted") Warn else TextMuted))
                            Spacer(Modifier.width(4.dp))
                            Text("预计入库", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Warn)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Spacer(Modifier.width(92.dp).height(52.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.mediaTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (e.mediaKind == "tv") "S%02dE%02d".format(e.seasonNumber, e.episodeNumber) else "电影",
                            fontSize = 13.sp, color = TextMuted, maxLines = 1,
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = TextFaint, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** iOS SubsHomeRestingDivider：上下发丝线 + 中间竖排小字（宽 18、10/600 faint） */
@Composable
private fun RestingDivider(label: String, height: androidx.compose.ui.unit.Dp) {
    Column(
        Modifier.width(18.dp).height(height),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.12f)))
        Spacer(Modifier.height(8.dp))
        Text(
            // 中文竖排：逐字换行即可（iOS 用 fixedSize 让它自然竖排）
            label.toCharArray().joinToString("\n"),
            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = TextFaint,
            lineHeight = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.12f)))
    }
}

/** iOS SubsHomeSeeAllCard：与海报同尺寸的透明玻璃 + 2×2 图标 + 「查看全部」+ 「N 部」 */
@Composable
private fun SeeAllCard(total: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .width(126.dp).height(189.dp)
            .clip(RoundedCornerShape(McMetrics.posterRadius))
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(McMetrics.posterRadius))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.GridOn, contentDescription = null, tint = TextMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(8.dp))
        Text("查看全部", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(2.dp))
        Text("$total 部", fontSize = 12.sp, color = TextFaint)
    }
}

@Composable
private fun SubCard(sub: SubscriptionView, dim: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(126.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(189.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF101219))
                .graphicsLayer { if (dim) { alpha = 0.5f } },
        ) {
            RemoteImage(
                url = sub.media.posterUrl,
                origin = null,
                contentDescription = sub.media.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
            // 状态小签（左上，内距 7）：黑 38% 胶囊 + 状态色圆点 + 10.5/600 文字（iOS SubsHomeChipView）
            val (chipText, chipColor) = subChip(sub)
            if (chipText.isNotEmpty()) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(7.dp).clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.38f)).padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(chipColor))
                    Spacer(Modifier.width(4.dp))
                    Text(chipText, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = chipColor)
                }
            }
            val imported = sub.progress.imported
            val total = sub.progress.total
            if (imported in 1 until total) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.5.dp).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxWidth(imported.toFloat() / total.toFloat()).height(2.5.dp).background(Color.White.copy(alpha = 0.92f)))
                }
            }
        }
        Text(sub.media.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (dim) TextMuted else TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        val meta = if (sub.media.kind == "tv") {
            val season = sub.selectedSeasons.firstOrNull()
            "第 ${season ?: 1} 季 · ${sub.progress.imported} / ${sub.progress.total}"
        } else {
            listOfNotNull(sub.media.year?.toString(), if (sub.progress.imported > 0) "已入库" else "未入库").joinToString(" · ")
        }
        Text(meta, fontSize = 12.sp, color = TextFaint, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * 订阅页空态 —— 照移动端网页的 `ContentEmptyState variant="subscription"` 搬：
 * 一张银玻璃展台（圆环 + 两张斜着的胶片/电视小卡 + 中间圆形书签座 + 右下角铃铛与
 * 呼吸绿点），一句「从一部想看的作品开始」与去发现的动线。
 *
 * 空数据不是故障：这里不用警告式面板，也不用「暂无数据」这种什么都没说的话——
 * 网页原话是「空数据不是故障」，说明白了下一步该去哪。
 */
@Composable
private fun SubscriptionEmptyState(onOpenDiscover: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = McMetrics.pagePadding)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 装饰图（读屏不朗读）
        Box(Modifier.height(140.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(140.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape))
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.025f))
                    .border(1.dp, Color.White.copy(alpha = 0.09f), CircleShape),
            )
            // 左胶片 / 右电视：各自斜一点
            Box(
                Modifier
                    .offset(x = (-78).dp, y = (-28).dp)
                    .rotate(-6f)
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(20.dp)) }
            Box(
                Modifier
                    .offset(x = 78.dp, y = (-28).dp)
                    .rotate(6f)
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Tv, contentDescription = null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(20.dp)) }
            // 中间的圆形玻璃座：书签
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF202530).copy(alpha = 0.9f))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.BookmarkBorder, contentDescription = null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(32.dp)) }
            // 右下角小圆钮：铃铛 + 呼吸的绿点（有合适资源时会通知）
            Box(
                Modifier
                    .offset(x = 34.dp, y = 44.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF303743))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Notifications, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp)) }
            Box(
                Modifier
                    .offset(x = 46.dp, y = 58.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Ok),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("从一部想看的作品开始", style = McType.headline, color = TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "去发现页挑选一部剧集或电影，打开详情并点击「订阅追踪」，有合适资源时会自动下载入库。",
            style = McType.body,
            color = TextMuted,
            textAlign = TextAlign.Center,
            lineHeight = 26.sp,
        )
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Accent)
                .clickable(onClick = onOpenDiscover)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Explore, contentDescription = null, tint = Color(0xFF0A0E12), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("去发现剧集", style = McType.subSemibold, color = Color(0xFF0A0E12))
        }
    }
}
