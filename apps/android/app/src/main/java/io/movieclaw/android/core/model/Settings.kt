package io.movieclaw.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(val nickname: String)

@Serializable
data class ChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String,
    val signOutPaired: Boolean = false,
)

/** 「我的设备」列表里的一台设备(登录设备 ld-N / Jellyfin 播放器 jf-N) */
@Serializable
data class LoginDevice(
    val id: String,
    val kind: String = "",
    val kindLabel: String = "",
    val family: String = "",
    val name: String = "",
    val scope: String = "full",
    val platform: String? = null,
    val clientVersion: String? = null,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val lastSeenIp: String? = null,
    val current: Boolean = false,
    val connected: Boolean = false,
    val renamable: Boolean = true,
    val ownerId: Int = 0,
    val ownerUsername: String = "",
    val ownerNickname: String = "",
)

@Serializable
data class DeviceRenameRequest(val name: String)

/** 播放策略(意愿开关 + 实测的硬件能力) */
@Serializable
data class PlaybackPolicy(
    val softwareTranscodeEnabled: Boolean = false,
    val trickplayEnabled: Boolean = true,
    val transcodeCacheEnabled: Boolean = true,
    val hardwareAvailable: Boolean = false,
    val hwBackends: List<String> = emptyList(),
)

@Serializable
data class PlaybackPolicyPatchFull(
    val softwareTranscodeEnabled: Boolean? = null,
    val trickplayEnabled: Boolean? = null,
    val transcodeCacheEnabled: Boolean? = null,
)

@Serializable
data class MemberView(
    val id: Int,
    val username: String = "",
    val nickname: String = "",
    val avatarUrl: String? = null,
    val status: String = "active",
    val lastLoginAt: String? = null,
    val allowSubscribe: Boolean = false,
    val allowSearch: Boolean = false,
    val allowDirectDownload: Boolean = false,
    val allLibraries: Boolean = true,
    val libraryIds: List<Int> = emptyList(),
    val deviceCount: Int = 0,
)

/* ---------------- 家庭成员管理(P2 设置区) ---------------- */

@Serializable
data class MemberCreateRequest(
    val username: String,
    val password: String,
    val nickname: String = "",
)

@Serializable
data class MemberUpdateRequest(
    val nickname: String? = null,
    val allowSubscribe: Boolean? = null,
    val allowSearch: Boolean? = null,
    val allowDirectDownload: Boolean? = null,
    val allLibraries: Boolean? = null,
    val libraryIds: List<Int>? = null,
)

@Serializable
data class MemberStatusRequest(val enabled: Boolean)

/** 重置密码的响应:明文只此一次 */
@Serializable
data class MemberPasswordReset(
    val id: Int = 0,
    val username: String = "",
    val password: String = "",
)
