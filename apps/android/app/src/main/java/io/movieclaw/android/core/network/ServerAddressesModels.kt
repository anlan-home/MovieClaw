package io.movieclaw.android.core.network

import kotlinx.serialization.Serializable

/** GET /server/addresses：这台服务器的访问地址簿（内网 / 外网） */
@Serializable
data class ServerAddressesView(
    @kotlinx.serialization.SerialName("instance_id") val instanceId: String = "",
    @kotlinx.serialization.SerialName("host_key") val hostKey: String = "",
    val addresses: List<ServerAddressItem> = emptyList(),
    @kotlinx.serialization.SerialName("lan_subnets") val lanSubnets: List<String> = emptyList(),
)

@Serializable
data class ServerAddressItem(
    val url: String,
    /** lan = 内网（在家用），wan = 外网（出门用） */
    val kind: String,
    /** learned / configured / manual */
    val source: String,
    @kotlinx.serialization.SerialName("last_seen") val lastSeen: String? = null,
)

/**
 * 图片/取流等「按 origin 拼 URL」的地方统一经它换到当前选中的入口。
 * 由 [ServerAddressBook] 在选路后注入 provider（designsystem 不反向依赖会话层）。
 */
object ServerRoutes {
    @Volatile var provider: ((origin: String?) -> String?)? = null

    fun baseFor(origin: String?): String? = provider?.invoke(origin) ?: origin
}
