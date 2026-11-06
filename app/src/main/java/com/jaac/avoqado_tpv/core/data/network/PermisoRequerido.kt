package com.jaac.avoqado_tpv.core.data.network

import com.google.gson.JsonParser

/** 403 de permiso del servidor: `required` es el permiso que faltó; `overridable` sólo viene si el negocio tiene el PIN de gerente prendido. */
class PermisoRequeridoException(
    val required: String?,
    val overridable: Boolean,
    texto: String,
) : Exception(texto)

fun textoDePermiso(required: String?, textoDeAntes: String): String = when (required) {
    "orders:comp" -> "Esta cortesía la da un gerente con su sesión"
    "discounts:apply" -> "Este descuento lo da alguien con permiso de descuentos"
    "coupons:redeem" -> "Este cupón lo canjea alguien con permiso de cupones"
    else -> textoDeAntes
}

fun permisoRequeridoDe(code: Int, errorBody: String?, textoDeAntes: String): PermisoRequeridoException? {
    if (code != 403) return null
    val json = runCatching { JsonParser.parseString(errorBody.orEmpty()).asJsonObject }.getOrNull()
    val required = runCatching { json?.get("required")?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
    val overridable = runCatching { json?.get("overridable")?.asBoolean }.getOrNull() ?: false
    return PermisoRequeridoException(required, overridable, textoDePermiso(required, textoDeAntes))
}
