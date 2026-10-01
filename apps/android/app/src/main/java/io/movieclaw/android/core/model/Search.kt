package io.movieclaw.android.core.model

import kotlinx.serialization.Serializable

/* ---------------- 站点(种子)搜索 ---------------- */

/** 一级分类(服务端 TorrentCategory 枚举) */
enum class TorrentCategory(val id: String, val label: String) {
    MOVIE("movie", "电影"),
    TV("tv", "剧集"),
    DOCUMENTARY("documentary", "纪录片"),
    ANIME("anime", "动画"),
    MUSIC("music", "音乐"),
    GAME("game", "游戏"),
    AV("av", "其他视频"),
    OTHER("other", "其他"),
    ;

    companion object {
        fun of(id: String?): TorrentCategory? = entries.firstOrNull { it.id == id }
    }
}

/** 搜索结果里的一条种子(字段对齐 movieclaw_tracker.models.TorrentListItem + TorrentHit) */
@Serializable
data class TorrentHit(
    val torrentId: String = "",
    val title: String = "",
    val subtitle: String = "",
    val category: String? = null,
    val size: String? = null,
    val sizeBytes: Long = 0,
    val seeders: Int = 0,
    val leechers: Int = 0,
    val snatched: Int = 0,
    val uploadTime: String? = null,
    val uploader: String = "",
    val posterUrl: String? = null,
    val free: Boolean = false,
    val downloadVolumeFactor: Float = 1f,
    val uploadVolumeFactor: Float = 1f,
    val hitAndRun: Boolean? = null,
    val detailUrl: String? = null,
    val downloadUrl: String? = null,
    val siteId: String = "",
    val siteName: String = "",
)

/* ---------------- 站点搜索 SSE 事件载荷 ---------------- */

@Serializable
data class SearchStreamSite(val siteId: String = "", val siteName: String = "")

@Serializable
data class SearchStreamStart(
    val keyword: String = "",
    val label: String? = null,
    val categories: List<String> = emptyList(),
    val page: Int = 1,
    val sites: List<SearchStreamSite> = emptyList(),
)

@Serializable
data class SiteStreamResult(
    val siteId: String = "",
    val siteName: String = "",
    val count: Int = 0,
    val elapsedMs: Int = 0,
    val items: List<TorrentHit> = emptyList(),
)

@Serializable
data class SiteStreamError(
    val siteId: String = "",
    val siteName: String = "",
    val error: String = "",
    val elapsedMs: Int = 0,
)

@Serializable
data class SiteSearchStatus(
    val siteId: String = "",
    val siteName: String = "",
    val count: Int = 0,
    val error: String? = null,
)

@Serializable
data class SearchStreamDone(
    val total: Int = 0,
    val elapsedMs: Int = 0,
    val sites: List<SiteSearchStatus> = emptyList(),
)

/* ---------------- 标题搜索(TMDB/豆瓣) ---------------- */

/** 标题搜索结果条目(字段对齐服务端 schemas/discover.py DiscoveredTitleView) */
@Serializable
data class DiscoveredTitle(
    /** 服务端给出的稳定引用(tmdb:movie:123 / douban:456),订阅与详情原样消费 */
    val titleRef: String = "",
    val provider: String = "",
    val externalId: String = "",
    val mediaType: String? = null,
    val title: String = "",
    val originalTitle: String = "",
    val releaseYear: Int? = null,
    val providerRating: Float = 0f,
    val genres: List<String> = emptyList(),
    val extentLabel: String = "",
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    /** 非空 = 本库已入库(海报显示「已入库」缎带,iOS 同款语义) */
    val libraryStatus: MediaLibraryStatus? = null,
)

@Serializable
data class MediaLibraryStatus(
    val mediaItemId: Long = 0,
    val libraryCount: Int = 0,
    val fileCount: Int = 0,
)

@Serializable
data class TitleSearchView(
    val query: String = "",
    val titles: List<DiscoveredTitle> = emptyList(),
    val historyId: Long? = null,
)

@Serializable
data class TitleSearchRequest(
    val query: String,
    val provider: String = "all",
    val saveHistory: Boolean = true,
)

/* ---------------- 库内搜索 ---------------- */

@Serializable
data class LibrarySearchGroup(
    val libraryId: Long,
    val libraryName: String = "",
    val kind: String = "movie",
    val items: List<LibraryItemView> = emptyList(),
)

/* ---------------- 搜索历史 ---------------- */

@Serializable
data class SearchHistoryItem(
    val id: Long,
    val keyword: String = "",
    val vertical: String = "torrents",
    val label: String? = null,
    val categories: List<String> = emptyList(),
    val siteIds: List<String> = emptyList(),
    val searchCount: Int = 0,
    val lastSearchedAt: String? = null,
    val hasSnapshot: Boolean = false,
)

/* ---------------- 下载提交 ---------------- */

@Serializable
data class DownloadSubmitRequest(
    val siteId: String,
    val downloadUrl: String,
    val torrentId: String? = null,
    val libraryId: Long? = null,
    val title: String? = null,
    val year: Int? = null,
    val subtitle: String? = null,
    val autoRoute: Boolean = true,
    val mediaKind: String? = null,
    val tmdbId: Int? = null,
    val category: String? = null,
)

/* ---------------- 通知中心 ---------------- */

@Serializable
data class Notice(
    val id: Long,
    val severity: String = "info",
    val source: String = "",
    val title: String = "",
    val message: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
