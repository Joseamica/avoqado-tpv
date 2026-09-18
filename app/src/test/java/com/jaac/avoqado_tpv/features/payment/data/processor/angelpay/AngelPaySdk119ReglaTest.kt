package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.AngelPaySDK
import com.angelpay.angelpaysdk.models.AppErrorCatalog
import com.angelpay.angelpaysdk.models.PaymentResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * La regla del SDK 1.0.19 ([AngelPayOutcomeClassifier.decidirSegunElSdk119]) con `PaymentResult` REALES del AAR.
 *
 * 🔴 Nunca con `mockk<PaymentResult>(relaxed = true)`: un mock relajado devuelve `authorizationAttempted = false` e
 * `integratorReference = ""`, así que una prueba de la regla con él pasaría o fallaría por el motivo equivocado. Cada
 * fixture copia los ARGUMENTOS EXACTOS del constructor que usa el AAR 1.0.19 en ese camino (decompilado en
 * `jobs/9bb6b8e8/tmp/sdk119/out/sources/`, líneas citadas en cada una) y pasa por el MISMO adaptador que usa el
 * ViewModel ([paraLaReglaDelSdk]), para que un campo cruzado en el mapeo también se vea aquí.
 *
 * Diseño: `avoqado-server/.superpowers/sdd/2026-09-16-ventana-de-confirmacion-cobro-sin-evidencia/diseno-nexgo-sdk-1.0.19.md`
 * §2.1 y §6.3 (T1-T14), con las decisiones del founder del 18-sep (U = confiar en el SDK; E608 y `attempted=true` como hoy).
 */
class AngelPaySdk119ReglaTest {

    private val intento = "3593b54b-7c3d-4e21-9f0a-2b6c1d8e4f55"
    private val otroIntento = "e6d27691-0000-4000-8000-000000000001"
    private val version = AngelPayOutcomeClassifier.VERSION_SDK_AUDITADA
    private val centavos = 1_000L

    // ══════════════════════ Fixtures: los constructores del AAR 1.0.19, argumento por argumento ══════════════════════

    /**
     * El orquestador ANTES de ir en línea: `f0.a(f0, false, status, mensaje, código, null, null, 48)` (`f0.java:1823-1835`).
     * Referencia = `f0.A` (la de la petición, `:301`), `operationType = "VENTA"`, `amount = f0.o`, campos del host null (sin
     * `dVar`), campos de tarjeta leída = los del orquestador (null salvo que el manejador en línea los haya escrito),
     * `requireSignature = f0.D` (false), `authorizationAttempted = f0.m` (false: nunca sube antes del envío).
     */
    private fun delOrquestador(
        status: PaymentResult.Status,
        mensaje: String,
        codigo: AppErrorCatalog.Code,
        referencia: String? = intento,
        tarjetaLeida: Boolean = false,
    ) = PaymentResult(
        approved = false, status = status, message = mensaje, authCode = null, amount = centavos,
        code = null, description = null, cardBrand = null, cardBin = null, cardLast4 = null,
        cardEntryMode = if (tarjetaLeida) "contactless" else null, reference = null, affiliation = null,
        aid = if (tarjetaLeida) "A0000000031010" else null, arqc = if (tarjetaLeida) "9F2601AB" else null,
        applicationLabel = if (tarjetaLeida) "VISA CREDITO" else null, tvr = if (tarjetaLeida) "0000008000" else null,
        tsi = if (tarjetaLeida) "E800" else null, authentication = if (tarjetaLeida) "Sin firma" else null,
        integratorReference = referencia, folio = null, transactionDate = null, issuingBank = null, cardType = null,
        operationType = "VENTA", requireSignature = false, authorizationAttempted = false,
        callResult = callResultDe(codigo),
    )

    /**
     * El `catch` de `b0.s` (`s.java:145` U101 «timeout esperando al kernel», `:159` I999 excepción): referencia de la
     * PETICIÓN, `amount` de la petición, `operationType` NULL (máscara 66584552), `authorizationAttempted = f0.b()` = `m`.
     */
    private fun delCatch(status: PaymentResult.Status, mensaje: String, codigo: AppErrorCatalog.Code, attempted: Boolean) =
        PaymentResult(
            approved = false, status = status, message = mensaje, amount = centavos,
            integratorReference = intento, requireSignature = false, authorizationAttempted = attempted,
            callResult = callResultDe(codigo),
        )

    /** Botón Cancelar (`PaymentActivity.java:690`) y tecla atrás (`b0/o.java` → la misma función): `false` escrito a mano. */
    private fun delBotonCancelar(referencia: String? = intento) = PaymentResult(
        approved = false, status = PaymentResult.Status.CANCELLED, message = "User cancelled", amount = centavos,
        integratorReference = referencia, requireSignature = false, authorizationAttempted = false,
        callResult = callResultDe(AppErrorCatalog.Code.U100),
    )

    /**
     * El constructor de resultados DEL HOST (`f0.a(b.d, Boolean, String)`, `f0.java:1784-1820`): campos del host del
     * gateway y `authorizationAttempted = this.m` (true: sólo se llega aquí después de mandar).
     */
    private fun delHost(
        aprobado: Boolean,
        codigo: AppErrorCatalog.Code,
        codigoEmisor: String? = if (aprobado) "00" else "05",
        attempted: Boolean = true,
    ) = PaymentResult(
        approved = aprobado, status = if (aprobado) PaymentResult.Status.APPROVED else PaymentResult.Status.DECLINED,
        message = if (aprobado) "APPROVED" else "DECLINED", authCode = if (aprobado) "368490" else null, amount = centavos,
        code = codigoEmisor, description = "Respuesta del emisor", cardBrand = "VISA", cardBin = "411111", cardLast4 = "1111",
        cardEntryMode = "contactless", reference = "260917233900", affiliation = "9814275", aid = "A0000000031010",
        arqc = "9F2601AB", applicationLabel = "VISA CREDITO", tvr = "0000008000", tsi = "E800", authentication = "Sin firma",
        integratorReference = intento, folio = "000123", transactionDate = "2026-09-17T23:39:00", issuingBank = "BBVA",
        cardType = "CREDITO", operationType = "VENTA", requireSignature = false, authorizationAttempted = attempted,
        callResult = callResultDe(codigo),
    )

    private fun decidir(r: PaymentResult, attemptId: String? = intento, versionSdk: String? = version): DecisionDelSdk =
        AngelPayOutcomeClassifier.decidirSegunElSdk119(r.paraLaReglaDelSdk(versionSdk), attemptId)

    /** Lo que diría [AngelPayOutcomeClassifier.clasificar] (la regla de hoy) sobre el MISMO resultado. */
    private fun comoHoy(r: PaymentResult): DesenlaceDelCobro = AngelPayOutcomeClassifier.clasificar(
        aprobado = r.approved, status = r.status.name, codigoSdk = r.callResult?.code, codigoGateway = r.code,
    )

    // ═════════════════════════════════════════ T1 · 3e: «no se cobró», cierto ═════════════════════════════════════════

    @Test
    fun `P1 T1 lo que el SDK arma ANTES de enviar, con nuestra referencia, es SIN_AUTORIZACION`() {
        val casos = mapOf(
            "U101 búsqueda agotada (TIME_OUT, f0:481)" to
                delOrquestador(PaymentResult.Status.TIMEOUT, "Tiempo de espera agotado", AppErrorCatalog.Code.U101),
            "E618 tarjeta ya puesta (f0:377) — la de las 21:59 y 23:13" to
                delOrquestador(PaymentResult.Status.ERROR, "Retire la tarjeta del lector e intente de nuevo", AppErrorCatalog.Code.E618),
            "E622 AMEX no soportada (f0:557)" to
                delOrquestador(PaymentResult.Status.DECLINED, "Tarjeta AMEX no soportada", AppErrorCatalog.Code.E622),
            "E699 error del kernel antes de ir en línea (f0:914)" to
                delOrquestador(PaymentResult.Status.ERROR, "SDK error -8012", AppErrorCatalog.Code.E699),
            "U100 del orquestador (revisión previa / cabeza del bucle, f0:374-396)" to
                delOrquestador(PaymentResult.Status.CANCELLED, "Cancelled", AppErrorCatalog.Code.U100),
            "I999 del catch de b0.s con m=false (s:159)" to
                delCatch(PaymentResult.Status.ERROR, "Ocurrio un error inesperado. Intente de nuevo.", AppErrorCatalog.Code.I999, attempted = false),
            "U101 del catch de b0.s con m=false (s:145)" to
                delCatch(PaymentResult.Status.TIMEOUT, "Tiempo de espera agotado", AppErrorCatalog.Code.U101, attempted = false),
            "U100 del botón Cancelar / tecla atrás — decisión U: confiar en el SDK (op=null, medido en la N86)" to
                delBotonCancelar(),
        )
        casos.forEach { (caso, resultado) ->
            assertWithMessage(caso).that(decidir(resultado)).isEqualTo(DecisionDelSdk.SIN_AUTORIZACION)
        }
    }

    // ═══════════════ T2 · 3a: SIN referencia ⇒ INCIERTO (Codex r1, P1-1: un resultado sin correlación nunca es un negativo) ═══════════════

    @Test
    fun `P1 T2 sin referencia es INCIERTO - los respaldos del contrato no afirman nada`() {
        // `AngelPayPaymentContract.parseResult` (`:102-129`): `false` POR DEFECTO, sin referencia y sin `callResult`.
        val respaldos = mapOf(
            "validación (EXTRA_VALIDATION_ERROR)" to PaymentResult(approved = false, status = PaymentResult.Status.ERROR, message = "Monto inválido"),
            "Error al parsear resultado" to PaymentResult(approved = false, status = PaymentResult.Status.ERROR, message = "Error al parsear resultado"),
            "Cancelled (RESULT_CANCELED sin datos)" to PaymentResult(approved = false, status = PaymentResult.Status.CANCELLED, message = "Cancelled"),
            "Unknown error" to PaymentResult(approved = false, status = PaymentResult.Status.ERROR, message = "Unknown error"),
        )
        respaldos.forEach { (caso, resultado) ->
            assertWithMessage("regla · $caso").that(decidir(resultado)).isEqualTo(DecisionDelSdk.INCIERTO)
            assertWithMessage("hoy · $caso").that(comoHoy(resultado)).isEqualTo(DesenlaceDelCobro.INCIERTO)
        }
    }

    @Test
    fun `P1 T2 un U101 o un U100 SIN referencia no se vuelven no se cobro`() {
        // Aunque el código y el estado sean los de un «no se cobró», sin NUESTRA referencia no los armó quien conocía el cobro.
        listOf(
            delOrquestador(PaymentResult.Status.TIMEOUT, "Tiempo de espera agotado", AppErrorCatalog.Code.U101, referencia = null),
            delOrquestador(PaymentResult.Status.ERROR, "Retire la tarjeta del lector e intente de nuevo", AppErrorCatalog.Code.E618, referencia = ""),
            delBotonCancelar(referencia = null),
            delBotonCancelar(referencia = "   "),
        ).forEach { resultado ->
            assertWithMessage(resultado.callResult?.code).that(decidir(resultado)).isEqualTo(DecisionDelSdk.INCIERTO)
            assertWithMessage("hoy · ${resultado.callResult?.code}").that(comoHoy(resultado)).isEqualTo(DesenlaceDelCobro.INCIERTO)
        }
    }

    @Test
    fun `P1 T2 la validacion al ABRIR la pantalla del SDK (C200, sin referencia) queda INCIERTA aunque hoy sea un rechazo`() {
        // `PaymentActivity.onCreate` (`:181`, `:186`): C200 sin referencia. HOY la tabla lo da por rechazo cierto (Reintentar y
        // `PROCESSOR_DECLINED`); sin NUESTRA referencia nada lo correlaciona con este intento, así que la regla lo deja INCIERTO
        // (Codex r1, P1-1). La única excepción sin referencia es la forma de sesión expirada (T11), que se evalúa antes.
        val c200 = PaymentResult(
            approved = false, status = PaymentResult.Status.ERROR, message = "Missing request",
            callResult = callResultDe(AppErrorCatalog.Code.C200),
        )
        assertThat(decidir(c200)).isEqualTo(DecisionDelSdk.INCIERTO)
        assertThat(comoHoy(c200)).isEqualTo(DesenlaceDelCobro.RECHAZADO_CONFIRMADO)
    }

    // ═══════════════════════════════ T3 · 3a: la referencia de OTRO intento no decide éste ═══════════════════════════════

    @Test
    fun `P1 T3 con la referencia de OTRO intento es INCIERTO, aunque hoy fuera un rechazo`() {
        listOf(
            delOrquestador(PaymentResult.Status.TIMEOUT, "Tiempo de espera agotado", AppErrorCatalog.Code.U101, referencia = otroIntento),
            delOrquestador(PaymentResult.Status.ERROR, "Retire la tarjeta del lector e intente de nuevo", AppErrorCatalog.Code.E618, referencia = otroIntento),
            // E622 HOY es un rechazo confirmado: con la referencia de otro cobro no puede cerrar ESTE intento.
            delOrquestador(PaymentResult.Status.DECLINED, "Tarjeta AMEX no soportada", AppErrorCatalog.Code.E622, referencia = otroIntento),
            delBotonCancelar(referencia = otroIntento),
        ).forEach { resultado ->
            assertWithMessage(resultado.callResult?.code).that(decidir(resultado)).isEqualTo(DecisionDelSdk.INCIERTO)
        }
    }

    @Test
    fun `P1 T3 sin intento propio con que comparar tampoco se afirma nada`() {
        val u101 = delOrquestador(PaymentResult.Status.TIMEOUT, "Tiempo de espera agotado", AppErrorCatalog.Code.U101)
        assertThat(decidir(u101, attemptId = null)).isEqualTo(DecisionDelSdk.INCIERTO)
        assertThat(decidir(u101, attemptId = "")).isEqualTo(DecisionDelSdk.INCIERTO)
    }

    // ══════════════════════════════ T5 · 5b: `false` con datos del HOST es una CONTRADICCIÓN ══════════════════════════════

    @Test
    fun `P1 T5 attempted=false con cualquiera de los 12 campos del host es CONTRADICCION`() {
        val base = delOrquestador(PaymentResult.Status.TIMEOUT, "Tiempo de espera agotado", AppErrorCatalog.Code.U101)
        val conCampo: Map<String, PaymentResult> = mapOf(
            "authCode" to base.copy(authCode = "600287"),
            "code" to base.copy(code = "00"),
            "description" to base.copy(description = "APROBADA"),
            "cardBrand" to base.copy(cardBrand = "VISA"),
            "cardBin" to base.copy(cardBin = "411111"),
            "cardLast4" to base.copy(cardLast4 = "1111"),
            "reference" to base.copy(reference = "260917235506"),
            "affiliation" to base.copy(affiliation = "9814275"),
            "folio" to base.copy(folio = "000123"),
            "transactionDate" to base.copy(transactionDate = "2026-09-17"),
            "issuingBank" to base.copy(issuingBank = "BBVA"),
            "cardType" to base.copy(cardType = "CREDITO"),
        )
        assertThat(conCampo.keys).containsExactlyElementsIn(base.paraLaReglaDelSdk(version).camposDelHost.keys)
        conCampo.forEach { (campo, resultado) ->
            assertWithMessage(campo).that(decidir(resultado)).isEqualTo(DecisionDelSdk.CONTRADICCION)
        }
    }

    @Test
    fun `P1 T5 un campo del host en BLANCO sigue contando, y status APPROVED sin aprobar es contradiccion`() {
        val base = delOrquestador(PaymentResult.Status.TIMEOUT, "Tiempo de espera agotado", AppErrorCatalog.Code.U101)
        // El SDK escribe null cuando no hay host; un "" es un productor que el binario no tiene.
        assertThat(decidir(base.copy(authCode = ""))).isEqualTo(DecisionDelSdk.CONTRADICCION)
        assertThat(decidir(base.copy(status = PaymentResult.Status.APPROVED))).isEqualTo(DecisionDelSdk.CONTRADICCION)
    }

    // ═════════════════ T6 · 5c: datos de TARJETA LEÍDA sin E608 son incierto · E608 sigue como hoy ═════════════════

    @Test
    fun `P1 T6 cualquiera de los 7 campos de tarjeta leida, sin E608, es INCIERTO`() {
        val base = delOrquestador(PaymentResult.Status.ERROR, "SDK error -8012", AppErrorCatalog.Code.E699)
        val conCampo: Map<String, PaymentResult> = mapOf(
            "cardEntryMode" to base.copy(cardEntryMode = "chip"),
            "aid" to base.copy(aid = "A0000000031010"),
            "arqc" to base.copy(arqc = "9F2601AB"),
            "applicationLabel" to base.copy(applicationLabel = "VISA"),
            "tvr" to base.copy(tvr = "0000008000"),
            "tsi" to base.copy(tsi = "E800"),
            "authentication" to base.copy(authentication = "Sin firma"),
        )
        assertThat(conCampo.keys).containsExactlyElementsIn(base.paraLaReglaDelSdk(version).camposDeTarjetaLeida.keys)
        conCampo.forEach { (campo, resultado) ->
            assertWithMessage(campo).that(decidir(resultado)).isEqualTo(DecisionDelSdk.INCIERTO)
        }
        // También con U101, E618 y el U100 del botón.
        assertThat(decidir(delOrquestador(PaymentResult.Status.TIMEOUT, "Tiempo de espera agotado", AppErrorCatalog.Code.U101, tarjetaLeida = true)))
            .isEqualTo(DecisionDelSdk.INCIERTO)
        assertThat(decidir(delOrquestador(PaymentResult.Status.ERROR, "Retire la tarjeta", AppErrorCatalog.Code.E618, tarjetaLeida = true)))
            .isEqualTo(DecisionDelSdk.INCIERTO)
        assertThat(decidir(delBotonCancelar().copy(aid = "A0000000031010"))).isEqualTo(DecisionDelSdk.INCIERTO)
    }

    @Test
    fun `P1 E608 sigue como hoy - un rechazo con Reintentar en la MISMA venta`() {
        // `f0.java:682`: los campos de tarjeta leída YA están escritos. Decisión del 18-sep: E608 no cambia.
        val e608 = delOrquestador(PaymentResult.Status.DECLINED, "Límite contactless excedido", AppErrorCatalog.Code.E608, tarjetaLeida = true)
        assertThat(decidir(e608)).isEqualTo(DecisionDelSdk.REGLAS_DE_HOY)
        assertThat(decidir(e608.copy(cardEntryMode = null, aid = null, arqc = null, applicationLabel = null, tvr = null, tsi = null, authentication = null)))
            .isEqualTo(DecisionDelSdk.REGLAS_DE_HOY)
        assertThat(comoHoy(e608)).isEqualTo(DesenlaceDelCobro.RECHAZADO_CONFIRMADO)
    }

    @Test
    fun `P1 E608 sin NUESTRA referencia no es el E608 de hoy - INCIERTO`() {
        // El E608 que no cambia es el del orquestador (`f0.java:682`), con NUESTRA referencia. Sin ella —o con la de otro
        // intento— nada lo correlaciona con este cobro: no puede certificar un rechazo con «Reintentar» (diseño §2.1, 3a).
        val e608 = delOrquestador(PaymentResult.Status.DECLINED, "Límite contactless excedido", AppErrorCatalog.Code.E608, tarjetaLeida = true)
        assertThat(decidir(e608.copy(integratorReference = null))).isEqualTo(DecisionDelSdk.INCIERTO)
        assertThat(decidir(e608.copy(integratorReference = otroIntento))).isEqualTo(DecisionDelSdk.INCIERTO)
    }

    @Test
    fun `P1 sin codigo de catalogo no se afirma nada aunque la referencia sea la nuestra`() {
        // Todo productor del 1.0.19 con nuestra referencia trae código: sin él es un productor que el binario no tiene.
        val sinCodigo = delOrquestador(PaymentResult.Status.ERROR, "¿?", AppErrorCatalog.Code.E699).copy(callResult = null)
        assertThat(decidir(sinCodigo)).isEqualTo(DecisionDelSdk.INCIERTO)
    }

    // ═════════════════════════ T7 · T8 · decisión 2: `attempted = true` se clasifica COMO HOY ═════════════════════════

    @Test
    fun `P1 T7 attempted=true sin respuesta decisiva sigue como hoy - incierto`() {
        val casos = listOf(
            // `verify_in_doubt` sin el cobro en su historial (f0:1444-1545) y los fallos de red después de mandar.
            delHost(aprobado = false, codigo = AppErrorCatalog.Code.G505, codigoEmisor = null),
            delHost(aprobado = false, codigo = AppErrorCatalog.Code.N402, codigoEmisor = null),
            delCatch(PaymentResult.Status.ERROR, "Ocurrio un error inesperado. Intente de nuevo.", AppErrorCatalog.Code.I999, attempted = true),
            delCatch(PaymentResult.Status.TIMEOUT, "Tiempo de espera agotado", AppErrorCatalog.Code.U101, attempted = true),
        )
        casos.forEach { r ->
            assertWithMessage("regla · ${r.callResult?.code}").that(decidir(r)).isEqualTo(DecisionDelSdk.REGLAS_DE_HOY)
            assertWithMessage("hoy · ${r.callResult?.code}").that(comoHoy(r)).isEqualTo(DesenlaceDelCobro.INCIERTO)
        }
    }

    @Test
    fun `P1 T8 attempted=true con rechazo de la tabla sigue como hoy - rechazo confirmado (4a sin endurecer)`() {
        // Decisión del 18-sep: G500/N400 con attempted=true NO se endurecen en esta entrega (§2.2 4a queda pendiente).
        listOf(
            delHost(aprobado = false, codigo = AppErrorCatalog.Code.G500),
            delHost(aprobado = false, codigo = AppErrorCatalog.Code.G500, codigoEmisor = null),
            delHost(aprobado = false, codigo = AppErrorCatalog.Code.N400, codigoEmisor = null),
        ).forEach { r ->
            assertWithMessage("regla · ${r.callResult?.code}/${r.code}").that(decidir(r)).isEqualTo(DecisionDelSdk.REGLAS_DE_HOY)
            assertWithMessage("hoy · ${r.callResult?.code}/${r.code}").that(comoHoy(r)).isEqualTo(DesenlaceDelCobro.RECHAZADO_CONFIRMADO)
        }
    }

    // ══════════════════════════ T10 · T11: aprobado y sesión expirada ganan como siempre ══════════════════════════

    @Test
    fun `P1 T10 aprobado gana siempre - la regla no lo toca, ni con attempted=false`() {
        val aprobado = delHost(aprobado = true, codigo = AppErrorCatalog.Code.S000)
        assertThat(decidir(aprobado)).isEqualTo(DecisionDelSdk.REGLAS_DE_HOY)
        assertThat(comoHoy(aprobado)).isEqualTo(DesenlaceDelCobro.APROBADO)
        // Aprobado con `false` es una contradicción que el ViewModel GRITA, pero manda el dinero.
        assertThat(decidir(aprobado.copy(authorizationAttempted = false))).isEqualTo(DecisionDelSdk.REGLAS_DE_HOY)
    }

    @Test
    fun `P1 T11 la forma de sesion expirada sigue ganando`() {
        // Candado de registro (`s.java:249`): D308/N400 SIN referencia — lo cubre la forma de sesión expirada (VM :2588).
        val registro = PaymentResult(
            approved = false, status = PaymentResult.Status.ERROR,
            message = "No fue posible registrar la terminal antes del cobro",
            callResult = callResultDe(AppErrorCatalog.Code.N400),
        )
        val d308 = PaymentResult(
            approved = false, status = PaymentResult.Status.ERROR, message = "Sesión Expirada", integratorReference = intento,
            authorizationAttempted = false, callResult = callResultDe(AppErrorCatalog.Code.D308),
        )
        assertThat(decidir(registro)).isEqualTo(DecisionDelSdk.REGLAS_DE_HOY)
        assertThat(decidir(d308)).isEqualTo(DecisionDelSdk.REGLAS_DE_HOY)
    }

    // ══════════════════════════════════════════ T12 · candado de versión ══════════════════════════════════════════

    @Test
    fun `P1 T12 con otra version del SDK la regla no corre`() {
        val u101 = delOrquestador(PaymentResult.Status.TIMEOUT, "Tiempo de espera agotado", AppErrorCatalog.Code.U101)
        assertThat(decidir(u101)).isEqualTo(DecisionDelSdk.SIN_AUTORIZACION) // control: con 1.0.19 sí
        listOf("1.0.18", "1.0.20", "1.0.190", "", null).forEach { otra ->
            assertWithMessage("versión=$otra").that(decidir(u101, versionSdk = otra)).isEqualTo(DecisionDelSdk.REGLAS_DE_HOY)
        }
    }

    @Test
    fun `P1 T12 el AAR del proyecto es EXACTAMENTE el auditado - cambiarlo exige re-auditar la regla`() {
        // 🔴 Si esto cae porque llegó un SDK nuevo: NO actualices el hash a ciegas. La regla confía en `authorizationAttempted`
        // tal como lo implementa ESTE binario (`b0.f0.m`); re-audítalo contra el nuevo antes de mover el candado.
        val aar = listOf(File("libs/angelpaySDK-v1.0.19-fat-release.aar"), File("app/libs/angelpaySDK-v1.0.19-fat-release.aar"))
            .firstOrNull { it.exists() }
        assertWithMessage("no se encontró el AAR 1.0.19 (directorio de trabajo: ${File(".").absolutePath})").that(aar).isNotNull()
        val sha = MessageDigest.getInstance("SHA-256").digest(aar!!.readBytes()).joinToString("") { "%02x".format(it) }
        assertThat(sha).isEqualTo("fe5ef7683e9d8cbcf34d1785610c6c2e05f71d1ccad0ca35dc3f99da1853e777")
        // La versión que el binario dice ser es la del candado.
        assertThat(AngelPaySDK.version()).isEqualTo(AngelPayOutcomeClassifier.VERSION_SDK_AUDITADA)
        // Y el build empaqueta ESE archivo en las cuatro configuraciones (compileOnly, nexgo, nexgoProd, pruebas).
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.exists() }.readText()
        val aars = Regex("""files\("libs/(angelpaySDK-[^"]+)"\)""").findAll(gradle).map { it.groupValues[1] }.toList()
        assertThat(aars).containsExactly(
            "angelpaySDK-v1.0.19-fat-release.aar", "angelpaySDK-v1.0.19-fat-release.aar",
            "angelpaySDK-v1.0.19-fat-release.aar", "angelpaySDK-v1.0.19-fat-release.aar",
        )
    }

    // ══════════════════════════════════════ El adaptador no cruza campos ══════════════════════════════════════

    @Test
    fun `P1 el adaptador lee cada campo de su propio lugar`() {
        val r = PaymentResult(
            approved = false, status = PaymentResult.Status.DECLINED, message = "m", authCode = "auth", amount = centavos,
            code = "code", description = "desc", cardBrand = "brand", cardBin = "bin", cardLast4 = "last4",
            cardEntryMode = "entry", reference = "ref-host", affiliation = "afil", aid = "aid", arqc = "arqc",
            applicationLabel = "label", tvr = "tvr", tsi = "tsi", authentication = "autenticacion",
            integratorReference = "ref-integrador", folio = "folio", transactionDate = "fecha", issuingBank = "banco",
            cardType = "tipo", operationType = "VENTA", requireSignature = true, authorizationAttempted = true,
            callResult = callResultDe(AppErrorCatalog.Code.G500),
        )
        val m = r.paraLaReglaDelSdk("1.0.19")
        assertThat(m).isEqualTo(
            ResultadoDelSdk(
                versionSdk = "1.0.19", approved = false, authorizationAttempted = true, status = "DECLINED", codigoSdk = "G500",
                message = "m", integratorReference = "ref-integrador", authCode = "auth", code = "code", description = "desc",
                cardBrand = "brand", cardBin = "bin", cardLast4 = "last4", reference = "ref-host", affiliation = "afil",
                folio = "folio", transactionDate = "fecha", issuingBank = "banco", cardType = "tipo", cardEntryMode = "entry",
                aid = "aid", arqc = "arqc", applicationLabel = "label", tvr = "tvr", tsi = "tsi", authentication = "autenticacion",
            ),
        )
    }
}

/**
 * `AppErrorCatalog.toCallResult` es una extensión MIEMBRO del objeto (`fun Code.toCallResult()` dentro de
 * `object AppErrorCatalog`): desde Kotlin se llama con el objeto como receptor de despacho. Es la MISMA función con
 * la que el AAR 1.0.19 arma el `callResult` de cada resultado (`AppErrorCatalog.INSTANCE.toCallResult(...)`).
 */
private fun callResultDe(codigo: AppErrorCatalog.Code) = with(AppErrorCatalog) { codigo.toCallResult() }
