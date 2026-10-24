package io.movieclaw.android.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private val Context.addressStore by preferencesDataStore(name = "mc_addresses")

/**
 * 内外双地址自动选连（docs/design/server-addresses.md §4；iOS `ServerRoutes` 的对应物）。
 *
 * 服务器的身份是登录地址（[SessionRepository] 不迁移）；这里只维护一张「路由表」：
 * 拉地址簿 → 按当前网络预筛 → 竞速探测（/health?nonce= 验实例号 + proof）→
 * 把选中的地址挂进 [ApiFactory.setRoutedBase]，之后的请求自动走新地址。
 *
 * 预筛与竞速口径照设计稿：蜂窝一律不试内网；记过的网络直接用记忆（后台核实）；
 * 内网 1.5s / 外网 5s 超时；外网晚 250ms 发（Happy Eyeballs）；先通先用。
 */
@Singleton
class ServerAddressBook @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiFactory: ApiFactory,
) {
    data class Entry(val url: String, val kind: String, val source: String)

    data class Selection(
        val origin: String,
        /** 当前选中的入口（绝对地址，含协议+主机+端口） */
        val url: String,
        val kind: String,
        val fingerprint: String,
        /** 是否本轮竞速验证过（false = 按记忆直接用，后台核实） */
        val verified: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    private val _book = MutableStateFlow<List<Entry>>(emptyList())
    val book: StateFlow<List<Entry>> = _book.asStateFlow()

    @Volatile private var selecting = false
    @Volatile private var callbackRegistered = false

    /** 供 RemoteImage / 取流拼地址用：返回该登录 origin 当前应使用的入口 base（无则原样返回） */
    fun baseFor(origin: String?): String? {
        val s = _selection.value ?: return null
        if (s.origin != origin) return null
        return s.url.trimEnd('/')
    }

    /** 冷启动 / 登录后 / 网络变化时调用：选一个地址并挂上 */
    fun selectAndApply(origin: String?) {
        if (origin.isNullOrBlank() || selecting) return
        selecting = true
        scope.launch {
            runCatching { select(origin) }
            selecting = false
        }
    }

    /** 设置 → 网络：手动重选（忽略记忆里的结论重新竞速） */
    fun reselect(origin: String?) {
        scope.launch {
            context.addressStore.edit { prefs ->
                val fp = fingerprint()
                prefs.remove(stringPreferencesKey(key(fp)))
            }
            selectAndApply(origin)
        }
    }

    private suspend fun select(origin: String) {
        val api = apiFactory.forOrigin(origin)
        val book = runCatching { api.serverAddresses().dataOrThrow() }.getOrNull() ?: return
        val entries = book.addresses.map { Entry(it.url.trimEnd('/'), it.kind, it.source) }
        _book.value = entries
        val candidates = (entries.map { it.url } + origin.trimEnd('/')).distinct()

        val fp = fingerprint()
        val cellular = isCellular()
        val remembered = withTimeoutOrNull(2_000) {
            context.addressStore.data.first()[stringPreferencesKey(key(fp))]
        }?.trimEnd('/')
        val rememberedValid = remembered?.let { r -> candidates.firstOrNull { it == r } }

        // 预筛（不发请求）：蜂窝只考虑外网与 https 的登录地址；其余按记忆优先
        val lanCandidates = entries.filter { it.kind == "lan" }
            .map { it.url }
            .filter { !cellular }
        val wanCandidates = entries.filter { it.kind == "wan" }.map { it.url }
        val loginUrl = origin.trimEnd('/')

        // 先不发请求就能定一个（预选）：记忆 > 同网段内网 > 登录地址（非纯蜂窝）> 第一个外网
        val inSubnet = lanCandidates.firstOrNull { inSameSubnet(it) }
        val prePicked = rememberedValid
            ?: inSubnet
            ?: loginUrl.takeIf { !cellular || loginUrl.startsWith("https") }
            ?: wanCandidates.firstOrNull()

        prePicked?.let { apply(origin, it, kindOf(entries, it), verified = false) }

        // 竞速核实：内网全发（1.5s），外网晚 250ms（5s）；先过身份校验的先用
        val nonce = newNonce()
        val winner = race(
            lan = lanCandidates.ifEmpty { listOfNotNull(loginUrl.takeIf { isPrivateLiteral(it) }) },
            wan = (wanCandidates + listOfNotNull(loginUrl.takeIf { wanCandidates.isEmpty() }))
                .filter { it !in lanCandidates }
                .distinct(),
            nonce = nonce,
            instanceId = book.instanceId,
            hostKey = book.hostKey,
        )
        if (winner != null && winner.url != _selection.value?.url) {
            apply(origin, winner.url, kindOf(entries, winner.url), verified = true)
            context.addressStore.edit { prefs -> prefs[stringPreferencesKey(key(fp))] = winner.url }
        }
        registerNetworkCallback()
    }

    private fun apply(origin: String, url: String, kind: String, verified: Boolean) {
        val apiBase = url + "/api/v1"
        if (url.trimEnd('/') == origin.trimEnd('/')) {
            apiFactory.setRoutedBase(origin, null)
        } else {
            apiFactory.setRoutedBase(origin, apiBase)
        }
        // 图片等按 origin 拼 URL 的地方跟着换入口
        ServerRoutes.provider = { o -> if (o == origin) url.trimEnd('/') else null }
        _selection.value = Selection(origin, url, kind, fingerprint(), verified)
    }

    private fun kindOf(entries: List<Entry>, url: String): String =
        entries.firstOrNull { it.url == url }?.kind
            ?: if (isPrivateLiteral(url)) "lan" else "wan"

    /** 竞速：返回第一个通过身份校验的地址；内网在超时前后来也算升级成功 */
    private suspend fun race(
        lan: List<String>,
        wan: List<String>,
        nonce: String,
        instanceId: String,
        hostKey: String,
    ): Entry? = kotlinx.coroutines.coroutineScope {
        val result = MutableStateFlow<Entry?>(null)
        fun probe(url: String, timeoutMs: Long) = launch {
            val ok = verifyHealth(url, nonce, instanceId, hostKey, timeoutMs)
            if (ok && result.value == null) result.value = Entry(url, "", "")
        }
        lan.forEach { probe(it, 1_500) }
        val wanJobs = wan.map { url ->
            launch {
                kotlinx.coroutines.delay(250)
                probe(url, 5_000)
            }
        }
        suspendCancellableCoroutine { cont ->
            val job = launch {
                while (result.value == null) {
                    if (!cont.isActive) return@launch
                    kotlinx.coroutines.delay(100)
                }
                cont.resume(result.value)
            }
            cont.invokeOnCancellation { job.cancel() }
        }.also {
            wanJobs.forEach { it.cancel() }
        }
    }

    /** GET /health?nonce= → 校验 spec 头、instance_id、proof（host_key 在时） */
    private fun verifyHealth(url: String, nonce: String, instanceId: String, hostKey: String, timeoutMs: Long): Boolean {
        val body = runCatching {
            probeClient.newCall(
                Request.Builder()
                    .url(url.trimEnd('/') + "/api/v1/health?nonce=" + nonce)
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 401 && resp.code != 403) return false
                resp.body?.string()
            }
        }.getOrNull() ?: return false
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return false
        // 无信封结构：instance_id / proof 在顶层；spec hash 在响应头（body 里没有）
        val id = obj["instance_id"]?.jsonPrimitive?.contentOrNull ?: return false
        if (id != instanceId) return false
        val proof = obj["proof"]?.jsonPrimitive?.contentOrNull
        if (hostKey.isNotEmpty()) {
            if (proof.isNullOrEmpty()) return false
            if (!verifyProof(hostKey, nonce, id, proof)) return false
        }
        return true
    }

    /** Ed25519：proof = sign("movieclaw-health\n" + instance_id + "\n" + nonce)，base64url */
    private fun verifyProof(hostKeyB64: String, nonce: String, instanceId: String, proofB64: String): Boolean = runCatching {
        val spec = net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.getByName(
            net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.ED_25519,
        )
        val publicKey = net.i2p.crypto.eddsa.EdDSAPublicKey(
            net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec(Base64.getUrlDecoder().decode(hostKeyB64), spec),
        )
        val verifier = java.security.Signature.getInstance("EdDSA")
        verifier.initVerify(publicKey)
        verifier.update("movieclaw-health\n$instanceId\n$nonce".toByteArray(Charsets.UTF_8))
        verifier.verify(Base64.getUrlDecoder().decode(proofB64))
    }.getOrDefault(false)

    private fun newNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ── 网络指纹与回调 ──

    private fun key(fingerprint: String) = "chosen_" + fingerprint.hashCode().toString()

    /** 网络指纹 = 传输类型 + IPv4 /24（不读 Wi-Fi 名，不需要定位权限） */
    internal fun fingerprint(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val network = cm.activeNetwork ?: return "unknown"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            else -> "other"
        }
        val lp = cm.getLinkProperties(network)
        val subnet = lp?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }
            ?.firstOrNull()
            ?.hostAddress
            ?.substringBeforeLast('.', "0.0.0.0")
            ?.let { "$it.0/24" }
            ?: "nosubnet"
        return "$transport|$subnet"
    }

    private fun isCellular(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun inSameSubnet(url: String): Boolean {
        val fpSubnet = fingerprint().substringAfter('|').substringBefore("/")
        if (fpSubnet == "nosubnet") return false
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return host.startsWith(fpSubnet.removeSuffix(".0/24") + ".")
    }

    private fun isPrivateLiteral(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
    }

    /** 网络变化 → 重新选路（事件驱动，不轮询） */
    private fun registerNetworkCallback() {
        if (callbackRegistered) return
        callbackRegistered = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = android.net.NetworkRequest.Builder().build()
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var lastFingerprint = fingerprint()
                override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                    val fp = fingerprint()
                    if (fp != lastFingerprint) {
                        lastFingerprint = fp
                        _selection.value?.let { selectAndApply(it.origin) }
                    }
                }
            })
        }
    }
}
