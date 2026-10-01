package io.movieclaw.android.core.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.qualityStore by preferencesDataStore(name = "mc_quality")

/**
 * 画质记忆(iOS QualityMemory):按「网络环境 × 片名」记住用户选的画质上限。
 * 只记**限制性**的选择(上限低于源分辨率)以及等于「自动」的 0。
 * 上限 300 条,按时间 LRU 淘汰。
 */
@Singleton
class QualityMemory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    @Serializable
    private data class Entry(val key: String, val height: Int, val at: Long)

    /** 返回记住的画质上限;null = 没记过,0 = 记住的是「自动」 */
    suspend fun remembered(mediaItemId: Long, network: PlaybackNetwork): Int? {
        val key = key(network, mediaItemId)
        return load().firstOrNull { it.key == key }?.height
    }

    suspend fun remember(mediaItemId: Long, network: PlaybackNetwork, capHeight: Int?) {
        val key = key(network, mediaItemId)
        val entries = load().filterNot { it.key == key } +
            Entry(key, capHeight ?: 0, System.currentTimeMillis())
        val trimmed = entries.sortedByDescending { it.at }.take(MAX_ENTRIES)
        context.qualityStore.edit { it[KEY_MEMORY] = json.encodeToString(trimmed) }
    }

    suspend fun forget(mediaItemId: Long, network: PlaybackNetwork) {
        val key = key(network, mediaItemId)
        val entries = load().filterNot { it.key == key }
        context.qualityStore.edit { it[KEY_MEMORY] = json.encodeToString(entries) }
    }

    private suspend fun load(): List<Entry> {
        val raw = context.qualityStore.data.first()[KEY_MEMORY] ?: return emptyList()
        return runCatching { json.decodeFromString<List<Entry>>(raw) }.getOrDefault(emptyList())
    }

    private fun key(network: PlaybackNetwork, mediaItemId: Long) = "${network.id}:$mediaItemId"

    private companion object {
        val KEY_MEMORY = stringPreferencesKey("quality_memory")
        const val MAX_ENTRIES = 300
    }
}

/** 网络环境:服务器是私网地址 → 局域网;公网域名/IP → 外网(iOS 另有同网段判定,这里按私网近似) */
enum class PlaybackNetwork(val id: String, val label: String) {
    HOME("home", "局域网"),
    AWAY("away", "外网"),
    UNKNOWN("unknown", ""),
    ;

    companion object {
        fun of(host: String?): PlaybackNetwork {
            if (host.isNullOrEmpty()) return UNKNOWN
            return runCatching {
                val address = InetAddress.getByName(host)
                if (address.isSiteLocalAddress || address.isLoopbackAddress) HOME else AWAY
            }.getOrDefault(AWAY)
        }
    }
}
