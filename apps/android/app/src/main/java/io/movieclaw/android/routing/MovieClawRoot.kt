package io.movieclaw.android.routing

import kotlinx.coroutines.flow.MutableStateFlow
import io.movieclaw.android.feature.share.ShareRoute
import io.movieclaw.android.core.session.DeepLinkBus
import io.movieclaw.android.core.designsystem.LocalFeedback
import io.movieclaw.android.core.designsystem.FeedbackHost
import io.movieclaw.android.core.designsystem.FeedbackBus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.movieclaw.android.core.designsystem.Bg
import io.movieclaw.android.core.session.SessionPhase
import io.movieclaw.android.core.session.SessionRepository
import io.movieclaw.android.feature.onboarding.LoginScreen
import io.movieclaw.android.feature.root.MainTabScreen
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class RootViewModel @Inject constructor(
    private val repository: SessionRepository,
    val feedback: FeedbackBus,
    private val deepLinkBus: DeepLinkBus,
) : ViewModel() {
    val state = repository.ui
    val share = deepLinkBus.pendingShare

    var dismissedShare = MutableStateFlow<String?>(null)

    fun dismissShare(slug: String) {
        dismissedShare.value = slug
    }

    /** 当前要展示的访客分享(与登录态无关) */
    fun activeShareLink(): io.movieclaw.android.core.model.ShareLink? =
        share.value?.takeIf { it.slug != dismissedShare.value }

    init {
        if (repository.ui.value.phase == SessionPhase.BOOTING) {
            viewModelScope.launch { repository.boot() }
        }
    }
}

/** 顶层状态机:BOOTING(黑屏)→ NEEDS_LOGIN(登录页)/ READY(主壳) */
@Composable
fun MovieClawRoot(vm: RootViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val shareLink by vm.share.collectAsStateWithLifecycle()
    val toasts by vm.feedback.toasts.collectAsStateWithLifecycle()

    androidx.compose.runtime.CompositionLocalProvider(LocalFeedback provides vm.feedback) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            when {
                // 访客分享不需要登录:深链打开时优先展示分享页
                shareLink != null -> ShareRoute(
                    link = shareLink!!,
                    onExit = { vm.dismissShare(shareLink!!.slug) },
                )
                state.phase == SessionPhase.BOOTING -> Box(Modifier.fillMaxSize().background(Bg))
                state.phase == SessionPhase.NEEDS_LOGIN -> LoginScreen(presetUsername = state.presetUsername)
                else -> AppNav()
            }
            // 顶部 Toast 常驻在所有内容之上(iOS Feedback 宿主)
            FeedbackHost(
                toasts = toasts,
                onDismiss = vm.feedback::dismiss,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
            )
        }
    }
}
