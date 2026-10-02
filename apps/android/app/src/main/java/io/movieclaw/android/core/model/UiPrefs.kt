package io.movieclaw.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 媒体库首页的一「行」偏好 —— 照抄服务端 `schemas.ui.HomeRowPref`
 * （iOS `API.HomeRowPref` 同源）。
 *
 * id 的四种形状：
 *  - 内置行 `up-next` / `favorites` / `libraries`：只存 `hidden`（收藏行多一个 `sort`）；
 *  - 默认库行 `lib:<library_id>`：每库一条，能藏、能改排序和名字，不能删；
 *  - 自加行 `row:<slug>`：必须且只能带 `library_id` 或 `collection_id` 之一。
 *
 * 除 `id` 外全部可空：空即默认（排序用预设、名字跟随推荐、不隐藏）。
 */
@Serializable
data class HomeRowPref(
    val id: String,
    val sort: String? = null,
    /** asc / desc；空 = 该档的自然方向。只在**反转**自然方向时才存 */
    val order: String? = null,
    /** 用户起的名字；空 = 跟随推荐 */
    val name: String? = null,
    /** 只显示没看过的（仅库行） */
    val unwatched: Boolean? = null,
    val hidden: Boolean? = null,
    val libraryId: Long? = null,
    val collectionId: Long? = null,
)

/** 媒体库首页的行清单（每个成员一份，超管走全局域）。空列表 = 出厂布局。 */
@Serializable
data class HomeUiPrefs(
    val rows: List<HomeRowPref> = emptyList(),
)

/**
 * 全站界面偏好（`GET/PUT /ui/preferences`）。
 *
 * 只有 `home` 是我们认识的形状；主题 / 侧栏 / 蒙版 / 主导航这些**原样带走**——
 * PUT 是整体覆盖，丢了别的页面的设定就是把人家的偏好擦了（iOS LibraryCustomizeView 同处理）。
 */
@Serializable
data class UiPreferencesSetting(
    val theme: String? = null,
    val themeDesktop: String? = null,
    val themeMobile: String? = null,
    val sidebar: JsonElement? = null,
    val scrim: JsonElement? = null,
    val nav: JsonElement? = null,
    val home: HomeUiPrefs = HomeUiPrefs(),
)

/**
 * 「首页行清单被改过」的信号：自定义页保存成功后 bump 一下，媒体库首页收到就重拉。
 * iOS 用进程内共享单例（`LibraryHomePrefs.shared`）达到同样效果——改完返回首页立刻生效。
 */
object HomePrefsBus {
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version
    fun bump() {
        _version.value += 1
    }
}
