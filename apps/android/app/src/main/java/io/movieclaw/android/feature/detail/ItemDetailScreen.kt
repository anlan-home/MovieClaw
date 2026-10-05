package io.movieclaw.android.feature.detail

import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.movieclaw.android.core.designsystem.McType
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import io.movieclaw.android.core.designsystem.TextPrimary
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import io.movieclaw.android.core.designsystem.Accent2
import io.movieclaw.android.core.designsystem.AccentStrong
import io.movieclaw.android.core.designsystem.GlassCapsule
import io.movieclaw.android.core.designsystem.LineSoft
import io.movieclaw.android.core.designsystem.McMetrics
import io.movieclaw.android.core.designsystem.McNavButton
import io.movieclaw.android.core.designsystem.RemoteImage
import io.movieclaw.android.core.designsystem.Accent
import io.movieclaw.android.core.designsystem.Bg
import io.movieclaw.android.core.designsystem.ErrorPane
import io.movieclaw.android.core.designsystem.GlassCard
import io.movieclaw.android.core.designsystem.Loadable
import io.movieclaw.android.core.designsystem.PosterCard
import io.movieclaw.android.core.designsystem.Success
import io.movieclaw.android.core.designsystem.TextFaint
import io.movieclaw.android.core.designsystem.TextMuted
import io.movieclaw.android.core.designsystem.Warning
import io.movieclaw.android.core.model.EpisodeView
import io.movieclaw.android.core.model.LibraryItemDetailView
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.network.dataOrThrow
import io.movieclaw.android.core.network.friendlyMessage
import io.movieclaw.android.core.playback.PlayTarget
import io.movieclaw.android.core.session.SessionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Favorite
import io.movieclaw.android.core.designsystem.FeedbackTone
import io.movieclaw.android.core.designsystem.LocalFeedback
import io.movieclaw.android.core.designsystem.McNotice
import io.movieclaw.android.core.model.PlaybackMarks
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.draw.alpha
import io.movieclaw.android.core.playback.TrackLabels
import io.movieclaw.android.core.model.AudioStreamView
import io.movieclaw.android.core.model.SubtitleStreamView
import io.movieclaw.android.core.designsystem.McFormat
import io.movieclaw.android.core.model.PlaybackStateView
import io.movieclaw.android.core.model.PlaybackMarksRequest
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.update

@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SessionRepository,
    private val apiFactory: ApiFactory,
    private val preconnect: io.movieclaw.android.core.playback.PlaybackPreconnect,
) : ViewModel() {

    val libraryId: Long = savedStateHandle.get<String>("libraryId")?.toLongOrNull() ?: -1L
    val itemId: Long = savedStateHandle.get<String>("itemId")?.toLongOrNull() ?: -1L

    data class Detail(
        val item: LibraryItemDetailView,
        val episodes: Map<Int, List<EpisodeView>> = emptyMap(),
    )

    private val _state = MutableStateFlow<Loadable<Detail>>(Loadable.Loading)
    val state = _state.asStateFlow()

    private val _selectedSeason = MutableStateFlow<Int?>(null)
    val selectedSeason = _selectedSeason.asStateFlow()

    /**
     * 选中的单元 —— 季 + 集（iOS `LibraryItemDetailView.SelectedEpisode` 的对应物）。
     *
     * iOS 上它是**详情页的一等状态**：播放键（`play` 带 `season`/`episode`）、续播点
     * （`playbackResume(seasonNumber:episodeNumber:)`，键就是「季/集」）、已看（`playUnit`）、
     * 版本/文件（`selectedEpisode.files`）全都以它为准。安卓此前只有「季」这一个状态，
     * 播放键又自己去扫「跨季第一个没看的在库集」，于是**选了季也照旧从第一季起播**。
     */
    data class SelectedUnit(val season: Int, val episode: Int)

    private val _selectedEpisode = MutableStateFlow<SelectedUnit?>(null)
    val selectedEpisode = _selectedEpisode.asStateFlow()

    /** 初始选中（= 服务端续播那一集，iOS 由路由 `?season=&episode=` 带进来，这里由详情自己算） */
    private var seedUnit: SelectedUnit? = null
    private var episodesBySeason: Map<Int, List<EpisodeView>> = emptyMap()

    val origin: String? get() = repository.ui.value.origin

    init {
        // 详情页是播放入口：进页就把起播要用的两条连接连好（iOS `LibraryItemDetailView.task` 同款）
        preconnect.warm()
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
                val detail = api.libraryItemDetail(libraryId, itemId).dataOrThrow()
                val episodes = if (detail.kind == "tv") {
                    detail.seasons.associateWith { season ->
                        api.seasonEpisodes(libraryId, itemId, season).dataOrThrow().episodes
                    }
                } else {
                    emptyMap()
                }
                _state.value = Loadable.Ready(Detail(detail, episodes))
                episodesBySeason = episodes
                // 初始选中：服务端续播那一个单元（跨季第一个没看的在库集）。
                // 季的回退链照 iOS `SeasonEpisodesSection.currentSeason`：
                // 续播那季 → 第一个在库的季 → 第一个季
                val seed = pickResumeEpisode(detail, episodes).takeIf { it.second > 0 }
                    ?: detail.seasons.firstOrNull()?.let { season ->
                        episodes[season]?.firstOrNull()?.let { Triple(season, it.episodeNumber, it.name) }
                    }
                seedUnit = seed?.let { SelectedUnit(it.first, it.second) }
                val ownedSeasons = detail.files.map { it.seasonNumber }.toSet()
                _selectedSeason.value = seed?.first?.takeIf { episodes.containsKey(it) }
                    ?: detail.seasons.firstOrNull { it in ownedSeasons }
                    ?: detail.seasons.firstOrNull()
                _selectedEpisode.value = seedUnit
                    ?: _selectedSeason.value?.let { s ->
                        episodes[s]?.firstOrNull()?.let { SelectedUnit(s, it.episodeNumber) }
                    }
            } catch (e: Exception) {
                _state.value = Loadable.Failed(friendlyMessage(e))
            }
        }
    }

    /**
     * 换季：**连带把选中集切到这一季**（iOS `SeasonEpisodesSection.load(reset:)` 同口径）——
     * 优先「初始续播那一集」（若正好是这一季且在库）、否则这一季第一个没看的在库集、
     * 再次第一个在库集、最后第一集。只换季不换集的话，播放键会拿「第 3 季 + 第 1 季的那一集」
     * 去起播，服务端找不到就退回第一季——现象与用户报的完全一致。
     */
    fun selectSeason(season: Int) {
        _selectedSeason.value = season
        val list = episodesBySeason[season].orEmpty()
        val picked = seedUnit?.takeIf { it.season == season && list.any { e -> e.episodeNumber == it.episode && e.owned } }?.episode
            ?: list.firstOrNull { !it.played && it.owned }?.episodeNumber
            ?: list.firstOrNull { it.owned }?.episodeNumber
            ?: list.firstOrNull()?.episodeNumber
        if (picked != null) _selectedEpisode.value = SelectedUnit(season, picked)
    }

    /** 点某一集 = 选中它（播放键、续播点、已看、版本都跟着走，iOS 同款） */
    fun selectEpisode(season: Int, episode: Int) {
        _selectedEpisode.value = SelectedUnit(season, episode)
    }

    /* ---------- 已看 / 收藏（与 Jellyfin 同一个服务，网页同款乐观更新） ---------- */

    private val _marks = MutableStateFlow(PlaybackMarks())
    val marks = _marks.asStateFlow()

    /**
     * 续播点与记忆轨（`GET /playback/resume`，iOS `LibraryItemDetailView` 同款数据源）：
     * 播放键三态要用 position/played，轨选择要用 audio_track/subtitle_track
     * （服务端口径：本集记着的 > 沿用上一集 > 默认轨策略）。
     */
    private val _resume = MutableStateFlow<PlaybackStateView?>(null)
    val resume = _resume.asStateFlow()

    fun loadResume(mediaItemId: Long, seasonNumber: Int, episodeNumber: Int) {
        viewModelScope.launch {
            val origin = repository.ui.value.origin ?: return@launch
            runCatching {
                apiFactory.forOrigin(origin)
                    .resumeState(mediaItemId, seasonNumber, episodeNumber)
                    .dataOrThrow()
            }.onSuccess { _resume.value = it }
        }
    }

    private val _notice = MutableStateFlow<McNotice?>(null)
    val notice = _notice.asStateFlow()

    fun consumeNotice() {
        _notice.value = null
    }

    fun loadMarks(mediaItemId: Long) {
        viewModelScope.launch {
            val origin = repository.ui.value.origin ?: return@launch
            // 查不到收藏态不影响浏览，心按未收藏渲染即可
            runCatching {
                apiFactory.forOrigin(origin).playbackMarks(mediaItemId).dataOrThrow()
            }.onSuccess { _marks.value = it }
        }
    }

    fun toggleFavorite(mediaItemId: Long) {
        val next = !_marks.value.isFavorite
        _marks.update { it.copy(isFavorite = next) }
        viewModelScope.launch {
            val origin = repository.ui.value.origin ?: return@launch
            runCatching {
                apiFactory.forOrigin(origin)
                    .setPlaybackMarks(PlaybackMarksRequest(mediaItemId = mediaItemId, favorite = next))
                    .dataOrThrow()
            }.onSuccess {
                _marks.value = it
                // 收藏变了：首页「我的收藏」行与收藏墙立刻重拉（否则那两处还是进页时的快照）
                io.movieclaw.android.core.model.LibraryMarksBus.bump()
            }
                .onFailure {
                    _marks.update { s -> s.copy(isFavorite = !next) }
                    _notice.value = McNotice(friendlyMessage(it), FeedbackTone.Error)
                }
        }
    }

    /** 标记当前播放单元已看 / 未看（电影，或剧集当前那一集）。 */
    fun togglePlayed(mediaItemId: Long, seasonNumber: Int?, episodeNumber: Int?) {
        val next = !_marks.value.played
        _marks.update { it.copy(played = next) }
        viewModelScope.launch {
            val origin = repository.ui.value.origin ?: return@launch
            runCatching {
                apiFactory.forOrigin(origin)
                    .setPlaybackMarks(
                        PlaybackMarksRequest(
                            mediaItemId = mediaItemId,
                            seasonNumber = seasonNumber,
                            episodeNumber = episodeNumber,
                            played = next,
                        )
                    )
                    .dataOrThrow()
            }.onSuccess {
                // 标已看会清零续播位置、取消会清零播放次数——结论以服务端为准，写完重查
                runCatching {
                    apiFactory.forOrigin(origin).playbackMarks(
                        mediaItemId = mediaItemId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                    ).dataOrThrow()
                }.onSuccess { fresh -> _marks.value = fresh }
                // 「已看」会影响首页的「接下来继续」与各行的进度，一起让首页重拉
                io.movieclaw.android.core.model.LibraryMarksBus.bump()
            }.onFailure {
                _marks.update { s -> s.copy(played = !next) }
                _notice.value = McNotice(friendlyMessage(it), FeedbackTone.Error)
            }
        }
    }
}

@Composable
fun ItemDetailScreen(
    libraryId: Long,
    itemId: Long,
    onBack: () -> Unit,
    onPlay: (PlayTarget) -> Unit,
    /** 演职员点进「库内影人页」（iOS `LibraryItemDetailView` 的 `AppRoute.person` 同款） */
    onOpenPerson: (Int) -> Unit = {},
    vm: ItemDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selectedSeason by vm.selectedSeason.collectAsStateWithLifecycle()
    /** 选中的单元（季 + 集）：播放键、续播点、已看、版本都以它为准（iOS SelectedEpisode） */
    val selectedUnit by vm.selectedEpisode.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val resume by vm.resume.collectAsStateWithLifecycle()
    val origin = vm.origin
    val feedback = LocalFeedback.current

    // 只在换条目时拉一次标记（marks 自身变化不能再触发，否则死循环）
    val loadedItemId = (state as? Loadable.Ready)?.value?.item?.mediaItemId
    LaunchedEffect(loadedItemId) {
        loadedItemId?.let { vm.loadMarks(it) }
    }
    LaunchedEffect(vm) {
        vm.notice.collect { notice ->
            if (notice != null) {
                feedback.show(notice)
                vm.consumeNotice()
            }
        }
    }

    when (val s = state) {
        Loadable.Loading -> Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = TextMuted)
        }
        is Loadable.Failed -> ErrorPane(message = s.message, onRetry = vm::load)
        is Loadable.Ready -> Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .verticalScroll(rememberScrollState()),
        ) {
            Hero(
                detail = s.value.item,
                origin = origin,
                onBack = onBack,
                onPlay = onPlay,
                libraryId = libraryId,
                episodes = s.value.episodes,
                marks = marks,
                resume = resume,
                unit = selectedUnit,
                onResumeUnitChanged = { season, episode -> vm.loadResume(s.value.item.mediaItemId, season, episode) },
                onToggleFavorite = { vm.toggleFavorite(s.value.item.mediaItemId) },
                onTogglePlayed = { season, episode ->
                    vm.togglePlayed(s.value.item.mediaItemId, season, episode)
                },
            ) {
                // 网页正文顺序：分集区 → 章节 → **演职员** → 文件区
                if (s.value.item.kind == "tv" && s.value.episodes.isNotEmpty()) {
                    SeasonSection(
                        seasons = s.value.item.seasons,
                        // 库里实有的季（台账文件的季号集合）：季选择器里给没有的季标「未入库」，
                        // 与网页/iOS 同口径——元数据的季和实有的季混在一起，不标出来会以为本地存了那么多季
                        ownedSeasons = s.value.item.files.map { it.seasonNumber }.toSet(),
                        selected = selectedSeason,
                        episodes = s.value.episodes,
                        onSelect = vm::selectSeason,
                        // 分集横滚的选中与滚动定位跟着「选中单元」走（受控，不再各存一份）
                        selectedEpisode = selectedUnit?.takeIf { it.season == selectedSeason }?.episode,
                        onPickEpisode = { ep -> vm.selectEpisode(selectedSeason ?: 0, ep) },
                        origin = origin,
                        onPlay = onPlay,
                        libraryId = libraryId,
                        itemId = itemId,
                        itemTitle = s.value.item.title,
                    )
                }
                CastSection(detail = s.value.item, origin = origin, onOpenPerson = onOpenPerson)
                if (s.value.item.kind != "tv" || s.value.episodes.isEmpty()) {
                    FilesSection(detail = s.value.item)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * 详情头部 —— 按移动端网页实测重做：
 *   · 整幅剧照（高 min(52vh,420)）+ 一道自下而上的压暗渐变压到纯黑，
 *     标题与简介就落在图片淡出的区域上（网页是「图让开」，不是「字压图」）；
 *   · 浮动导航（返回 / 搜索 / ⋯）36×36、内距 8；
 *   · 正文相对图片上提 124：标题 28/700 · 元信息 14/62%（· 分隔）·「音轨」「字幕」两行
 *     （40 宽淡色标签 + 玻璃胶囊值）· 琥珀提示胶囊 ·「播放」通栏 48 银白胶囊 ·
 *     两枚玻璃药丸（收藏 / 标为已看）· 剧情 16/1.85 ·「展开全文 ⌄」。
 */
@Composable
private fun Hero(
    detail: LibraryItemDetailView,
    origin: String?,
    onBack: () -> Unit,
    onPlay: (PlayTarget) -> Unit,
    libraryId: Long,
    episodes: Map<Int, List<EpisodeView>>,
    marks: PlaybackMarks,
    /** 续播点与记忆轨（`/playback/resume`）；null = 还没问到，按键先按「播放」渲染 */
    resume: PlaybackStateView?,
    /** 选中的单元（季 + 集）：播放键、续播点、已看、版本都跟着它（iOS SelectedEpisode） */
    unit: ItemDetailViewModel.SelectedUnit?,
    onResumeUnitChanged: (Int, Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePlayed: (Int?, Int?) -> Unit,
    /** 正文其余部分（分集 / 演职员 / 文件）：与简介同用那一块上提 124 的列 */
    content: @Composable ColumnScope.() -> Unit,
) {
    // 版本/文件、音轨源、discSource 都跟着**选中的那一集**走（iOS `selectedEpisode.files`）：
    // 原来一律拿 `detail.files.firstOrNull()`（第 1 季第 1 个文件），选了季也对不上
    val unitFiles = remember(detail.mediaItemId, unit, episodes) {
        val ids = unit?.let { u -> episodes[u.season]?.firstOrNull { it.episodeNumber == u.episode }?.fileIds }
        detail.files.filter { f -> ids?.contains(f.id) == true }.ifEmpty { detail.files }
    }
    val file = unitFiles.firstOrNull()
    var expanded by remember { mutableStateOf(false) }
    // 「标为已看」与续播点都跟着选中的单元走：电影是整条（0/0），剧集是选中的那一集
    val playUnit = if (detail.kind == "tv") unit else null
    val resumeSeason = playUnit?.season ?: 0
    val resumeEpisode = playUnit?.episode ?: 0

    // 续播点与记忆轨：换单元要重新问（剧集从第 1 集切到第 3 集，续播点跟着变）
    LaunchedEffect(detail.mediaItemId, resumeSeason, resumeEpisode) {
        onResumeUnitChanged(resumeSeason, resumeEpisode)
    }

    /* ---------- 音轨 / 字幕的选择 ---------- */

    val audioOptions = remember(file?.id, file?.audioStreams) { trackOptions(file?.audioStreams.orEmpty(), false) }
    val subtitleOptions = remember(file?.id, file?.subtitleStreams) { trackOptions(file?.subtitleStreams.orEmpty(), true) }
    // 初始选中 = 服务端记忆的那条（本集记着的 > 沿用上一集 > 默认轨策略），用户点过就以用户为准
    var pickedAudio by remember(file?.id) { mutableStateOf<String?>(null) }
    var pickedSubtitle by remember(file?.id) { mutableStateOf<String?>(null) }
    // 初始选中：服务端算好的「起播会放哪条」（本集记着的 > 沿用同剧上一集 > 默认轨策略），
    // 用户点过就以用户为准。
    val defaults = file?.playbackDefaults
    val effectiveAudio = pickedAudio ?: defaults?.audioTrack?.takeIf { it.isNotBlank() }
        ?: audioOptions.firstOrNull { it.isDefault }?.ref ?: audioOptions.firstOrNull()?.ref
    val effectiveSubtitle = pickedSubtitle ?: defaults?.subtitleTrack?.takeIf { it.isNotBlank() }
        ?: resume?.subtitleTrack?.takeIf { it.isNotBlank() && it != "off" }
    var sheet by remember { mutableStateOf<String?>(null) }   // "audio" | "subtitle"
    val audioText = audioOptions.firstOrNull { it.ref == effectiveAudio }?.label
        ?: file?.audioStreams?.firstOrNull()?.let { "${it.language ?: "未标语言"} ${it.codec?.uppercase().orEmpty()}".trim() }
        ?: "尚未探测"
    val subtitleText = when {
        subtitleOptions.isEmpty() -> "无内封或外挂字幕"
        effectiveSubtitle == null -> "${subtitleOptions.size} 条字幕 · 未开启"
        else -> subtitleOptions.firstOrNull { it.ref == effectiveSubtitle }?.label ?: "${subtitleOptions.size} 条字幕"
    }

    // 播放键三态（iOS `LibraryItemDetailView`，v0.31 起同一口径）：继续 / 重新播放 / 播放。
    // **有续播点就续播**：看完（played）后重看到一半，服务端也记着续播点——
    // 旧口径 `!played && position>0` 会把这种重看判成「重新播放」，这次的进度就丢了
    val resumeMs = resume?.positionMs ?: 0L
    val finished = resume?.played == true && resumeMs <= 0L
    val resumable = resumeMs > 0L
    // 剧集写明续的是哪一集（iOS 42c1751d：只写「继续 46:56」看不出续的第几集）
    val playLabel = when {
        resumable -> if (detail.kind == "tv") {
            "继续 第 ${resumeSeason} 季第 ${resumeEpisode} 集 · ${McFormat.clock(resumeMs)}"
        } else "继续 ${McFormat.clock(resumeMs)}"
        finished -> "重新播放"
        else -> "播放"
    }
    val remainingMs = resume?.durationMs?.takeIf { it > 0 }?.minus(resumeMs)?.takeIf { it > 0 }

    Column {
        Box(Modifier.fillMaxWidth().height(heroHeight())) {
            RemoteImage(
                url = detail.backdropUrl ?: detail.posterUrl,
                origin = origin,
                contentDescription = detail.title,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.42f),
                            0.22f to Color.Transparent,
                            0.52f to Color.Black.copy(alpha = 0.28f),
                            0.84f to Color.Black.copy(alpha = 0.8f),
                            1f to Bg,
                        )
                    ),
            )
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                McNavButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", onClick = onBack)
                // 网页这里只有一颗「更多操作」（搜索资源 / 加入合集 / 分享 / 洗版 /
                // 重新刮削 / 换海报 / 转移 / 删除 / 清除观看记录）。
                // 那个菜单还没接，先不挂两颗点不动的键——假控件。
            }
        }

        Column(Modifier.padding(horizontal = McMetrics.pagePadding).offset(y = (-124).dp)) {
            // 片名 Logo（网页/iOS 同款，iOS titleArt）：区高固定 96dp——加载前后下面的
            // 元信息/类型行不跳；本地资产存的是给电视端用的原图；**加载失败或没有
            // Logo 都回退文字片名**（RemoteImage 的 fallback 正是失败回落）
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.BottomStart) {
                if (detail.logoUrl.isNullOrBlank()) {
                    Text(
                        detail.title,
                        style = McType.title.copy(lineHeight = 36.sp),
                        color = TextPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    RemoteImage(
                        url = detail.logoUrl,
                        origin = origin,
                        contentScale = ContentScale.Fit,
                        contentDescription = detail.title,
                        modifier = Modifier.fillMaxHeight(),
                        fallback = {
                            Text(
                                detail.title,
                                style = McType.title.copy(lineHeight = 36.sp),
                                color = TextPrimary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                metaLine(detail),
                style = McType.sub,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 类型跟在元信息下面、**音轨之上**（网页 `meta.genres.join(" · ")`，
            // 一行纯文本；片源规格 → 类型 → 轨道 → 开播，是网页那条动线）
            val genres = detail.localMeta?.genres.orEmpty()
            if (genres.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    genres.joinToString(" · "),
                    style = McType.sub,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(13.dp))
            TrackRow("音轨", audioText, onClick = if (audioOptions.isEmpty()) null else {{ sheet = "audio" }})
            Spacer(Modifier.height(13.dp))
            TrackRow(
                "字幕",
                subtitleText,
                dim = subtitleOptions.isEmpty(),
                onClick = if (subtitleOptions.isEmpty()) null else {{ sheet = "subtitle" }},
            )

            // 轨选择面板：列出这个文件的全部音轨/字幕，选中的打勾（与播放器菜单同一套对勾约定）
            sheet?.let { kind ->
                TrackPickerSheet(
                    title = if (kind == "audio") "音轨" else "字幕",
                    options = if (kind == "audio") audioOptions else subtitleOptions,
                    selectedRef = if (kind == "audio") effectiveAudio else effectiveSubtitle,
                    // 服务端给的原因原话（"沿用上一集" / "上次换的" / "影片原声"…）
                    note = (if (kind == "audio") defaults?.audioNote else defaults?.subtitleNote)
                        ?.takeIf { it.isNotBlank() },
                    onPick = { ref ->
                        if (kind == "audio") pickedAudio = ref else pickedSubtitle = ref
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x1AF5C451))
                    .border(1.dp, Color(0x57F5C451), RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("接入 AI 模型后即可解锁生成字幕能力。", style = McType.sub, color = Color(0xFFF7D488))
                Spacer(Modifier.width(6.dp))
                Text("去接入", style = McType.subSemibold, color = Color(0xFFFBE3A6))
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    // 起播的就是**选中的那一季那一集**（iOS `play(start:)` 带 selectedEpisode 的
                    // season/episode）；只有还没选中任何单元时才回退到「跨季第一个没看的」（种子）
                    val (season, episode, subtitle) = detailEpisodeOf(detail, episodes, unit)
                        ?: pickResumeEpisode(detail, episodes)
                    android.util.Log.i(
                        "McPlayer",
                        "详情页起播 音轨=$effectiveAudio 字幕=$effectiveSubtitle" +
                            "（服务端默认：音轨=${defaults?.audioTrack} 字幕=${defaults?.subtitleTrack}）",
                    )
                    onPlay(
                        PlayTarget(
                            mediaItemId = detail.mediaItemId,
                            libraryId = libraryId,
                            kind = detail.kind,
                            title = detail.title,
                            subtitle = subtitle,
                            seasonNumber = season,
                            episodeNumber = episode,
                            // 详情页选好的轨随起播请求带走（服务端 apply 后返回的 plan 就是它）
                            preferredAudio = effectiveAudio,
                            preferredSubtitle = effectiveSubtitle,
                            // 原盘（ISO / BDMV 目录）本机引擎读不了盘内结构，协商时特殊处理
                            discSource = file?.container == "iso" || file?.container == "bluray",
                        )
                    )
                },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFF141821)),
                contentPadding = PaddingValues(0.dp),
                elevation = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFF6F8FC), Color(0xFFCCD6E6)))),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(playLabel, style = McType.bodySemibold)
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassPill(
                    "收藏",
                    if (marks.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    marks.isFavorite,
                ) { onToggleFavorite() }
                GlassPill("标为已看", Icons.Rounded.CheckCircle, marks.played) {
                    onTogglePlayed(resumeSeason.takeIf { it > 0 }, resumeEpisode.takeIf { it > 0 })
                }
            }

            detail.localMeta?.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                Spacer(Modifier.height(18.dp))
                Text(
                    plot,
                    style = McType.body.copy(lineHeight = 30.sp),
                    color = TextMuted,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(9.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { expanded = !expanded },
                ) {
                    Text(if (expanded) "收起" else "展开全文", style = McType.sub, color = TextPrimary)
                    Icon(
                        if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // 正文其余部分都挂在这块**上提 124** 的列里：偏移只作用到这里，
            // 整块一起上提，简介与下一段之间才不会留下一个 124 高的空洞
            // （之前分集/文件在偏移列之外，那块空白就是它）
            content()
        }
    }
}

@Composable
private fun heroHeight(): androidx.compose.ui.unit.Dp =
    (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.52f)
        .coerceAtMost(420f).dp

private fun metaLine(detail: LibraryItemDetailView): String {
    val file = detail.files.firstOrNull()
    return buildList {
        detail.year?.let { add(it.toString()) }
        detail.localMeta?.runtimeMinutes?.let { add("$it 分钟") }
        file?.resolution?.let { add(it) }
        file?.videoCodec?.let { add(it.uppercase()) }
        file?.hdr?.let { add(it) }
        detail.localMeta?.rating?.takeIf { it > 0f }?.let { add("★ %.1f".format(it)) }
    }.joinToString("  ·  ")
}

/** 详情页一条可选轨（ref = 中性引用 embedded:N / external:文件名） */
internal data class DetailTrackOption(
    val ref: String,
    val label: String,
    val isDefault: Boolean = false,
    /** 本机渲染不了/服务端给不了地址时的原因；非空则置灰不可选（与播放器菜单同口径） */
    val unavailableReason: String? = null,
)

/**
 * 文件的流列表 → 可选轨。ref 按服务端口径拼：
 * 内封轨 `embedded:<序号>`（编号只数内封的），外挂轨 `external:<文件名>`。
 */
private fun trackOptions(
    streams: List<Any>,
    subtitle: Boolean,
): List<DetailTrackOption> {
    var embeddedIndex = -1
    return streams.mapNotNull { raw ->
        if (subtitle) {
            val s = raw as? SubtitleStreamView ?: return@mapNotNull null
            if (s.external) {
                val name = s.fileName ?: return@mapNotNull null
                val ref = "external:$name"
                DetailTrackOption(ref, TrackLabels.subtitle(s.language, kindOfCodec(s.codec), ref, false), s.default)
            } else {
                embeddedIndex++
                val ref = "embedded:$embeddedIndex"
                DetailTrackOption(
                    ref = ref,
                    label = TrackLabels.subtitle(s.language, kindOfCodec(s.codec), ref, false),
                    isDefault = s.default,
                    unavailableReason = TrackLabels.subtitleUnsupportedReason(kindOfCodec(s.codec)),
                )
            }
        } else {
            val s = raw as? AudioStreamView ?: return@mapNotNull null
            embeddedIndex++
            val ref = "embedded:$embeddedIndex"
            DetailTrackOption(
                ref = ref,
                label = TrackLabels.audio(s.language, s.codec, s.channels, ref),
                isDefault = s.default,
            )
        }
    }
}

/** 服务端决策里的 kind（vtt/ass/pgs）在详情页只有 codec，按编码反推 */
private fun kindOfCodec(codec: String?): String = when (codec?.lowercase()) {
    "ass", "ssa" -> "ass"
    "pgs", "sup", "hdmv_pgs_subtitle" -> "pgs"
    else -> "vtt"
}

/** 轨选择面板（底部弹出）。选中的那条打勾——与播放器菜单同一套视觉约定 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TrackPickerSheet(
    title: String,
    options: List<DetailTrackOption>,
    selectedRef: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    /** 服务端对「起播会放哪条」的一句解释；空的就不显示 */
    note: String? = null,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF15161A),
        contentColor = TextPrimary,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                title,
                style = McType.bodySemibold,
                color = TextPrimary,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = if (note == null) 8.dp else 2.dp),
            )
            if (note != null) {
                Text(
                    note,
                    style = McType.caption2,
                    color = TextFaint,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                )
            }
            options.forEach { option ->
                val reason = option.unavailableReason
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (reason == null) Modifier.clickable { onPick(option.ref) } else Modifier)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(20.dp)) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier
                                .size(16.dp)
                                .alpha(if (option.ref == selectedRef) 1f else 0f),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.label,
                            style = McType.sub,
                            color = if (reason != null) TextFaint else TextPrimary,
                            maxLines = 2,
                        )
                        if (reason != null) {
                            Text(reason, style = McType.caption2, color = TextFaint)
                        } else if (option.isDefault) {
                            Text("片源默认轨", style = McType.caption2, color = TextFaint)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    label: String,
    value: String,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = McType.sub, color = TextFaint, modifier = Modifier.width(40.dp))
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(GlassCapsule)
                .border(1.dp, LineSoft, RoundedCornerShape(999.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = McType.sub, color = if (dim) TextMuted else TextPrimary, maxLines = 1)
            if (onClick != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun RowScope.GlassPill(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(GlassCapsule)
            .border(1.dp, LineSoft, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) AccentStrong else TextPrimary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = McType.sub, color = if (active) AccentStrong else TextPrimary)
    }
}

/** 剧集:第一个未看完且在位的分集;电影返回哨兵 (0,0) */
/**
 * 选中单元 →（季, 集, 副标题）。没选中（或电影）返回 null，调用方再回退到种子。
 */
private fun detailEpisodeOf(
    detail: LibraryItemDetailView,
    episodes: Map<Int, List<EpisodeView>>,
    unit: ItemDetailViewModel.SelectedUnit?,
): Triple<Int, Int, String?>? {
    if (detail.kind != "tv" || unit == null) return null
    val episode = episodes[unit.season]?.firstOrNull { it.episodeNumber == unit.episode } ?: return null
    return Triple(
        unit.season,
        unit.episode,
        "第 ${unit.episode} 集" + (episode.name?.let { " · $it" } ?: ""),
    )
}

private fun pickResumeEpisode(
    detail: LibraryItemDetailView,
    episodes: Map<Int, List<EpisodeView>>,
): Triple<Int, Int, String?> {
    if (detail.kind != "tv") return Triple(0, 0, null)
    for (season in detail.seasons.sorted()) {
        val list = episodes[season] ?: continue
        val candidate = list.firstOrNull { !it.played && it.owned }
            ?: list.firstOrNull { it.owned }
        if (candidate != null) {
            return Triple(season, candidate.episodeNumber, "第 ${candidate.episodeNumber} 集" + (candidate.name?.let { " · $it" } ?: ""))
        }
    }
    return Triple(0, 0, null)
}

/**
 * 演职员（网页 `CastRow`）：104 宽、2:3 头像、姓名 + 「饰 X」，横滚一行。
 * 导演排在演员前（网页 `...directorCast, ...meta.actors`），头像来自 NFO 的
 * `<thumb>`；没有照片就渲染姓名首字。
 */
@Composable
private fun CastSection(detail: LibraryItemDetailView, origin: String?, onOpenPerson: (Int) -> Unit) {
    val meta = detail.localMeta ?: return
    // 一格 = 姓名 / 身份 / 头像 / 影人 id（id 非空才可点，进库内影人页——iOS 同款）
    data class Person(val name: String, val role: String, val avatar: String?, val personId: Int?)
    val people = buildList {
        // 导演：库内人物关系（带头像）优先；老条目没有关系表就退回 NFO 里的姓名占位
        if (meta.directorCredits.isNotEmpty()) {
            meta.directorCredits.forEach { director ->
                if (director.name.isNotBlank()) {
                    add(Person(director.name, "导演", director.thumbUrl, director.tmdbPersonId))
                }
            }
        } else {
            meta.directors.forEach { name ->
                if (name.isNotBlank()) add(Person(name, "导演", null, null))
            }
        }
        meta.actors.forEach { actor ->
            actor.name?.takeIf { it.isNotBlank() }?.let { name ->
                add(
                    Person(
                        name,
                        actor.role?.takeIf { it.isNotBlank() }?.let { "饰 $it" } ?: "演员",
                        // 服务端给的是 `thumb_url`（TMDB 图床的绝对地址）
                        actor.thumbUrl,
                        actor.tmdbPersonId,
                    ),
                )
            }
        }
    }
    if (people.isEmpty()) return
    Column(Modifier.padding(top = 18.dp)) {
        Text(
            "演职员",
            style = McType.title3,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = McMetrics.pagePadding),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = McMetrics.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(people, key = { "${it.name}-${it.role}" }) { person ->
                Column(
                    Modifier
                        .width(104.dp)
                        // 有 TMDB 影人 id 才可点（进库内影人页）；老条目只有姓名时保持静态（iOS 同款）
                        .clickable(enabled = person.personId != null) { person.personId?.let(onOpenPerson) },
                ) {
                    val initials: @Composable () -> Unit = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                io.movieclaw.android.core.designsystem.ImageInitials.of(person.name),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.3f),
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (person.avatar.isNullOrBlank()) {
                            initials()
                        } else {
                            // 头像多是 TMDB 图床的绝对地址；**加载失败也回落首字**，
                            // 不能留一块空黑（网页同款兜底）
                            RemoteImage(
                                person.avatar,
                                origin,
                                contentDescription = person.name,
                                modifier = Modifier.fillMaxSize(),
                                fallback = initials,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(person.name, style = McType.subSemibold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(person.role, style = McType.micro, color = TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * 分集区（网页 `SeasonEpisodesSection` / iOS `SeasonEpisodesSection`）：
 * **季选择器 + 一季一屏的横滚卡**，外加「在库 X / Y 集」计数。
 *
 * 集数多时两端都是这么解决的：**先按季收窄**（一次只铺一季的卡），其次把当前那一集
 * 自动滚到可见处（深链/续播进来一眼就看到自己追到哪，不用手动滑几十屏）。
 * 季选择器用下拉菜单而不是一排 chips：季多的剧（十几季很常见）chip 一行放不下，
 * 而这行不滚动——后排的季就点不到了；下拉菜单是可滚的，且能标「未入库」。
 */
@Composable
private fun SeasonSection(
    seasons: List<Int>,
    ownedSeasons: Set<Int>,
    selected: Int?,
    episodes: Map<Int, List<EpisodeView>>,
    onSelect: (Int) -> Unit,
    /** 选中的集号（受控：来自 VM 的选中单元，不再各存一份） */
    selectedEpisode: Int?,
    onPickEpisode: (Int) -> Unit,
    origin: String?,
    onPlay: (PlayTarget) -> Unit,
    libraryId: Long,
    itemId: Long,
    itemTitle: String,
) {
    val seasonLabel = { season: Int ->
        val name = if (season == 0) "特别篇" else "第 $season 季"
        if (season in ownedSeasons) name else "$name · 未入库"
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text("分集", style = McType.title3)
            Spacer(Modifier.width(10.dp))
            // 季选择器（iOS `SeasonEpisodesSection` 同款）：多季才给胶囊菜单（每项标「未入库」），
            // 只有一季时就是一行小字——单选的下拉菜单没有意义；缺省落在当前季，不再有「选择季」占位
            var seasonMenu by remember { mutableStateOf(false) }
            val activeSeason = selected ?: seasons.firstOrNull()
            if (seasons.size > 1) {
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(GlassCapsule)
                            .border(1.dp, LineSoft, RoundedCornerShape(999.dp))
                            .clickable { seasonMenu = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            activeSeason?.let(seasonLabel) ?: "第 1 季",
                            style = McType.subSemibold,
                            color = TextPrimary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Rounded.UnfoldMore,
                            contentDescription = "选择季",
                            tint = TextMuted,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = seasonMenu,
                        onDismissRequest = { seasonMenu = false },
                        containerColor = Color(0xFF1E212B),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.heightIn(max = 340.dp),
                    ) {
                        seasons.sorted().forEach { season ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        seasonLabel(season),
                                        style = McType.sub,
                                        color = if (season == activeSeason) Accent else TextPrimary,
                                    )
                                },
                                onClick = { seasonMenu = false; onSelect(season) },
                            )
                        }
                    }
                }
            } else {
                Text(activeSeason?.let(seasonLabel) ?: "", style = McType.sub, color = TextMuted)
            }
            // 「在库 X / Y 集」（网页/iOS 都有这一行）：一眼看出这一季收了多少
            val seasonEpisodes = episodes[selected].orEmpty()
            if (seasonEpisodes.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "在库 ${seasonEpisodes.count { it.owned }} / ${seasonEpisodes.size} 集",
                    style = McType.sub,
                    color = TextFaint,
                )
            }
        }

        // 分集横滚卡：一季一屏，点一集 = 选中它（播放是详情页那颗播放键，选中的集就是它要播的）
        val list = episodes[selected] ?: emptyList()
        val picked = selectedEpisode ?: list.firstOrNull()?.episodeNumber
        Spacer(Modifier.height(12.dp))
        val strip = rememberLazyListState()
        // 进页/换季时把选中那一集滚到可见处（续播进来的那一集常常在列表深处，
        // 不滚的话得手动滑几十屏；网页/iOS 同样会滚到当前集）
        LaunchedEffect(selected, list.size, picked) {
            val index = list.indexOfFirst { it.episodeNumber == picked }
            if (index > 0) strip.animateScrollToItem((index - 1).coerceAtLeast(0))
        }
        LazyRow(
            state = strip,
            contentPadding = PaddingValues(horizontal = McMetrics.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(list, key = { it.episodeNumber }) { episode ->
                EpisodeCard(
                    episode = episode,
                    selected = episode.episodeNumber == picked,
                    origin = origin,
                    onSelect = { onPickEpisode(episode.episodeNumber) },
                )
            }
        }
    }
}

/**
 * 分集横滚卡（网页 `EpisodeCard`）：200 宽、16:9 剧照 + 「N. 集名」。
 *
 * 观看状态与首页「最近观看」卡同一套语言：看了一半底部细进度条、看完右上角绿对勾，
 * 扫一眼就知道追到哪了；缺集整卡压暗 + 右上角「缺」；选中那集套一圈亮环。
 * 点一集**只切换选中**（网页行为），要播的是详情页那颗播放键。
 */
@Composable
private fun EpisodeCard(
    episode: EpisodeView,
    selected: Boolean,
    origin: String?,
    onSelect: () -> Unit,
) {
    val progress = if (episode.played) 100f else episode.progressPercent?.toFloat()
    Column(
        Modifier
            .width(200.dp)
            .clickable(onClick = onSelect)
            .then(if (episode.owned) Modifier else Modifier.alpha(0.45f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            RemoteImage(
                url = episode.stillUrl,
                origin = origin,
                contentDescription = "第 ${episode.episodeNumber} 集剧照",
                modifier = Modifier.fillMaxSize(),
                fallback = {
                    // 没有剧照就退回大大的集号（网页同款兜底）
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "${episode.episodeNumber}",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.2f),
                        )
                    }
                },
            )
            // 右上角状态位：缺集与已看对勾同排（看过之后文件丢了两者会同时出现）
            if (!episode.owned || episode.played) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!episode.owned) {
                        Text(
                            "缺",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Warning,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    if (episode.played) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Success),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = "已看完", tint = Color(0xFF07120C), modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
            when {
                progress != null -> Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((progress / 100f).coerceIn(0f, 1f))
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (episode.played) Success else Accent2),
                    )
                }
                // 有记录但算不出百分比（无时长）：一根半透明的整条兜底
                episode.owned && episode.played.not() && episode.positionMs > 0 -> Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Accent2.copy(alpha = 0.6f)),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${episode.episodeNumber}. " + (episode.name?.takeIf { it.isNotBlank() } ?: "第 ${episode.episodeNumber} 集"),
            style = if (selected) McType.subSemibold else McType.sub,
            color = if (selected) Color.White else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FilesSection(detail: LibraryItemDetailView) {
    if (detail.files.isEmpty()) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("文件 · ${detail.files.size}", style = McType.title3)
        Spacer(Modifier.height(8.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            detail.files.forEachIndexed { index, file ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Column {
                    Text(
                        file.fileName,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    val specs = buildList {
                        add(file.resolution ?: "未知分辨率")
                        file.videoCodec?.let { add(it.uppercase()) }
                        file.hdr?.let { add(it) }
                        file.container?.let { add(it) }
                        file.sizeBytes.takeIf { it > 0 }?.let { add("%.1f GB".format(it / 1e9)) }
                        val channels = file.audioStreams?.maxOfOrNull { it.channels ?: 0 } ?: 0
                        if (channels > 0) add("${channels}.1 声道")
                        val subtitleCount = file.subtitleStreams.size
                        if (subtitleCount > 0) add("字幕 ×$subtitleCount")
                    }
                    Text(specs.joinToString(" · "), fontSize = 11.sp, color = TextFaint)
                }
            }
        }
    }
}
