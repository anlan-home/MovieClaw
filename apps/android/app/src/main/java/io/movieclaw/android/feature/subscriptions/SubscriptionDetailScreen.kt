package io.movieclaw.android.feature.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.draw.alpha
import io.movieclaw.android.core.designsystem.Accent
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.movieclaw.android.core.designsystem.Danger
import io.movieclaw.android.core.designsystem.FeedbackTone
import io.movieclaw.android.core.designsystem.LineSoft
import io.movieclaw.android.core.designsystem.LocalFeedback
import io.movieclaw.android.core.designsystem.McMetrics
import io.movieclaw.android.core.designsystem.McNotice
import io.movieclaw.android.core.designsystem.McType
import io.movieclaw.android.core.designsystem.Ok
import io.movieclaw.android.core.designsystem.RemoteImage
import io.movieclaw.android.core.designsystem.Success
import io.movieclaw.android.core.designsystem.TextFaint
import io.movieclaw.android.core.designsystem.TextMuted
import io.movieclaw.android.core.designsystem.TextPrimary
import io.movieclaw.android.core.designsystem.Bg
import io.movieclaw.android.core.designsystem.Warn
import io.movieclaw.android.core.model.SubActivityView
import io.movieclaw.android.core.model.TrackingStateRequest
import io.movieclaw.android.core.model.WantedView
import io.movieclaw.android.core.network.ApiFactory
import io.movieclaw.android.core.network.dataOrThrow
import io.movieclaw.android.core.network.friendlyMessage
import io.movieclaw.android.core.session.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/* 订阅详情（iOS SubscriptionDetailView 同构）：
   摘要卡（四段收录进度条 + 图例）→ 操作行（立即搜索/手动选种/更多）
   → 追踪明细（按季）→ 排查记录；⋯ 菜单：调整订阅/洗一轮版/自动续订/规则组/暂停追踪/取消订阅 */

data class SubDetailState(
    val loading: Boolean = true,
    val error: String? = null,
    val busy: Boolean = false,
    val sub: io.movieclaw.android.core.model.SubscriptionView? = null,
    val wanted: List<WantedView> = emptyList(),
    val activities: List<SubActivityView> = emptyList(),
)

@HiltViewModel
class SubscriptionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val apiFactory: ApiFactory,
    private val repository: SessionRepository,
) : ViewModel() {

    val id: Long = savedStateHandle.get<String>("subscriptionId")?.toLongOrNull() ?: -1

    private val _ui = MutableStateFlow(SubDetailState())
    val ui = _ui.asStateFlow()

    val origin: String? get() = repository.ui.value.origin

    private val _notice = MutableStateFlow<McNotice?>(null)
    val notice = _notice.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val origin = origin
            if (origin == null) { _ui.update { it.copy(loading = false, error = "尚未连接服务器") }; return@launch }
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val api = apiFactory.forOrigin(origin)
                val sub = api.subscription(id).dataOrThrow()
                val acts = runCatching { api.subscriptionActivities(id, 50).dataOrThrow() }.getOrDefault(emptyList())
                _ui.update { it.copy(loading = false, sub = sub, wanted = sub.wanted, activities = acts) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = friendlyMessage(e)) }
            }
        }
    }

    private fun act(onDone: (() -> Unit)? = null, block: suspend (io.movieclaw.android.core.api.McApi) -> String) {
        viewModelScope.launch {
            val origin = origin ?: return@launch
            _ui.update { it.copy(busy = true) }
            try {
                val msg = block(apiFactory.forOrigin(origin))
                _notice.value = McNotice(msg)
                load()
                onDone?.invoke()
            } catch (e: Exception) {
                _notice.value = McNotice(friendlyMessage(e), FeedbackTone.Error)
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun searchNow() = act { api ->
        api.searchMissingResources(id)
        "已重新排队，正在搜索缺失资源"
    }

    // ── 管理态用到的数据（规则组、选季） ──

    private val _ruleSets = MutableStateFlow<List<io.movieclaw.android.core.model.RuleSetView>>(emptyList())
    val ruleSets = _ruleSets.asStateFlow()

    fun loadRuleSets() {
        viewModelScope.launch {
            val origin = origin ?: return@launch
            runCatching { apiFactory.forOrigin(origin).ruleSets().dataOrThrow() }
                .onSuccess { list -> _ruleSets.value = list }
        }
    }

    /** 调整订阅（换选季）：`PATCH /subscriptions/{id}`，只带要改的字段 */
    fun updateSeasons(seasons: List<Int>) = act { api ->
        api.updateSubscription(
            id,
            kotlinx.serialization.json.buildJsonObject {
                put("selected_seasons", kotlinx.serialization.json.JsonArray(seasons.sorted().map { kotlinx.serialization.json.JsonPrimitive(it) }))
            },
        )
        "已更新选季"
    }

    /** 更换规则组：同上，只带 rule_set_id */
    fun updateRuleSet(ruleSetId: Long) = act { api ->
        api.updateSubscription(
            id,
            kotlinx.serialization.json.buildJsonObject {
                put("rule_set_id", kotlinx.serialization.json.JsonPrimitive(ruleSetId))
            },
        )
        "已更换规则组"
    }

    fun upgradeRun() = act { api ->
        api.upgradeRun(id)
        "洗版已开始，进展在「追踪明细」里跟进"
    }

    fun setFollowFuture(v: Boolean) = act { api ->
        api.setFollowFuture(id, io.movieclaw.android.core.model.FollowFutureRequest(v))
        if (v) "已开启自动续订" else "已关闭自动续订"
    }

    fun setTracking(paused: Boolean) = act { api ->
        api.setTrackingState(id, TrackingStateRequest(if (paused) "paused" else "active"))
        if (paused) "已暂停追踪" else "已恢复追踪"
    }

    fun unsubscribe(onDone: () -> Unit) = act(onDone = onDone) { api ->
        api.deleteSubscription(id)
        // 列表页在另一个导航目标里，不会自己重建：发个信号让它重新拉一次
        // （否则"取消后返回卡片还在，冷启动才消失"）
        SubscriptionEvents.notifyChanged()
        "订阅已取消"
    }

    fun consumeNotice() { _notice.value = null }
}

private fun statusLabel(s: io.movieclaw.android.core.model.SubscriptionView): String {
    val base = when (s.status) {
        "paused" -> "已暂停"
        "completed" -> if (s.media.kind == "tv") "已收齐" else "已入库"
        else -> "追踪中"
    }
    return if (s.progress.upgrading > 0) "$base · 洗版中（${s.progress.upgrading}）" else base
}

private fun statusDot(s: io.movieclaw.android.core.model.SubscriptionView): Color = when {
    s.status == "paused" -> TextMuted
    s.status == "completed" -> Ok
    s.progress.upgrading > 0 -> Color(0xFF2DD4BF)
    s.progress.grabbed > 0 -> Color(0xFF7FB0FF)
    else -> Warn
}

@Composable
fun SubscriptionDetailScreen(
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    vm: SubscriptionDetailViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val ruleSets by vm.ruleSets.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var cancelOpen by remember { mutableStateOf(false) }
    val sub = state.sub

    LaunchedEffect(vm) {
        vm.notice.collect { n ->
            if (n != null) {
                feedback.show(n)
                vm.consumeNotice()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = TextMuted,
                    modifier = Modifier
                        .size(32.dp).clip(CircleShape).clickable { onBack() }.padding(6.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    sub?.media?.title ?: "订阅详情",
                    style = McType.headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 这里原来有个右上角「管理」图标——网页端没有这个位置的控制：
                // 管理动作全在摘要卡底部那一行（立即搜索 / 手动选种 / 更多），"更多"点开是底部抽屉
                if (state.busy) CircularProgressIndicator(color = TextMuted, modifier = Modifier.size(18.dp))
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TextMuted)
                }
                state.error != null -> Column(Modifier.padding(24.dp)) {
                    Text(state.error!!, color = TextMuted)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.load() }) { Text("重试", color = TextPrimary) }
                }
                sub == null -> {}
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 32.dp),
                ) {
                    // ── 摘要卡 ──
                    Column(Modifier.padding(horizontal = McMetrics.pagePadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (sub.media.kind == "tv") "剧集订阅" else "电影订阅",
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp,
                                color = Color(0xFF9FB0C9),
                            )
                            Spacer(Modifier.weight(1f))
                            Box(Modifier.size(6.dp).clip(CircleShape).background(statusDot(sub)))
                            Spacer(Modifier.width(6.dp))
                            Text(statusLabel(sub), fontSize = 15.sp, color = Color.White.copy(alpha = 0.65f))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            RemoteImage(
                                url = sub.media.posterUrl, origin = vm.origin,
                                contentDescription = sub.media.title,
                                modifier = Modifier.width(80.dp).height(120.dp).clip(RoundedCornerShape(10.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(sub.media.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                sub.media.year?.let { Text(it.toString(), fontSize = 13.sp, color = TextMuted, modifier = Modifier.padding(top = 2.dp)) }
                                sub.createdAt?.let {
                                    Text("订阅于 " + it.take(16).replace('T', ' '), fontSize = 13.sp, color = TextFaint, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        FactRow("收录范围", if (sub.selectedSeasons.isEmpty()) "正片" else sub.selectedSeasons.sorted().joinToString("、") { "第 $it 季" })
                        FactRow("自动续订", if (sub.followFuture) "已开启" else "已关闭")
                        FactRow("规则组", "规则组 #${sub.ruleSetId}")
                        Spacer(Modifier.height(14.dp))

                        // ── 收录进度（四段 + 图例） ──
                        val p = sub.progress
                        val dl = (p.grabbed - p.downloaded).coerceAtLeast(0)
                        val missing = (p.total - p.imported - p.upgrading - dl).coerceAtLeast(0)
                        Text("收录进度", fontSize = 13.sp, color = TextMuted)
                        Text("${p.imported} / ${p.total} 集", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.height(6.dp))
                        val segs = listOf(
                            Success to p.imported,
                            Color(0xFF2DD4BF) to p.upgrading,
                            Color(0xFF60A5FA) to dl,
                            Color.White.copy(alpha = 0.16f) to missing,
                        )
                        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
                            segs.forEach { (c, n) -> if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxWidth().height(6.dp).background(c)) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("已入库 ${p.imported}    洗版中 ${p.upgrading}    下载中 $dl    缺失 $missing", fontSize = 12.sp, color = TextMuted)

                        Spacer(Modifier.height(10.dp))
                        // 最近一轮搜索：从排查记录里取最新一条搜索类活动（iOS SearchRoundBar 同语义）
                        state.activities.firstOrNull { it.type.contains("search", true) }?.let { act ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF9FB0C9)))
                                Spacer(Modifier.width(8.dp))
                                Text(act.message, fontSize = 13.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton("立即搜索", filled = true, modifier = Modifier.weight(1f)) { vm.searchNow() }
                            ActionButton("手动选种", modifier = Modifier.weight(1f)) { onOpenSearch() }
                            ActionButton("更多", modifier = Modifier.weight(1f)) { menuOpen = true }
                        }
                    }

                    // ── 追踪明细（按季） ──
                    if (state.wanted.isNotEmpty()) {
                        Text("追踪明细", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, modifier = Modifier.padding(start = McMetrics.pagePadding, top = 26.dp, bottom = 4.dp))
                        val bySeason = state.wanted.groupBy { it.seasonNumber }
                        bySeason.forEach { (season, items) ->
                            val imported = items.count { it.importedAt != null }
                            val dlCount = items.count { it.grabbedAt != null && it.downloadedAt == null }
                            val missingCount = items.count { it.status == "wanted" && it.grabbedAt == null }
                            Row(Modifier.padding(horizontal = McMetrics.pagePadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                // 季 chip（iOS 追踪明细的 S1/S2 徽标）
                                Text(
                                    if (season == 0) "SP" else "S$season",
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("第 $season 季 · $imported / ${items.size} 集已入库", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Spacer(Modifier.height(4.dp))
                                    // 里程碑链：缺 N 集 — M 集下载中 — 洗版 K（iOS 同序）
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val chain = listOfNotNull(
                                            if (missingCount > 0) "缺 $missingCount 集" else null,
                                            if (dlCount > 0) "$dlCount 集下载中" else null,
                                            if (sub.progress.upgrading > 0) "洗版 ${sub.progress.upgrading}" else null,
                                        )
                                        if (chain.isEmpty()) {
                                            Text("等待新集播出", fontSize = 12.sp, color = TextMuted)
                                        } else chain.forEachIndexed { i, s ->
                                            if (i > 0) {
                                                Box(Modifier.padding(horizontal = 6.dp).width(14.dp).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                                            }
                                            Text(s, fontSize = 12.sp, color = TextMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── 排查记录 ──
                    if (state.activities.isNotEmpty()) {
                        Text("排查记录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, modifier = Modifier.padding(start = McMetrics.pagePadding, top = 26.dp, bottom = 4.dp))
                        Column(Modifier.padding(horizontal = McMetrics.pagePadding)) {
                            state.activities.take(20).forEach { act ->
                                Row(Modifier.padding(vertical = 6.dp)) {
                                    Text("•", color = TextFaint, modifier = Modifier.padding(end = 8.dp))
                                    Text(act.message, fontSize = 13.sp, color = TextMuted, lineHeight = 18.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 更多：底部抽屉（网页移动端就是底部弹出，不是右上角下拉） ──
        if (menuOpen && sub != null) {
            var pickSeasons by remember { mutableStateOf(false) }
            var pickRule by remember { mutableStateOf(false) }
            if (pickSeasons) {
                val current = sub.selectedSeasons.toSet()
                var chosen by remember { mutableStateOf(current) }
                SeasonPickerSheet(
                    title = "调整订阅 · 选季",
                    seasons = (sub.seasonCollection.map { it.seasonNumber } + sub.selectedSeasons).distinct().sorted(),
                    selected = chosen,
                    onToggle = { n -> chosen = if (n in chosen) chosen - n else chosen + n },
                    onConfirm = { vm.updateSeasons(chosen.toList()); pickSeasons = false; menuOpen = false },
                    onDismiss = { pickSeasons = false },
                )
            } else if (pickRule) {
                RuleSetPickerSheet(
                    ruleSets = ruleSets,
                    currentId = sub.ruleSetId,
                    onPick = { rid -> vm.updateRuleSet(rid); pickRule = false; menuOpen = false },
                    onDismiss = { pickRule = false },
                )
            } else {
                ManageSheet(
                    followFuture = if (sub.media.kind == "movie") null else sub.followFuture,
                    paused = sub.status == "paused",
                    completed = sub.status == "completed",
                    onAdjust = { pickSeasons = true },
                    onUpgradeRun = { vm.upgradeRun(); menuOpen = false },
                    onToggleFollowFuture = { vm.setFollowFuture(!sub.followFuture); menuOpen = false },
                    onSwitchRule = {
                        vm.loadRuleSets()
                        pickRule = true
                    },
                    onTogglePause = { vm.setTracking(sub.status == "paused"); menuOpen = false },
                    onRemove = { menuOpen = false; cancelOpen = true },
                    onDismiss = { menuOpen = false },
                )
            }
        }

        // ── 取消订阅确认 ──
        if (cancelOpen && sub != null) {
            AlertDialog(
                onDismissRequest = { cancelOpen = false },
                containerColor = Color(0xFF1E212B),
                title = { Text("取消订阅", color = TextPrimary) },
                text = {
                    Text(
                        "取消订阅《${sub.media.title}》将停止追踪剩余内容。默认只取消订阅，已经下载或入库的内容都会保留。",
                        color = TextMuted, fontSize = 14.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { cancelOpen = false; vm.unsubscribe { onBack() } }) {
                        Text("取消订阅", color = Danger)
                    }
                },
                dismissButton = { TextButton(onClick = { cancelOpen = false }) { Text("先不", color = TextPrimary) } },
            )
        }
    }
}

@Composable
private fun FactRow(k: String, v: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(k, fontSize = 13.sp, color = TextFaint, modifier = Modifier.width(76.dp))
        Text(v, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
    }
}

@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (filled) Brush.linearGradient(listOf(Color(0xFFF6F8FC), Color(0xFFCCD6E6)))
                else androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.14f))
            )
            .border(1.dp, if (filled) Color.Transparent else LineSoft, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (filled) Color(0xFF141821) else Color.White)
    }
}

@Composable
private fun MenuRow(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 15.sp, color = if (danger) Danger else TextPrimary,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
    )
}

/**
 * 「更多」底部抽屉（网页移动端范式）：条目与网页 `SubscriptionManageMenu` 一一对应，
 * 且每一项都真的打接口（此前「调整订阅」「更换规则组」是演示占位）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ManageSheet(
    followFuture: Boolean?,
    paused: Boolean,
    completed: Boolean,
    onAdjust: () -> Unit,
    onUpgradeRun: () -> Unit,
    onToggleFollowFuture: () -> Unit,
    onSwitchRule: () -> Unit,
    onTogglePause: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF15161A),
        contentColor = TextPrimary,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            SheetRow("调整订阅…", onClick = onAdjust)
            SheetRow("洗一轮版…", onClick = onUpgradeRun)
            if (followFuture != null) {
                SheetRow(if (followFuture) "关闭自动续订" else "开启自动续订", onClick = onToggleFollowFuture)
            }
            SheetRow("更换规则组…", onClick = onSwitchRule)
            SheetRow(
                if (paused) "恢复追踪" else "暂停追踪",
                enabled = !completed,
                onClick = onTogglePause,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
            SheetRow("取消订阅", danger = true, onClick = onRemove)
        }
    }
}

@Composable
private fun SheetRow(
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        label,
        fontSize = 15.sp,
        color = when {
            !enabled -> TextFaint
            danger -> Danger
            else -> TextPrimary
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 15.dp),
    )
}

/** 调整订阅的选季抽屉：对应网页 `SubscriptionAdjustDialog` 的选季部分 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SeasonPickerSheet(
    title: String,
    seasons: List<Int>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF15161A),
        contentColor = TextPrimary,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            if (seasons.isEmpty()) {
                Text("这条订阅没有可选的季", fontSize = 12.sp, color = TextFaint, modifier = Modifier.padding(start = 20.dp))
            }
            seasons.forEach { n ->
                val on = n in selected
                Row(
                    Modifier.fillMaxWidth().clickable { onToggle(n) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(22.dp)) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp).alpha(if (on) 1f else 0f),
                        )
                    }
                    Text(if (n == 0) "特别篇" else "第 $n 季", fontSize = 14.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onConfirm) { Text("保存", color = Accent, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** 更换规则组抽屉（`GET /rule-sets` → `PATCH /subscriptions/{id}` 带 rule_set_id） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RuleSetPickerSheet(
    ruleSets: List<io.movieclaw.android.core.model.RuleSetView>,
    currentId: Long?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF15161A),
        contentColor = TextPrimary,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text("更换规则组", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            if (ruleSets.isEmpty()) {
                CircularProgressIndicator(color = TextMuted, modifier = Modifier.padding(20.dp).size(18.dp))
            }
            ruleSets.forEach { rs ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(rs.id) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(22.dp)) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp).alpha(if (rs.id == currentId) 1f else 0f),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(rs.name, fontSize = 14.sp)
                        if (rs.isDefault) Text("默认规则组", fontSize = 11.sp, color = TextFaint)
                    }
                }
            }
        }
    }
}
