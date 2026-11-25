package com.avoqado.motorlab

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** El venue del laboratorio: el motor no lo revisa y sólo viaja a 127.0.0.1 (`ReversoDelServidorHttp.kt:187-191`). */
internal const val VENUE = "laboratorio"

/** 127.0.0.1 por sus bytes: `localhost` podría resolver a `::1`. */
internal val LOCAL: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

/**
 * Bloque 4-L · L-d1: el «SERVIDOR» del motor en el laboratorio (Ruling 2). A toda petición contesta `404` sin cuerpo y con
 * `Connection: close`, DESPUÉS de leerla entera: así cerrar la conexión nunca tira bytes sin leer (un RST que el cliente vería
 * como «sin respuesta»). Lo que el motor hace con ese `404` lo fijan sus propias pruebas: el permiso sigue al reverso y el
 * resultado queda sin entregar. Escucha sólo en 127.0.0.1, en un puerto que da el sistema, y atiende una conexión a la vez en
 * su hilo. Cuenta lo contestado por ruta: `intencion`, `resultado` u `otra`. No guarda ni enseña nada de lo que recibe.
 */
internal class RespondedorLocal : AutoCloseable {
    private val servidor = ServerSocket(0, 16, LOCAL)
    private val cuentas = ConcurrentHashMap<String, AtomicInteger>()

    val puerto: Int = servidor.localPort
    val direccion: InetAddress = servidor.inetAddress

    // Al final: el hilo arranca con todo lo de arriba ya listo.
    private val hilo = thread(isDaemon = true, name = "motorlab-respondedor") { atender() }

    /** Lo contestado de [ruta]: `intencion`, `resultado` u `otra`. */
    fun contestadas(ruta: String): Int = cuentas[ruta]?.get() ?: 0

    fun resumen(): String = RUTAS.joinToString(" · ") { "$it ${contestadas(it)}" }

    /**
     * Ya no acepta: cierra el `ServerSocket` y ESPERA a su hilo. Mientras el hilo sigue dentro de `accept`, el sistema todavía
     * acepta conexiones en ese puerto (Linux suelta el socket cuando esa llamada regresa); al volver de aquí, ya las rechaza.
     *
     * ponytail: también espera a una conexión en curso (5 s por lectura como mucho); si algún día se cierra con clientes lentos,
     * cerrar también la conexión en curso.
     */
    override fun close() {
        servidor.close()
        hilo.join()
    }

    private fun atender() {
        try {
            while (true) {
                val conexion = try {
                    servidor.accept()
                } catch (_: Exception) {
                    return
                }
                // Un cliente que se va, que tarda más de 5 s o que manda algo sin forma no tumba el hilo: se cierra y sigue.
                runCatching {
                    conexion.use { c ->
                        c.soTimeout = 5_000
                        val ruta = leerPeticion(c.getInputStream().buffered()) ?: return@use // sin forma: sin contestar
                        // Se cuenta ANTES de contestar: quien recibe el 404 ya ve su cuenta.
                        cuentas.getOrPut(tipo(ruta)) { AtomicInteger() }.incrementAndGet()
                        c.getOutputStream().apply {
                            write(NO_ENCONTRADO)
                            flush()
                        }
                    }
                }
            }
        } finally {
            servidor.close()
        }
    }

    private fun tipo(ruta: String) = when {
        ruta.endsWith("/reverso/intencion") -> "intencion"
        ruta.endsWith("/reverso/resultado") -> "resultado"
        else -> "otra"
    }

    private companion object {
        val RUTAS = listOf("intencion", "resultado", "otra")
        val NO_ENCONTRADO = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * La petición ENTERA: la línea del pedido, las cabeceras y EXACTAMENTE `Content-Length` bytes de cuerpo, ni uno más. Da la
 * ruta (el segundo campo del pedido), o `null` si algo no tiene forma: una línea sin `\n` o de más de 8 KiB, un
 * `Content-Length` que no es número o fuera de `0..65536`, o un cuerpo que se corta. Sin `Content-Length`, no hay cuerpo.
 *
 * ponytail: sólo `Content-Length`, que es lo que manda el motor (`EnvioUnico.kt:223`, un cuerpo de largo conocido); un cuerpo
 * `chunked` se leería como vacío. Si algún día llega uno, leerlo aquí.
 */
internal fun leerPeticion(e: InputStream): String? {
    val pedido = linea(e) ?: return null
    var largo = 0
    while (true) {
        val cabecera = linea(e) ?: return null
        if (cabecera.isEmpty()) break
        if (cabecera.substringBefore(':').trim().equals("Content-Length", ignoreCase = true)) {
            largo = cabecera.substringAfter(':').trim().toIntOrNull()?.takeIf { it in 0..MAXIMO_CUERPO } ?: return null
        }
    }
    repeat(largo) { if (e.read() < 0) return null }
    return pedido.split(' ').getOrNull(1)
}

/** Hasta `\n`, sin su `\r`; `null` si el flujo se acaba antes o si pasa de 8 KiB. Byte a byte: nunca lee de más. */
private fun linea(e: InputStream): String? {
    val s = StringBuilder()
    while (true) {
        val c = e.read()
        if (c < 0) return null
        if (c == '\n'.code) return s.removeSuffix("\r").toString()
        if (s.length == MAXIMO_LINEA) return null
        s.append(c.toChar())
    }
}

private const val MAXIMO_LINEA = 8 * 1024
private const val MAXIMO_CUERPO = 64 * 1024
