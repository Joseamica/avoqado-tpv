package com.jaac.avoqado_tpv.features.payments.data.dto

import com.google.gson.annotations.SerializedName

/**
 * La liga del recibo digital de un cobro ya hecho: lo que hace falta para dibujar el QR cuando se
 * REIMPRIME un ticket desde el historial de pagos.
 *
 * 🔴 Por qué existe: `printPaymentHistoryReceipt` nunca imprimió QR — no por olvido de una línea,
 * sino porque el dato no llegaba: el historial de pagos no devuelve la llave del recibo. Vivía sólo
 * en la respuesta del cobro (Asana «POS - Reimpresion de Ticket sin QR de facturacion», 11-sep-2026).
 *
 * Espejo de `ReceiptLink.kt` (avoqado-android) y `ReceiptLink.swift` (avoqado-ios).
 */
data class ReceiptLinkResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("receipt") val receipt: ReceiptLinkDto? = null,
)

data class ReceiptLinkDto(
    @SerializedName("accessKey") val accessKey: String? = null,
    @SerializedName("receiptUrl") val receiptUrl: String? = null,
    /**
     * Sólo decide la LEYENDA del ticket («…y factura» vs «recibo digital»).
     * El QR se imprime igual: lleva al recibo, se pueda facturar o no.
     */
    @SerializedName("autofacturaAvailable") val autofacturaAvailable: Boolean = false,
)
