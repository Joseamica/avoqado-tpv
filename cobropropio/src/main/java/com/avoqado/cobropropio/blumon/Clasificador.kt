package com.avoqado.cobropropio.blumon

import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.privacidad.Enmascarar
import com.avoqado.cobropropio.red.Respuesta
import com.avoqado.cobropropio.red.Apariciones
import com.avoqado.cobropropio.red.Escalar
import com.avoqado.cobropropio.red.Segmento
import java.math.BigDecimal
import java.time.Instant

sealed interface DesenlaceDeVenta {
    data class Aprobada(val operationNumber: Long, val autorizacion: String) : DesenlaceDeVenta
    data class Rechazada(val codigo: String, val leyenda: String) : DesenlaceDeVenta
    data object NoSe : DesenlaceDeVenta

    /** ISO 10 (aprobado parcial): la evidencia saneada que trajo, para que la libreta la guarde ANTES de reversar. */
    data class DebeReversar(val operationNumber: Long?, val importeAutorizadoCentavos: Long?) : DesenlaceDeVenta
    data object NoEnviada : DesenlaceDeVenta
}

sealed interface DesenlaceDeCancelacion {
    data class Cancelada(val operationNumber: Long) : DesenlaceDeCancelacion
    data class Rechazada(val codigo: String, val leyenda: String) : DesenlaceDeCancelacion
    data object NoSe : DesenlaceDeCancelacion
    data object NoEnviada : DesenlaceDeCancelacion
}

/** `codigo` va normalizado: del catálogo, de las listas del plan o `DESCONOCIDO`. Nunca un texto crudo del procesador. */
sealed interface ClaseDelReverso {
    data class Confirmado(val hora: Instant) : ClaseDelReverso
    data class Rechazado(val codigo: String, val hora: Instant) : ClaseDelReverso
    data class Reintentar(val codigo: String, val pruebaQueNoEntro: Boolean, val hora: Instant) : ClaseDelReverso
    data object NoEnviado : ClaseDelReverso
}

/**
 * Lo que dice un sobre de Blumon, ya saneado, leído por [Apariciones] (el lector estricto, R1-2, R3-1, R3-8; v5 §3.2). De
 * cada campo que decide se juntan TODAS sus apariciones, a cualquier profundidad. El sobre es contradictorio (`malFormado`)
 * con una clave repetida, con un campo que decide PRESENTE con un tipo o una forma que no corresponde, o con un campo de
 * valor único (`status`, folio, autorización) que aparece también en otra ruta, o con una clave conocida escrita con otra
 * grafía a cualquier profundidad (rondas 3 y 4 · W e Y). Contradictorio nunca da un veredicto
 * definitivo ni guarda evidencia. Ningún campo lleva una racha de 12 o más dígitos.
 */
internal class SobreDeBlumon private constructor(
    /** `status` de la raíz. */
    val status: Boolean?,
    /** Todos los códigos del sobre (`code`, `codeResponse`, `responseCode` en cualquier objeto), normalizados. */
    val codigos: List<String>,
    /** `error.code`, normalizado. */
    val codigoDeError: String?,
    /** `id` de la raíz. */
    val operationNumber: Long?,
    /** `dataResponse.authorization`. */
    val autorizacion: String?,
    /** Todas las `reference`: cada una tiene que ser la del intento. */
    val referencias: List<String>,
    /** Todos los `amount`, en centavos: cada uno tiene que ser el que se coteja. */
    val importesCentavos: List<Long>,
    /** `true` si cualquier `simulation` es `true`; si no, la de `dataResponse` (o `null`). */
    val simulation: Boolean?,
    /** `dataResponse.description`, sólo si es la única `description` del cuerpo. */
    val descripcion: String?,
    /** P1: `error` (raíz) trae algo ([Apariciones.tieneAlgoBajo]). Un sobre así nunca es un éxito definitivo. */
    val conError: Boolean,
    /** P2: `dataResponse` (raíz) trae algo. Un sobre así nunca es un definitivo de error. */
    val conDatos: Boolean,
    /** P2: `error.httpStatusCode` como número de 3 dígitos; `null` si no vino. */
    val httpDelError: Int?,
    /** P2 y ronda 3 · U: `error.httpStatusCode` vino, pero no como número de 3 dígitos (`null`, texto, decimal…): duda. */
    val httpDelErrorRaro: Boolean,
    /** Ronda 3 · V: `error` es plano (nada más que escalares o `null` directamente bajo él). */
    val errorPlano: Boolean,
    /** Ronda 4 · X: bajo `error` sólo vienen claves medidas ([CLAVES_DEL_ERROR]), escritas exactamente así. */
    val errorConClavesMedidas: Boolean,
    val malFormado: Boolean,
) {
    /** Lo que se coteja contra la tarjeta de la lectura. */
    fun textos(): List<String> = codigos + referencias + listOfNotNull(operationNumber?.toString(), autorizacion, descripcion)

    override fun toString() = "SobreDeBlumon(oculto)"

    companion object {
        private val AUTORIZACION = Regex("^[A-Za-z0-9]{1,12}$")
        private val REFERENCIA = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val REFERENCIA_DE_BLUMON = Regex("^[0-9]{12}$")
        private val DESCRIPCION = Regex("^[A-Z0-9_ ]{1,32}$")
        private val RACHA = Regex("[0-9]{12,}")
        private const val FOLIO_MAXIMO = 9_999_999_999L
        private val CAMPOS_DE_CODIGO = listOf("code", "codeResponse", "responseCode")
        /**
         * Las formas crudas del catálogo (`blumon-catalogo-de-rechazos.tsv`): un código de Blumon (`TX_024`, `CRP_001`), un ISO
         * de dos caracteres (`51`, `05`, `N0`, `1A`) o el ISO numérico con `.0` del catálogo (`51.0`, `5.0`, `1001.0`). Nada más:
         * ni espacios, ni minúsculas, ni ceros a la izquierda de más, ni dígitos que no sean ASCII.
         */
        private val CODIGO_CRUDO = Regex("^(?:[A-Z]{2,4}_[0-9]{3}|[0-9A-Z]{2}|(?:0|[1-9][0-9]{0,3})\\.0)$")
        private val FOLIO = Regex("^[1-9][0-9]{0,9}$")
        private val HTTP = Regex("^[1-5][0-9]{2}$")
        /**
         * Ronda 4 · Y: los nombres que este clasificador busca por nombre exacto, sacados de todas sus búsquedas en el lector:
         * `de`, `deSalvo`, `en`, `unico`, `unica`, `soloEn`, `tieneAlgoBajo`, `contenedorDirectoBajo`, `segmentosDirectosBajo`,
         * `CAMPOS_DE_CODIGO` y `pideLlavesNuevas`. Uno nuevo se agrega aquí.
         */
        private val NOMBRES_QUE_SE_BUSCAN = CAMPOS_DE_CODIGO + listOf(
            "status", "error", "dataResponse", "httpStatusCode", "id", "authorization", "reference", "batch", "amount",
            "amountInfo", "total", "simulation", "description", "renewKey",
        )
        /** Ronda 4 · X: las cuatro claves del bloque `error` medido (el `TX_024` del sandbox, 20-nov). */
        private val CLAVES_DEL_ERROR = setOf("httpStatusCode", "code", "description", "simulation")
        /** Pesos con 0, 1 o 2 decimales, sin signo, sin exponente y sin ceros de más a la izquierda. */
        private val IMPORTE_CRUDO = Regex("^(?:0|[1-9][0-9]{0,9})(?:\\.[0-9]{1,2})?$")

        fun de(a: Apariciones): SobreDeBlumon {
            var mal = a.ambigua

            /** Texto o número como texto; otro tipo presente ensucia; `null` de JSON cuenta como ausente. */
            fun texto(e: Escalar?): String? = when (e) {
                null, Escalar.Nulo -> null
                is Escalar.Texto -> e.v
                is Escalar.Numero -> e.crudo
                else -> { mal = true; null }
            }

            /**
             * Un campo de valor único, en su ruta exacta desde la raíz; si su clave aparece en otra ruta, el sobre es
             * contradictorio. El valor se valida CRUDO (R5-2): sin recortar ni convertir antes de revisar su forma.
             */
            fun <T> unico(forma: (String) -> T?, vararg ruta: String): T? {
                if (!a.soloEn(*ruta)) { mal = true; return null }
                val v = texto(a.en(*ruta)) ?: return null
                return forma(v) ?: run { mal = true; null }
            }

            fun conForma(v: String, f: Regex) = v.takeIf { f.matches(it) && !RACHA.containsMatchIn(it) }

            /** R5-2: la forma cruda del catálogo primero; sólo entonces se normaliza (nunca vuelve válido lo inválido). */
            fun codigo(e: Escalar): String? {
                val crudo = texto(e) ?: return null
                if (!CODIGO_CRUDO.matches(crudo)) { mal = true; return null }
                return normalizarCodigo(crudo) ?: run { mal = true; null }
            }

            fun centavos(e: Escalar): Long? {
                val v = texto(e) ?: return null
                if (!IMPORTE_CRUDO.matches(v)) { mal = true; return null }
                return BigDecimal(v).movePointRight(2).longValueExact()
            }

            if (!a.soloEn("status")) mal = true
            // Rondas 3 y 4 · W e Y: una clave que este lector busca por nombre exacto, escrita con otra grafía a CUALQUIER
            // profundidad (`Error`, `dataResponse.Batch`, `error.Code`), ensucia el sobre.
            if (a.claves().any { k -> NOMBRES_QUE_SE_BUSCAN.any { it.equals(k, ignoreCase = true) && it != k } }) mal = true
            val status = when (val e = a.en("status")) {
                null, Escalar.Nulo -> null
                is Escalar.Booleano -> e.v
                else -> { mal = true; null }
            }
            val codigoDeError = a.en("error", "code")?.let(::codigo)
            val codigos = CAMPOS_DE_CODIGO.flatMap { k -> a.de(k).mapNotNull(::codigo) }
            val folio = unico({ v -> v.takeIf(FOLIO::matches)?.toLong()?.takeIf { it <= FOLIO_MAXIMO } }, "id")
            val autorizacion = unico({ conForma(it, AUTORIZACION) }, "dataResponse", "authorization")
            // T8 (sandbox, 20-nov): Blumon contesta en `dataResponse.reference` SU referencia (texto de 12 dígitos, como el RRN
            // de ISO 8583), no la nuestra. Sólo con esa forma exacta y en ese lugar no se coteja ni se guarda; cualquier otra
            // `reference` (otra forma u otro lugar) se lee como la nuestra, igual que antes.
            val deBlumon = (a.en("dataResponse", "reference") as? Escalar.Texto)?.v?.let(REFERENCIA_DE_BLUMON::matches) == true
            val referencias = (if (deBlumon) a.deSalvo("reference", "dataResponse", "reference") else a.de("reference"))
                .mapNotNull { e -> texto(e)?.let { conForma(it, REFERENCIA) ?: run { mal = true; null } } } +
                // T8 arreglos 1 (C): el sandbox repite nuestra referencia en `dataResponse.batch`. Sólo con forma de
                // `AttemptId` cuenta como referencia (y tiene que ser la nuestra); otra forma, quizá un lote, no cambia nada.
                listOfNotNull((a.en("dataResponse", "batch") as? Escalar.Texto)?.v?.takeIf(AttemptId.REGEX::matches))
            // T8 arreglos 1 (B): el sobre real no trae `amount`; el cobro viene en `dataResponse.amountInfo.total`.
            val importes = (a.de("amount") + listOfNotNull(a.en("dataResponse", "amountInfo", "total"))).mapNotNull(::centavos)
            // `simulation` en CUALQUIER lugar (r2 familia C): un `true` gana; una que no es booleana (también `null`) ensucia.
            val simulaciones = a.de("simulation").mapNotNull { e -> (e as? Escalar.Booleano)?.v ?: run { mal = true; null } }
            val simulation = if (true in simulaciones) true else (a.en("dataResponse", "simulation") as? Escalar.Booleano)?.v
            // La descripción sólo se compara con `REVERSADA`: una sin forma o repetida no confirma, pero no ensucia el sobre.
            // Cruda (R5-2): sin recortar ni pasar a mayúsculas.
            val descripcion = (a.unica("dataResponse", "description") as? Escalar.Texto)?.v
                ?.takeIf { DESCRIPCION.matches(it) && !RACHA.containsMatchIn(it) }

            val crudoHttp = a.en("error", "httpStatusCode")
            val httpDelError = (crudoHttp as? Escalar.Numero)?.crudo?.takeIf(HTTP::matches)?.toInt()
            // U: si la clave viene, tiene que ser un número de 3 dígitos; también `null` es duda.
            val httpDelErrorRaro = crudoHttp != null && httpDelError == null

            return SobreDeBlumon(
                status = status,
                codigos = codigos,
                codigoDeError = codigoDeError,
                operationNumber = folio,
                autorizacion = autorizacion,
                referencias = referencias,
                importesCentavos = importes,
                simulation = simulation,
                descripcion = descripcion,
                conError = a.tieneAlgoBajo("error"),
                conDatos = a.tieneAlgoBajo("dataResponse"),
                httpDelError = httpDelError,
                httpDelErrorRaro = httpDelErrorRaro,
                errorPlano = !a.contenedorDirectoBajo("error"),
                errorConClavesMedidas = a.segmentosDirectosBajo("error").all { it is Segmento.Clave && it.k in CLAVES_DEL_ERROR },
                malFormado = mal,
            )
        }
    }
}

/**
 * Clasifica lo que contestó Blumon. Regla general (Global Constraints): primero el transporte, luego se normaliza, un
 * código «no sé» en cualquier campo es duda, un éxito exige 2xx + el sobre de éxito de ESA operación + coherencia, y un
 * rechazo exige 2xx o 4xx de negocio + el sobre de error + un código de la lista de negativas definitivas de ESA
 * operación. Nunca lanza: lo que no cuadra es duda.
 */
object Clasificador {

    /**
     * El contrato congelado del intento. **Todo veredicto definitivo** exige que, si el sobre trae `reference` o importe,
     * sean éstos, y que ningún campo lleve datos de la tarjeta.
     */
    class Esperado(
        val reference: AttemptId,
        val importeCentavos: Long,
        private val pan: String?,
        /** Ruling T8: sólo con `SANDBOX` vale un `simulation=true`. Por defecto, lo estricto. */
        val ambiente: Ambiente = Ambiente.PRODUCCION,
    ) {
        fun contieneDatoDeLaTarjeta(valor: String): Boolean = Enmascarar.contieneDatoDeLaTarjeta(valor, pan)
        override fun toString() = "Esperado(oculto)"
    }

    const val SIN_RESPUESTA = "SIN_RESPUESTA"
    const val DESCONOCIDO = "DESCONOCIDO"
    private const val APROBADA = "00"
    private const val PARCIAL = "10"

    private fun lista(vararg c: String) = c.map { requireNotNull(normalizarCodigo(it)) }.toSet()

    /** Conjunto «no sé» del catálogo (medido el 18-nov): nunca es rechazo y gana sobre un `status=true`. */
    private val NO_SE = lista("68", "9", "94", "90", "91", "92", "96", "R9", "RQ_006", "TX_033", "TX_035")

    private val NEGATIVAS_DE_VENTA = lista(
        "01", "03", "04", "05", "07", "12", "13", "14", "15", "41", "43", "51", "54", "55", "57", "58", "59", "61", "62", "65",
        "75", "N0",
    )
    private val NEGATIVAS_DE_CANCELACION = NEGATIVAS_DE_VENTA + "TX_024"

    /**
     * Ola final · 1 (Codex r21): «cancelada/reversada anteriormente». Un sobre de ERROR no trae el eco de nuestro folio, así
     * que NO confirma el reverso: es duda («pudo entrar») con su código guardado. «No se cobró» y el `REVERSADA` al SERVIDOR
     * salen sólo de un `REVERSADA` de éxito con eco. Se quedan en [CONOCIDOS] sólo para que su código se guarde.
     */
    private val REVERSO_YA_HECHO_SIN_ECO = lista("TX_014", "TX_013")
    private const val REVERSO_RECHAZADO = "TX_007"
    private val REVERSO_NO_ENTRO = lista("TX_008", "TX_009", "RQ_005", "RQ_006")

    private val CONOCIDOS = NO_SE + NEGATIVAS_DE_CANCELACION + REVERSO_YA_HECHO_SIN_ECO + REVERSO_RECHAZADO + REVERSO_NO_ENTRO

    private val NEGOCIO_4XX = setOf(400, 402, 404, 409, 422)
    private const val LEYENDA_GENERICA = "RECHAZADA"

    private fun es2xx(codigo: Int) = codigo in 200..299
    private fun transporteDudoso(codigo: Int) = codigo in setOf(401, 403, 408, 429) || codigo in 500..599

    /**
     * Sin forma rota, sin `simulation=true` (en NINGÚN lugar del sobre: r1 R1-2, r2 familia C; en el sandbox sí, ruling T8), con el `reference` y el
     * importe del intento (si vienen) y sin datos de la tarjeta. Sin esto no hay veredicto definitivo: la venta y la
     * cancelación dan `NoSe`, y el reverso «pudo entrar». Un parcial coteja su importe aparte ([conImporte] = false).
     */
    private fun confiable(s: SobreDeBlumon, e: Esperado, conImporte: Boolean = true) =
        !s.malFormado && simulacionAceptable(s, e) &&
            s.referencias.all { it == e.reference.valor } &&
            (!conImporte || s.importesCentavos.all { it == e.importeCentavos }) &&
            s.textos().none { e.contieneDatoDeLaTarjeta(it) }

    /**
     * Ruling T8 (medido el 20-nov: el sandbox marca `simulation: true` en TODO, también en el aprobado y en `REVERSADA`): un
     * `simulation=true` sólo vale con `Ambiente.SANDBOX`, donde todo es simulación y no hay dinero. En producción, nunca.
     */
    private fun simulacionAceptable(s: SobreDeBlumon, e: Esperado) = s.simulation != true || e.ambiente == Ambiente.SANDBOX

    /**
     * T8 arreglos 2 (R12-1): un sobre de ÉXITO (venta `APROBADA`, parcial `10`, reverso `REVERSADA`) sólo da un definitivo
     * con el ECO de nuestro `AttemptId`: en `dataResponse.batch` o en una `reference` que no sea el folio de Blumon. Sin eco
     * nada lo ata a este intento (podría ser otra operación del mismo importe). `confiable` ya exige que todas sean la nuestra.
     * Falla cerrado: si producción no repite nuestro folio en ningún campo, TODA venta sale `NoSe` y se reversa.
     * Ronda 2 · P1 (éxito puro): además, nada bajo `error` y todos los códigos de éxito. Un solo lugar para todos los éxitos;
     * el preflight, que no lleva eco, usa [exitoPuro].
     */
    private fun exitoAtado(s: SobreDeBlumon, exito: String = APROBADA) = s.referencias.isNotEmpty() && exitoPuro(s, exito)

    /** P1: un sobre de éxito sin nada bajo `error` y con sólo códigos de éxito ([exito]: `00`, o `10` en el parcial). */
    private fun exitoPuro(s: SobreDeBlumon, exito: String = APROBADA) = !s.conError && s.codigos.all { it == exito }

    /**
     * Ronda 2 · P2 (error puro): un definitivo de ERROR exige nada bajo `dataResponse`, un `error` plano (ronda 3 · V: sólo
     * escalares o `null` directamente bajo él) con sólo las claves medidas (ronda 4 · X), y un `error.httpStatusCode` ausente o
     * de negocio (2xx o 4xx de negocio). Un sobre de error impuro cae a la duda de siempre. Un solo lugar para todos los errores.
     */
    private fun errorPuro(s: SobreDeBlumon) =
        !s.conDatos && s.errorPlano && s.errorConClavesMedidas && !s.httpDelErrorRaro && s.httpDelError.let { it == null || es2xx(it) || it in NEGOCIO_4XX }

    /** El éxito de la venta y de la cancelación exige además que `simulation` VENGA (`false`, o `true` en el sandbox). */
    private fun simulacionDeExito(s: SobreDeBlumon, e: Esperado) = s.simulation != null && simulacionAceptable(s, e)

    /**
     * T8 arreglos 1 (D): `dataResponse.renewKey` = `true` (booleano, en su ruta exacta, en un sobre sin claves repetidas) ⇒
     * la siguiente venta renueva llaves. No cambia el desenlace en curso; otra forma se ignora. Medido: siempre `false`.
     */
    fun pideLlavesNuevas(r: Respuesta): Boolean =
        (r as? Respuesta.Http)?.apariciones()?.takeIf { !it.ambigua }?.en("dataResponse", "renewKey").let { it is Escalar.Booleano && it.v }

    fun venta(r: Respuesta, esperado: Esperado, catalogo: CatalogoDeRechazos): DesenlaceDeVenta {
        val http = when (r) {
            Respuesta.NoEnviada -> return DesenlaceDeVenta.NoEnviada // 1
            is Respuesta.SinRespuesta -> return DesenlaceDeVenta.NoSe // 2
            is Respuesta.Http -> r
        }
        if (transporteDudoso(http.codigo)) return DesenlaceDeVenta.NoSe // 2
        val s = http.sobre() ?: return DesenlaceDeVenta.NoSe // 3 (y 4: el sobre ya viene normalizado)
        if (s.codigos.any { it in NO_SE }) return DesenlaceDeVenta.NoSe // 5
        if (PARCIAL in s.codigos) { // 6: se reversa SIEMPRE; la evidencia sólo de un 2xx de éxito no contradictorio (R3-8)
            val parcial = s.importesCentavos.distinct().singleOrNull()
            val cotejado = es2xx(http.codigo) && s.status == true && simulacionDeExito(s, esperado) && s.codigos.all { it == PARCIAL } &&
                confiable(s, esperado, conImporte = false) && exitoAtado(s, PARCIAL) && parcial != null &&
                parcial in 1 until esperado.importeCentavos && !esperado.contieneDatoDeLaTarjeta(parcial.toString())
            return if (cotejado) DesenlaceDeVenta.DebeReversar(s.operationNumber, parcial) else DesenlaceDeVenta.DebeReversar(null, null)
        }
        val confiable = confiable(s, esperado)
        if (s.status == true) { // 7
            val folio = s.operationNumber
            val autorizacion = s.autorizacion
            val exito = es2xx(http.codigo) && simulacionDeExito(s, esperado) && folio != null && autorizacion != null && confiable && exitoAtado(s) &&
                s.codigos.all { it == APROBADA }
            return if (exito) DesenlaceDeVenta.Aprobada(folio!!, autorizacion!!) else DesenlaceDeVenta.NoSe
        }
        rechazo(http, s, confiable, NEGATIVAS_DE_VENTA)?.let { return DesenlaceDeVenta.Rechazada(it, leyenda(catalogo, it)) } // 8
        return DesenlaceDeVenta.NoSe // 9
    }

    /**
     * Los pasos 1-5, 7 y 8 de la venta; la autorización es opcional y `TX_024` es una negativa. T9: el éxito exige, como la
     * venta (R12-1), el eco del folio de ESTA cancelación ([Esperado.reference]); el de la venta original no la ata.
     */
    fun cancelacion(r: Respuesta, esperado: Esperado, catalogo: CatalogoDeRechazos): DesenlaceDeCancelacion {
        val http = when (r) {
            Respuesta.NoEnviada -> return DesenlaceDeCancelacion.NoEnviada
            is Respuesta.SinRespuesta -> return DesenlaceDeCancelacion.NoSe
            is Respuesta.Http -> r
        }
        if (transporteDudoso(http.codigo)) return DesenlaceDeCancelacion.NoSe
        val s = http.sobre() ?: return DesenlaceDeCancelacion.NoSe
        if (s.codigos.any { it in NO_SE }) return DesenlaceDeCancelacion.NoSe
        val confiable = confiable(s, esperado)
        if (s.status == true) {
            val folio = s.operationNumber
            val exito = es2xx(http.codigo) && simulacionDeExito(s, esperado) && folio != null && confiable && exitoAtado(s) &&
                s.codigos.all { it == APROBADA }
            return if (exito) DesenlaceDeCancelacion.Cancelada(folio!!) else DesenlaceDeCancelacion.NoSe
        }
        rechazo(http, s, confiable, NEGATIVAS_DE_CANCELACION)?.let {
            return DesenlaceDeCancelacion.Rechazada(it, leyenda(catalogo, it))
        }
        return DesenlaceDeCancelacion.NoSe
    }

    /**
     * `validateCancelation` con su propio sobre (el del piloto): 2xx + `status=true`, sin contradicción y sin código «no sé».
     * No exige `simulation=false` (el piloto no lo traía, ruling p3 c8). T9: `simulation=true` con la regla de la venta, por
     * ambiente: sólo vale en el sandbox. r1 B y ronda 2 Q: `null` = no hubo respuesta de Blumon (no salió, venció, se cortó
     * la conexión, o un HTTP de transporte dudoso: 401, 403, 408, 429, 5xx); sólo `true` deja seguir.
     */
    fun preflightDeCancelacion(r: Respuesta, ambiente: Ambiente = Ambiente.PRODUCCION): Boolean? {
        if (r !is Respuesta.Http || transporteDudoso(r.codigo)) return null
        if (!es2xx(r.codigo)) return false
        val s = r.sobre() ?: return false
        return !s.malFormado && exitoPuro(s) && (s.simulation != true || ambiente == Ambiente.SANDBOX) && s.status == true
    }

    /**
     * El transporte va primero; los códigos se leen sólo en un sobre coherente. `simulation` ausente o `false` acredita
     * el éxito (medido en producción el 16-nov); `true` nunca. La hora sale del reloj de pared al recibir.
     */
    fun reverso(
        r: Respuesta,
        esperado: Esperado,
        catalogo: CatalogoDeRechazos = CatalogoDeRechazos.BLUMON,
        relojDePared: () -> Instant = { Instant.now() },
    ): ClaseDelReverso {
        val http = when (r) {
            Respuesta.NoEnviada -> return ClaseDelReverso.NoEnviado // 1
            is Respuesta.SinRespuesta -> return ClaseDelReverso.Reintentar(SIN_RESPUESTA, false, relojDePared()) // 2
            is Respuesta.Http -> r
        }
        val hora = relojDePared()
        if (transporteDudoso(http.codigo)) return ClaseDelReverso.Reintentar(SIN_RESPUESTA, false, hora) // 2
        val s = http.sobre() ?: return ClaseDelReverso.Reintentar(SIN_RESPUESTA, false, hora) // 2

        val c = s.codigoDeError
        if ((es2xx(http.codigo) || http.codigo in NEGOCIO_4XX) && confiable(s, esperado)) { // 3
            if (s.status == true && es2xx(http.codigo) && s.descripcion == "REVERSADA" && simulacionAceptable(s, esperado) && exitoAtado(s) &&
                s.codigos.all { it == APROBADA }
            ) return ClaseDelReverso.Confirmado(hora)
            if (s.status == false && c != null && s.codigos.all { it == c } && errorPuro(s)) {
                when (c) {
                    REVERSO_RECHAZADO -> return ClaseDelReverso.Rechazado(c, hora)
                    in REVERSO_NO_ENTRO -> return ClaseDelReverso.Reintentar(c, true, hora)
                }
            }
        }
        // 4: todo lo demás «pudo entrar». Se guarda el código «no sé» si lo hay, si no el de error; ajeno ⇒ DESCONOCIDO.
        val guardado = (s.codigos.firstOrNull { it in NO_SE } ?: c)
            ?.takeIf { (it in CONOCIDOS || catalogo.contiene(it)) && !esperado.contieneDatoDeLaTarjeta(it) }
        return ClaseDelReverso.Reintentar(guardado ?: DESCONOCIDO, false, hora)
    }

    private fun rechazo(http: Respuesta.Http, s: SobreDeBlumon, confiable: Boolean, negativas: Set<String>): String? {
        if (s.status != false || !(es2xx(http.codigo) || http.codigo in NEGOCIO_4XX) || !confiable || !errorPuro(s)) return null
        val c = s.codigoDeError ?: return null
        return c.takeIf { it in negativas && s.codigos.all { otro -> otro in negativas } }
    }

    private fun leyenda(catalogo: CatalogoDeRechazos, codigo: String) = catalogo.leyenda(codigo) ?: LEYENDA_GENERICA
}
