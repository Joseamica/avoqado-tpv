package com.jaac.avoqado_tpv.features.checkout.domain

import com.jaac.avoqado_tpv.core.domain.models.ApiException

/**
 * El texto que ve el cajero cuando falla crear/cobrar una orden en Cobrar. Un error tipado ([ApiException]) trae su texto en
 * `userMessage` (en español, sin el prefijo técnico «HTTP 403:» que lleva `message`); cualquier otra excepción conserva
 * su `message` de siempre, y [respaldo] si no tiene.
 */
fun textoDelErrorParaElCajero(e: Throwable, respaldo: String): String =
    (e as? ApiException)?.userMessage ?: e.message ?: respaldo
