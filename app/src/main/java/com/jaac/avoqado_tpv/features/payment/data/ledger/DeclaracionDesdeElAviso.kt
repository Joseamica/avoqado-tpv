package com.jaac.avoqado_tpv.features.payment.data.ledger

import androidx.room.ColumnInfo
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.remotepayment.AvisoDeCobrosPendientes
import com.jaac.avoqado_tpv.core.remotepayment.BandejaServerRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Un cobro al que el cajero le puede afirmar «no se cobró» desde el banner ([PaymentAttemptDao.observarCobrosDeclarables]).
 * Con [requestId] vino del POS y la declaración va por el servidor; sin él es un Pago rápido y va a la libreta. [attemptId] es null en
 * una solicitud de la bandeja que nunca llegó a abrir intento.
 */
data class CobroDeclarable(
    @ColumnInfo(name = "attempt_id") val attemptId: String?,
    @ColumnInfo(name = "request_id") val requestId: String?,
    @ColumnInfo(name = "total_centavos") val totalCentavos: Long,
    @ColumnInfo(name = "desde_millis") val desdeMillis: Long,
    @ColumnInfo(name = "server_answered_at") val serverAnsweredAt: Long?,
    /** F1 (tarea 4): false = trae dinero o una contradicción; se puede CONSULTAR, pero no admite declaración. */
    @ColumnInfo(name = "declarable") val declarable: Boolean = true,
    /** F1 bloque 2 (R1): el SDK de ESTE intento puede regresar todavía (misma ejecución, sin regreso comprobado): sin [Firmar]. */
    @ColumnInfo(name = "ejecucion_viva") val ejecucionViva: Boolean = false,
    /** F1 bloque 5: `ATTEMPT` o `REMOTE` — con [clave], la identidad del renglón. */
    @ColumnInfo(name = "clase") val clase: String = if (attemptId != null) CLASE_INTENTO else CLASE_SOLICITUD,
    @ColumnInfo(name = "clave") val clave: String = attemptId ?: requestId.orEmpty(),
    /** F1 bloque 5: el estado del intento (null en una solicitud sin intento) y si es venta o devolución: quién es su dueño. */
    @ColumnInfo(name = "estado") val estado: String? = null,
    @ColumnInfo(name = "tipo") val tipo: String = PaymentAttemptEntity.KIND_SALE,
) {
    /**
     * F1 bloque 5 (I5): un renglón por INTENTO (o por solicitud sin intento). Dos intentos de la misma solicitud son dos renglones: el
     * `requestId` sólo sirve para mirar a los hermanos y decidir si una firma de solicitud es segura.
     */
    val identidad: String get() = "$clase:$clave"

    /** F1: los últimos 6 caracteres del intento (o de la solicitud), para distinguir dos filas del mismo importe y minuto. */
    val referenciaCorta: String get() = clave.filter { it.isLetterOrDigit() }.takeLast(6).uppercase()

    companion object {
        const val CLASE_INTENTO = "ATTEMPT"
        const val CLASE_SOLICITUD = "REMOTE"
    }
}

/** Lo que le pasó a la declaración, con el texto que lee el cajero. */
sealed interface ResultadoDeLaDeclaracion {
    data object Declarado : ResultadoDeLaDeclaracion
    /** D22: un Pago rápido declarado ahora, y Avoqado lo guardó (con el nombre de la sesión, la hora y el monto). */
    data object DeclaradoEnAvoqado : ResultadoDeLaDeclaracion
    /** F1 (Codex código r2 #5): al firmar resultó que ya estaba declarado en Avoqado por otra persona (p. ej. la cajera, por la sesión). */
    data object YaDeclaradoEnAvoqado : ResultadoDeLaDeclaracion
    /** F1 (Codex código r2 #6): Avoqado tiene evidencia que contradice «no se cobró» pero no es del banco para ESTE intento (un veto). */
    data object HayEvidenciaEnAvoqado : ResultadoDeLaDeclaracion
    /** D22: un Pago rápido declarado ahora, pero Avoqado no lo recibió (sin red, sin respuesta o un servidor anterior). */
    data object DeclaradoSoloEnLaTerminal : ResultadoDeLaDeclaracion
    data object PidePin : ResultadoDeLaDeclaracion
    data class Rechazado(val mensaje: String) : ResultadoDeLaDeclaracion
    data object SinRed : ResultadoDeLaDeclaracion
    data object HayEvidencia : ResultadoDeLaDeclaracion
    data object FaltaConsultar : ResultadoDeLaDeclaracion
    data object SinPermiso : ResultadoDeLaDeclaracion
    data object UsaElPos : ResultadoDeLaDeclaracion
    /** F1: otra PERSONA ya revisó la fila elegida (su declaración, aquí, en el POS o en otra pantalla). No es la respuesta del banco. */
    data object YaResuelto : ResultadoDeLaDeclaracion
    /** F1: la fila elegida resultó cobrada: Avoqado la registró. */
    data object YaCobrado : ResultadoDeLaDeclaracion
    /** F1: la fila elegida ya no está por revisar y la terminal no sabe cómo quedó (una solicitud del POS). */
    data object YaNoEstaPorRevisar : ResultadoDeLaDeclaracion
    /** F1 bloque 2 (R1): el SDK de la PAX puede regresar todavía con ESTE cobro: no se firma. */
    data object SigueTrabajando : ResultadoDeLaDeclaracion
    /** F1 bloque 5: una devolución: la resuelve su pantalla, no «Por revisar». */
    data object EsDevolucion : ResultadoDeLaDeclaracion
    /** F1 bloque 5: el cobro ya está en la cola de pagos: lo sube la cola. */
    data object EnLaCola : ResultadoDeLaDeclaracion
    /** F1 bloque 7: el banco aprobó ESTE intento y falta que Avoqado lo registre (no se firma). */
    data object AprobadoSinRegistro : ResultadoDeLaDeclaracion
    /** Familia «el texto miente» (Codex final r2 #3): una lectura del historial de AngelPay de este cobro sigue sin respuesta. */
    data object HistorialPendiente : ResultadoDeLaDeclaracion
    /** Familia «el texto miente»: otra fila de este cobro o de su cuenta todavía frena la tarjeta. */
    data object TarjetaPorConfirmar : ResultadoDeLaDeclaracion

    /** [Declarado] y [PidePin] no se avisan: uno cierra el diálogo y el otro abre el del PIN. */
    fun texto(): String = when (this) {
        is Rechazado -> mensaje
        // F1: la firma del gerente va a Avoqado; sin red nada se cierra.
        SinRed -> "Sin conexión: la firma del gerente necesita internet. El cobro sigue por confirmar; inténtalo cuando vuelva."
        // F1 (Codex código r1 #9): también la lee el gerente en una fila «sólo consulta» (el banco aprobó después de cerrarla).
        HayEvidencia -> "El banco sí registró algo de este cobro: no lo vuelvas a cobrar ni lo marques como no cobrado."
        // Codex código r2 #6: un veto (evidencia de otra terminal, un pago que no se puede atribuir) no se presenta como del banco.
        // F1 bloque 7: un 409, un veto ajeno o evidencia sin dueño: no se afirma dinero de ESTE cobro.
        HayEvidenciaEnAvoqado -> "Avoqado encontró información que impide liberar este cobro."
        // Codex código r2 #5: no firmó este gerente; la declaración ya estaba (no hay un nombre que mostrar: el acuse trae un id).
        // F1 bloque 7: la declaración que ya estaba guardada (otra persona, o la respuesta perdida): nunca «quedó tu firma».
        // F1-textos (catálogo D5, fila 6): la de ANDROID (`yaEstabaDeclarada`), sin la hora: este texto no la tiene a la mano.
        YaDeclaradoEnAvoqado -> "Avoqado ya tenía guardada la firma de este cobro. No se agregó otra."
        FaltaConsultar -> "Primero hay que consultar a Avoqado: conéctate a internet."
        SinPermiso -> "Para dejar constancia de que no se cobró se necesita el permiso de conciliar cobros; pídeselo a tu gerente."
        UsaElPos -> "Esta versión todavía no permite declararlo desde la terminal: contéstalo en el POS."
        // F1 (Codex código r1 #11): una revisión humana nunca se presenta como respuesta del banco.
        YaResuelto -> "Este cobro ya lo revisó otra persona: ya no está por revisar."
        YaCobrado -> "Este cobro SÍ pasó: Avoqado ya lo registró. No lo vuelvas a cobrar."
        YaNoEstaPorRevisar -> "Este cobro ya no está por revisar: se resolvió en el POS o en Avoqado. Revisa Pagos para ver cómo quedó."
        SigueTrabajando -> DeclaracionDesdeElAviso.TEXTO_SIGUE_TRABAJANDO
        EsDevolucion -> "Es una devolución: se revisa en Devoluciones, no aquí."
        EnLaCola -> "Este cobro ya está en la cola para subir a Avoqado. No lo vuelvas a cobrar."
        AprobadoSinRegistro -> "El banco autorizó este intento. Falta confirmar el registro."
        // F1-textos (catálogo D5, filas 4 y 5 ⚙): aquí no se escoge por flujo (la fila no viaja en el resultado; pasarla cruzaría
        // capas): va la frase que vale para POS → TPV y para TPV SOLA.
        HistorialPendiente -> FrenoDelResuelto.HISTORIAL_PENDIENTE.texto
        TarjetaPorConfirmar -> FrenoDelResuelto.TARJETA_FRENADA.texto
        // D22: después de declarar un Pago rápido se dice cuál de los dos pasó; la confirmación no lo podía saber de antemano.
        // F1: lo que acredita la firma es lo que el gerente revisó, no que el banco dijo «no». F1-textos (fila 7): lo lee el gerente mismo.
        DeclaradoEnAvoqado -> "Listo: quedó tu firma con tu nombre y la hora."
        // F1-textos (fila 8 · F-F): puede que Avoqado sí la guardó y se perdió la respuesta; no se invita a cobrar.
        DeclaradoSoloEnLaTerminal -> FALTA_CONFIRMAR_LA_REVISION
        Declarado, PidePin -> ""
    }
}

/**
 * 🔴 El botón «No se cobró» del banner verde (founder, 3-nov: «A»). Lo que escribe es una afirmación de que un cobro NO ocurrió, y aquí no
 * se decide nada de dinero: con solicitud es la MISMA declaración del POS, y la decide `reconcileUncharged` en el servidor; sin solicitud
 * es [BlumonAttemptResolver.declareChecked], con sus defensas (Codex plan r1: el veto que no se pudo guardar y «falta consultar»). Si el banco
 * avisa después que sí se cobró, se registra y salta la alerta, como hoy.
 */
@Singleton
class DeclaracionDesdeElAviso @Inject constructor(
    private val ledger: PaymentAttemptLedger,
    private val resolver: BlumonAttemptResolver,
    private val recuperacion: LedgerServerRecovery,
    private val bandeja: BandejaServerRecovery,
    private val api: TerminalAttemptApiService,
    private val socket: dagger.Lazy<SocketManager>,
) {
    /** Un solo envío a la vez: el doble toque no manda dos declaraciones. */
    private val envio = Mutex()

    /** Por identidad y no por posición: la lista cambia entre una vuelta y otra. */
    @Volatile private var ultimoRefrescado: String? = null

    fun observarDeclarables(venueId: String, limite: Int = TOPE_DE_LA_CONSULTA) = ledger.observarCobrosDeclarables(venueId, limite)

    /** F1 bloque 5: cuántos hay por revisar, exacto. */
    fun observarTotal(venueId: String) = ledger.observarTotalPorRevisar(venueId)

    /**
     * Antes de pedir confirmación. `null` = se puede confirmar. Un Pago rápido consulta primero (Codex plan r1 #2): sin respuesta de Avoqado
     * nunca, la libreta rechazaría la declaración y no se ofrece un botón que no puede cumplir. Con solicitud decide el servidor.
     */
    suspend fun preparar(venueId: String, cobro: CobroDeclarable): ResultadoDeLaDeclaracion? = sinTumbarLaApp {
        if (cobro.requestId != null) return@sinTumbarLaApp null
        val attemptId = cobro.attemptId ?: return@sinTumbarLaApp GENERICO
        val lectura = resolver.prepareChecked(venueId, attemptId)
        lectura.resultJson?.let(socket.get()::emitDurableTerminalPaymentResult)
        if (lectura.state == BlumonAttemptResolver.State.PENDING) {
            if (lectura.row?.serverAnsweredAt == null) ResultadoDeLaDeclaracion.FaltaConsultar else null
        } else desenlaceEnLaLibreta(lectura)
    }

    /** `null` = ya hay un envío en curso: contesta el primero. */
    suspend fun declarar(venueId: String, cobro: CobroDeclarable, supervisorPin: String? = null): ResultadoDeLaDeclaracion? {
        if (!envio.tryLock()) return null
        try {
            return sinTumbarLaApp {
                val requestId = cobro.requestId
                sinMentir(cobro, if (requestId != null) declararPorElServidor(venueId, requestId, cobro, supervisorPin)
                    else declararEnLaLibreta(venueId, cobro, supervisorPin))
            }
        } finally {
            envio.unlock()
        }
    }

    /**
     * Mientras el banner esté a la vista se consulta UN cobro por llamada, en rueda y con tope (Codex plan r1 #3): uno lento no tapa a los
     * demás y el banner se va solo si se declaró en el POS. Sin estampar: no gasta el turno de la recuperación. No usa
     * `ConsultaDelCierreDeCaja`, que empieza siempre por el primero, ni `liberarDudasLocales`, que puede hacer POST por otros intentos.
     * true = Avoqado contestó (o no había nada que consultar).
     */
    suspend fun refrescarUno(venueId: String, cobros: List<CobroDeclarable>): Boolean {
        val enOrden = cobros.sortedBy { it.identidad }
        val anterior = ultimoRefrescado
        val cobro = enOrden.firstOrNull { anterior == null || it.identidad > anterior } ?: enOrden.firstOrNull() ?: return true
        ultimoRefrescado = cobro.identidad
        return consultarConTope(venueId, cobro)
    }

    /**
     * hz:248b195b:D20 (21-nov): al MOSTRARSE «Por revisar» (y al volver a Pagos o de segundo plano) la rueda da hasta
     * [BARRIDO_AL_MOSTRARSE] vueltas seguidas, SIN las pausas de 15 s. Una duda que el gerente firmó en la tablet mientras la terminal estaba
     * en otra pantalla se va al abrir Pagos, no cuando le toque su turno (antes, hasta N × 15 s). Mismo orden que la rueda (Codex r2: con
     * otro orden, la fila que ya tenía el siguiente turno podía llegar más tarde que antes): cada fila se consulta, a lo más, cuando la
     * consultaba la rueda sola. Es la MISMA consulta: no firma, no libera ni declara nada. La primera sin respuesta (sin red, tope o 5xx)
     * detiene el barrido y devuelve false: quien llama sigue con la rueda en el acto, sin insistir fila por fila. true = no se cortó.
     */
    suspend fun refrescarAlMostrarse(venueId: String, cobros: List<CobroDeclarable>): Boolean {
        // ponytail: tope de 10 vueltas por vista (Codex r1 #3: «Ver anteriores» llega a cientos); las demás siguen en la rueda de 15 s.
        repeat(minOf(cobros.size, BARRIDO_AL_MOSTRARSE)) { if (!refrescarUno(venueId, cobros)) return false }
        return true
    }

    /** Una consulta con [TOPE_DEL_REFRESCO_MS]. true = Avoqado contestó; sin respuesta, tope o un fallo inesperado, false. */
    private suspend fun consultarConTope(venueId: String, cobro: CobroDeclarable): Boolean = try {
        withTimeoutOrNull(TOPE_DEL_REFRESCO_MS) { consultar(venueId, cobro) }?.sinRespuesta == false
    } catch (cancelada: CancellationException) {
        throw cancelada
    } catch (error: Exception) {
        Timber.w(error, "🧾 [NoSeCobro] no se pudo refrescar %s", cobro.identidad)
        false
    }

    private suspend fun declararPorElServidor(
        venueId: String,
        requestId: String,
        cobro: CobroDeclarable,
        supervisorPin: String?,
    ): ResultadoDeLaDeclaracion {
        // Entre ofrecer el botón y tocarlo pudo llegar el aprobado: el servidor todavía no lo sabe, la libreta sí.
        yaNoEsDeclarable(venueId, cobro)?.let { return it }
        val respuesta = try {
            api.declararSinCobro(venueId, requestId, DeclaracionSinCobroRequest(idDeLaDeclaracion(venueId, requestId), supervisorPin = supervisorPin))
        } catch (cancelada: CancellationException) {
            throw cancelada
        } catch (error: Exception) {
            // Sólo el nombre: nada del cuerpo, que puede llevar el PIN, llega al log.
            Timber.w("🧾 [NoSeCobro] sin respuesta al declarar %s: %s", requestId, error.javaClass.simpleName)
            return ResultadoDeLaDeclaracion.SinRed
        }
        if (respuesta.code() >= 500) return ResultadoDeLaDeclaracion.SinRed
        if (!respuesta.isSuccessful) return rechazo(respuesta, alImpedir = { impedidoPorAvoqado(venueId, cobro) })
        val cuerpo = respuesta.body() ?: return ResultadoDeLaDeclaracion.SinRed
        val lectura = try {
            consultar(venueId, cobro)
        } catch (cancelada: CancellationException) {
            throw cancelada
        } catch (error: Exception) {
            Timber.w(error, "🧾 [NoSeCobro] declarado, pero no se pudo consultar %s: lo termina el refresco", requestId)
            null
        }
        // F1 bloque 7 (I4): un `released` sin el comprobante de una PERSONA de ESTA solicitud no acredita nada (no se sintetiza el acuse).
        val comprobante = cuerpo.declarationReceipt
        // ponytail: sin la llave `declarationReceipt` sigue la regla de antes. La manda el SERVIDOR desde F1 bloque 7 (avoqado-server
        // `fix/f1-minimo` 70ca51c5f); sin ella contesta el de hoy en producción. Se quita cuando ese SERVIDOR ya esté publicado.
        if (cuerpo.released == true && comprobante != null && !comprobante.acreditaPersona(null, requestId)) return resultadoDe(venueId, cobro)
        // 🔴 Codex final r2 #2: el dinero de CUALQUIER intento de la solicitud (no sólo el elegido), también antes de un «Declarado».
        val dinero = dineroDe(cobro)
        return when {
            // 🔴 Codex final r1 #6: lo recién leído manda — dinero/aprobado e impedimento (los separa [evidenciaDe]) ANTES que el
            // comprobante: un comprobante repetido con un aprobado tardío nunca dice «Ya se puede volver a cobrar».
            cuerpo.released == true && (lectura?.evidenciaPositivaSinRegistro == true || lectura?.vetoDelServidor != null) ->
                evidenciaDe(cobro.attemptId?.let { ledger.leerIntento(it) }, sinGuardar = lectura?.evidenciaPositivaSinRegistro == true)
            cuerpo.released == true && dinero != null -> evidenciaDeLaSolicitud(cobro, dinero.fila)
            // Pasa por [sinMentir] al salir de [declarar].
            cuerpo.released == true && comprobante?.alreadyStored == true -> ResultadoDeLaDeclaracion.YaDeclaradoEnAvoqado
            cuerpo.released == true -> {
                // Sin sonda de por medio la bandeja seguiría PROCESSING y Caja la contaría. Nunca dice «no se cobró»: lo que certifica es
                // que nada de esta solicitud sigue corriendo, y que no vuelve a empezar.
                if (cobro.attemptId != null) ledger.cerrarSolicitudSiCeso(requestId)
                ResultadoDeLaDeclaracion.Declarado
            }
            cuerpo.status == "COMPLETED" || cuerpo.paymentId != null -> ResultadoDeLaDeclaracion.HayEvidencia
            else -> ResultadoDeLaDeclaracion.Rechazado(paraElCajero(cuerpo.message))
        }
    }

    private suspend fun declararEnLaLibreta(venueId: String, cobro: CobroDeclarable, supervisorPin: String?): ResultadoDeLaDeclaracion {
        val attemptId = cobro.attemptId ?: return GENERICO
        val resultado = resolver.declareChecked(venueId, attemptId, supervisorPin)
        resultado.resultJson?.let(socket.get()::emitDurableTerminalPaymentResult)
        // PENDING = no se cerró: nunca contestó Avoqado, no hubo respuesta (F1: la firma necesita conexión), Avoqado no devolvió el
        // acuse firmado, o algo cambió entre leer y escribir y el CAS no aplicó.
        return when {
            resultado.state == BlumonAttemptResolver.State.SIGNATURE_REQUIRED -> ResultadoDeLaDeclaracion.PidePin
            resultado.state == BlumonAttemptResolver.State.RELEASED && resultado.yaEstabaDeclarado -> ResultadoDeLaDeclaracion.YaDeclaradoEnAvoqado
            resultado.state == BlumonAttemptResolver.State.PENDING -> when {
                resultado.row?.serverAnsweredAt == null -> ResultadoDeLaDeclaracion.FaltaConsultar
                !resultado.serverAnswered -> ResultadoDeLaDeclaracion.SinRed
                else -> GENERICO
            }
            // D22: la declaración del cajero de ESTA libreta dice si quedó en Avoqado; otra liberación (del servidor) se cierra como antes.
            resultado.state == BlumonAttemptResolver.State.RELEASED &&
                resultado.row?.lastError?.startsWith(PaymentAttemptEntity.PREFIJO_DECLARADO_SIN_COBRO) == true ->
                if (PaymentAttemptEntity.declaradoEnAvoqado(resultado.row?.lastError)) ResultadoDeLaDeclaracion.DeclaradoEnAvoqado
                else ResultadoDeLaDeclaracion.DeclaradoSoloEnLaTerminal
            // Codex final r1 #7: la cerró la ventana automática (nadie firmó): se dice que ya no está por revisar, no «declaración guardada».
            resultado.state == BlumonAttemptResolver.State.RELEASED &&
                resultado.row?.serverOutcome == PaymentAttemptEntity.SERVER_RELEASED_NO_EVIDENCE -> ResultadoDeLaDeclaracion.YaNoEstaPorRevisar
            else -> desenlaceEnLaLibreta(resultado)
        }
    }

    private fun desenlaceEnLaLibreta(resultado: BlumonAttemptResolver.Result): ResultadoDeLaDeclaracion = when (resultado.state) {
        BlumonAttemptResolver.State.RELEASED -> ResultadoDeLaDeclaracion.Declarado
        BlumonAttemptResolver.State.RECORDED -> ResultadoDeLaDeclaracion.HayEvidencia
        BlumonAttemptResolver.State.MONEY_EVIDENCE -> evidenciaDe(resultado.row)
        BlumonAttemptResolver.State.PERMISSION_REQUIRED -> ResultadoDeLaDeclaracion.SinPermiso
        BlumonAttemptResolver.State.SIGNATURE_REQUIRED -> ResultadoDeLaDeclaracion.PidePin
        BlumonAttemptResolver.State.PENDING, BlumonAttemptResolver.State.UNAVAILABLE -> GENERICO
    }

    /**
     * F1 (tarea 4): cómo quedó la fila ELEGIDA, para decírselo al gerente en vez de cerrar en silencio. Sólo lee la libreta: nunca afirma
     * «no se cobró» de algo que sigue en duda.
     */
    suspend fun resultadoDe(venueId: String, cobro: CobroDeclarable): ResultadoDeLaDeclaracion = sinTumbarLaApp {
        val fila = cobro.attemptId?.let { ledger.leerIntento(it) }?.takeIf { it.venueId == venueId }
        val dinero = dineroDe(cobro)
        sinMentir(cobro, when {
            // F1 bloque 5: ver, firmar y reconocer son acciones distintas; cada renglón ofrece lo de su dueño.
            fila?.kind == PaymentAttemptEntity.KIND_REFUND -> ResultadoDeLaDeclaracion.EsDevolucion
            fila?.state == PaymentAttemptEntity.STATE_REGISTRADO -> ResultadoDeLaDeclaracion.YaCobrado
            fila?.state == PaymentAttemptEntity.STATE_ENTREGADA_A_COLA -> ResultadoDeLaDeclaracion.EnLaCola
            fila?.state in setOf(PaymentAttemptEntity.STATE_PREPARANDO, PaymentAttemptEntity.STATE_KERNEL_ACTIVO,
                PaymentAttemptEntity.STATE_AUTORIZANDO) -> ResultadoDeLaDeclaracion.SigueTrabajando
            dinero != null -> evidenciaDeLaSolicitud(cobro, dinero.fila)
            // Codex final r1 #8: una solicitud del POS que «Por revisar» enseña sólo para consultar (su intento quedó descartado, o el
            // efectivo ya arrancó) y que sigue ahí tras consultar: no se dice que ya no está por revisar.
            fila == null && !cobro.declarable && ledger.renglonPorRevisar(venueId, cobro.clase, cobro.clave) != null -> GENERICO
            fila == null -> ResultadoDeLaDeclaracion.YaNoEstaPorRevisar
            // F1 bloque 2 (R1): mientras el SDK de ESTE intento pueda regresar, no se firma (ni sale el texto genérico).
            fila.state == PaymentAttemptEntity.STATE_INDETERMINADO && ledger.laEjecucionPuedeSeguir(fila) -> ResultadoDeLaDeclaracion.SigueTrabajando
            // Codex código r1 #11: «lo revisó otra persona» sólo si fue una persona (su firma o «no presentó tarjeta»); lo que soltó
            // la ventana del banco o el silencio no lo revisó nadie.
            PaymentAttemptEntity.cerradaPorUnaPersona(fila) -> ResultadoDeLaDeclaracion.YaResuelto
            fila.state == PaymentAttemptEntity.STATE_DESCARTADA -> ResultadoDeLaDeclaracion.YaNoEstaPorRevisar
            else -> GENERICO
        })
    } ?: GENERICO

    /** F1: la consulta de una fila que no admite declaración (dinero o contradicción): se pregunta a Avoqado y se dice cómo quedó. */
    suspend fun consultarYDecir(venueId: String, cobro: CobroDeclarable): ResultadoDeLaDeclaracion {
        // F1 bloque 5: sólo una VENTA se consulta a Avoqado desde aquí; una devolución tiene su dueño.
        if (cobro.tipo == PaymentAttemptEntity.KIND_SALE) sinTumbarLaApp { consultar(venueId, cobro); null }
        return resultadoDe(venueId, cobro)
    }

    /** Lo que deja una consulta: el intento por S6, o esa sola solicitud de la bandeja. Lo que resolvió la bandeja se emite, como el trigger. */
    private suspend fun consultar(venueId: String, cobro: CobroDeclarable): LedgerServerRecovery.LecturaDelIntento? {
        val attemptId = cobro.attemptId
        if (attemptId == null) {
            // 🔴 Codex final r2 #2: lo que Avoqado contestó de ESA solicitud ya no se tira antes de elegir el mensaje.
            val respuesta = bandeja.conciliar(venueId, soloLaSolicitud = cobro.requestId)
            return LedgerServerRecovery.LecturaDelIntento(
                null, evidenciaPositivaSinRegistro = respuesta.conDinero,
                sinRespuesta = respuesta.sinRespuesta + respuesta.conErrorDelServidor > 0,
            )
        }
        val lectura = recuperacion.recoverOne(venueId, attemptId, estampar = false)
        lectura.bandejaResueltaJson?.let(socket.get()::emitDurableTerminalPaymentResult)
        resolver.recordarVetoSinGuardar(attemptId, lectura)
        return lectura
    }

    /** null = sigue declarable. Si ya no: lo que la libreta sabe de dinero, o nada que afirmar. Una lectura fallida tampoco deja pasar. */
    private suspend fun yaNoEsDeclarable(venueId: String, cobro: CobroDeclarable): ResultadoDeLaDeclaracion? {
        // D20 (Codex r2): sin el tope de la lista. «Ver anteriores» ofrece cobros fuera de los 50 más recientes, y buscar sólo ahí
        // diría «no se puede» de uno que sí. ponytail: una lectura local sin tope al declarar; Room, no el servidor.
        // F1 bloque 5: el renglón por su clave, sin tope (lo de «Ver anteriores» también se encuentra).
        val sigue = ledger.renglonPorRevisar(venueId, cobro.clase, cobro.clave)?.declarable == true &&
            // F1 bloque 2: el aprobado que sólo está en memoria tampoco deja firmar (la SQL no lo ve).
            cobro.attemptId?.let { ledger.tieneEvidenciaSinGuardar(it) } != true
        return when {
            sigue -> null
            dineroDe(cobro) != null -> ResultadoDeLaDeclaracion.HayEvidencia
            else -> GENERICO
        }
    }

    /**
     * F1 (Codex código r2 #6): qué evidencia es. Del banco para ESTE intento (aprobó el host, Avoqado tiene la aprobación o un
     * desenlace con dinero) ⇒ «El banco sí registró algo»; sólo un veto (evidencia de otra terminal, pago no atribuible, evidencia
     * sin dueño) ⇒ «Avoqado tiene evidencia». Lo que no se pudo leer cuenta como del banco (el lado que no invita a cobrar).
     */
    private fun evidenciaDe(fila: PaymentAttemptEntity?, sinGuardar: Boolean = false): ResultadoDeLaDeclaracion {
        // F1 bloque 7 (I4): el impedimento del 409 que la libreta no pudo escribir todavía: no se atribuye al banco.
        if (fila != null && fila.hostApproved != true && ledger.tieneImpedimentoSinGuardar(fila.attemptId))
            return ResultadoDeLaDeclaracion.HayEvidenciaEnAvoqado
        // F1 bloque 7: la aprobación PROPIA (el banco contestó a esta terminal) que Avoqado todavía no registra.
        if (fila != null && (fila.hostApproved == true || ledger.tieneEvidenciaSinGuardar(fila.attemptId)) && fila.serverPaymentId == null &&
            fila.serverVeto.isNullOrBlank()) return ResultadoDeLaDeclaracion.AprobadoSinRegistro
        val delBanco = sinGuardar || fila == null || fila.hostApproved == true ||
            fila.serverProcessorEvidence == PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED ||
            fila.serverOutcome in PaymentAttemptEntity.SERVER_OUTCOMES_CON_DINERO || ledger.tieneEvidenciaSinGuardar(fila.attemptId)
        return if (delBanco || fila?.serverVeto.isNullOrBlank()) ResultadoDeLaDeclaracion.HayEvidencia
        else ResultadoDeLaDeclaracion.HayEvidenciaEnAvoqado
    }

    /** Codex final r2 #2: el dinero de CUALQUIER intento de su solicitud, o del propio ([PaymentAttemptLedger.loQueFrenaElResuelto]). */
    private suspend fun dineroDe(cobro: CobroDeclarable): LoQueFrenaElResuelto? =
        ledger.loQueFrenaElResuelto(cobro.attemptId, cobro.requestId)?.takeIf { it.freno == FrenoDelResuelto.DINERO }

    /** [evidenciaDe] de la fila con dinero; «el banco autorizó ESTE intento» sólo si es el elegido (si es un hermano, «de este cobro»). */
    private fun evidenciaDeLaSolicitud(cobro: CobroDeclarable, fila: PaymentAttemptEntity?): ResultadoDeLaDeclaracion =
        evidenciaDe(fila).let {
            if (it == ResultadoDeLaDeclaracion.AprobadoSinRegistro && fila?.attemptId != cobro.attemptId) ResultadoDeLaDeclaracion.HayEvidencia else it
        }

    /**
     * 🔴 Familia «el texto miente sobre el dinero» (Codex final r2 #2 y #3): ningún «ya se puede volver a cobrar», «ya se resolvió /
     * ya no está por revisar» ni «declaración guardada» sale de aquí sin pasar por [PaymentAttemptLedger.loQueFrenaElResuelto], el mismo
     * predicado que frena la tarjeta. Un «Declarado» recién firmado no es texto (cierra el diálogo y la fila sigue en «Por revisar» si
     * algo la frena): su dinero lo mira [declararPorElServidor].
     */
    private suspend fun sinMentir(cobro: CobroDeclarable, resultado: ResultadoDeLaDeclaracion): ResultadoDeLaDeclaracion {
        if (resultado !in DAN_POR_RESUELTO) return resultado
        val freno = ledger.loQueFrenaElResuelto(cobro.attemptId, cobro.requestId) ?: return resultado
        return when (freno.freno) {
            FrenoDelResuelto.DINERO -> evidenciaDeLaSolicitud(cobro, freno.fila)
            FrenoDelResuelto.HISTORIAL_PENDIENTE -> ResultadoDeLaDeclaracion.HistorialPendiente
            FrenoDelResuelto.TARJETA_FRENADA -> ResultadoDeLaDeclaracion.TarjetaPorConfirmar
        }
    }

    /**
     * F1 bloque 7 (I4): el `409 POSITIVE_EVIDENCE_EXISTS` de una solicitud deja el IMPEDIMENTO en su intento (cerca la cuenta y sale en
     * «Por revisar») y dispara la consulta, que guarda lo que Avoqado sí pueda atribuir. Nunca escribe la aprobación del banco.
     */
    private suspend fun impedidoPorAvoqado(venueId: String, cobro: CobroDeclarable) {
        val attemptId = cobro.attemptId ?: return
        ledger.marcarImpedimentoDelServidor(venueId, attemptId)
        try {
            consultar(venueId, cobro)
        } catch (cancelada: CancellationException) {
            throw cancelada
        } catch (error: Exception) {
            Timber.w(error, "🧾 [NoSeCobro] 409 de %s: no se pudo consultar, lo termina el refresco", attemptId)
        }
    }

    private suspend fun rechazo(respuesta: Response<*>, alImpedir: suspend () -> Unit = {}): ResultadoDeLaDeclaracion {
        // 404: un servidor anterior a esta ruta. 🔴 Nunca se vuelve declaración local: se contesta en el POS.
        if (respuesta.code() == 404) return ResultadoDeLaDeclaracion.UsaElPos
        // 401: su texto («Token has been revoked») no es para el cajero.
        if (respuesta.code() == 401) return GENERICO
        val cuerpo = runCatching { JsonParser.parseString(respuesta.errorBody()?.string().orEmpty()).asJsonObject }.getOrNull()
        fun texto(campo: String) = runCatching { cuerpo?.get(campo)?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()
        return when {
            // F1 bloque 7: el 409 genérico es un impedimento de Avoqado, nunca «el banco cobró».
            respuesta.code() == 409 && texto("code") == "POSITIVE_EVIDENCE_EXISTS" -> {
                alImpedir()
                ResultadoDeLaDeclaracion.HayEvidenciaEnAvoqado
            }
            respuesta.code() == 403 && texto("code") == "SUPERVISOR_AUTHORIZATION_REQUIRED" -> ResultadoDeLaDeclaracion.PidePin
            // La negación de la puerta (`checkPermission`): sin ninguno de los dos permisos. Su texto es técnico, y en inglés.
            respuesta.code() == 403 && texto("required") != null -> ResultadoDeLaDeclaracion.SinPermiso
            else -> ResultadoDeLaDeclaracion.Rechazado(paraElCajero(texto("message")))
        }
    }

    /** El mensaje del servidor viaja tal cual si está escrito para una persona. Nunca «Validation error:» (D3, QA 2-nov). */
    private fun paraElCajero(mensaje: String?): String =
        mensaje?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("validation error", ignoreCase = true) } ?: TEXTO_GENERICO

    /** Un fallo inesperado (la base, un cuerpo raro) no afirma nada ni tumba la pantalla. */
    private inline fun sinTumbarLaApp(cuerpo: () -> ResultadoDeLaDeclaracion?): ResultadoDeLaDeclaracion? = try {
        cuerpo()
    } catch (cancelada: CancellationException) {
        throw cancelada
    } catch (error: Exception) {
        Timber.e("🧾 [NoSeCobro] falla inesperada al declarar: %s", error.javaClass.simpleName)
        GENERICO
    }

    companion object {
        /** El tope de una vuelta del refresco. */
        const val TOPE_DEL_REFRESCO_MS = 5_000L
        /** D20: cuántas dudas, las más recientes, se consultan al mostrarse «Por revisar». */
        const val BARRIDO_AL_MOSTRARSE = 10
        const val TEXTO_GENERICO = "Este cobro todavía no se puede declarar. Vuelve a consultar en un momento."
        /** F1 bloque 2 (R1): el texto exacto de una fila cuyo SDK puede regresar todavía. */
        const val TEXTO_SIGUE_TRABAJANDO = "La terminal sigue trabajando en este cobro. Si no avanza, apaga y prende la terminal."
        private val GENERICO = ResultadoDeLaDeclaracion.Rechazado(TEXTO_GENERICO)

        /** Los resultados que dicen «se resolvió» o invitan a volver a cobrar: todos pasan por [sinMentir]. */
        private val DAN_POR_RESUELTO = setOf(
            ResultadoDeLaDeclaracion.DeclaradoEnAvoqado, ResultadoDeLaDeclaracion.DeclaradoSoloEnLaTerminal,
            ResultadoDeLaDeclaracion.YaDeclaradoEnAvoqado, ResultadoDeLaDeclaracion.YaResuelto, ResultadoDeLaDeclaracion.YaNoEstaPorRevisar,
        )

        /** Lo que se ofrece ahora: la ventana del aviso, y una por solicitud (la lista viene de la más reciente a la más vieja). */
        // F1 bloque 5: sin `distinctBy` por solicitud — cada intento es su propio renglón. El aviso del Inicio sigue con lo de siempre
        // (las dudas, lo que trae dinero tras cerrarse y las solicitudes sin intento), no con un cobro que sigue en curso.
        fun vigentes(cobros: List<CobroDeclarable>, ahoraMillis: Long): List<CobroDeclarable> = cobros
            .filter { ahoraMillis - it.desdeMillis < AvisoDeCobrosPendientes.VENTANA_AVISO_MS }
            .filter { it.estado == null || it.estado == PaymentAttemptEntity.STATE_INDETERMINADO || it.estado == PaymentAttemptEntity.STATE_DESCARTADA }

        /** D20 y F1 bloque 5: «Por revisar» (Pagos) muestra TODO lo que cuenta Caja o frena una tarjeta, un renglón por intento. */
        fun porRevisar(cobros: List<CobroDeclarable>): List<CobroDeclarable> = cobros

        /** Cuántos trae cada mitad de [PaymentAttemptDao.observarCobrosDeclarables] (intentos y solicitudes sin intento) por vuelta. */
        const val TOPE_DE_LA_CONSULTA = 50

        /**
         * D20 (Codex r1 #1 y r2): la consulta trae los [limite] más recientes de cada mitad. Llena = puede haber más viejos, y el recuadro
         * ofrece «Ver anteriores» (otros 50) en vez de esconderlos.
         */
        fun recortada(cobros: List<CobroDeclarable>, limite: Int = TOPE_DE_LA_CONSULTA): Boolean = cobros.size >= limite

        /** F1 bloque 5: con el total exacto, ¿falta traer renglones? Llega al último. */
        fun faltanRenglones(cobros: List<CobroDeclarable>, total: Int): Boolean = total > cobros.size

        /** La MISMA declaración en cada intento, también tras reiniciar: el servidor la trata como replay. */
        private fun idDeLaDeclaracion(venueId: String, requestId: String): String =
            UUID.nameUUIDFromBytes("tpv:uncharged:$venueId:$requestId".toByteArray(Charsets.UTF_8)).toString()
    }
}
