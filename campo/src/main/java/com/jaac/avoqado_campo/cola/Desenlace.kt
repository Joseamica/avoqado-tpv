package com.jaac.avoqado_campo.cola

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.jaac.avoqado_campo.red.objetoJson

/** Lo que quedó de un 2xx (P8, B12). `AVISO`: ya no se sube, pero el promotor lo ve en «Mis pendientes» con su texto. */
enum class Desenlace { HECHA, AVISO }

data class Lectura(val desenlace: Desenlace, val code: String? = null, val texto: String? = null, val datosJson: String? = null)

/**
 * R7-4: el `resultado` de un `{ resuelto: "APLICADO", resultado }` de la gracia; si no, el cuerpo tal cual (también el de
 * `aplicadoEnGracia`). Es lo que se guarda en `resultadoJson`: así el `paymentId` y el `timeEntryId` se leen igual por las dos rutas.
 */
fun cuerpoEfectivo(cuerpo: String): String {
    val o = objetoJson(cuerpo) ?: return cuerpo
    val r = o.get("resultado")
    return if (o.txt("resuelto") == "APLICADO" && r != null && r.isJsonObject) r.toString() else cuerpo
}

/** R2-3: lee el cuerpo efectivo por tipo. [pidioNumero] = la evidencia mandó `linkingNumber` o `portedNumber` (R3-1). */
fun desenlaceDe(tipo: String, cuerpo: String, pidioNumero: Boolean = false): Lectura {
    val o = objetoJson(cuerpoEfectivo(cuerpo)) ?: return HECHA
    return when (tipo) {
        TipoPieza.EVIDENCIA -> evidencia(o, pidioNumero)
        TipoPieza.CUSTODIA_ACEPTAR -> custodia(o)
        else -> HECHA
    }
}

/**
 * H4: ¿el 2xx trae el dato que el SERVIDOR siempre manda cuando lo aplicó? Sin él (`200 {}` de algo en medio) no es HECHA: la
 * pieza espera, como sin red. Se lee el cuerpo efectivo, así que vale igual para `aplicadoEnGracia` y `resuelto: APLICADO`.
 * La custodia recuperada de la bitácora del SERVIDOR (`custodiaHecha`) trae `eventos` en vez de su respuesta normal.
 */
fun traeSuResultado(tipo: String, cuerpo: String): Boolean {
    val o = objetoJson(cuerpoEfectivo(cuerpo)) ?: return false
    fun texto(campo: String) = !o.txt(campo).isNullOrEmpty()
    val deLaBitacora = o.arreglo("eventos") != null
    return when (tipo) {
        TipoPieza.VENTA -> texto("paymentId")
        TipoPieza.ENTRADA, TipoPieza.SALIDA -> texto("timeEntryId")
        TipoPieza.EVIDENCIA -> texto("verificationId")
        TipoPieza.CUSTODIA_ACEPTAR -> o.get("summary")?.isJsonObject == true || deLaBitacora
        TipoPieza.CUSTODIA_RECHAZAR -> texto("custodyState") || deLaBitacora
        else -> false // un tipo desconocido se rechaza antes de mandarse
    }
}

private val HECHA = Lectura(Desenlace.HECHA)
private fun aviso(code: String, texto: String, datos: JsonObject? = null) = Lectura(Desenlace.AVISO, code, texto, datos?.toString())

private val NUMERO_NO_GUARDADO = mapOf(
    "DISABLED" to "la captura de números está apagada en tu tienda",
    "LOCKED_BY_BACKOFFICE" to "tu supervisor ya lo capturó",
    "NOT_PORTABILIDAD" to "esta venta no es portabilidad",
)

private fun evidencia(o: JsonObject, pidioNumero: Boolean): Lectura {
    if (o.txt("outcome") == "SALE_ALREADY_APPROVED") {
        return aviso("SALE_ALREADY_APPROVED", "Tu supervisor ya aprobó esta venta; la foto nueva no se guardó.")
    }
    val foto = o.txt("photoOutcome")
    if (foto != null && foto != "APPLIED" && foto != "SUPERSEDED_BY_NEWER") {
        return aviso("FOTO_NO_APLICADA", "La foto no se guardó. Revísalo con tu supervisor.")
    }
    for ((campo, cual) in listOf("linkingNumberOutcome" to "de vinculación", "portedNumberOutcome" to "portado")) {
        val n = o.txt(campo) ?: continue
        val porque = NUMERO_NO_GUARDADO[n] ?: continue
        return aviso(n, "La foto quedó; el número $cual no se guardó: $porque.")
    }
    if (pidioNumero && o.txt("recuperado") == "true") {
        return aviso("NUMERO_SIN_CONFIRMAR", "La foto quedó; no pudimos confirmar el número. Revísalo con tu supervisor.")
    }
    return HECHA
}

/** Las aceptadas cuentan como hechas; las fallidas (o las que la bitácora no confirmó) van en `datosJson`. */
private fun custodia(o: JsonObject): Lectura {
    val fallidas = JsonArray().apply {
        o.arreglo("results")?.filter { it.isJsonObject && it.asJsonObject.txt("status") == "error" }?.forEach { add(it) }
    }
    val failed = runCatching { o.getAsJsonObject("summary")?.get("failed")?.asInt }.getOrNull() ?: 0
    if (failed > 0 || fallidas.size() > 0) {
        val total = runCatching { o.getAsJsonObject("summary")?.get("total")?.asInt }.getOrNull()
        val texto = if (total != null) "$failed de $total SIMs no se aceptaron." else "Algunas SIMs no se aceptaron."
        return aviso("CUSTODIA_EN_PARTE", texto, JsonObject().apply { add("fallidas", fallidas) })
    }
    val faltan = o.arreglo("faltan")
    if (faltan != null && faltan.size() > 0) {
        val series = faltan.joinToString(", ") { it.asString }
        return aviso("CUSTODIA_SIN_CONFIRMAR", "No pudimos confirmar estas SIMs: $series. Revísalo con tu supervisor.", JsonObject().apply { add("faltan", faltan) })
    }
    return HECHA
}

private fun JsonObject.txt(campo: String): String? = get(campo)?.takeIf { it.isJsonPrimitive }?.asString
private fun JsonObject.arreglo(campo: String): JsonArray? = get(campo)?.takeIf { it.isJsonArray }?.asJsonArray
