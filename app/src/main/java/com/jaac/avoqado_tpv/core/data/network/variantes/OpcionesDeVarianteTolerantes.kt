package com.jaac.avoqado_tpv.core.data.network.variantes

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import java.lang.reflect.Type

/**
 * Una opción de variante («Talla = M») tal como la manda `GET tpv/venues/{venueId}/products`. Vive en `core` porque la leen los DOS
 * DTO de producto (Órdenes/Cobro/Kiosco y Mesas) y Mesas no puede importar nada de `features/ordering`.
 */
data class OpcionDeVarianteDto(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: String,
    @SerializedName("namePosition") val namePosition: Int,
    @SerializedName("valuePosition") val valuePosition: Int,
)

/**
 * `variantOptions` con forma inesperada NO puede tumbar el catálogo: Gson lanza `JsonSyntaxException` ante un tipo equivocado y la
 * lista ENTERA de productos se perdería. Aquí se lee el `JsonElement` y cualquier cosa que no sea un arreglo de objetos con `name` y
 * `value` (texto no vacío) y posiciones enteras se vuelve `null`, o sea que el producto se pinta suelto. No se adivina: si UNA opción
 * viene mal, se descarta la lista completa (una familia con la mitad de sus ejes agruparía mal). Un `null` de JSON ni llega aquí
 * (`@JsonAdapter` es nullSafe) y `[]` pasa como lista vacía, que `agruparCatalogo` ya rechaza como familia.
 */
internal class OpcionesDeVarianteTolerantes : JsonDeserializer<List<OpcionDeVarianteDto>?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): List<OpcionDeVarianteDto>? {
        if (json == null || !json.isJsonArray) return null
        return json.asJsonArray.map { opcion(it) ?: return null }
    }

    private fun opcion(e: JsonElement): OpcionDeVarianteDto? {
        if (!e.isJsonObject) return null
        val o = e.asJsonObject
        return OpcionDeVarianteDto(
            name = texto(o, "name") ?: return null,
            value = texto(o, "value") ?: return null,
            namePosition = entero(o, "namePosition") ?: return null,
            valuePosition = entero(o, "valuePosition") ?: return null,
        )
    }

    private fun texto(o: JsonObject, campo: String): String? =
        o.get(campo)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }

    // `1.0` cuenta como 1 (JSON no distingue); `0.5`, `"0"`, `null`, un campo ausente o un número fuera de Int, no.
    private fun entero(o: JsonObject, campo: String): Int? =
        o.get(campo)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asBigDecimal.intValueExact() }.getOrNull() }
}

/**
 * Los cuatro campos de familia de un producto, o `null` si es un producto suelto (sin `productGroupId`). Un solo lugar para que
 * Órdenes/Cobro/Kiosco y Mesas apliquen la misma regla; el mapper lo llama UNA vez al leer el catálogo (la agrupación lee la
 * familia muchas veces por producto, así que el modelo la guarda ya armada, no la recalcula).
 */
fun datosDeFamiliaDeDto(
    groupId: String?,
    groupName: String?,
    groupImageUrl: String?,
    variantOptions: List<OpcionDeVarianteDto>?,
): DatosDeFamilia? =
    if (groupId.isNullOrBlank()) null
    else DatosDeFamilia(
        groupId = groupId,
        groupName = groupName,
        groupImageUrl = groupImageUrl,
        opciones = variantOptions?.map { OpcionDeVariante(it.name, it.value, it.namePosition, it.valuePosition) },
    )
