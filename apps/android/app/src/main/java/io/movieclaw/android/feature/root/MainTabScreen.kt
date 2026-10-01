package io.movieclaw.android.feature.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.movieclaw.android.core.designsystem.Bg
import io.movieclaw.android.core.designsystem.McCapsuleTabBar
import io.movieclaw.android.core.designsystem.McMetrics
import io.movieclaw.android.core.playback.PlayTarget
import io.movieclaw.android.feature.activity.ActivityScreen
import io.movieclaw.android.feature.discover.DiscoverScreen
import io.movieclaw.android.feature.library.LibraryScreen
import io.movieclaw.android.feature.more.MoreScreen
import io.movieclaw.android.feature.subscriptions.SubsHomeScreen

enum class MainTab(
    val label: String,
    val icon: ImageVector,
) {
    DISCOVER("发现", io.movieclaw.android.core.designsystem.McTabIcons.House),
    LIBRARY("媒体库", io.movieclaw.android.core.designsystem.McTabIcons.Library),
    SUBSCRIPTIONS("订阅", io.movieclaw.android.core.designsystem.McTabIcons.Bookmark),
    ACTIVITY("活动", io.movieclaw.android.core.designsystem.McTabIcons.Wave),
    MORE("我的", Icons.Rounded.Person),
}

/**
 * 五 Tab 主壳 —— 底栏按移动端网页实测重做：
 * 悬浮胶囊 348×54（左右 21、距底 22）、圆角 999、**图标-only**（无文字标签）、
 * 当前格背后一枚白 15% 药丸；最后一格是账号头像（25 圆、白底、深色首字母）。
 * 之前用的是 Material3 的标准 NavigationBar（贴底、带文字、方块指示器），与网页完全不同。
 */
@Composable
fun MainTabScreen(
    onOpenLibrary: (Long, String) -> Unit,
    onOpenItem: (Long, Long) -> Unit,
    onPlay: (PlayTarget) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenNotices: () -> Unit,
    onOpenSubscription: (Long) -> Unit,
    onOpenTitle: (String) -> Unit,
    onOpenCollection: (String, String) -> Unit,
    onSubscribeTitle: (String, io.movieclaw.android.feature.subscriptions.SubscribeSheetHost.Seed?) -> Unit,
    onOpenManage: () -> Unit,
    onOpenFiltered: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenAgent: () -> Unit,
    /** 活动页「交给 AI 分析」建好会话后直接进那个会话 */
    onOpenAgentSession: (String) -> Unit,
    /** 活动页进二级页（active / history / plays / stats） */
    onOpenActivityDetail: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccounts: () -> Unit,
    subscriptions: io.movieclaw.android.feature.subscriptions.SubscriptionIndex,
) {
    var current by rememberSaveable { mutableStateOf(MainTab.DISCOVER) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (current) {
            MainTab.DISCOVER -> DiscoverScreen(
                onOpenLibrary = onOpenLibrary,
                onOpenItem = onOpenItem,
                onPlay = onPlay,
                onOpenSearch = onOpenSearch,
                onOpenTitle = onOpenTitle,
                onOpenCollection = onOpenCollection,
                onSubscribeTitle = onSubscribeTitle,
                onOpenFiltered = onOpenFiltered,
                subscriptions = subscriptions,
            )
            MainTab.LIBRARY -> LibraryScreen(
                onOpenLibrary = onOpenLibrary,
                onOpenItem = onOpenItem,
                onPlay = onPlay,
                onOpenSearch = onOpenSearch,
                onOpenManage = onOpenManage,
                onOpenFavorites = onOpenFavorites,
            )
            MainTab.SUBSCRIPTIONS -> SubsHomeScreen(
                onOpenSubscription = onOpenSubscription,
                onPlay = onPlay,
                onOpenTitle = onOpenTitle,
                // 空态的「去发现剧集」：切到发现页（网页是跳 /discover/tv）
                onOpenDiscover = { current = MainTab.DISCOVER },
            )
            MainTab.ACTIVITY -> ActivityScreen(
                onOpenAgentSession = onOpenAgentSession,
                onOpenActivityDetail = onOpenActivityDetail,
            )
            MainTab.MORE -> MoreScreen(
                onOpenNotices = onOpenNotices,
                onOpenAgent = onOpenAgent,
                onOpenSettings = onOpenSettings,
                onOpenAccounts = onOpenAccounts,
            )
        }

        // 悬浮胶囊底栏：内容从它下面穿过（实测就是这样，底栏不占布局高度）
        McCapsuleTabBar(
            icons = MainTab.entries.map { it.icon },
            labels = MainTab.entries.map { it.label },
            selectedIndex = current.ordinal,
            onSelect = { current = MainTab.entries[it] },
            avatarInitials = "AN",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = McMetrics.tabBarBottom),
        )
    }
}
