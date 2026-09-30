package com.jaac.avoqado_tpv.features.payment.domain.processor

import java.time.Instant
import java.time.ZoneOffset

/**
 * El corte diario de AngelPay, que decide si un cobro todavía se puede CANCELAR desde la terminal.
 *
 * Lo que dijo AngelPay (Norman Saldaña, 29-sep-2026): las devoluciones están APAGADAS para todos los comercios —se piden
 * a soporte de AngelPay— y sólo existe la cancelación, completa y antes del corte de las 11 pm del mismo día.
 */
object AngelPayCorte {

    /** México no cambia de horario desde oct-2022. Offset FIJO a propósito (ver [corteDeLaVenta]). */
    val ZONA_MEXICO: ZoneOffset = ZoneOffset.ofHours(-6)

    /** Hora local del corte de AngelPay. */
    const val HORA_DE_CORTE = 23

    /** AngelPay reactivó las devoluciones posteriores al corte: cambiar a `true` sólo cuando ellos lo confirmen. */
    const val DEVOLUCION_POSTERIOR_HABILITADA = false

    /** Por qué ya no se puede devolver desde la terminal (lo que dice la lista de pagos). */
    const val MENSAJE_FUERA_DE_CORTE =
        "Ya pasó el corte de AngelPay (11 pm del día de la venta). La devolución se gestiona con soporte de AngelPay."

    /** Lo que contesta un INTENTO de devolución que el corte detuvo antes de tocar nada: ése sí sabe que no se hizo nada. */
    const val MENSAJE_FUERA_DE_CORTE_SIN_INTENTO = "$MENSAJE_FUERA_DE_CORTE No se reembolsó nada."

    /**
     * El corte que cierra el lote del cobro: las 23:00 (hora de México) del día de la venta; una venta hecha a las 23:00 o
     * después cae en el lote siguiente, cuyo corte es a las 23:00 del día siguiente.
     *
     * 🔴 Desfase FIJO −06:00 y no `ZoneId.of("America/Mexico_City")`: el ICU de la Nexgo N86 (Android 9) todavía aplica el
     * horario de verano que México abolió en 2022 y daría el corte una hora corrido de abril a octubre (medido el 29-sep).
     * ⚠️ La zona del corte de AngelPay se le preguntó a Norman el 29-sep; si contesta otra, se cambia AQUÍ y en ningún otro lado.
     */
    fun corteDeLaVenta(cobradoEn: Instant): Instant {
        val local = cobradoEn.atOffset(ZONA_MEXICO)
        val corteDelDia = local.toLocalDate().atTime(HORA_DE_CORTE, 0).atOffset(ZONA_MEXICO)
        return if (local.isBefore(corteDelDia)) corteDelDia.toInstant() else corteDelDia.plusDays(1).toInstant()
    }

    /** ¿Todavía se puede CANCELAR este cobro? */
    fun sePuedeCancelar(cobradoEn: Instant, ahora: Instant): Boolean = ahora.isBefore(corteDeLaVenta(cobradoEn))

    /** ¿Se puede intentar desde la terminal? La cancelación antes del corte; después, sólo si AngelPay reactivó la devolución. */
    fun sePuedeIntentarDesdeLaTerminal(cobradoEn: Instant, ahora: Instant): Boolean =
        sePuedeCancelar(cobradoEn, ahora) || DEVOLUCION_POSTERIOR_HABILITADA
}
