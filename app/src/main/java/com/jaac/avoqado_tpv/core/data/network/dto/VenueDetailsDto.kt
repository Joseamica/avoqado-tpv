package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName

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
     * na7522fd (encargo 1df453): el encabezado del ticket que arma el SERVIDOR (`buildReceiptInfo`). Sus `legalName`/`rfc` son
     * los del emisor PRINCIPAL de Facturación; sin emisor vienen vacíos. Un servidor viejo no lo manda (null).
     */
    @SerializedName("receiptInfo")
    val receiptInfo: ReceiptInfoDto? = null,
)

/** Lo que la TPV lee del `receiptInfo` del SERVIDOR: el par fiscal del emisor principal. */
data class ReceiptInfoDto(
    @SerializedName("legalName")
    val legalName: String? = null,

    @SerializedName("rfc")
    val rfc: String? = null,
)

/** El par fiscal que imprime el ticket, y si vino del emisor de Facturación de un negocio (`emisorDe`) o de las columnas de siempre (null). */
data class DatosFiscalesDelTicket(val legalName: String?, val rfc: String?, val emisorDe: String?)

/**
 * na7522fd (encargo 1df453): qué razón social y RFC imprime el ticket de la TPV. Mismo orden que el SERVIDOR y ANDROID, sin el
 * emisor por comercio (todavía): el emisor principal de Facturación → las columnas de siempre del negocio.
 *
 * Codex r1 #6: si la respuesta no trae `receiptInfo` (un SERVIDOR nuevo que no pudo armar el encabezado en ESA respuesta), no se
 * pisa el emisor que ya estaba guardado para ESTE negocio: devuelve null («conserva lo guardado»). Sin nada guardado de este
 * negocio (o un servidor viejo), las columnas de siempre, como antes.
 */
fun datosFiscalesDelTicket(venue: VenueDetailsDto, venueId: String, emisorGuardadoDe: String?): DatosFiscalesDelTicket? {
    val info = venue.receiptInfo
        ?: return if (emisorGuardadoDe == venueId) null else DatosFiscalesDelTicket(venue.legalName, venue.rfc, emisorDe = null)
    if (!info.rfc.isNullOrBlank() || !info.legalName.isNullOrBlank()) {
        return DatosFiscalesDelTicket(info.legalName, info.rfc, emisorDe = venueId)
    }
    return DatosFiscalesDelTicket(venue.legalName, venue.rfc, emisorDe = null)
}
