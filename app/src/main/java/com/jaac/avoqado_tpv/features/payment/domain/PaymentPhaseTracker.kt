package com.jaac.avoqado_tpv.features.payment.domain

/**
 * Observador de fases del cobro con tarjeta — **independiente de la pantalla**.
 *
 * 🔴 Por qué existe (Testarudo, 8-sep-2026): una PAX se quedó congelada en
 * «Procesando chip…» —PASO 3, `StartEmvTrans`, con el teclado del PED encima— y
 * NO hubo forma de saber desde fuera en qué llamada se detuvo. El detector que ya
 * existía vive en la PANTALLA (`PaymentScreen`, `LaunchedEffect(currentState)`) y
 * **reinicia su contador cada vez que cambia el estado**; `PaymentState.Processing`
 * cambia por el mensaje y por el `watchdogLevel` (a los 8 s y a los 25 s), así que
 * ese día no llegó a los 45 s ni una sola vez. Este tracker no mira el estado:
 * lo alimentan las llamadas al SDK y un reloj.
 *
 * 🔴 **Esto NO cancela nada, NO reintenta nada y NO impone timeouts.** Sólo mide y
 * describe. Poner un `withTimeout` sobre `SaleIcc`/`StartEmvTrans`/`CompleteEmvTrans`
 * está VETADO por Blumon (doble cobro): la autorización sigue viva hasta que el SDK
 * responda, porque si el procesador aprobó y nosotros abandonáramos habría dinero
 * movido que la app no conoce.
 *
 * 🔑 **El reloj es MONOTÓNICO en producción** (`SystemClock.elapsedRealtime`), no
 * `System.currentTimeMillis()` y no un acumulador de `delay`. Las dos alternativas
 * mienten justo en el caso que queremos medir: el reloj de pared salta con NTP, y un
 * acumulador de `delay` subestima el atasco si el kernel EMV bloquea el hilo — que es
 * exactamente la hipótesis del incidente.
 *
 * 🔒 **Privacidad por construcción**: la API sólo acepta enums, `Boolean` e `Int`.
 * No hay ningún parámetro de texto libre, así que un PIN, un PAN o un track2 no
 * tienen por dónde entrar. `PaymentViewModelTest` lo fija.
 *
 * Puro: sin Compose, sin Firebase, sin Android. Testeable en JVM.
 * Sincronizado porque lo tocan tres hilos: el flujo de pago (`Dispatchers.IO`),
 * los callbacks del SDK (Main) y el propio observador (`Dispatchers.Default`).
 */
enum class PaymentSdkPhase(val thresholdMs: Long) {
    /** Configura el kernel EMV. No espera a nadie: si tarda, algo está mal. */
    PRE_TRANS(30_000),

    /** Espera HUMANA a que inserten o acerquen la tarjeta — umbral holgado a propósito. */
    DETECT_CARD(120_000),

    /** Chip + PIN, todo LOCAL (kernel EMV + teclado del PED). Aquí se trabó la PAX. */
    START_EMV_TRANS(60_000),

    /** Equivalente contactless de la anterior. */
    START_CTLS_TRANS(60_000),

    /** Extracción de los 23 tags del chip. Local y rápida. */
    GET_EMV_TAG_LIST(30_000),

    /** SaleIcc / SaleCtls: el único paso que sale a la red y al banco. */
    ONLINE_AUTH(75_000),

    /** 🔴 Post-autorización: el dinero YA se movió. Sólo falta cerrar con el chip. */
    COMPLETE_EMV_TRANS(45_000),
}

enum class PhaseOutcome { OK, FAILED, CANCELLED }

/** Los tres momentos en que el SDK nos pide algo y se queda esperando la respuesta. */
enum class PaymentSdkCallback { APP_SELECTION, CONFIRM_CARD_READ, PIN_ENTRY }

/** Lo que la pantalla pinta: segundos y un mensaje que depende SÓLO de la fase. */
data class ProcessingClock(val elapsedSeconds: Int, val message: String)

data class PaymentPhaseSnapshot(
    val phase: PaymentSdkPhase,
    val elapsedInPhaseMs: Long,
    val elapsedInAttemptMs: Long,
) {
    fun toProcessingClock(): ProcessingClock = ProcessingClock(
        elapsedSeconds = (elapsedInPhaseMs / 1_000L).toInt(),
        message = mensajeDeFase(phase),
    )
}

data class PaymentStallReport(
    val phase: PaymentSdkPhase,
    val attemptId: String?,
    val flowOrigin: String,
    val elapsedInPhaseSeconds: Int,
    val elapsedInAttemptSeconds: Int,
    /** `DETECT_CARD:4s=OK>START_EMV_TRANS:60s…` — entrada y salida de cada llamada. */
    val trace: String,
    /** `APP_SELECTION(n=2)?>APP_SELECTION!ok(0)>PIN_ENTRY?` */
    val callbackTrace: String,
    /** Lo último que el SDK PIDIÓ. */
    val lastCallback: String?,
    /** Lo que le CONTESTAMOS, o `null` si seguimos debiéndole la respuesta. */
    val lastCallbackResponse: String?,
    val message: String,
)

/**
 * 🔴 El mensaje depende SÓLO de la fase, **nunca del tiempo transcurrido**.
 *
 * Es la regla de dinero del repo: que algo tarde no es evidencia de que no hubo
 * cargo. Mientras la autorización sigue viva, decirle al cajero «no se cobró»
 * lo invita a cobrar dos veces. Por eso aquí no hay una sola rama que mire el
 * reloj, y `PaymentViewModelTest` afirma que ningún mensaje contiene «no se
 * cobró», «canceló», «falló», «rechazó» ni «error».
 */
fun mensajeDeFase(phase: PaymentSdkPhase): String = when (phase) {
    PaymentSdkPhase.PRE_TRANS,
    PaymentSdkPhase.DETECT_CARD,
    PaymentSdkPhase.START_EMV_TRANS,
    PaymentSdkPhase.START_CTLS_TRANS,
    PaymentSdkPhase.GET_EMV_TAG_LIST ->
        "Esperando la tarjeta o el teclado"

    PaymentSdkPhase.ONLINE_AUTH ->
        "Autorizando con el banco. No vuelvas a cobrar"

    // El banco ya aprobó: aquí sólo se cierra la conversación con el chip.
    PaymentSdkPhase.COMPLETE_EMV_TRANS ->
        "Pago aprobado. Guardando el comprobante, no vuelvas a cobrar"
}

class PaymentPhaseTracker(private val clock: () -> Long) {

    companion object {
        /** Marca la fase que quedó ABIERTA: es la sospechosa del atasco. */
        const val OPEN_PHASE_MARK = "…"

        private const val MAX_PHASES_IN_TRACE = 12
        private const val MAX_CALLBACKS_IN_TRACE = 12
    }

    private var attemptId: String? = null
    private var flowOrigin: String = "UNKNOWN"
    private var attemptActive: Boolean = false
    private var attemptStartedAt: Long = 0L

    private var openPhase: PaymentSdkPhase? = null
    private var openPhaseStartedAt: Long = 0L

    private val phaseTrace = ArrayDeque<String>()
    private val callbackTrace = ArrayDeque<String>()
    private var lastCallback: String? = null
    private var lastCallbackResponse: String? = null

    /** Tope de UN evento por intento — compartido con el detector de la pantalla. */
    private var stallReported: Boolean = false

    @Synchronized
    fun beginAttempt(attemptId: String?, flowOrigin: String) {
        this.attemptId = attemptId
        this.flowOrigin = flowOrigin
        attemptActive = true
        attemptStartedAt = clock()
        openPhase = null
        openPhaseStartedAt = attemptStartedAt
        phaseTrace.clear()
        callbackTrace.clear()
        lastCallback = null
        lastCallbackResponse = null
        stallReported = false
    }

    @Synchronized
    fun enterPhase(phase: PaymentSdkPhase) {
        if (!attemptActive) return
        // Una fase que sigue abierta al empezar otra se cierra con desenlace desconocido
        // en vez de desaparecer: la traza no puede perder un tramo.
        openPhase?.let { anterior -> pushPhase(anterior, outcome = null) }
        openPhase = phase
        openPhaseStartedAt = clock()
    }

    @Synchronized
    fun exitPhase(phase: PaymentSdkPhase, outcome: PhaseOutcome) {
        if (!attemptActive) return
        // 🔴 Un desenlace tardío de una llamada ANTERIOR no puede borrar la fase en
        // curso (pasa de verdad: el reintento chip-only cierra su DetectCard cuando
        // ya vamos en la autorización).
        if (openPhase != phase) return
        pushPhase(phase, outcome)
        openPhase = null
    }

    @Synchronized
    fun noteCallbackRequested(callback: PaymentSdkCallback, count: Int? = null) {
        if (!attemptActive) return
        val etiqueta = if (count != null) "${callback.name}(n=$count)" else callback.name
        lastCallback = etiqueta
        // Le debemos la respuesta hasta que noteCallbackResponded diga lo contrario.
        lastCallbackResponse = null
        push(callbackTrace, "$etiqueta?", MAX_CALLBACKS_IN_TRACE)
    }

    @Synchronized
    fun noteCallbackResponded(callback: PaymentSdkCallback, ok: Boolean, code: Int? = null) {
        if (!attemptActive) return
        val desenlace = buildString {
            append(if (ok) "ok" else "err")
            if (code != null) append("($code)")
        }
        lastCallbackResponse = desenlace
        push(callbackTrace, "${callback.name}!$desenlace", MAX_CALLBACKS_IN_TRACE)
    }

    @Synchronized
    fun endAttempt() {
        openPhase?.let { pushPhase(it, outcome = null) }
        openPhase = null
        attemptActive = false
    }

    @Synchronized
    fun snapshot(): PaymentPhaseSnapshot? {
        val fase = openPhase ?: return null
        if (!attemptActive) return null
        val ahora = clock()
        return PaymentPhaseSnapshot(
            phase = fase,
            elapsedInPhaseMs = ahora - openPhaseStartedAt,
            elapsedInAttemptMs = ahora - attemptStartedAt,
        )
    }

    /**
     * Devuelve un reporte SÓLO la primera vez que una fase excede su umbral en este
     * intento. Consume la compuerta compartida, así que la pantalla ya no puede
     * emitir un segundo evento por el mismo cobro.
     */
    @Synchronized
    fun evaluateStall(): PaymentStallReport? {
        if (stallReported) return null
        val fase = openPhase ?: return null
        if (!attemptActive) return null
        val ahora = clock()
        val enFase = ahora - openPhaseStartedAt
        if (enFase < fase.thresholdMs) return null

        stallReported = true
        return PaymentStallReport(
            phase = fase,
            attemptId = attemptId,
            flowOrigin = flowOrigin,
            elapsedInPhaseSeconds = (enFase / 1_000L).toInt(),
            elapsedInAttemptSeconds = ((ahora - attemptStartedAt) / 1_000L).toInt(),
            trace = traceConFaseAbierta(fase, enFase),
            callbackTrace = callbackTrace.joinToString(">"),
            lastCallback = lastCallback,
            lastCallbackResponse = lastCallbackResponse,
            message = mensajeDeFase(fase),
        )
    }

    /**
     * Compuerta compartida con el detector de la pantalla
     * (`PaymentViewModel.reportProcessingTimeoutIfNeeded`). Devuelve `true` una sola
     * vez por intento: quien la tome es el único que reporta. Sin esto, los dos
     * detectores mandarían dos eventos por el mismo atasco.
     */
    @Synchronized
    fun claimStallReport(): Boolean {
        if (stallReported) return false
        stallReported = true
        return true
    }

    // ── interno ──────────────────────────────────────────────────────────────

    private fun pushPhase(phase: PaymentSdkPhase, outcome: PhaseOutcome?) {
        val segundos = (clock() - openPhaseStartedAt) / 1_000L
        val desenlace = outcome?.name ?: "?"
        push(phaseTrace, "${phase.name}:${segundos}s=$desenlace", MAX_PHASES_IN_TRACE)
    }

    private fun traceConFaseAbierta(fase: PaymentSdkPhase, enFaseMs: Long): String {
        val abierta = "${fase.name}:${enFaseMs / 1_000L}s$OPEN_PHASE_MARK"
        return (phaseTrace + abierta).joinToString(">")
    }

    private fun push(cola: ArrayDeque<String>, valor: String, tope: Int) {
        cola.addLast(valor)
        while (cola.size > tope) cola.removeFirst()
    }
}

/**
 * Cada cuánto mira el observador. 1 s porque es también el latido del reloj
 * informativo que ve el cajero; el coste es un `delay` por segundo mientras hay
 * un cobro en vuelo, y el job se cancela en cuanto el cobro termina.
 */
const val PHASE_OBSERVER_TICK_MS = 1_000L
