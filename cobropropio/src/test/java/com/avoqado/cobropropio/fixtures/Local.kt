package com.avoqado.cobropropio.fixtures

import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress

/**
 * Ola final, 6ª parte: el CI de GitHub tronó en `ReversoDelServidorHttpTest` (`500 expected:<ErrorDelServidor> but was:
 * <SinRespuesta>`, siempre), y en la Mac también. Allí `localhost` resuelve a DOS direcciones (127.0.0.1 y ::1) y MockWebServer
 * escucha sólo en una. Tras una llamada cortada a medio cuerpo, OkHttp marca esa ruta como fallida y en la siguiente prueba
 * primero la OTRA dirección; con `retryOnConnectionFailure(false)` (el cliente del SERVIDOR) no regresa a la buena y sale
 * `SinRespuesta` al instante. Así que estos servidores escuchan en 127.0.0.1 y el cliente les habla por esa dirección literal:
 * la prueba ya no depende de cómo resuelva `localhost` la máquina.
 */
object Local {
    val IPV4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
}

/** Arranca en 127.0.0.1 (nunca en «la primera dirección de localhost»). */
fun MockWebServer.arrancarEnIpv4() = start(Local.IPV4, 0)

/** La URL base por la dirección literal; el servidor debe haber arrancado con [arrancarEnIpv4]. */
fun MockWebServer.baseLiteral(): String = "http://127.0.0.1:$port/"
