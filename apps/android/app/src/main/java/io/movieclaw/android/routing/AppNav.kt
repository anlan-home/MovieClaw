package io.movieclaw.android.routing

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.movieclaw.android.core.playback.PlaybackSessionHolder
import io.movieclaw.android.core.playback.PlayTarget
import io.movieclaw.android.feature.agent.AgentConversationScreen
import io.movieclaw.android.feature.agent.AgentNewSessionScreen
import io.movieclaw.android.feature.agent.AgentSessionsScreen
import io.movieclaw.android.feature.detail.ItemDetailScreen
import io.movieclaw.android.feature.discover.CollectionScreen
import io.movieclaw.android.feature.discover.TitleDetailScreen
import io.movieclaw.android.feature.library.LibraryDetailScreen
import io.movieclaw.android.feature.notices.NoticeCenterScreen
import io.movieclaw.android.feature.settings.DevicesSettingsScreen
import io.movieclaw.android.feature.settings.PlaybackSettingsScreen
import io.movieclaw.android.feature.settings.ProfileSettingsScreen
import io.movieclaw.android.feature.settings.SettingsIndexScreen
import io.movieclaw.android.feature.settings.WebManagedSectionScreen
import io.movieclaw.android.feature.account.AccountSwitcherScreen
import io.movieclaw.android.feature.onboarding.LoginScreen
import io.movieclaw.android.feature.settings.LogsSettingsScreen
import io.movieclaw.android.feature.settings.MaintenanceSettingsScreen
import io.movieclaw.android.feature.settings.NetworkSettingsScreen
import io.movieclaw.android.feature.settings.MembersSettingsScreen
import io.movieclaw.android.feature.player.PlayerScreen
import io.movieclaw.android.feature.root.MainTabScreen
import io.movieclaw.android.feature.search.SearchScreen
import io.movieclaw.android.feature.subscriptions.SubscribeScreen
import io.movieclaw.android.feature.subscriptions.SubscriptionDetailScreen
import javax.inject.Inject
import io.movieclaw.android.feature.library.FavoritesScreen
import io.movieclaw.android.feature.library.CollectionsScreen
import io.movieclaw.android.feature.library.LibraryManageScreen
import io.movieclaw.android.feature.discover.FilteredResultsScreen
import io.movieclaw.android.feature.discover.PersonScreen

@HiltViewModel
class NavViewModel @Inject constructor(private val holder: PlaybackSessionHolder) : ViewModel() {
    fun open(target: PlayTarget) = holder.open(target)
}

/** M1 导航:五 Tab 主壳 + 库详情 + 条目详情 + 播放器(全屏,会话在应用级宿主) */
@Composable
fun AppNav() {
    val navController = rememberNavController()
    val navVm: NavViewModel = hiltViewModel()
    val context = androidx.compose.ui.platform.LocalContext.current

    // 全站订阅索引（单例）：发现页的订阅判断由它统一提供
    val subscriptionsEntry = androidx.compose.runtime.remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            SubscriptionIndexEntry::class.java,
        )
    }
    val subscriptions = androidx.compose.runtime.remember { subscriptionsEntry.subscriptionIndex() }
    // 订阅在别处被改动（弹层里取消 / 新建）后，索引必须作废并重拉——否则卡片的
    // 「已订阅」会继续挂到 30 秒缓存过期（用户报"取消后卡片还显示已订阅"）。
    androidx.compose.runtime.LaunchedEffect(
        io.movieclaw.android.feature.subscriptions.SubscriptionEvents.revision
    ) {
        if (io.movieclaw.android.feature.subscriptions.SubscriptionEvents.revision > 0) {
            subscriptions.invalidate()
            subscriptions.ensureLoaded()
        }
    }

    // 取消订阅的 VM 通过它把那条从索引里摘掉（比等一次重拉更及时）
    io.movieclaw.android.feature.subscriptions.SubscribeSheetHost.removeFromIndex = { id ->
        subscriptions.remove(id)
    }

    NavHost(navController = navController, startDestination = "tabs") {
        composable("tabs") {
            MainTabScreen(
                onOpenLibrary = { id, name ->
                    navController.navigate("library/$id?name=${Uri.encode(name)}")
                },
                onOpenItem = { libraryId, itemId ->
                    navController.navigate("item/$libraryId/$itemId")
                },
                onPlay = { target ->
                    navVm.open(target)
                    navController.navigate("player")
                },
                onOpenSearch = { navController.navigate("search") },
                onOpenNotices = { navController.navigate("notices") },
                onOpenSubscription = { id -> navController.navigate("subscription/$id") },
                onOpenTitle = { ref -> navController.navigate("title?ref=${Uri.encode(ref)}") },
                onOpenCollection = { ref, title ->
                    navController.navigate("collection?ref=${Uri.encode(ref)}&title=${Uri.encode(title)}")
                },
                onSubscribeTitle = { ref, seed ->
                    io.movieclaw.android.feature.subscriptions.SubscribeSheetHost.open(ref, seed)
                },
                onOpenManage = { navController.navigate("libraryManage") },
                onOpenFavorites = { navController.navigate("favorites") },
                onOpenFiltered = { navController.navigate("filtered") },
                subscriptions = subscriptions,
                onOpenAgent = { navController.navigate("agent") },
                // 活动页「交给 AI 分析」：工单已作为首条消息提交，直接进那个会话
                onOpenAgentSession = { sessionId -> navController.navigate("agentConversation/$sessionId") },
                onOpenActivityDetail = { view -> navController.navigate("activityDetail/$view") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenAccounts = { navController.navigate("accounts") },
            )
        }
        composable("agent") {
            AgentSessionsScreen(
                onBack = { navController.popBackStack() },
                onOpenSession = { id -> navController.navigate("agentConversation/$id") },
                onNewSession = { navController.navigate("agentNew") },
            )
        }
        composable("agentNew") {
            AgentNewSessionScreen(
                onBack = { navController.popBackStack() },
                onCreated = { id ->
                    navController.navigate("agentConversation/$id") {
                        popUpTo("agent")
                    }
                },
            )
        }
        composable("agentConversation/{sessionId}") {
            AgentConversationScreen(onBack = { navController.popBackStack() })
        }
        composable("title?ref={ref}") { entry ->
            TitleDetailScreen(
                titleRef = entry.arguments?.getString("ref").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenTitle = { ref -> navController.navigate("title?ref=${Uri.encode(ref)}") },
                onSubscribe = { ref ->
                    io.movieclaw.android.feature.subscriptions.SubscribeSheetHost.open(ref)
                },
                onOpenItem = { libraryId, itemId -> navController.navigate("item/$libraryId/$itemId") },
                onPlay = { target ->
                    navVm.open(target)
                    navController.navigate("player")
                },
            )
        }
        composable("collection?ref={ref}&title={title}") { entry ->
            CollectionScreen(
                title = entry.arguments?.getString("title").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenTitle = { ref -> navController.navigate("title?ref=${Uri.encode(ref)}") },
            )
        }
        composable("favorites") { entry ->
            FavoritesScreen(
                onBack = { navController.popBackStack() },
                onOpenItem = { libId, itemId -> navController.navigate("item/$libId/$itemId") },
            )
        }
        composable("collections") { entry ->
            CollectionsScreen(
                onBack = { navController.popBackStack() },
                onOpenCollection = { id, name -> navController.navigate("collection?ref=$id&title=${Uri.encode(name)}") },
            )
        }
        composable("libraryManage") { entry ->
            LibraryManageScreen(
                onBack = { navController.popBackStack() },
                onOpenLibrary = { id, name -> navController.navigate("library/$id?name=${Uri.encode(name)}") },
            )
        }
        composable("filtered") { entry ->
            FilteredResultsScreen(
                onBack = { navController.popBackStack() },
                onOpenTitle = { ref -> navController.navigate("title?ref=${Uri.encode(ref)}") },
            )
        }
        composable("person/{tmdbPersonId}") { entry ->
            PersonScreen(
                onBack = { navController.popBackStack() },
                onOpenTitle = { ref -> navController.navigate("title?ref=${Uri.encode(ref)}") },
            )
        }
        composable("search") {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenLibraryItem = { libraryId, itemId ->
                    if (libraryId > 0) navController.navigate("item/$libraryId/$itemId")
                },
                onSubscribe = { titleRef ->
                    io.movieclaw.android.feature.subscriptions.SubscribeSheetHost.open(titleRef)
                },
                onOpenTitle = { ref -> navController.navigate("title?ref=${Uri.encode(ref)}") },
            )
        }
        // 订阅弹层不再是一个导航目标：它由 `SubscribeSheetHost` 在**当前页面之上**渲染
        // （导航过去会让原页面离开组合，弹层的遮罩就压在一片空黑上——用户报的"整页变黑"）
        composable("subscription/{subscriptionId}") {
            SubscriptionDetailScreen(onBack = { navController.popBackStack() })
        }
        composable("notices") {
            NoticeCenterScreen(onBack = { navController.popBackStack() })
        }
        // 活动二级页（网页 /activity?view=）：进行中 / 已结束 / 最近播放 / 观看统计
        composable("activityDetail/{view}") { entry ->
            io.movieclaw.android.feature.activity.ActivityDetailScreen(
                view = entry.arguments?.getString("view").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            SettingsIndexScreen(
                onBack = { navController.popBackStack() },
                onOpen = { section -> navController.navigate("settings/${section.name}") },
            )
        }
        composable("accounts") {
            AccountSwitcherScreen(
                onBack = { navController.popBackStack() },
                onAddAccount = { navController.navigate("addAccount") },
            )
        }
        composable("addAccount") {
            LoginScreen(
                presetUsername = null,
                onDone = { navController.popBackStack() },
            )
        }
        composable("settings/{section}") { entry ->
            val section = entry.arguments?.getString("section")
                ?.let { name -> io.movieclaw.android.feature.settings.SettingsSection.entries.firstOrNull { it.name == name } }
                ?: return@composable
            when (section) {
                io.movieclaw.android.feature.settings.SettingsSection.PROFILE ->
                    ProfileSettingsScreen(onBack = { navController.popBackStack() })
                io.movieclaw.android.feature.settings.SettingsSection.DEVICES ->
                    DevicesSettingsScreen(onBack = { navController.popBackStack() })
                io.movieclaw.android.feature.settings.SettingsSection.PLAYBACK ->
                    PlaybackSettingsScreen(onBack = { navController.popBackStack() })
                io.movieclaw.android.feature.settings.SettingsSection.MEMBERS ->
                    MembersSettingsScreen(onBack = { navController.popBackStack() })
                io.movieclaw.android.feature.settings.SettingsSection.MAINTENANCE ->
                    MaintenanceSettingsScreen(onBack = { navController.popBackStack() })
                io.movieclaw.android.feature.settings.SettingsSection.NETWORK ->
                    NetworkSettingsScreen(onBack = { navController.popBackStack() })
                io.movieclaw.android.feature.settings.SettingsSection.LOGS ->
                    LogsSettingsScreen(onBack = { navController.popBackStack() })
                else -> WebManagedSectionScreen(section, onBack = { navController.popBackStack() })
            }
        }
        composable("library/{libraryId}?name={name}") { entry ->
            val libraryId = entry.arguments?.getString("libraryId")?.toLongOrNull() ?: return@composable
            LibraryDetailScreen(
                libraryId = libraryId,
                title = entry.arguments?.getString("name").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenItem = { libraryId2, itemId -> navController.navigate("item/$libraryId2/$itemId") },
            )
        }
        composable("item/{libraryId}/{itemId}") { entry ->
            val libraryId = entry.arguments?.getString("libraryId")?.toLongOrNull() ?: return@composable
            val itemId = entry.arguments?.getString("itemId")?.toLongOrNull() ?: return@composable
            ItemDetailScreen(
                libraryId = libraryId,
                itemId = itemId,
                onBack = { navController.popBackStack() },
                onPlay = { target ->
                    navVm.open(target)
                    navController.navigate("player")
                },
            )
        }
        composable("player") {
            PlayerScreen(onExit = { navController.popBackStack() })
        }
    }

    // 订阅底部抽屉：宿主在这里，浮在当前页面之上（遮罩只压暗当前页，与 HTML 原型一致）
    io.movieclaw.android.feature.subscriptions.SubscribeSheetHost.Render(
        onOpenSubscription = { id -> navController.navigate("subscription/$id") },
    )
}

/** 从 Application 取单例（AppNav 是普通可组合，不在 Hilt 的注入图里） */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SubscriptionIndexEntry {
    fun subscriptionIndex(): io.movieclaw.android.feature.subscriptions.SubscriptionIndex
}
