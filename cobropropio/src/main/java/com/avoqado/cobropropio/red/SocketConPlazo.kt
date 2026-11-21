package com.avoqado.cobropropio.red

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Se lanza ANTES de escribir: ni un byte de esa escritura sale al cable. */
internal class PlazoVencido : IOException("plazo vencido")

/**
 * El plazo de UNA llamada y lo que ya salió. `bytesEscritos` sube ANTES de entregar los bytes al socket TLS: si vale 0,
 * ningún `write` de la petición llegó a intentarse (evidencia de cero bytes).
 */
internal class Medidor(
    private val noEmpezarDespuesDe: Long?,
    private val ahora: () -> Long,
    private val ganchoDePrueba: (bytesEscritos: Long) -> Unit,
) {
    val bytesEscritos = AtomicLong(0)

    fun vencido(): Boolean = noEmpezarDespuesDe != null && ahora() >= noEmpezarDespuesDe

    fun antesDeEscribir(len: Int) {
        ganchoDePrueba(bytesEscritos.get()) // sólo pruebas: en producción no hace nada
        if (vencido()) throw PlazoVencido()
        bytesEscritos.addAndGet(len.toLong())
    }
}

/**
 * Envuelve el socket TLS que arma OkHttp. Sólo los bytes de la PETICIÓN (headers y cuerpo) pasan por su `OutputStream`;
 * el saludo TLS va por dentro del socket envuelto. Cada `write` revisa el plazo antes de escribir.
 */
internal class FabricaConPlazo(private val delegada: SSLSocketFactory, private val medidor: Medidor) : SSLSocketFactory() {

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        val tls = delegada.createSocket(s, host, port, autoClose) as SSLSocket
        // Sin el adaptador de plataforma de OkHttp (no reconoce el envoltorio), el SNI se pone aquí.
        runCatching { tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(SNIHostName(host)) } }
        return SocketConPlazo(tls, medidor)
    }

    override fun getDefaultCipherSuites(): Array<String> = delegada.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegada.supportedCipherSuites

    // OkHttp sólo arma TLS encima de su propio socket; las otras formas no se usan.
    override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        throw UnsupportedOperationException()
    override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        throw UnsupportedOperationException()
}

internal class SocketConPlazo(private val d: SSLSocket, private val medidor: Medidor) : SSLSocket() {

    private val salida: OutputStream by lazy {
        val real = d.outputStream
        object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len == 0) return
                medidor.antesDeEscribir(len)
                real.write(b, off, len)
            }
            override fun flush() = real.flush()
            override fun close() = real.close()
        }
    }

    override fun getOutputStream(): OutputStream = salida
    override fun getInputStream(): InputStream = d.inputStream

    override fun startHandshake() = d.startHandshake()
    override fun getSession(): SSLSession = d.session
    override fun getHandshakeSession(): SSLSession? = d.handshakeSession
    // Sin `getApplicationProtocol`: sólo HTTP/1.1, sin ALPN (OkHttp atrapa el UnsupportedOperationException por defecto).
    override fun getSSLParameters(): SSLParameters = d.sslParameters
    override fun setSSLParameters(params: SSLParameters?) { d.sslParameters = params }
    override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
    override fun getEnabledCipherSuites(): Array<String> = d.enabledCipherSuites
    override fun setEnabledCipherSuites(suites: Array<String>?) { d.enabledCipherSuites = suites }
    override fun getSupportedProtocols(): Array<String> = d.supportedProtocols
    override fun getEnabledProtocols(): Array<String> = d.enabledProtocols
    override fun setEnabledProtocols(protocols: Array<String>?) { d.enabledProtocols = protocols }
    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = d.addHandshakeCompletedListener(listener)
    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) =
        d.removeHandshakeCompletedListener(listener)
    override fun setUseClientMode(mode: Boolean) { d.useClientMode = mode }
    override fun getUseClientMode(): Boolean = d.useClientMode
    override fun setNeedClientAuth(need: Boolean) { d.needClientAuth = need }
    override fun getNeedClientAuth(): Boolean = d.needClientAuth
    override fun setWantClientAuth(want: Boolean) { d.wantClientAuth = want }
    override fun getWantClientAuth(): Boolean = d.wantClientAuth
    override fun setEnableSessionCreation(flag: Boolean) { d.enableSessionCreation = flag }
    override fun getEnableSessionCreation(): Boolean = d.enableSessionCreation

    override fun close() { d.close(); super.close() }
    override fun isClosed(): Boolean = d.isClosed
    override fun isConnected(): Boolean = d.isConnected
    override fun isBound(): Boolean = d.isBound
    override fun shutdownInput() = d.shutdownInput()
    override fun shutdownOutput() = d.shutdownOutput()
    override fun isInputShutdown(): Boolean = d.isInputShutdown
    override fun isOutputShutdown(): Boolean = d.isOutputShutdown
    override fun getInetAddress(): InetAddress? = d.inetAddress
    override fun getLocalAddress(): InetAddress = d.localAddress
    override fun getPort(): Int = d.port
    override fun getLocalPort(): Int = d.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = d.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = d.localSocketAddress
    override fun setSoTimeout(timeout: Int) { d.soTimeout = timeout }
    override fun getSoTimeout(): Int = d.soTimeout
    override fun setTcpNoDelay(on: Boolean) { d.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = d.tcpNoDelay
    override fun setKeepAlive(on: Boolean) { d.keepAlive = on }
    override fun getKeepAlive(): Boolean = d.keepAlive

    override fun toString() = "SocketConPlazo"
}
