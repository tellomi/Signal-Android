/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.network.util

import okhttp3.internal.tls.OkHostnameVerifier
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Tellomi（#1078）：HTTPS 代理用的 socketFactory——和代理之间**先做 TLS，再在里面发 CONNECT**。
 * 用在 app 的内容代理上（`ContentProxySelector` 那几个 OkHttpClient）。
 *
 * OkHttp 自己不支持 HTTPS 代理：对 `Proxy.Type.HTTP` 它总是开一条明文 TCP、直接在上面写 CONNECT。
 * 这里在 [Socket.connect] 那一刻把连接升级成 TLS（SNI 与证书都按 [proxyHost]），于是 OkHttp 之后写的
 * CONNECT、以及它在隧道里再做的那层 TLS，全都跑在这条加密通道里——墙上只看得到我们自己的域名。
 *
 * 和上游 `org.signal.network.util.TlsProxySocketFactory`（给 Signal 那种按 SNI 转发的 TLS 代理用的）的区别：
 * - 连的是 OkHttp **已经解析好**的代理地址。解析走 client 自己的 Dns（app 里是 `SignalServiceNetworkAccess.DNS`，
 *   最后一档是 static-ips 回落）；上游那个在 `createSocket()` 里用系统 DNS 重新解析，回落就用不上了。
 * - TCP 连接遵守 OkHttp 的 connectTimeout，TLS 握手也限在同一个时限里；上游那个在 `createSocket()`
 *   里连、不受任何超时约束。
 * - 主机名在握手之后用 [OkHostnameVerifier] 校验（OkHttp 对普通 HTTPS 就是这么做的）。
 *   `SSLParameters.endpointIdentificationAlgorithm` 在 Android 上要 API 24，而 minSdk 是 23。
 *
 * 前提：用它的 client 必须**每个请求都走代理**。app 的 `ContentProxySelector.select` 从不返回 DIRECT
 * （白名单内一律走代理，白名单外直接抛异常），所以挂在那几个 client 上是对的；别挂到可能直连的 client 上——
 * 直连的目标会被当成代理去做 TLS，证书对不上直接失败。
 */
class HttpsProxySocketFactory @JvmOverloads constructor(
  private val proxyHost: String,
  private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
  private val hostnameVerifier: HostnameVerifier = OkHostnameVerifier
) : SocketFactory() {

  override fun createSocket(): Socket = TlsOnConnectSocket(proxyHost, sslSocketFactory, hostnameVerifier)

  @Throws(IOException::class)
  override fun createSocket(host: String, port: Int): Socket {
    return createSocket().apply { connect(InetSocketAddress(host, port)) }
  }

  @Throws(IOException::class)
  override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
    return createSocket().apply {
      bind(InetSocketAddress(localHost, localPort))
      connect(InetSocketAddress(host, port))
    }
  }

  @Throws(IOException::class)
  override fun createSocket(host: InetAddress, port: Int): Socket {
    return createSocket().apply { connect(InetSocketAddress(host, port)) }
  }

  @Throws(IOException::class)
  override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
    return createSocket().apply {
      bind(InetSocketAddress(localAddress, localPort))
      connect(InetSocketAddress(address, port))
    }
  }

  /**
   * 连接之前一切操作落在原始 socket 上；[connect] 里握手成功之后，全部改落在 TLS socket 上。
   * OkHttp 拿到的始终是这一个对象，它之后读写的流、设的超时，都经 [delegate] 转到 TLS 那一层。
   */
  private class TlsOnConnectSocket(
    private val proxyHost: String,
    private val sslSocketFactory: SSLSocketFactory,
    private val hostnameVerifier: HostnameVerifier
  ) : Socket() {

    private val raw = Socket()

    @Volatile
    private var tls: SSLSocket? = null

    private val delegate: Socket
      get() = tls ?: raw

    @Throws(IOException::class)
    override fun connect(endpoint: SocketAddress) {
      connect(endpoint, 0)
    }

    @Throws(IOException::class)
    override fun connect(endpoint: SocketAddress, timeout: Int) {
      raw.connect(endpoint, timeout)

      val port = (endpoint as InetSocketAddress).port
      val ssl = sslSocketFactory.createSocket(raw, proxyHost, port, true) as SSLSocket
      try {
        // 握手也限在 connectTimeout 里（0 = 不限，和 connect 的语义一致）；OkHttp 在 connect 之前
        // 设过的读超时，握手完再还给它。
        val readTimeout = raw.soTimeout
        ssl.soTimeout = timeout
        ssl.startHandshake()
        ssl.soTimeout = readTimeout

        if (!hostnameVerifier.verify(proxyHost, ssl.session)) {
          throw SSLPeerUnverifiedException("Content proxy certificate does not match $proxyHost")
        }
      } catch (e: IOException) {
        ssl.close()
        throw e
      }

      tls = ssl
    }

    @Throws(IOException::class)
    override fun bind(bindpoint: SocketAddress?) {
      raw.bind(bindpoint)
    }

    override fun getInetAddress(): InetAddress? = delegate.inetAddress

    override fun getLocalAddress(): InetAddress = delegate.localAddress

    override fun getPort(): Int = delegate.port

    override fun getLocalPort(): Int = delegate.localPort

    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress

    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress

    // 上游 ProxySocket 把它声明成非空再转给 SSLSocket，而 SSLSocket 的 channel 恒为 null。
    override fun getChannel(): SocketChannel? = null

    @Throws(IOException::class)
    override fun getInputStream(): InputStream = delegate.getInputStream()

    @Throws(IOException::class)
    override fun getOutputStream(): OutputStream = delegate.getOutputStream()

    override fun setTcpNoDelay(on: Boolean) {
      delegate.tcpNoDelay = on
    }

    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay

    override fun setSoLinger(on: Boolean, linger: Int) {
      delegate.setSoLinger(on, linger)
    }

    override fun getSoLinger(): Int = delegate.soLinger

    @Throws(IOException::class)
    override fun sendUrgentData(data: Int) {
      delegate.sendUrgentData(data)
    }

    override fun setOOBInline(on: Boolean) {
      delegate.oobInline = on
    }

    override fun getOOBInline(): Boolean = delegate.oobInline

    override fun setSoTimeout(timeout: Int) {
      delegate.soTimeout = timeout
    }

    override fun getSoTimeout(): Int = delegate.soTimeout

    override fun setSendBufferSize(size: Int) {
      delegate.sendBufferSize = size
    }

    override fun getSendBufferSize(): Int = delegate.sendBufferSize

    override fun setReceiveBufferSize(size: Int) {
      delegate.receiveBufferSize = size
    }

    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize

    override fun setKeepAlive(on: Boolean) {
      delegate.keepAlive = on
    }

    override fun getKeepAlive(): Boolean = delegate.keepAlive

    override fun setTrafficClass(tc: Int) {
      delegate.trafficClass = tc
    }

    override fun getTrafficClass(): Int = delegate.trafficClass

    override fun setReuseAddress(on: Boolean) {
      delegate.reuseAddress = on
    }

    override fun getReuseAddress(): Boolean = delegate.reuseAddress

    // OkHttp 取消请求就是关 socket：握手进行到一半时关的是 raw，握手会随之失败退出。
    @Throws(IOException::class)
    override fun close() {
      delegate.close()
    }

    @Throws(IOException::class)
    override fun shutdownInput() {
      delegate.shutdownInput()
    }

    @Throws(IOException::class)
    override fun shutdownOutput() {
      delegate.shutdownOutput()
    }

    override fun toString(): String = "HttpsProxy[$proxyHost] $delegate"

    override fun isConnected(): Boolean = delegate.isConnected

    override fun isBound(): Boolean = delegate.isBound

    override fun isClosed(): Boolean = delegate.isClosed

    override fun isInputShutdown(): Boolean = delegate.isInputShutdown

    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown

    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) {
      delegate.setPerformancePreferences(connectionTime, latency, bandwidth)
    }
  }
}
