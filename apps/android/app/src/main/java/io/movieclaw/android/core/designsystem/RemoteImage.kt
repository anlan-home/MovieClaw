package io.movieclaw.android.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

/** 服务器图片:相对路径拼 origin,经带鉴权头的 ImageLoader 加载;空值回落渐变占位 */
@Composable
fun RemoteImage(
    url: String?,
    origin: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    /**
     * 没有地址或**加载失败**时的回落内容。null = 渐变占位。
     *
     * 失败也要回落是有实际场景的：媒体库封面 `GET /libraries/{id}/cover` 在
     * 「该库还没有海报资产」时返回 404（服务端原文），不接一下就是一块空黑。
     */
    fallback: (@Composable () -> Unit)? = null,
) {
    if (url.isNullOrEmpty() || (origin == null && !url.startsWith("http"))) {
        if (fallback != null) fallback() else PosterPlaceholder(seed = url ?: "movieclaw", modifier = modifier)
        return
    }
    // 绝对地址（TMDB 等外部 CDN）直接用；相对地址拼服务端来源，并补 `/api/v1` 前缀——
    // 服务端给的库内图片是站点根路径（/images/assets/{id}/poster.jpg），
    // 缺前缀会 404（返回前端 HTML），媒体库封面全空（实机日志抓到过）。
    val resolved = remember(url, origin) {
        val base = origin?.trimEnd('/')
        when {
            url.startsWith("http") -> url
            base == null -> url
            url.startsWith("/api/") -> base + url
            url.startsWith("/") -> "$base/api/v1$url"
            else -> "$base/$url"
        }
    }
    var failed by remember(resolved) { mutableStateOf(false) }
    if (failed) {
        if (fallback != null) fallback() else PosterPlaceholder(seed = url, modifier = modifier)
        return
    }
    val context = LocalContext.current.applicationContext
    val loader = remember(context) { (context as io.movieclaw.android.MovieClawApp).imageLoaders.loader }
    AsyncImage(
        model = resolved,
        imageLoader = loader,
        contentDescription = contentDescription,
        contentScale = contentScale,
        onState = { state -> if (state is AsyncImagePainter.State.Error) failed = true },
        modifier = modifier,
    )
}
