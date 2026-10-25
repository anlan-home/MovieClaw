package io.movieclaw.android.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 请求级故障转移的判定（docs/design/server-addresses.md §4.6）：
 * 只有「换地址可能有用」的硬错误才切换；超时与 DNS 失败属于软错误，照旧重试/累计。
 */
class HttpFailoverTest {

    @Test
    fun `硬错误才换地址`() {
        assertTrue(isHardNetworkFailure(ConnectException("refused")))
        assertTrue(isHardNetworkFailure(NoRouteToHostException("no route")))
        assertTrue(isHardNetworkFailure(SSLHandshakeException("tls")))
        assertTrue(isHardNetworkFailure(IOException("wrapped", ConnectException("refused"))))
    }

    @Test
    fun `超时和 DNS 失败不换地址`() {
        assertFalse(isHardNetworkFailure(SocketTimeoutException("timeout")))
        assertFalse(isHardNetworkFailure(UnknownHostException("dns")))
        assertFalse(isHardNetworkFailure(IOException("other")))
    }
}
