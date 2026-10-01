package io.movieclaw.android.feature.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.movieclaw.android.core.designsystem.Accent
import io.movieclaw.android.core.designsystem.Bg
import io.movieclaw.android.core.designsystem.Danger
import io.movieclaw.android.core.designsystem.FlatCard
import io.movieclaw.android.core.designsystem.FlatRow
import io.movieclaw.android.core.designsystem.GroupLabel
import io.movieclaw.android.core.designsystem.LineSoft
import io.movieclaw.android.core.designsystem.McMetrics
import io.movieclaw.android.core.designsystem.McNavButton
import io.movieclaw.android.core.designsystem.McTabBarContentPadding
import io.movieclaw.android.core.designsystem.McTopBar
import io.movieclaw.android.core.designsystem.McTopBarVariant
import io.movieclaw.android.core.designsystem.McType
import io.movieclaw.android.core.designsystem.TextFaint
import io.movieclaw.android.core.designsystem.TextMuted
import io.movieclaw.android.core.designsystem.TextPrimary
import io.movieclaw.android.core.model.SessionView
import io.movieclaw.android.core.network.BuildInfo
import io.movieclaw.android.core.session.SessionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MoreViewModel @Inject constructor(
    private val repository: SessionRepository,
) : ViewModel() {
    val ui = repository.ui
    val servers = repository.servers

    private val _loggingOut = MutableStateFlow(false)
    val loggingOut = _loggingOut.asStateFlow()

    fun logout() {
        if (_loggingOut.value) return
        viewModelScope.launch {
            _loggingOut.value = true
            try {
                repository.logout()
            } finally {
                _loggingOut.value = false
            }
        }
    }
}

/**
 * 「我的」—— 按移动端网页实测重排：
 *   · 顶栏是页签根页形态（标题 16/600 在 x=12，右侧搜索圆钮）；
 *   · 账号行**不带卡片**：头像 56×56（白底、深色首字母 22/700）+ 名字 20/600 +
 *     副行「@ 昵称 · 超级管理员」16/62% + 行尾雪佛龙；
 *   · 之下是分组小标签（11/650、36% 白）配**平色卡**（white 4% + white 8% 描边 + r16，
 *     行间 6% 白细线），每行 49 高、行首 30×30 图标位、文字 16/500；
 *   · 「退出登录」整行文字用 --danger。
 */
@Composable
fun MoreScreen(
    onOpenNotices: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSearch: () -> Unit = {},
    vm: MoreViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val loggingOut by vm.loggingOut.collectAsStateWithLifecycle()

    val scroll = rememberScrollState()
    io.movieclaw.android.core.designsystem.TrackTabBarMinimize(scroll)

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(top = McMetrics.topBarHeight, bottom = McTabBarContentPadding),
        ) {
            AccountRow(
                session = ui.session,
                origin = ui.origin,
                onClick = onOpenAccounts,
            )

            GroupLabel("常用", modifier = Modifier.padding(top = 20.dp))
            FlatCard(Modifier.padding(horizontal = McMetrics.pagePadding).fillMaxWidth()) {
                FlatRow(
                    title = "设置",
                    icon = Icons.Rounded.Settings,
                    onClick = onOpenSettings,
                )
                RowDivider()
                FlatRow(
                    title = "通知中心",
                    subtitle = "订阅失败、上游不可达等需要处理的问题",
                    icon = Icons.Rounded.Notifications,
                    onClick = onOpenNotices,
                )
                RowDivider()
                FlatRow(
                    title = "AI 助手",
                    subtitle = "对话式管理：搜索、订阅、整理、排障",
                    icon = Icons.Rounded.AutoAwesome,
                    onClick = onOpenAgent,
                )
            }

            GroupLabel("账号", modifier = Modifier.padding(top = 20.dp))
            FlatCard(Modifier.padding(horizontal = McMetrics.pagePadding).fillMaxWidth()) {
                FlatRow(
                    title = "切换账号",
                    icon = Icons.Rounded.SwitchAccount,
                    onClick = onOpenAccounts,
                )
                RowDivider()
                FlatRow(
                    title = if (loggingOut) "正在退出…" else "退出登录",
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    titleColor = Danger,
                    showChevron = false,
                    onClick = { if (!loggingOut) vm.logout() },
                )
            }

            GroupLabel("服务器", modifier = Modifier.padding(top = 20.dp))
            FlatCard(Modifier.padding(horizontal = McMetrics.pagePadding).fillMaxWidth()) {
                FlatRow(
                    title = "地址",
                    subtitle = ui.origin ?: "-",
                    icon = Icons.Rounded.Smartphone,
                    showChevron = false,
                )
                RowDivider()
                FlatRow(title = "已保存的服务器", subtitle = "${servers.size} 个", showChevron = false)
                RowDivider()
                FlatRow(
                    title = "客户端",
                    subtitle = "MovieClaw-Android/${BuildInfo.APP_VERSION}",
                    showChevron = false,
                )
                RowDivider()
                FlatRow(title = "成员权限", subtitle = capabilityText(ui.session), showChevron = false)
            }

            if (ui.cached) {
                Text(
                    "已用本地快照渲染，后台校验中",
                    style = McType.micro,
                    color = TextFaint,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        McTopBar(
            variant = McTopBarVariant.Root,
            title = "我的",
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            McNavButton(Icons.Rounded.Search, contentDescription = "搜索", onClick = onOpenSearch)
        }
    }
}

/** 平色卡的 6% 白细线（实测 divide-white/[0.06]） */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(LineSoft),
    )
}

/** 账号行：实测无卡片、头像 56、名字 20/600、副行 16/62% */
@Composable
private fun AccountRow(session: SessionView?, origin: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = McMetrics.pagePadding, vertical = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Brush.linearGradient(listOf(Color.White, Color(0xFFDFE4EC))), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (session?.nickname ?: session?.username ?: "M").take(2).uppercase(),
                style = McType.title2,
                color = Color(0xFF141821),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session?.nickname ?: session?.username ?: "未登录",
                style = McType.title3,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append("@ ")
                    append(session?.username ?: "-")
                    append(" · ")
                    append(roleLabel(session?.role))
                    if (!origin.isNullOrEmpty()) append(" · ").append(origin)
                },
                style = McType.body,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = TextFaint,
            modifier = Modifier.size(15.dp),
        )
    }
}

private fun roleLabel(role: String?): String = when (role) {
    "admin" -> "超级管理员"
    "member" -> "成员"
    else -> "访客"
}

private fun capabilityText(session: SessionView?): String {
    val caps = session?.capabilities ?: return "-"
    return listOfNotNull(
        if (caps.allowSearch) "搜索" else null,
        if (caps.allowSubscribe) "订阅" else null,
        if (caps.allowDirectDownload) "一键下载" else null,
    ).ifEmpty { listOf("仅浏览") }.joinToString(" / ")
}
