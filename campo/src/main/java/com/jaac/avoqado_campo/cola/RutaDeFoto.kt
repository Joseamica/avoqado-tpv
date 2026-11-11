package com.jaac.avoqado_campo.cola

import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** B1 v2: Storage por REST, bucket fijo. La URL va SIN token y se fija al encolar. */
const val BASE_STORAGE = "https://firebasestorage.googleapis.com/"
const val BUCKET = "avoqado-d0a24.appspot.com"

private val MEXICO = ZoneId.of("America/Mexico_City")

fun fechaEnMexico(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(MEXICO).toLocalDate()

/** `clase`: `venta` (evidencia) o `checada` (entrada/salida). */
fun rutaDe(env: String, venueId: String, clase: String, fecha: LocalDate, llave: String, slot: String): String =
    "$env/venues/$venueId/campo/$clase/$fecha/${llave}_$slot.jpg"

/** Codificación de componente: `/` queda como `%2F` y el espacio como `%20`. */
fun codificar(ruta: String): String = URLEncoder.encode(ruta, "UTF-8").replace("+", "%20")

fun urlDe(ruta: String): String = "${BASE_STORAGE}v0/b/$BUCKET/o/${codificar(ruta)}?alt=media"
