package com.avoqado.cobropropio.motor

import android.content.Context
import android.os.SystemClock
import com.avoqado.cobropropio.blumon.CatalogoDeRechazos
import com.avoqado.cobropropio.blumon.ClienteBlumon
import com.avoqado.cobropropio.blumon.CredencialDelCliente
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.dominio.LectorDeTarjeta
import com.avoqado.cobropropio.dominio.Red
import com.avoqado.cobropropio.libreta.Libreta
import com.avoqado.cobropropio.privacidad.HuellaHmac
import com.avoqado.cobropropio.red.Endpoints
import com.avoqado.cobropropio.red.EnvioUnico
import com.avoqado.cobropropio.servidor.ColaDeResultados
import com.avoqado.cobropropio.servidor.CredencialDelServidor
import com.avoqado.cobropropio.servidor.ReversoDelServidorHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.SecretKey

/**
 * La ÚNICA forma de armar el motor fuera del módulo, una vez por proceso. Fija Blumon (ClienteBlumon + EnvioUnico con los
 * endpoints del ambiente de [identidad]), la misma identidad para el motor y para Blumon, los relojes de producción, el
 * catálogo de rechazos, la libreta de `Libreta.abrir` (con `applicationContext`), `SystemClock.elapsedRealtime` (el mismo reloj
 * que la libreta) y un `fondo` propio supervisado. El SERVIDOR: `https://` obligatorio en PRODUCCION; [pinDelServidor] opcional
 * (hoy la app lo tiene apagado a propósito, `NetworkModule.provideCertificatePinner`), pero si trae pines, alguno aplica al host
 * y la URL es `https://` (OkHttp sólo revisa el pin en TLS).
 * Una segunda llamada en el mismo proceso truena.
 */
fun crearMotorDeCobro(
    context: Context,
    identidad: Identidad,
    lector: LectorDeTarjeta,
    red: Red,
    credencialBlumon: CredencialDelCliente,
    urlDelServidor: String,
    credencialDelServidor: CredencialDelServidor,
    pinDelServidor: CertificatePinner?,
    llaveDeHuellas: SecretKey,
): MotorDeCobro {
    // Todo lo inválido truena ANTES de abrir la libreta: una llamada fallida no toma su dueño.
    if (identidad.ambiente == Ambiente.PRODUCCION) require(urlDelServidor.startsWith("https://")) { "SERVIDOR sin https en producción" }
    if (pinDelServidor != null) {
        require(urlDelServidor.startsWith("https://")) { "pines del SERVIDOR sin https" }
        val host = urlDelServidor.toHttpUrl().host
        require(pinDelServidor.pins.any { it.matchesHostname(host) }) { "ningún pin del SERVIDOR aplica a su host" }
    }

    val ahora: () -> Long = SystemClock::elapsedRealtime
    val endpoints = endpointsDe(identidad.ambiente)
    val blumon = ClienteBlumon(endpoints, EnvioUnico(endpoints, ahora), credencialBlumon, identidad, SecureRandom())
    val servidor = ReversoDelServidorHttp(urlDelServidor, credencialDelServidor, pinDelServidor)
    val libreta = Libreta.abrir(context)
    return MotorDeCobro(
        identidad, lector, red, blumon, servidor, libreta, ColaDeResultados(libreta, servidor), HuellaHmac(llaveDeHuellas),
        CatalogoDeRechazos.BLUMON, Relojes(), CoroutineScope(SupervisorJob() + Dispatchers.Default), ahora, Instant::now,
    )
}

internal fun endpointsDe(ambiente: Ambiente): Endpoints = when (ambiente) {
    Ambiente.PRODUCCION -> Endpoints.PRODUCCION
    Ambiente.SANDBOX -> Endpoints.SANDBOX
}
