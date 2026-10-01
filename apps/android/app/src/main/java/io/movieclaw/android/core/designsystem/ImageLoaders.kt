package io.movieclaw.android.core.designsystem

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import io.movieclaw.android.core.network.GeneralChannel
import io.movieclaw.android.core.network.authInterceptor
import io.movieclaw.android.core.session.TokenVault
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath

/**
 * Coil 图片加载器:复用通用通道的鉴权拦截器(会员区图片需 Bearer),
 * 磁盘缓存 300MB(对齐 iOS Nuke 缓存配额)。
 */
@Singleton
class ImageLoaders @Inject constructor(
    @ApplicationContext context: Context,
    vault: TokenVault,
    @GeneralChannel baseClient: OkHttpClient,
) {
    /** 图片用的带鉴权客户端；ambient 取色也走它（服务端图片需要带 token） */
    val http: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(authInterceptor(vault))
        .build()

    val loader: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { http }))
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "image_cache").absolutePath.toPath())
                .maxSizeBytes(300L * 1024 * 1024)
                .build()
        }
        .build()
}
