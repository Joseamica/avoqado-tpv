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
) {
    /** Una declaración por solicitud: dos intentos de la misma son el mismo cobro para el cajero. */
    val identidad: String get() = requestId ?: attemptId.orEmpty()
}

/** Lo que le pasó a la declaración, con el texto que lee el cajero. */
sealed interface ResultadoDeLaDeclaracion {
    data object Declarado : ResultadoDeLaDeclaracion
    data object PidePin : ResultadoDeLaDeclaracion
    data class Rechazado(val mensaje: String) : ResultadoDeLaDeclaracion
    data object SinRed : ResultadoDeLaDeclaracion
    data object HayEvidencia : ResultadoDeLaDeclaracion
    data object FaltaConsultar : ResultadoDeLaDeclaracion
    data object SinPermiso : ResultadoDeLaDeclaracion
    data object UsaElPos : ResultadoDeLaDeclaracion

    /** [Declarado] y [PidePin] no se avisan: uno cierra el diálogo y el otro abre el del PIN. */
    fun texto(): String = when (this) {
        is Rechazado -> mensaje
        SinRed -> "Sin conexión: esto necesita internet. Cuando vuelva, inténtalo otra vez."
        HayEvidencia -> "Avoqado tiene evidencia de este cobro: no lo declares ni lo vuelvas a cobrar."
        FaltaConsultar -> "Primero hay que consultar a Avoqado: conéctate a internet."
        SinPermiso -> "Para dejar constancia de que no se cobró se necesita el permiso de conciliar cobros; pídeselo a tu gerente."
        UsaElPos -> "Esta versión todavía no permite declararlo desde la terminal: contéstalo en el POS."
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

    fun observarDeclarables(venueId: String) = ledger.observarCobrosDeclarables(venueId)

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
                if (requestId != null) declararPorElServidor(venueId, requestId, cobro, supervisorPin)
                else declararEnLaLibreta(venueId, cobro)
            }
        } finally {
            envio.unlock()
        }
    }

    /**
     * Mientras el banner esté a la vista se consulta UN cobro por llamada, en rueda y con tope (Codex plan r1 #3): uno lento no tapa a los
     * demás y el banner se va solo si se declaró en el POS. Sin estampar: no gasta el turno de la recuperación. No usa
     * `ConsultaDelCierreDeCaja`, que empieza siempre por el primero, ni `liberarDudasLocales`, que puede hacer POST por otros intentos.
     */
    suspend fun refrescarUno(venueId: String, cobros: List<CobroDeclarable>) {
        val enOrden = cobros.sortedBy { it.identidad }
        val anterior = ultimoRefrescado
        val cobro = enOrden.firstOrNull { anterior == null || it.identidad > anterior } ?: enOrden.firstOrNull() ?: return
        ultimoRefrescado = cobro.identidad
        try {
            withTimeoutOrNull(TOPE_DEL_REFRESCO_MS) { consultar(venueId, cobro) }
        } catch (cancelada: CancellationException) {
            throw cancelada
        } catch (error: Exception) {
            Timber.w(error, "🧾 [NoSeCobro] no se pudo refrescar %s", cobro.identidad)
        }
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
        if (!respuesta.isSuccessful) return rechazo(respuesta)
        val cuerpo = respuesta.body() ?: return ResultadoDeLaDeclaracion.SinRed
        val lectura = try {
            consultar(venueId, cobro)
        } catch (cancelada: CancellationException) {
            throw cancelada
        } catch (error: Exception) {
            Timber.w(error, "🧾 [NoSeCobro] declarado, pero no se pudo consultar %s: lo termina el refresco", requestId)
            null
        }
        return when {
            cuerpo.released == true ->
                if (lectura?.evidenciaPositivaSinRegistro == true || lectura?.vetoDelServidor != null || tieneDinero(cobro)) ResultadoDeLaDeclaracion.HayEvidencia
                else {
                    // Sin sonda de por medio la bandeja seguiría PROCESSING y Caja la contaría. Nunca dice «no se cobró»: lo que certifica es
                    // que nada de esta solicitud sigue corriendo, y que no vuelve a empezar.
                    if (cobro.attemptId != null) ledger.cerrarSolicitudSiCeso(requestId)
                    ResultadoDeLaDeclaracion.Declarado
                }
            cuerpo.status == "COMPLETED" || cuerpo.paymentId != null -> ResultadoDeLaDeclaracion.HayEvidencia
            else -> ResultadoDeLaDeclaracion.Rechazado(paraElCajero(cuerpo.message))
        }
    }

    private suspend fun declararEnLaLibreta(venueId: String, cobro: CobroDeclarable): ResultadoDeLaDeclaracion {
        val attemptId = cobro.attemptId ?: return GENERICO
        val resultado = resolver.declareChecked(venueId, attemptId)
        resultado.resultJson?.let(socket.get()::emitDurableTerminalPaymentResult)
        // PENDING = no se cerró: nunca contestó Avoqado, o algo cambió entre leer y escribir y el CAS no aplicó.
        return if (resultado.state == BlumonAttemptResolver.State.PENDING) {
            if (resultado.row?.serverAnsweredAt == null) ResultadoDeLaDeclaracion.FaltaConsultar else GENERICO
        } else desenlaceEnLaLibreta(resultado)
    }

    private fun desenlaceEnLaLibreta(resultado: BlumonAttemptResolver.Result): ResultadoDeLaDeclaracion = when (resultado.state) {
        BlumonAttemptResolver.State.RELEASED -> ResultadoDeLaDeclaracion.Declarado
        BlumonAttemptResolver.State.RECORDED, BlumonAttemptResolver.State.MONEY_EVIDENCE -> ResultadoDeLaDeclaracion.HayEvidencia
        BlumonAttemptResolver.State.PERMISSION_REQUIRED -> ResultadoDeLaDeclaracion.SinPermiso
        BlumonAttemptResolver.State.PENDING, BlumonAttemptResolver.State.UNAVAILABLE -> GENERICO
    }

    /** Lo que deja una consulta: el intento por S6, o esa sola solicitud de la bandeja. Lo que resolvió la bandeja se emite, como el trigger. */
    private suspend fun consultar(venueId: String, cobro: CobroDeclarable): LedgerServerRecovery.LecturaDelIntento? {
        val attemptId = cobro.attemptId
        if (attemptId == null) {
            bandeja.conciliar(venueId, soloLaSolicitud = cobro.requestId)
            return null
        }
        val lectura = recuperacion.recoverOne(venueId, attemptId, estampar = false)
        lectura.bandejaResueltaJson?.let(socket.get()::emitDurableTerminalPaymentResult)
        return lectura
    }

    /** null = sigue declarable. Si ya no: lo que la libreta sabe de dinero, o nada que afirmar. Una lectura fallida tampoco deja pasar. */
    private suspend fun yaNoEsDeclarable(venueId: String, cobro: CobroDeclarable): ResultadoDeLaDeclaracion? {
        val sigue = ledger.observarCobrosDeclarables(venueId).first().any { it.identidad == cobro.identidad }
        return when {
            sigue -> null
            tieneDinero(cobro) -> ResultadoDeLaDeclaracion.HayEvidencia
            else -> GENERICO
        }
    }

    private suspend fun tieneDinero(cobro: CobroDeclarable): Boolean {
        val attemptId = cobro.attemptId ?: return false
        val fila = ledger.leerIntento(attemptId) ?: return false
        return fila.hostApproved == true || fila.serverProcessorEvidence == PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED ||
            !fila.serverVeto.isNullOrBlank() || fila.serverOutcome in PaymentAttemptEntity.SERVER_OUTCOMES_CON_DINERO ||
            ledger.tieneEvidenciaSinGuardar(attemptId)
    }

    private fun rechazo(respuesta: Response<*>): ResultadoDeLaDeclaracion {
        // 404: un servidor anterior a esta ruta. 🔴 Nunca se vuelve declaración local: se contesta en el POS.
        if (respuesta.code() == 404) return ResultadoDeLaDeclaracion.UsaElPos
        // 401: su texto («Token has been revoked») no es para el cajero.
        if (respuesta.code() == 401) return GENERICO
        val cuerpo = runCatching { JsonParser.parseString(respuesta.errorBody()?.string().orEmpty()).asJsonObject }.getOrNull()
        fun texto(campo: String) = runCatching { cuerpo?.get(campo)?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()
        return when {
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
        const val TEXTO_GENERICO = "Este cobro todavía no se puede declarar. Vuelve a consultar en un momento."
        private val GENERICO = ResultadoDeLaDeclaracion.Rechazado(TEXTO_GENERICO)

        /** Lo que se ofrece ahora: la ventana del aviso, y una por solicitud (la lista viene de la más reciente a la más vieja). */
        fun vigentes(cobros: List<CobroDeclarable>, ahoraMillis: Long): List<CobroDeclarable> = cobros
            .filter { ahoraMillis - it.desdeMillis < AvisoDeCobrosPendientes.VENTANA_AVISO_MS }
            .distinctBy { it.identidad }

        /** La MISMA declaración en cada intento, también tras reiniciar: el servidor la trata como replay. */
        private fun idDeLaDeclaracion(venueId: String, requestId: String): String =
            UUID.nameUUIDFromBytes("tpv:uncharged:$venueId:$requestId".toByteArray(Charsets.UTF_8)).toString()
    }
}
