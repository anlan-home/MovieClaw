package io.movieclaw.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class MediaBrief(
    val mediaItemId: Long = 0,
    val kind: String = "movie",
    val tmdbId: Int? = null,
    val title: String = "",
    val originalTitle: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val status: String? = null,
)

@Serializable
data class SeasonOverview(
    val seasonNumber: Int = 0,
    val name: String? = null,
    val airDate: String? = null,
    val episodeCount: Int? = null,
    val airedCount: Int = 0,
    val ownedCount: Int = 0,
)

/** POST /subscriptions/title-preview 的结果 */
@Serializable
data class PrepareView(
    val status: String = "not_found",
    val media: MediaBrief? = null,
    val seasons: List<SeasonOverview> = emptyList(),
    val existingSubscriptionId: Long? = null,
    val movieOwned: Boolean = false,
    val suggestedSeasons: List<Int> = emptyList(),
)

/**
 * `POST /subscriptions` 的返回（**不是** SubscriptionView）。
 * 声明错了会报 `Field 'id' is required for ... SubscriptionView ... at path: $.data`——
 * 服务端这一层的 `data` 是 `{subscription: {...}, download_routing: {...}}`，没有 id。
 */
@Serializable
data class SubscriptionCreateView(
    val subscription: SubscriptionView,
    /** 管理员可见的下载与入库路由预检；成员调用时为空 */
    val downloadRouting: DispatchPreviewView? = null,
)

/**
 * 投递路由预检（`GET /subscriptions/download-routing-preview`，管理员）：
 * 与真实投递同源判定，把"下载完成后能不能进库"在订阅那一刻就回答掉。
 * 字段与服务端 `schemas/subscription.py: DispatchPreviewView` 一一对应。
 */
@Serializable
data class DispatchPreviewView(
    /** watch = 监听导入目录 / inplace = 直接下载进库 / downloader_default = 下载器默认目录 */
    val mode: String = "",
    val path: String? = null,
    /** 条目目录的完整路径预览（按生效的命名模板渲染，前端不要自己拼） */
    val entryDir: String? = null,
    val stagingPath: String? = null,
    /** 解析出的目标库（前端预选用） */
    val libraryId: Long? = null,
    val libraryName: String? = null,
    val downloaderName: String? = null,
    val routeMatched: Boolean? = null,
    /** 路由理由（中文整句，弹窗直接展示：自动选库：…） */
    val routeReason: String? = null,
    val ruleSetId: Long? = null,
    val ruleSetName: String? = null,
    val ruleSetMatched: Boolean? = null,
    val ruleSetReason: String? = null,
    /** 按当前配置投递能否顺利入库 */
    val ok: Boolean = false,
    /** 不 ok 时的中文指引 */
    val warning: String? = null,
)

@Serializable
data class SubscriptionCreateRequest(
    val titleRef: String,
    val sourceTitleRef: String? = null,
    val selectedSeasons: List<Int> = emptyList(),
    val followFuture: Boolean = false,
    val ruleSetId: Long? = null,
    val libraryId: Long? = null,
)

@Serializable
data class ProgressView(
    val total: Int = 0,
    val wanted: Int = 0,
    val grabbed: Int = 0,
    val downloaded: Int = 0,
    val imported: Int = 0,
    val upgrading: Int = 0,
)

/**
 * 一轮洗版的体检报告（`POST /subscriptions/{id}/upgrade-runs`）。
 * `summary` 是**服务端给的中文摘要句**，前端直接展示——不自己拼。
 */
@Serializable
data class UpgradeRunView(
    val targetLabel: String = "",
    val ruleSetId: Long? = null,
    val summary: String = "",
    val counts: Map<String, Int> = emptyMap(),
)

/** `GET /rule-sets`：过滤规则组（订阅换绑用） */
@Serializable
data class RuleSetView(
    val id: Long,
    val name: String = "",
    val isDefault: Boolean = false,
    val referenceCount: Int = 0,
    /** 规则内容（摘要由 `specSummary` 渲染，口径同网页） */
    val spec: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class SubscriptionView(
    val id: Long,
    val media: MediaBrief = MediaBrief(),
    val status: String = "",
    val selectedSeasons: List<Int> = emptyList(),
    val followFuture: Boolean = false,
    val ruleSetId: Long? = null,
    val libraryId: Long? = null,
    val progress: ProgressView = ProgressView(),
    val seasonCollection: List<SeasonOverview> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** 详情接口附带：追踪工单明细（列表接口为空数组） */
    val wanted: List<WantedView> = emptyList(),
)

@Serializable
data class FollowFutureRequest(val followFuture: Boolean)

/** POST /subscriptions/title-preview 请求 */
@Serializable
data class TitlePreviewRequest(val titleRef: String)

/** 今日/近期入库条目 */
@Serializable
data class TodayArrival(
    val subscriptionId: Long = 0,
    val mediaTitle: String = "",
    val mediaKind: String = "tv",
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val status: String = "wanted",
    val airDate: String? = null,
    val expectedDay: String? = null,
    val daysAhead: Int = 0,
    val estimatedReleaseToImportMinutes: Int? = null,
)

@Serializable
data class PipelineHealth(
    val status: String = "ok",
    val errorCount: Int = 0,
    val warnCount: Int = 0,
    val downloaderOk: Boolean = false,
    val sitesConfigured: Boolean = false,
    val downloadersConfigured: Boolean = false,
)

/** GET /subscriptions/today-arrivals —— 带媒体美术资源，供订阅首页英雄/日程使用 */
@Serializable
data class TodayArrivalFull(
    val subscriptionId: Long = 0,
    val mediaTitle: String = "",
    val mediaKind: String = "tv",
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val status: String = "wanted",
    val airDate: String? = null,
    val expectedDay: String? = null,
    val daysAhead: Int = 0,
    val grabbedAt: String? = null,
    val downloadedAt: String? = null,
    val estimatedReleaseToImportMinutes: Int? = null,
    val estimatedDownloadToImportMinutes: Int? = null,
)

/** GET /subscriptions/recent-arrivals —— 订阅首页「刚刚入库」卡片 */
@Serializable
data class RecentArrivalView(
    val subscriptionId: Long = 0,
    val media: MediaBrief = MediaBrief(),
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val episodeName: String? = null,
    val stillUrl: String? = null,
    val units: List<RecentArrivalUnit> = emptyList(),
    val progressPercent: Int? = null,
)

@Serializable
data class RecentArrivalUnit(
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
)

/** 订阅详情的追踪工单（GET /subscriptions/{id} wanted[]） */
@Serializable
data class WantedView(
    val id: Long = 0,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val status: String = "wanted",
    val airDate: String? = null,
    val grabbedAt: String? = null,
    val downloadedAt: String? = null,
    val importedAt: String? = null,
    val lastSearchAt: String? = null,
    val lastRejectReason: String? = null,
)

/** GET /subscriptions/{id}/activities —— 排查记录 */
@Serializable
data class SubActivityView(
    val id: Long = 0,
    val type: String = "",
    val message: String = "",
    val createdAt: String? = null,
)

/** PATCH /subscriptions/{id}/tracking-state */
@Serializable
data class TrackingStateRequest(val state: String)
