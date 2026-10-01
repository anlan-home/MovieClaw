package io.movieclaw.android.core.network

import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.movieclaw.android.core.session.TokenVault
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool

object BuildInfo {
    const val APP_VERSION = "0.1.0"
    const val BUILD_NUMBER = 1
    val USER_AGENT: String =
        "MovieClaw-Android/$APP_VERSION (${Build.MODEL}; Android ${Build.VERSION.RELEASE}; build $BUILD_NUMBER)"
}

fun newDeviceClientInfo(installationId: String) = io.movieclaw.android.core.model.DeviceClientInfo(
    kind = "android",
    name = Build.MODEL ?: "Android",
    installationId = installationId,
    clientVersion = BuildInfo.APP_VERSION,
    platform = "Android ${Build.VERSION.RELEASE}",
)

/** 通用通道:页面与常规请求 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeneralChannel

/** 实时通道:SSE 与高频轮询,独立连接池防队头阻塞 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LiveChannel

/** 播放通道:会话建立/进度/心跳,保证 stop 不排在图片请求后面 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackChannel

private fun userAgentInterceptor() = Interceptor { chain ->
    chain.proceed(
        chain.request().newBuilder()
            .header("User-Agent", BuildInfo.USER_AGENT)
            .build()
    )
}

/** 为当前活跃服务器的请求附带 Bearer token(登录探测等匿名请求自动跳过) */
internal fun authInterceptor(vault: TokenVault) = Interceptor { chain ->
    val request = chain.request()
    val token = vault.activeToken()
    if (token != null && vault.activeOrigin?.let { request.url.toString().startsWith(it) } == true) {
        chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    } else {
        chain.proceed(request)
    }
}

private fun baseClient(vault: TokenVault): OkHttpClient.Builder =
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(userAgentInterceptor())
        .addInterceptor(loggingInterceptor())
        .addInterceptor(authInterceptor(vault))

/**
 * 非 2xx 一律打日志：URL、状态码、服务端返回体（前 400 字）。
 * 服务端的 VALIDATION_ERROR 会带 details，据此能直接定位是哪个参数不对。
 */
private fun loggingInterceptor(): Interceptor = Interceptor { chain ->
    val response = chain.proceed(chain.request())
    if (!response.isSuccessful) {
        val body = response.peekBody(400).string()
        android.util.Log.w("McHttp", "${response.code} ${chain.request().method} ${chain.request().url} -> $body")
    }
    response
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        namingStrategy = JsonNamingStrategy.SnakeCase
        // 关键:kotlinx 默认不序列化等于默认值的字段——DeviceClientInfo.kind("android")、
        // capability.isMobile/nativeHls 等会被静默丢弃,服务端 422(缺必填字段)或误判能力
        encodeDefaults = true
    }

    @Provides
    @Singleton
    @GeneralChannel
    fun generalClient(vault: TokenVault): OkHttpClient = baseClient(vault).build()

    @Provides
    @Singleton
    @LiveChannel
    fun liveClient(vault: TokenVault): OkHttpClient = baseClient(vault)
        .connectionPool(ConnectionPool(4, 65, TimeUnit.SECONDS))
        .build()

    @Provides
    @Singleton
    @PlaybackChannel
    fun playbackClient(vault: TokenVault): OkHttpClient = baseClient(vault)
        .connectionPool(ConnectionPool(4, 65, TimeUnit.SECONDS))
        .build()
}
