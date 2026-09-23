package org.signal.network.util

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.tls.OkHostnameVerifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Collections
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Tellomi（#1078）：[HttpsProxySocketFactory] 让 OkHttp 的 CONNECT 走在「先和代理做的那层 TLS」里。
 *
 * 本机起一个假的 TLS 代理（现签一张 `contentproxy.test` 的自签证书）：它做完握手、记下 SNI 和收到的第一行，
 * 然后像香港那台的 tinyproxy 对白名单外的目标那样回 403。判据全部落在**代理那一侧看到了什么**上，
 * 而不是客户端自己说了什么。
 *
 * 为什么要有这层：明文 CONNECT 把目标主机名（api.giphy.com）交给了路上的每一跳，从大陆出发会被 reset。
 * 2026-09-23 真网络对照（OkHttp 5.3.2，大陆直连 contentproxy.tellomi.app）：明文 → `Connection reset`；
 * 外层 TLS → CONNECT 200、Giphy 401。
 */
class HttpsProxySocketFactoryTest {

  private val proxyHost = "contentproxy.test"
  private val tls = TestTls(proxyHost)
  private val closeables: MutableList<AutoCloseable> = Collections.synchronizedList(mutableListOf())

  @After
  fun tearDown() {
    closeables.forEach { runCatching { it.close() } }
  }

  @Test
  fun `CONNECT travels inside TLS with the proxy host as SNI`() {
    val proxy = FakeTlsProxy(tls.serverContext).also { closeables += it }

    val error = call(proxy.port, HttpsProxySocketFactory(proxyHost, tls.clientSocketFactory, OkHostnameVerifier))

    val seen = proxy.seen.get(5, TimeUnit.SECONDS)
    assertThat(seen.handshakeError).isNull()
    assertThat(seen.sni).isEqualTo(listOf(proxyHost))
    assertThat(seen.requestLine).isEqualTo("CONNECT api.giphy.com:443 HTTP/1.1")
    // 代理在 TLS 里回的 403 被 OkHttp 读到并报成 CONNECT 失败：两个方向都走通了这条加密通道。
    assertThat(error?.message).isNotNull().contains("CONNECT: 403")
  }

  /** 红控制：同一个 TLS 代理，不挂工厂（= 上游的配法），代理那边一个 CONNECT 都收不到。 */
  @Test
  fun `without the factory the proxy never sees a CONNECT`() {
    val proxy = FakeTlsProxy(tls.serverContext).also { closeables += it }

    val error = call(proxy.port, socketFactory = null)

    val seen = proxy.seen.get(5, TimeUnit.SECONDS)
    assertThat(error).isNotNull()
    assertThat(seen.requestLine).isNull()
    // 明文的「CONNECT …」被当成一个坏掉的 ClientHello，握手在代理那一侧就失败了。
    assertThat(seen.handshakeError).isNotNull()
  }

  @Test
  fun `a certificate for another host is rejected before anything is sent`() {
    val proxy = FakeTlsProxy(tls.serverContext).also { closeables += it }

    // 证书是 contentproxy.test 的（而且受信任），但工厂以为自己在连 other.test。
    val error = call(proxy.port, HttpsProxySocketFactory("other.test", tls.clientSocketFactory, OkHostnameVerifier))

    assertThat(error).isNotNull().isInstanceOf(SSLPeerUnverifiedException::class)
    assertThat(proxy.seen.get(5, TimeUnit.SECONDS).requestLine).isNull()
  }

  @Test
  fun `a proxy that never answers the handshake fails within the connect timeout`() {
    val silent = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { closeables += it }
    // 只接 TCP、从不回 ServerHello：握手会一直等下去，除非它被 connectTimeout 限住。
    Thread {
      runCatching {
        while (true) {
          val socket: Socket = silent.accept()
          closeables += socket
        }
      }
    }.apply { isDaemon = true }.start()

    val start = System.nanoTime()
    // 读超时故意放到 30 s：握手要是没被 connectTimeout 限住，就会拖到读超时才失败，两边差一个数量级。
    val error = call(silent.localPort, HttpsProxySocketFactory(proxyHost, tls.clientSocketFactory, OkHostnameVerifier), connectTimeoutMs = 1_000, readTimeoutMs = 30_000)
    val elapsedMs = (System.nanoTime() - start) / 1_000_000

    assertThat(error).isNotNull()
    assertThat(elapsedMs).isLessThan(5_000L)
  }

  /** 发一个 giphy 请求，返回失败原因（这几个场景都不会成功）。 */
  private fun call(proxyPort: Int, socketFactory: SocketFactory?, connectTimeoutMs: Long = 5_000, readTimeoutMs: Long = 5_000): Throwable? {
    val client = OkHttpClient.Builder()
      .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxyHost, proxyPort)))
      // 代理主机名由 client 自己的 Dns 解析成本机——同时钉住「连的是 OkHttp 解析好的地址」这一条。
      .dns(
        object : Dns {
          override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
        }
      )
      .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
      .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
      .retryOnConnectionFailure(false)
      .apply { if (socketFactory != null) socketFactory(socketFactory) }
      .build()

    return runCatching {
      client.newCall(Request.Builder().url("https://api.giphy.com/v1/gifs/trending").build()).execute().close()
    }.exceptionOrNull()
  }

  /** 只接一个连接：做 TLS 握手，记下 SNI 和第一行，读完请求头，回 403。 */
  private class FakeTlsProxy(context: SSLContext) : AutoCloseable {
    data class Seen(val sni: List<String>, val requestLine: String?, val handshakeError: Throwable?)

    private val server = context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket

    val port: Int
      get() = server.localPort

    val seen = CompletableFuture<Seen>()

    init {
      Thread {
        try {
          (server.accept() as SSLSocket).use { socket ->
            socket.soTimeout = 5_000
            try {
              socket.startHandshake()
            } catch (e: IOException) {
              seen.complete(Seen(emptyList(), null, e))
              return@use
            }

            val sni = (socket.session as ExtendedSSLSession).requestedServerNames.map { (it as SNIHostName).asciiName }
            val reader = socket.inputStream.bufferedReader(Charsets.ISO_8859_1)
            val requestLine: String? = reader.readLine()
            // 读完整个请求头再回话：没读完就关连接，客户端可能先收到 RST 而不是 403。
            var line = requestLine
            while (!line.isNullOrEmpty()) {
              line = reader.readLine()
            }
            if (requestLine != null) {
              socket.outputStream.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
              socket.outputStream.flush()
            }
            seen.complete(Seen(sni, requestLine, null))
          }
        } catch (e: Throwable) {
          seen.complete(Seen(emptyList(), null, e))
        }
      }.apply { isDaemon = true }.start()
    }

    override fun close() {
      server.close()
    }
  }

  /** 现签一张只对 [host] 有效的自签证书：服务端拿它握手，客户端只信任它。 */
  private class TestTls(host: String) {
    val serverContext: SSLContext
    val clientSocketFactory: SSLSocketFactory

    init {
      val password = "test".toCharArray()
      val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
      val name = X500Name("CN=$host")
      val now = System.currentTimeMillis()
      val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 60_000), Date(now + 3_600_000), name, keyPair.public)
        .addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, host)))
        .build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
      val certificate = JcaX509CertificateConverter().getCertificate(holder)

      val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setKeyEntry("proxy", keyPair.private, password, arrayOf(certificate))
      }
      val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }.keyManagers
      serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }

      val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setCertificateEntry("proxy", certificate)
      }
      val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }.trustManagers
      clientSocketFactory = SSLContext.getInstance("TLS").apply { init(null, trustManagers, null) }.socketFactory
    }
  }
}
