package io.movieclaw.android.core.network

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import io.movieclaw.android.core.api.McApi

/** 按服务器缓存 Retrofit 实例(多服务器各一个 baseUrl) */
@Singleton
class ApiFactory @Inject constructor(
    private val json: Json,
    @GeneralChannel private val client: OkHttpClient,
) {
    private val cache = ConcurrentHashMap<String, McApi>()

    /** 探活发现的实际 API 基址(反向代理子路径/重定向后的最终形态) */
    private val apiBaseOverrides = ConcurrentHashMap<String, String>()

    fun registerApiBase(origin: String, apiBase: String) {
        apiBaseOverrides[origin] = apiBase.trimEnd('/')
    }

    fun apiBaseOf(origin: String): String? = apiBaseOverrides[origin]

    fun forOrigin(origin: String): McApi {
        val normalized = ServerAddress.normalize(origin)
            ?: throw ApiException("INVALID_ORIGIN", "无效的服务器地址:$origin")
        return cache.computeIfAbsent(normalized.origin) {
            val base = apiBaseOverrides[normalized.origin] ?: normalized.apiBase
            Retrofit.Builder()
                .baseUrl(base + "/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(McApi::class.java)
        }
    }
}
