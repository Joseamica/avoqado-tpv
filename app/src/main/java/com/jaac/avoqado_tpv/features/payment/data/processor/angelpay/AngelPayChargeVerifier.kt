package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.processor.TransactionHistoryQuery
import kotlinx.coroutines.delay
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Qué contestó el procesador cuando se le preguntó por un cobro de desenlace incierto. */
sealed interface VerificacionDelCobro {

    /** El cobro SÍ está en el historial del procesador, con autorización. El dinero se movió. */
    data class Cobrado(
        val authCode: String,
        val referencia: String,
        val cardBin: String?,
    ) : VerificacionDelCobro

    /** El procesador contestó y el cobro NO existe. Ahora sí es seguro reintentar. */
    data object NoCobrado : VerificacionDelCobro

    /** No se pudo preguntar (sin red, error del SDK…). Sigue sin saberse: NO reintentar. */
    data class NoSePudoVerificar(val motivo: String) : VerificacionDelCobro
}

/**
 * Le pregunta a AngelPay si un cobro de desenlace [DesenlaceDelCobro.INCIERTO] llegó a
 * moverse, consultando **su** historial de transacciones.
 *
 * No es una idea nuestra: es lo que pide el propio catálogo del SDK — `G505` =
 * «Resultado no concluyente, **verifique el historial de transacciones**». Y es el mismo
 * patrón con el que Adyen resuelve una autorización sin respuesta.
 *
 * 🔴 **La identificación es por `integratorReference`, y sólo por ahí.** Ese campo lleva
 * nuestro `paymentAttemptId` y AngelPay lo devuelve tal cual (confirmado con el vendor el
 * 2026-05-28). Emparejar por monto y hora sería adivinar: la propina se elige EN la
 * terminal, así que un cobro legítimo casi nunca coincide con el importe pedido, y dos
 * ventas del mismo importe en el mismo minuto son lo normal en un mostrador. El servidor
 * ya tomó esa misma decisión en su vigilante y por el mismo motivo.
 *
 * Reutiliza el [PostOperationsAdapterFactory] que ya existe para la pantalla de
 * Transacciones — no estrena ningún camino hacia el procesador.
 */
@Singleton
class AngelPayChargeVerifier @Inject constructor(
    private val adapterFactory: PostOperationsAdapterFactory,
) {

    /**
     * @param attemptId el `paymentAttemptId` que viajó como `integratorReference`.
     * @param terminalSerial acota la consulta a esta terminal (null = sin filtro).
     * @param hoy fecha civil del cobro; se consulta también el día anterior.
     * @param intentos vistazos al historial antes de rendirse.
     * @param esperaEntreIntentosMs pausa entre vistazos.
     */
    suspend fun verificar(
        attemptId: String,
        terminalSerial: String?,
        hoy: LocalDate = LocalDate.now(java.time.ZoneId.of("America/Mexico_City")),
        intentos: Int = INTENTOS_POR_DEFECTO,
        esperaEntreIntentosMs: Long = ESPERA_ENTRE_INTENTOS_MS,
        affiliation: String? = null,
    ): VerificacionDelCobro {
        val adapter = adapterFactory.get(ProcessorType.ANGELPAY)
        // 🔴 Se consulta ayer TAMBIÉN: un cobro de las 23:59 verificado pasada la medianoche
        // desaparecería de una consulta de un solo día, y esa ausencia se leería como
        // "no se cobró" — el error que cuesta dinero.
        val query = TransactionHistoryQuery(
            startDate = hoy.minusDays(1).format(FORMATO),
            endDate = hoy.format(FORMATO),
            // `reference` filtra por la referencia DE ANGELPAY, no por la nuestra: mandarle
            // el attemptId devuelve siempre vacío. El filtro nuestro se aplica abajo.
            reference = null,
            integratorReference = attemptId,
            terminal = terminalSerial?.takeIf { it.isNotBlank() },
        )

        var ultimoFallo: String? = null
        repeat(intentos.coerceIn(1, 3)) { intento ->
            if (intento > 0 && esperaEntreIntentosMs > 0) delay(esperaEntreIntentosMs * (1L shl intento.coerceAtMost(4)))

            val resultado = runCatching { adapter.getTransactionHistory(query) }
                .getOrElse { Result.failure(it) }

            resultado.fold(
                onSuccess = { transacciones ->
                    val mia = transacciones.filter { it.integratorReference == attemptId }.singleOrNull()
                    if (mia != null && mia.authorizationCode.isNotBlank() &&
                        mia.reference.isNotBlank() &&
                        mia.processorType == ProcessorType.ANGELPAY &&
                        mia.status.uppercase() in setOf("APROBADA", "APPROVED") &&
                        !terminalSerial.isNullOrBlank() && mia.terminal == terminalSerial &&
                        (!affiliation.isNullOrBlank() && mia.affiliation == affiliation) &&
                        mia.operationType?.uppercase() in setOf("VENTA", "SALE") &&
                        mia.postOperationStatus.isNullOrBlank()) {
                        Timber.i(
                            "🔍 [AngelPay] Cobro CONFIRMADO en el historial | attemptId=%s auth=%s ref=%s",
                            attemptId, mia.authorizationCode, mia.reference,
                        )
                        return VerificacionDelCobro.Cobrado(
                            authCode = mia.authorizationCode,
                            referencia = mia.reference,
                            cardBin = mia.cardBin.takeIf { bin -> bin.isNotBlank() },
                        )
                    }
                    // Todavía no está. Puede que el historial vaya con retraso frente a un
                    // cobro de hace segundos, así que se vuelve a mirar antes de concluir.
                    ultimoFallo = null
                    Timber.w(
                        "🔍 [AngelPay] El historial aun no trae el cobro | attemptId=%s intento=%d/%d",
                        attemptId, intento + 1, intentos,
                    )
                },
                onFailure = { error ->
                    ultimoFallo = error.message ?: error::class.java.simpleName
                    Timber.w(error, "🔍 [AngelPay] No se pudo consultar el historial | intento=%d/%d", intento + 1, intentos)
                },
            )
        }

        // This API returns a history slice, with no completeness/finality guarantee.
        // An absent entry can never prove that authorization did not occur.
        return VerificacionDelCobro.NoSePudoVerificar(ultimoFallo ?: "El historial aún no confirma este intento")
    }

    companion object {
        /** Tres vistazos cubren el retraso normal del historial sin dejar al cajero esperando. */
        const val INTENTOS_POR_DEFECTO = 3
        const val ESPERA_ENTRE_INTENTOS_MS = 3_000L
        private val FORMATO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}

/**
 * El serial con el que se le pregunta a AngelPay por una transacción.
 *
 * 🔴 AngelPay conoce el serial del HARDWARE crudo (`N860W173397`), sin el prefijo `AVQD-`
 * que le anteponen TODAS nuestras fuentes (`DeviceInfoManager.getSerialNumber` lo documenta:
 * «Serial number with "AVQD-" prefix»; `SecureStorage` guarda lo mismo). Mandarlo con el
 * prefijo puesto rompe las DOS veces que el verificador usa el valor —el filtro de la
 * consulta y la condición `mia.terminal == terminalSerial`— así que el historial vuelve
 * vacío y un cobro aprobado NUNCA se puede acreditar.
 *
 * 🔴 Y NO se puede pasar `null` «para no filtrar»: la condición de aceptación exige
 * `!terminalSerial.isNullOrBlank()`, de modo que sin serial el verificador tampoco confirma
 * nada. El único valor que sirve es el serial real del aparato, sin prefijo.
 *
 * Medido en la Nexgo N86 el 2026-09-12: se consultaba con `terminal=2841548417` —el
 * `DEFAULT_SERIAL` de `TerminalConfig`, que es el serial de un COMERCIO Blumon y encima una
 * PAX— y el historial contestaba `count=0` en los 3 intentos.
 */
fun serialParaAngelPay(serialDelAparato: String?): String? =
    serialDelAparato
        ?.trim()
        ?.removePrefix("AVQD-")
        ?.removePrefix("avqd-")
        ?.uppercase()
        ?.takeIf { it.isNotBlank() }
