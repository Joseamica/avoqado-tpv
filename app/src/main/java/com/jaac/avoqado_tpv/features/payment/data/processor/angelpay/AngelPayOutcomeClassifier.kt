package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

/**
 * Los TRES desenlaces posibles de un cobro con AngelPay.
 *
 * 🔴 El defecto que esto existe para cerrar: la app sólo distinguía DOS —"aprobado" y
 * "todo lo demás"—, así que un `U101` («Tiempo de espera agotado») terminaba en la misma
 * rama que un `G500` («Transacción rechazada por el gateway»): pantalla de error con
 * botón Reintentar, fila DESCARTADA en la libreta y `failed` al POS. Las tres cosas le
 * dicen al cajero «no se cobró» sobre una venta que **quizá sí se cobró**, y la acción
 * que invitan es exactamente la que cuesta dinero: volver a cobrar.
 */
enum class DesenlaceDelCobro {
    /** El procesador autorizó. El dinero se movió. */
    APROBADO,

    /** Hay veredicto y dice que NO: el dinero no se movió. Reintentar es seguro. */
    RECHAZADO_CONFIRMADO,

    /**
     * El SDK volvió SIN veredicto del procesador. No se sabe si el dinero se movió,
     * y por eso no se puede ni cobrar de nuevo ni declarar la venta perdida: hay que
     * VERIFICAR (ver [AngelPayChargeVerifier]).
     */
    INCIERTO,
}

/**
 * Clasifica el resultado del SDK de AngelPay en uno de los tres [DesenlaceDelCobro].
 *
 * Función pura y sin tipos del SDK a propósito: el AAR es `compileOnly` en las variantes
 * PAX y la regla que decide sobre dinero tiene que poder ejercitarse sin él (mismo criterio
 * que `AngelPayErrorMapper`, que trabaja sobre `CallResultData` en vez de la clase del SDK).
 *
 * **La pregunta que contesta es una sola: ¿contestó el procesador?** No «¿falló?».
 *
 * Los códigos vienen del catálogo `AppErrorCatalog$Code` **leído del bytecode del AAR
 * v1.0.18** (`javap`, 2026-09-08), no de memoria — con su `status`, su `category` y su
 * política de reintento. Los cinco de [CODIGOS_SIN_VEREDICTO] son los que describen una
 * salida en la que la autorización pudo haber llegado al emisor.
 *
 * 🔴 **Con el SDK 1.0.19 hay una pregunta ANTERIOR: ¿salió la petición al host?** El AAR 1.0.19 la contesta él mismo
 * con `PaymentResult.authorizationAttempted` (en su orquestador `b0.f0.m`: nace `false` en cada cobro y sube a `true`
 * pegado al envío al gateway, en EMV y en banda). [decidirSegunElSdk119] la aplica ANTES de [clasificar] y sólo puede
 * CONVERTIR un resultado en «no se cobró» cierto ([DecisionDelSdk.SIN_AUTORIZACION]) o forzarlo a incierto; cuando no
 * aplica, manda [clasificar], que no cambió. Por eso un `U101`/`E618`/`U100` sigue siendo INCIERTO para [clasificar]
 * (y para cualquier otra versión del SDK): lo que lo vuelve cierto es la regla, no el código.
 */
object AngelPayOutcomeClassifier {

    /**
     * Códigos del catálogo de AngelPay que NO afirman un veredicto del procesador.
     * Texto literal del AAR v1.0.18 entre paréntesis:
     *
     *  - `U101` («Tiempo de espera agotado») — el vendor lo marca `CANCELLED`/`USER`, como si
     *    lo hubiera cancelado una persona. No es lo mismo: **el tiempo se agotó esperando**,
     *    y quien esperaba era el aparato, no el cajero. (Con el SDK 1.0.19, un `U101` que el propio SDK arma con
     *    `authorizationAttempted=false` y NUESTRA referencia sí es «no se cobró»: lo decide [decidirSegunElSdk119],
     *    no esta lista.)
     *  - `N402` («Timeout contra el gateway») — la petición salió y no volvió respuesta.
     *  - `G502` («Se requiere reversal») — pedir un reversal sólo tiene sentido si la
     *    autorización pudo alcanzar al emisor.
     *  - `G505` («Resultado no concluyente, verifique el historial de transacciones») — el
     *    propio vendor pide verificar; tratarlo como rechazo es hacer lo contrario.
     *  - `I999` («Error desconocido»).
     *
     * 🔴 Es una lista EXPLÍCITA, no un rango ni una categoría. Un `N400` («Sin conexión a
     * internet») es de la misma familia `NETWORK` y **no entra**: sin red la petición nunca
     * salió. Sobre-atrapar aquí bloquea ventas buenas; el criterio es la evidencia del
     * catálogo, código por código.
     */
    val CODIGOS_SIN_VEREDICTO = setOf("U101", "N402", "G502", "G505", "I999")

    /**
     * 🔴 La ÚNICA tabla de rechazos confirmados (P2-13, auditoría del 11-sep). El ViewModel tenía una
     * lista paralela —`{G500, G504, E605, E606}`— para decidir la EVIDENCIA, así que un rechazo que sólo
     * reconocía ésta (D308, C208, E608…) viajaba al servidor como `PRE_AUTHORIZATION` —«ninguna ejecución
     * capaz de autorizar empezó»— aunque el SDK ya se hubiera lanzado. Dos tablas, dos verdades.
     *
     * Explicit financial refusals, or request/session validation before a sale can run. A retry hint,
     * cancellation status, printing error or known catalogue membership does not prove that
     * authorization never happened.
     */
    internal val CODIGOS_RECHAZO_CONFIRMADO = setOf(
        "G500", "G504", "E605", "E606",
        "C200", "C201", "C202", "C203", "C204", "C206", "C207", "C208", "C209", "C210", "C211", "C212",
        "D302", "D303", "D304", "D305", "D306", "D307", "D308", "N400",
        "E601", "E602", "E604", "E608", "E610", "E613", "E614", "E615", "E619", "E621", "E622", "E623", "E624", "E625",
    )
    internal val CODIGOS_RECHAZO_EMISOR = setOf("05", "14", "41", "43", "51", "54", "55", "57", "58", "61", "62", "65", "1A")

    /**
     * Evidencia que viaja al servidor con un [DesenlaceDelCobro.RECHAZADO_CONFIRMADO] que llegó como
     * RESULTADO del SDK o de la app de AngelPay.
     *
     * Siempre `PROCESSOR_DECLINED`, y eso ES el arreglo (P2-13): la lista paralela del ViewModel decidía esto código
     * por código y mandaba como PRE_AUTHORIZATION rechazos que esta tabla sí reconoce (D308, C208, E608…). Qué es un
     * rechazo confirmado lo decide [CODIGOS_RECHAZO_CONFIRMADO]; con qué evidencia viaja, esta constante.
     *
     * ⚠️ Corrección del 18-sep: el argumento de antes decía «si hay resultado del procesador, el SDK se lanzó, así que
     * `PRE_AUTHORIZATION` sería falso». **Lanzar el SDK ≠ mandar el cobro al host.** Con el SDK 1.0.19 el propio
     * proveedor pone la frontera en el ENVÍO (`authorizationAttempted`): un resultado que el SDK armó con
     * `authorizationAttempted=false` y NUESTRA referencia acredita que la petición no salió, y viaja como
     * [EVIDENCIA_SIN_AUTORIZACION] aunque el SDK sí se haya lanzado ([decidirSegunElSdk119]). Esta constante sigue
     * siendo la de un RECHAZO del procesador; no aplica a esos resultados.
     */
    const val EVIDENCIA_RECHAZO_CONFIRMADO = "PROCESSOR_DECLINED"

    /**
     * Evidencia de [DecisionDelSdk.SIN_AUTORIZACION]: la que ya acepta el servidor (`terminal-payment.service.ts`,
     * lista blanca) como «ninguna ejecución capaz de autorizar salió». La escribe la BANDEJA a partir de la libreta
     * (`RemotePaymentRequestDao.resolverDesenlaceNegativo`: intento DESCARTADA sin `host_approved = 0`), no la pantalla.
     */
    const val EVIDENCIA_SIN_AUTORIZACION = "PRE_AUTHORIZATION"

    /**
     * El ÚNICO AAR de AngelPay auditado para [decidirSegunElSdk119] (`AngelPaySDK.version()`; SHA-256 del archivo
     * fijado en `AngelPaySdk119ReglaTest`). Con cualquier otra versión la regla NO corre y manda [clasificar] tal cual:
     * un SDK nuevo puede mover la frontera del envío, y eso se re-audita en el binario antes de volver a confiar.
     */
    const val VERSION_SDK_AUDITADA = "1.0.19"

    /**
     * 🔴 La regla del SDK 1.0.19 (diseño `diseno-nexgo-sdk-1.0.19.md` §2.1 + decisiones del founder del 18-sep).
     *
     * Contesta «¿el SDK acreditó que el cobro NO salió al banco?». En orden:
     *
     *  0. **Candado de versión.** Otra versión ⇒ [DecisionDelSdk.REGLAS_DE_HOY]. (No existe una «huella» del 1.0.18
     *     en esta base: el 1.0.18 simplemente sigue con [clasificar].)
     *  1. **Aprobado** ⇒ REGLAS_DE_HOY: el dinero se movió y [clasificar] lo dice primero. (Aprobado con
     *     `authorizationAttempted=false` es una contradicción que el ViewModel grita, pero manda el dinero.)
     *  2. **Forma de sesión expirada** (D308 o «registrar la terminal antes del cobro») ⇒ REGLAS_DE_HOY: ya es un
     *     rechazo cierto con relanzamiento automático ([AngelPayErrorMapper]), y el candado de registro del SDK la arma
     *     sin referencia.
     *  3. **`authorizationAttempted = true`** ⇒ REGLAS_DE_HOY. ⬜ Pendiente declarado (decisión del founder, 18-sep):
     *     NO se endurece aquí un `G500`/`N400` sin respuesta del host (§2.2 4a); `attempted=true` se clasifica como hoy.
     *  4. **La referencia no prueba que el resultado sea de ESTE intento** —ninguna (null o en blanco), la de OTRO intento,
     *     o sin intento propio con qué compararla— ⇒ [DecisionDelSdk.INCIERTO] (diseño §2.1, 3a; Codex r1, P1-1). Los
     *     resultados de respaldo del contrato (`Cancelled`, `Error al parsear resultado`, `Unknown error`, validación) y la
     *     validación al abrir la pantalla del SDK (`C200`) traen `false` POR DEFECTO, sin que nadie lo haya decidido: nuestra
     *     referencia sólo la pone quien conocía el cobro (el orquestador, su `catch` y la pantalla del SDK). Sin ella nada lo
     *     correlaciona con este cobro, y un resultado sin correlación nunca es un negativo cierto — aunque la tabla de hoy lo
     *     diera por rechazo (el `C200`) o fuera el `E608`. La ÚNICA excepción sin referencia es la forma de sesión expirada
     *     (paso 2, ya evaluada).
     *  5. **`E608`** (límite sin contacto) con nuestra referencia ⇒ REGLAS_DE_HOY, a propósito (decisión del 18-sep): hoy
     *     es un rechazo con «Reintentar» en la MISMA venta —el cajero inserta el chip— y eso no cambia.
     *  6. Con `attempted = false` y nuestra referencia:
     *     a. Cualquier campo que sólo escribe el HOST (autorización, código del emisor, descripción, marca, BIN,
     *        terminación, referencia del gateway, afiliación, folio, fecha, banco, tipo) o `status = APPROVED` ⇒
     *        [DecisionDelSdk.CONTRADICCION]: en el 1.0.19 ningún productor con `false` los trae [MEDIDO-bin].
     *     b. Cualquier campo de TARJETA LEÍDA (modo, AID, ARQC, etiqueta, TVR, TSI, autenticación) ⇒ INCIERTO: se
     *        escriben justo antes de ir en línea y el único `return` entre ahí y el envío es el `E608` (ya fuera).
     *     c. Sin código de catálogo ⇒ INCIERTO: todo productor del 1.0.19 con nuestra referencia trae uno; sin él es un
     *        productor que el binario no tiene.
     *     d. En otro caso ⇒ [DecisionDelSdk.SIN_AUTORIZACION]: «no se cobró», cierto. Incluye el `U100` del botón
     *        Cancelar y de la tecla atrás (**decisión U del founder, 18-sep: confiar en el SDK**; medido en la N86, llega
     *        con `operationType = null`, así que ese campo NO se exige).
     *
     * Por qué 6d es cierto: un resultado que arma el orquestador o su `catch` con `m = false` significa que el único
     * hilo capaz de mandar ya terminó sin mandar; `m` sube de forma síncrona y pegada al envío, en el hilo principal; y
     * AngelPay define así su propio campo. Esto enmienda la fila `:142` de `.claude/rules/cobro-remoto-pos-a-tpv.md`
     * PARA AngelPay 1.0.19: el proveedor pone la frontera en el envío, no en el kernel.
     *
     * ⬜ **Sin interruptor remoto** en esta entrega (decisión del founder, 18-sep; diseño §2.3 punto 7 fuera): la salida
     * es el candado de versión —con otro AAR la regla no corre— o un APK nuevo.
     *
     * @param attemptId el intento que ESTE ViewModel lanzó (`currentPaymentAttemptId`); null ⇒ no hay con qué comparar.
     */
    fun decidirSegunElSdk119(r: ResultadoDelSdk, attemptId: String?): DecisionDelSdk {
        // 0. Candado de versión: la frontera del envío se auditó en ESTE binario, no en el que venga después.
        if (r.versionSdk?.trim() != VERSION_SDK_AUDITADA) return DecisionDelSdk.REGLAS_DE_HOY
        // 1. El dinero manda: un aprobado lo clasifica [clasificar] antes que nada.
        if (r.approved) return DecisionDelSdk.REGLAS_DE_HOY
        val codigo = r.codigoSdk?.trim()?.uppercase()
        // 2. La sesión expirada ya es un rechazo cierto con relanzamiento automático: no se le cambia el camino.
        if (AngelPayErrorMapper.isAuthError(codigo) || AngelPayErrorMapper.isPreChargeRegisterFailure(r.message)) {
            return DecisionDelSdk.REGLAS_DE_HOY
        }
        // 3. La petición salió (o estaba por salir) al host: como hoy (4a queda pendiente).
        if (r.authorizationAttempted) return DecisionDelSdk.REGLAS_DE_HOY
        // 4. Sólo NUESTRA referencia correlaciona el resultado con ESTE intento. Sin ella (null o en blanco), con la de otro
        //    intento o sin intento propio con qué compararla ⇒ INCIERTO: un resultado sin correlación nunca es un negativo
        //    cierto, tampoco el C200 al abrir la pantalla ni un E608 (Codex r1, P1-1). La única excepción sin referencia es
        //    la forma de sesión expirada (paso 2, ya evaluada).
        val referencia = r.integratorReference
        if (referencia.isNullOrBlank() || attemptId.isNullOrBlank() || referencia != attemptId) return DecisionDelSdk.INCIERTO
        // 5. E608 con nuestra referencia: rechazo con «Reintentar» en la MISMA venta, como hoy.
        if (codigo == CODIGO_LIMITE_SIN_CONTACTO) return DecisionDelSdk.REGLAS_DE_HOY
        // 6a. `false` con algo que sólo escribe el host: un productor que el binario no tiene.
        if (r.camposDelHost.values.any { it != null } || r.status?.trim()?.uppercase() == "APPROVED") {
            return DecisionDelSdk.CONTRADICCION
        }
        // 6b. La tarjeta ya se leyó: el único `return` entre esos campos y el envío es el E608 (ya fuera).
        if (r.camposDeTarjetaLeida.values.any { it != null }) return DecisionDelSdk.INCIERTO
        // 6c. Todo productor del 1.0.19 con nuestra referencia trae código de catálogo.
        if (codigo.isNullOrBlank()) return DecisionDelSdk.INCIERTO
        // 6d. «No se cobró», cierto.
        return DecisionDelSdk.SIN_AUTORIZACION
    }

    /** `E608` («Límite contactless excedido»): hoy es un rechazo con «Reintentar» en la misma venta, y eso no cambia. */
    private const val CODIGO_LIMITE_SIN_CONTACTO = "E608"

    /**
     * @param aprobado `PaymentResult.approved`.
     * @param status nombre de `PaymentResult.Status` (APPROVED/DECLINED/ERROR/CANCELLED/TIMEOUT).
     * @param codigoSdk `callResult.code` — el código del catálogo AngelPay (p.ej. "G500").
     * @param codigoGateway `PaymentResult.code` — el código del EMISOR (p.ej. "05", "00").
     */
    fun clasificar(
        aprobado: Boolean,
        status: String?,
        codigoSdk: String?,
        codigoGateway: String?,
    ): DesenlaceDelCobro {
        // 🔴 Primero y sin excepciones: si el procesador autorizó, el dinero se movió.
        // Ninguna otra señal puede discutir eso — un resultado aprobado que se leyera
        // como incierto dejaría un cobro real sin registrar.
        if (aprobado) return DesenlaceDelCobro.APROBADO

        val estado = status?.trim()?.uppercase()
        if (estado == "TIMEOUT") return DesenlaceDelCobro.INCIERTO
        val catalogo = codigoSdk?.trim()?.uppercase()
        if (!catalogo.isNullOrBlank()) {
            return if (catalogo in CODIGOS_RECHAZO_CONFIRMADO) DesenlaceDelCobro.RECHAZADO_CONFIRMADO
            else DesenlaceDelCobro.INCIERTO
        }
        return if (codigoGateway?.trim()?.uppercase() in CODIGOS_RECHAZO_EMISOR)
            DesenlaceDelCobro.RECHAZADO_CONFIRMADO else DesenlaceDelCobro.INCIERTO
    }
}

/** Lo que dice [AngelPayOutcomeClassifier.decidirSegunElSdk119] sobre un resultado del SDK. */
enum class DecisionDelSdk {
    /** La regla no dice nada: manda [AngelPayOutcomeClassifier.clasificar], exactamente como antes del 1.0.19. */
    REGLAS_DE_HOY,

    /** El SDK acreditó que la petición NO salió al host: «no se cobró», cierto e inmediato. */
    SIN_AUTORIZACION,

    /** `authorizationAttempted=false` que no prueba nada (otra referencia, datos de tarjeta leída): incierto. */
    INCIERTO,

    /** `authorizationAttempted=false` CON datos que sólo escribe el host: algo que el binario no explica. Incierto + 🚨. */
    CONTRADICCION,
}

/**
 * Un `PaymentResult` del SDK reducido a lo que la regla del 1.0.19 necesita — `String?` y `Boolean`, sin tipos del
 * SDK (el AAR es `compileOnly` en las variantes PAX). Lo arma `paraLaReglaDelSdk` desde el resultado REAL.
 *
 * Los campos van con los MISMOS nombres que en `PaymentResult` para que un mapeo cruzado se vea a simple vista.
 */
data class ResultadoDelSdk(
    /** `AngelPaySDK.version()` del AAR que armó el resultado; null si no se pudo leer (⇒ la regla no corre). */
    val versionSdk: String?,
    val approved: Boolean,
    /** `PaymentResult.authorizationAttempted` (1.0.19): el SDK mandó —o estaba por mandar— la petición al host. */
    val authorizationAttempted: Boolean,
    /** Nombre de `PaymentResult.Status`. */
    val status: String?,
    /** `callResult.code` — el código del catálogo de AngelPay («U101», «E618»…). */
    val codigoSdk: String?,
    val message: String? = null,
    val integratorReference: String? = null,
    // ── Campos que sólo escribe una respuesta del HOST (constructor `a(b.d, Boolean, String)` del orquestador) ──
    val authCode: String? = null,
    /** `PaymentResult.code` — el código del EMISOR («05», «00»). */
    val code: String? = null,
    val description: String? = null,
    val cardBrand: String? = null,
    val cardBin: String? = null,
    val cardLast4: String? = null,
    val reference: String? = null,
    val affiliation: String? = null,
    val folio: String? = null,
    val transactionDate: String? = null,
    val issuingBank: String? = null,
    val cardType: String? = null,
    // ── Campos de TARJETA LEÍDA (los escribe el orquestador justo antes de ir en línea, `f0.java:614-671`) ──
    val cardEntryMode: String? = null,
    val aid: String? = null,
    val arqc: String? = null,
    val applicationLabel: String? = null,
    val tvr: String? = null,
    val tsi: String? = null,
    val authentication: String? = null,
) {
    /** Los 12 campos del host, por nombre. Presente = no nulo (un campo en blanco SÍ cuenta: el SDK escribe null). */
    val camposDelHost: Map<String, String?>
        get() = mapOf(
            "authCode" to authCode, "code" to code, "description" to description, "cardBrand" to cardBrand,
            "cardBin" to cardBin, "cardLast4" to cardLast4, "reference" to reference, "affiliation" to affiliation,
            "folio" to folio, "transactionDate" to transactionDate, "issuingBank" to issuingBank, "cardType" to cardType,
        )

    /** Los 7 campos de tarjeta leída, por nombre. */
    val camposDeTarjetaLeida: Map<String, String?>
        get() = mapOf(
            "cardEntryMode" to cardEntryMode, "aid" to aid, "arqc" to arqc, "applicationLabel" to applicationLabel,
            "tvr" to tvr, "tsi" to tsi, "authentication" to authentication,
        )
}
