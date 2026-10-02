package io.movieclaw.android.core.model

import kotlinx.serialization.Serializable

/**
 * M0 手写模型:只包含当前页面用到的字段。
 * JSON 全局启用 ignoreUnknownKeys + SnakeCase 命名映射(见 NetworkModule.json),
 * 服务端新增字段不会破坏解码;代码生成管线(§4.2)就绪后由生成物接管。
 */

@Serializable
data class DeviceClientInfo(
    val kind: String = "android",
    val name: String,
    val installationId: String,
    val clientVersion: String,
    val platform: String,
)

@Serializable
data class DeviceLoginRequest(
    val username: String,
    val password: String,
    val client: DeviceClientInfo,
)

@Serializable
data class CreateAdminRequest(val username: String, val password: String)

@Serializable
data class BootstrapStatus(val initialized: Boolean = true)

/** GET /api/v1/health —— 公开端点,非信封结构 */
@Serializable
data class HealthView(
    val status: String? = null,
    val service: String? = null,
    val environment: String? = null,
    val specHash: String? = null,
)

@Serializable
data class LoginDeviceView(
    val id: String? = null,
    val name: String? = null,
    val kind: String? = null,
    val platform: String? = null,
)

@Serializable
data class SessionView(
    val username: String,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val role: String = "member",
    val capabilities: Capabilities = Capabilities(),
) {
    @Serializable
    data class Capabilities(
        val allowSubscribe: Boolean = false,
        val allowSearch: Boolean = false,
        val allowDirectDownload: Boolean = false,
    )
}

@Serializable
data class DeviceLoginView(
    val token: String,
    val device: LoginDeviceView? = null,
    val session: SessionView,
)

@Serializable
data class LibraryStats(
    val itemCount: Int = 0,
    val fileCount: Int = 0,
    val totalSizeBytes: Long = 0,
)

@Serializable
data class LibraryView(
    val id: Long,
    val name: String,
    val kind: String = "movie",
    val source: String = "tmdb",
    val excludeFromHome: Boolean = false,
    val viewerAccess: Boolean = true,
    /** 默认库（成员落点弹窗按它预选同类型的库） */
    val isDefault: Boolean = false,
    val rootPaths: List<String> = emptyList(),
    /** 服务端预算好的库存统计（iOS libraryStatsSummary 用的就是它） */
    val stats: LibraryStats = LibraryStats(),
)

@Serializable
data class UpNextItem(
    val mediaItemId: Long,
    val libraryId: Long,
    val kind: String = "movie",
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val posterAspect: Float = 2f / 3f,
    val backdropUrl: String? = null,
    val episodeStillUrl: String? = null,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val episodeTitle: String? = null,
    val unwatchedAheadCount: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val progressPercent: Int = 0,
    /** 卡片已经翻过篇：最近播放那一集看完了，这张卡指向它之后的下一集 */
    val advanced: Boolean = false,
    val lastPlayedAt: String? = null,
)

@Serializable
data class UpNextView(val items: List<UpNextItem> = emptyList())
