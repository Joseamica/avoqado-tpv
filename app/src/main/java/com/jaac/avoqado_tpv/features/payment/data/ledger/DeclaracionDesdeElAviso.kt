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
) {
    /** Una declaración por solicitud: dos intentos de la misma son el mismo cobro para el cajero. */
    val identidad: String get() = requestId ?: attemptId.orEmpty()

    /** F1: los últimos 6 caracteres de la identidad, para distinguir dos filas del mismo importe y minuto. */
    val referenciaCorta: String get() = identidad.filter { it.isLetterOrDigit() }.takeLast(6).uppercase()
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

    /** [Declarado] y [PidePin] no se avisan: uno cierra el diálogo y el otro abre el del PIN. */
    fun texto(): String = when (this) {
        is Rechazado -> mensaje
        // F1: la firma del gerente va a Avoqado; sin red nada se cierra.
        SinRed -> "Sin conexión: la firma del gerente necesita internet. El cobro sigue por confirmar; inténtalo cuando vuelva."
        // F1 (Codex código r1 #9): también la lee el gerente en una fila «sólo consulta» (el banco aprobó después de cerrarla).
        HayEvidencia -> "El banco sí registró algo de este cobro: no lo vuelvas a cobrar ni lo marques como no cobrado."
        // Codex código r2 #6: un veto (evidencia de otra terminal, un pago que no se puede atribuir) no se presenta como del banco.
        HayEvidenciaEnAvoqado -> "Avoqado tiene evidencia de este cobro: no lo vuelvas a cobrar ni lo marques como no cobrado."
        // Codex código r2 #5: no firmó este gerente; la declaración ya estaba (no hay un nombre que mostrar: el acuse trae un id).
        YaDeclaradoEnAvoqado -> "Ya estaba declarado en Avoqado; ya se puede volver a cobrar."
        FaltaConsultar -> "Primero hay que consultar a Avoqado: conéctate a internet."
        SinPermiso -> "Para dejar constancia de que no se cobró se necesita el permiso de conciliar cobros; pídeselo a tu gerente."
        UsaElPos -> "Esta versión todavía no permite declararlo desde la terminal: contéstalo en el POS."
        // F1 (Codex código r1 #11): una revisión humana nunca se presenta como respuesta del banco.
        YaResuelto -> "Este cobro ya lo revisó otra persona: ya no está por revisar."
        YaCobrado -> "Este cobro SÍ pasó: Avoqado ya lo registró. No lo vuelvas a cobrar."
        YaNoEstaPorRevisar -> "Este cobro ya no está por revisar: se resolvió en el POS o en Avoqado. Revisa Pagos para ver cómo quedó."
        // D22: después de declarar un Pago rápido se dice cuál de los dos pasó; la confirmación no lo podía saber de antemano.
        // F1: lo que acredita la firma es lo que el gerente revisó, no que el banco dijo «no».
        DeclaradoEnAvoqado -> "Listo: quedó en Avoqado la firma del gerente, con su nombre y la hora: revisó la terminal y este cobro no aparece. Ya se puede volver a cobrar."
        DeclaradoSoloEnLaTerminal ->
            "Listo, pero Avoqado no lo recibió: quedó anotado sólo en esta terminal y no aparece en Avoqado. Puedes volver a cobrar."
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
                else declararEnLaLibreta(venueId, cobro, supervisorPin)
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
                if (lectura?.evidenciaPositivaSinRegistro == true || lectura?.vetoDelServidor != null || tieneDinero(cobro))
                    evidenciaDe(cobro.attemptId?.let { ledger.leerIntento(it) }, sinGuardar = lectura?.evidenciaPositivaSinRegistro == true)
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
        when {
            fila?.state == PaymentAttemptEntity.STATE_REGISTRADO -> ResultadoDeLaDeclaracion.YaCobrado
            tieneDinero(cobro) -> evidenciaDe(fila)
            fila == null -> ResultadoDeLaDeclaracion.YaNoEstaPorRevisar
            // Codex código r1 #11: «lo revisó otra persona» sólo si fue una persona (su firma o «no presentó tarjeta»); lo que soltó
            // la ventana del banco o el silencio no lo revisó nadie.
            fila.state == PaymentAttemptEntity.STATE_DESCARTADA &&
                (fila.lastError?.startsWith(PaymentAttemptEntity.PREFIJO_DECLARADO_SIN_COBRO) == true ||
                    fila.serverOutcome == PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT) -> ResultadoDeLaDeclaracion.YaResuelto
            fila.state == PaymentAttemptEntity.STATE_DESCARTADA -> ResultadoDeLaDeclaracion.YaNoEstaPorRevisar
            else -> GENERICO
        }
    } ?: GENERICO

    /** F1: la consulta de una fila que no admite declaración (dinero o contradicción): se pregunta a Avoqado y se dice cómo quedó. */
    suspend fun consultarYDecir(venueId: String, cobro: CobroDeclarable): ResultadoDeLaDeclaracion {
        sinTumbarLaApp { consultar(venueId, cobro); null }
        return resultadoDe(venueId, cobro)
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
        resolver.recordarVetoSinGuardar(attemptId, lectura)
        return lectura
    }

    /** null = sigue declarable. Si ya no: lo que la libreta sabe de dinero, o nada que afirmar. Una lectura fallida tampoco deja pasar. */
    private suspend fun yaNoEsDeclarable(venueId: String, cobro: CobroDeclarable): ResultadoDeLaDeclaracion? {
        // D20 (Codex r2): sin el tope de la lista. «Ver anteriores» ofrece cobros fuera de los 50 más recientes, y buscar sólo ahí
        // diría «no se puede» de uno que sí. ponytail: una lectura local sin tope al declarar; Room, no el servidor.
        val sigue = ledger.observarCobrosDeclarables(venueId, Int.MAX_VALUE).first().any { it.identidad == cobro.identidad && it.declarable }
        return when {
            sigue -> null
            tieneDinero(cobro) -> ResultadoDeLaDeclaracion.HayEvidencia
            else -> GENERICO
        }
    }

    /**
     * F1 (Codex código r2 #6): qué evidencia es. Del banco para ESTE intento (aprobó el host, Avoqado tiene la aprobación o un
     * desenlace con dinero) ⇒ «El banco sí registró algo»; sólo un veto (evidencia de otra terminal, pago no atribuible, evidencia
     * sin dueño) ⇒ «Avoqado tiene evidencia». Lo que no se pudo leer cuenta como del banco (el lado que no invita a cobrar).
     */
    private fun evidenciaDe(fila: PaymentAttemptEntity?, sinGuardar: Boolean = false): ResultadoDeLaDeclaracion {
        val delBanco = sinGuardar || fila == null || fila.hostApproved == true ||
            fila.serverProcessorEvidence == PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED ||
            fila.serverOutcome in PaymentAttemptEntity.SERVER_OUTCOMES_CON_DINERO || ledger.tieneEvidenciaSinGuardar(fila.attemptId)
        return if (delBanco || fila?.serverVeto.isNullOrBlank()) ResultadoDeLaDeclaracion.HayEvidencia
        else ResultadoDeLaDeclaracion.HayEvidenciaEnAvoqado
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

        /** D20: «Por revisar» (Pagos) ofrece lo mismo que [vigentes], sin la ventana de 10 min: la duda no se va con el aviso. */
        fun porRevisar(cobros: List<CobroDeclarable>): List<CobroDeclarable> = cobros.distinctBy { it.identidad }

        /** Cuántos trae cada mitad de [PaymentAttemptDao.observarCobrosDeclarables] (intentos y solicitudes sin intento) por vuelta. */
        const val TOPE_DE_LA_CONSULTA = 50

        /**
         * D20 (Codex r1 #1 y r2): la consulta trae los [limite] más recientes de cada mitad. Llena = puede haber más viejos, y el recuadro
         * ofrece «Ver anteriores» (otros 50) en vez de esconderlos.
         */
        fun recortada(cobros: List<CobroDeclarable>, limite: Int = TOPE_DE_LA_CONSULTA): Boolean =
            cobros.count { it.attemptId != null } >= limite || cobros.count { it.attemptId == null } >= limite

        /** La MISMA declaración en cada intento, también tras reiniciar: el servidor la trata como replay. */
        private fun idDeLaDeclaracion(venueId: String, requestId: String): String =
            UUID.nameUUIDFromBytes("tpv:uncharged:$venueId:$requestId".toByteArray(Charsets.UTF_8)).toString()
    }
}
