package com.jaac.avoqado_tpv.core.remotepayment

import com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalAttemptApiService
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔴 Pieza D (22-sep-2026) — el aviso de «cobro sin confirmar» que NADIE podía quitar.
 *
 * Medido en hardware, en una Nexgo N86: llevaba **25 horas** mostrando «Quedó un cobro de $50.00 sin confirmar»,
 * con el contador subiendo. Los tres lados decían cosas distintas:
 *
 *  · **servidor** → la solicitud estaba `FAILED / OPERATOR_RECONCILED_NO_CHARGE`: resuelta el día anterior;
 *  · **bandeja** → `PROCESSING`, como si siguiera en curso;
 *  · **libreta** → CERO intentos de esa solicitud.
 *
 * El repo ya describía ese estado («una fila PROCESSING sin intento correlacionado requiere conciliación
 * explícita»), pero no había con qué: toda la recuperación pregunta por INTENTO, y esa fila no tiene intento.
 * El cajero veía un aviso permanente sobre dinero ya conciliado y «Revisar» sólo lo llevaba al historial.
 *
 * Esto es esa conciliación, y sólo eso: pregunta al servidor por la SOLICITUD y cierra la fila **únicamente**
 * cuando él la declara resuelta.
 *
 * 🔴 Lo que NO hace, a propósito:
 *  · no libera nada por antigüedad — el plazo nunca acredita ausencia de cobro;
 *  · no toca filas CON intento — ésas las resuelve la libreta, que sabe de dinero;
 *  · no interpreta un 404, un error de red ni una respuesta sin `resuelta` como «no se cobró»: se conserva la fila.
 *
 * Es de sólo lectura contra el servidor e idempotente: repetirla sobre una fila ya cerrada no hace nada.
 */
@Singleton
class BandejaServerRecovery @Inject constructor(
    private val dao: RemotePaymentRequestDao,
    private val api: TerminalAttemptApiService,
) {
    data class Resultado(val consultadas: Int, val conciliadas: Int, val sinRespuesta: Int)

    suspend fun conciliar(venueId: String, now: Long = System.currentTimeMillis()): Resultado {
        var consultadas = 0
        var conciliadas = 0
        var sinRespuesta = 0

        val candidatas = runCatching { dao.candidatasHuerfanas(venueId) }.getOrDefault(emptyList())
        for (fila in candidatas) {
            try {
                val respuesta = api.getRequestStatus(venueId, fila.requestId)
                consultadas++
                if (!respuesta.isSuccessful) {
                    // 404, 403, 5xx: nada acredita nada. La fila se conserva con su aviso, que es lo honesto.
                    // Codex r6 (P2-11): pero se manda al final de la fila, o 20 filas con un 404 permanente dejarían
                    // sin consultar nunca a la 21.
                    Timber.d("🧾 [BandejaD] %s ⇒ HTTP %d: la fila se conserva", fila.requestId, respuesta.code())
                    dao.estamparConsultaHuerfana(fila.requestId, venueId, now)
                    continue
                }
                val cuerpo = respuesta.body()
                // Sólo `resuelta = true` autoriza cerrar. Un cuerpo sin ese campo (servidor anterior a la pieza D)
                // se comporta como antes: no se toca nada.
                if (cuerpo?.resuelta != true) { dao.estamparConsultaHuerfana(fila.requestId, venueId, now); continue }

                val status = runCatching { cuerpo.request?.get("status")?.asString }.getOrNull()
                if (status == null) { dao.estamparConsultaHuerfana(fila.requestId, venueId, now); continue }
                val failureCode = runCatching { cuerpo.request?.get("failureCode")?.asString }.getOrNull()
                val cerradas = dao.conciliarHuerfanaConElServidor(fila.requestId, venueId, status, failureCode, now)
                if (cerradas != 1) dao.estamparConsultaHuerfana(fila.requestId, venueId, now)
                if (cerradas == 1) {
                    conciliadas++
                    Timber.w(
                        "🧾 [BandejaD] %s conciliada con el servidor (%s/%s): el aviso de «cobro sin confirmar» se apaga",
                        fila.requestId, status, failureCode ?: "-",
                    )
                }
            } catch (cancelada: CancellationException) {
                throw cancelada
            } catch (error: Exception) {
                sinRespuesta++
                Timber.w(error, "🧾 [BandejaD] sin respuesta para %s — la fila se conserva", fila.requestId)
                // Codex r7 (P2-9): también aquí va al final de la fila. Sin esto, veinte respuestas que revientan siempre
                // (al deserializar, por ejemplo) quedaban primeras para siempre y la 21 no se consultaba nunca.
                try {
                    dao.estamparConsultaHuerfana(fila.requestId, venueId, now)
                } catch (cancelada: CancellationException) {
                    throw cancelada
                } catch (_: Exception) {
                }
            }
        }
        if (consultadas + sinRespuesta > 0) {
            Timber.i("🧾 [BandejaD] pasada | consultadas=%d conciliadas=%d sinRespuesta=%d", consultadas, conciliadas, sinRespuesta)
        }
        return Resultado(consultadas, conciliadas, sinRespuesta)
    }
}
