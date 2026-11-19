package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.core.data.local.DatosFiscalesDelTicket

/**
 * Minimal venue details for receipt/header printing.
 *
 * Maps to GET /tpv/venues/{venueId} (full venue object).
 * We only parse the fields needed by the TPV.
 */
data class VenueDetailsDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("legalName")
    val legalName: String? = null,

    @SerializedName("rfc")
    val rfc: String? = null,

    @SerializedName("address")
    val address: String? = null,

    @SerializedName("city")
    val city: String? = null,

    @SerializedName("state")
    val state: String? = null,

    @SerializedName("zipCode")
    val zipCode: String? = null,

    @SerializedName("logo")
    val logo: String? = null,

    /**
     * D50: el encabezado del ticket que arma el SERVIDOR (`buildReceiptInfo`, `devicePayload.service.ts`). Su `legalName`/`rfc`
     * son los del emisor PRINCIPAL de Facturación (nulos si el negocio no tiene emisor). Lo omite si no pudo armarlo.
     */
    @SerializedName("receiptInfo")
    val receiptInfo: ReceiptInfoDto? = null,
)

/** D50: lo que la TPV lee del `receiptInfo`: el par fiscal del emisor principal. */
data class ReceiptInfoDto(
    @SerializedName("legalName")
    val legalName: String? = null,

    @SerializedName("rfc")
    val rfc: String? = null,
)

/**
 * D50: qué razón social y RFC imprime el ticket, en el orden del SERVIDOR y de ANDROID (sin el emisor por comercio): el emisor
 * principal de Facturación → las columnas de siempre del negocio, cada par completo de su fuente. null = conservar lo guardado:
 * la respuesta no trajo `receiptInfo` (el SERVIDOR no pudo armarlo) y lo guardado de ESTE negocio es su emisor (Codex r1 #6 del
 * encargo 1df453 y r1 #6 de D50: unas columnas guardadas sí se renuevan con las de la respuesta).
 */
fun datosFiscalesDeLaRespuesta(venue: VenueDetailsDto, emisorGuardadoDeEsteNegocio: Boolean): DatosFiscalesDelTicket? {
    val emisor = venue.receiptInfo
    return when {
        emisor == null && emisorGuardadoDeEsteNegocio -> null
        emisor != null && (!emisor.legalName.isNullOrBlank() || !emisor.rfc.isNullOrBlank()) ->
            DatosFiscalesDelTicket(emisor.legalName, emisor.rfc, delEmisor = true)
        else -> DatosFiscalesDelTicket(venue.legalName, venue.rfc, delEmisor = false)
    }
}
