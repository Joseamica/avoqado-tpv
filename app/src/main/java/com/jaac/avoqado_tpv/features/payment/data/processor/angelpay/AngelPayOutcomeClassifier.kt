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
 */
object AngelPayOutcomeClassifier {

    /**
     * Códigos del catálogo de AngelPay que NO afirman un veredicto del procesador.
     * Texto literal del AAR v1.0.18 entre paréntesis:
     *
     *  - `U101` («Tiempo de espera agotado») — el vendor lo marca `CANCELLED`/`USER`, como si
     *    lo hubiera cancelado una persona. No es lo mismo: **el tiempo se agotó esperando**,
     *    y quien esperaba era el aparato, no el cajero.
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
     * Siempre `PROCESSOR_DECLINED`, y eso ES el arreglo (P2-13): si hay resultado del procesador, el SDK
     * se lanzó, así que `PRE_AUTHORIZATION` —«ninguna ejecución capaz de autorizar empezó»— sería falso.
     * La lista paralela del ViewModel decidía esto código por código y mandaba como PRE_AUTHORIZATION
     * rechazos que esta tabla sí reconoce (D308, C208, E608…). Qué es un rechazo confirmado lo decide
     * [CODIGOS_RECHAZO_CONFIRMADO]; con qué evidencia viaja, esta constante.
     */
    const val EVIDENCIA_RECHAZO_CONFIRMADO = "PROCESSOR_DECLINED"

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
