package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import android.content.Context
import com.angelpay.angelpaysdk.AngelPaySDK
import com.angelpay.angelpaysdk.models.AuthenticateSimpleResult
import com.angelpay.angelpaysdk.models.MerchantOption
import com.angelpay.angelpaysdk.models.MerchantSummary
import com.angelpay.angelpaysdk.models.PaymentRequest
import com.angelpay.angelpaysdk.models.SessionInfo
import timber.log.Timber
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class AngelPaySdkGateway @Inject constructor() {

    private companion object {
        // ⏱️ Auto-close of the SDK's result screen (AngelPay SDK >= 1.0.10).
        // Before 1.0.10 the SDK stayed on its result screen until the cashier
        // tapped "Aceptar", and the TPV only recorded the Payment AFTER that —
        // which left a window for "orphan" charges (webhook arrives, cashier
        // walks away, Payment never recorded). With these the SDK auto-returns
        // the ActivityResult to us after N ms (equivalent to pressing Aceptar),
        // so AngelPayPaymentViewModel records the Payment hands-free.
        // 0 would skip the SDK result screen entirely; we keep a brief
        // confirmation visible before falling back to our own success screen.
        const val APPROVED_RESULT_DISPLAY_MILLIS = 3000L
        const val ERROR_RESULT_DISPLAY_MILLIS = 5000L
    }

    fun isInitialized(): Boolean = AngelPaySDK.isInitialized()

    fun isAuthenticated(): Boolean = AngelPaySDK.isAuthenticated()

    fun ensureInitialized(context: Context, env: String): Result<Unit> {
        return runCatching {
            if (!AngelPaySDK.isInitialized()) {
                AngelPaySDK.initialize(context = context, env = env)
            }
            check(AngelPaySDK.isInitialized()) { "AngelPay SDK no quedó inicializado" }
        }
    }

    @Deprecated(
        message = "Use AngelPayAuthRepository.ensureAuthenticated() — handles credential resolution " +
            "(D4 backend-preferred + BuildConfig fallback), retry/backoff, state machine, and post-auth " +
            "config validation. This legacy entry point still exists for the app-to-app fallback path " +
            "that has not yet migrated to backend-resolved credentials (see Task 34).",
        level = DeprecationLevel.WARNING,
    )
    suspend fun ensureAuthenticated(credentials: AngelPayCredentials): Result<Unit> {
        if (AngelPaySDK.isAuthenticated()) return Result.success(Unit)

        return AngelPaySDK.authenticateSimple(
            email = credentials.email,
            password = credentials.password,
        ).fold(
            onSuccess = { authResult ->
                when (authResult) {
                    is AuthenticateSimpleResult.Success -> Result.success(Unit)
                    is AuthenticateSimpleResult.MerchantSelectionRequired -> {
                        selectConfiguredMerchant(authResult, credentials)
                    }
                }
            },
            onFailure = { error -> Result.failure(error) },
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun selectConfiguredMerchant(
        authResult: AuthenticateSimpleResult.MerchantSelectionRequired,
        credentials: AngelPayCredentials,
    ): Result<Unit> {
        val merchant = authResult.merchants.findByAffiliation(credentials.affiliation)
            ?: authResult.merchants.singleOrNull()
            ?: return Result.failure(
                IllegalStateException(
                    "AngelPay requiere seleccionar comercio, pero no se encontro afiliacion ${credentials.affiliation}"
                )
            )

        return AngelPaySDK.selectMerchant(
            merchantId = merchant.id,
            temporaryToken = authResult.temporaryToken,
        )
    }

    fun validatePaymentIntent(context: Context, request: PaymentRequest): Result<Unit> {
        return AngelPaySDK.createPaymentIntent(context, request)
            .map { Unit }
    }

    /**
     * Deja constancia del TIPO del comercio activo antes de cobrar.
     *
     * AngelPay define tres (`MerchantInfo.type`, su DevHub): `"Venta"` (retail),
     * `"Venta con propina"` (restaurante) y `"Check In"` (hotel). Nosotros mandamos lo
     * MISMO a los tres — el tipo ni siquiera es parámetro de [buildPaymentRequest] — pero
     * sí necesitamos saber contra cuál se cobró:
     *
     * 1. El bug de propinas (Rest MX, 2026-08-09/10, $1,225.65) **sólo se manifestaba en
     *    comercios tipo restaurante**, y tardamos días en descubrirlo justamente porque el
     *    tipo no aparecía en ningún lado. Con esta línea, el primer log de cualquier
     *    terminal dice de qué tipo es su comercio.
     * 2. Desde que mandamos `tipCents = 0` ya **no hay forma de inferir el tipo** por el
     *    comportamiento: antes un retail se delataba rechazando la propina con `C208`; hoy
     *    ninguno rechaza nada. Esta es la única fuente que queda.
     *
     * Nivel `w` a propósito: ProGuard borra `d/v/i` en release (`-assumenosideeffects`),
     * así que un `Timber.i` no existiría en las terminales — que es donde hace falta.
     */
    fun logTipoDeComercio(contexto: String) {
        val info = runCatching { AngelPaySDK.getMerchantInfo() }.getOrNull()
        if (info == null) {
            Timber.w("🏪 [AngelPay] Tipo de comercio no disponible | contexto=$contexto")
            return
        }
        Timber.w(
            "🏪 [AngelPay] Comercio activo | nombre=${info.name}, tipo=\"${info.type}\", " +
                "afiliacion=${info.affiliation}, id=${info.commerceId}, contexto=$contexto"
        )
    }

    /**
     * 💰 Se manda **SIEMPRE el TOTAL A COBRAR** (venta + propina) en `amountCents`,
     * y **`tipCents` SIEMPRE en 0** — sin importar el tipo de comercio.
     *
     * 🔴 NO "arregles" esto mandando la propina en `tipCents`. Para AngelPay la propina
     * NO es un extra que se suma: es un DESGLOSE que se RESTA de `amountCents`. Su propio
     * recibo lo dice (Restbar, 2026-08-09):
     *
     *     Pago con tarjeta $330.00 = Importe $280.50 + Propina $49.50
     *
     * Ahí mandábamos `amountCents = 330.00` (la venta SIN propina) y AngelPay cobró 330.00
     * tratándolo como total. Resultado: **11 ventas cobradas de menos por $1,225.65** — el
     * cliente pagó MENOS de lo que aceptó y el restaurante nunca recibió esas propinas.
     * Pérdida asumida por Avoqado.
     *
     * **Por qué `tipCents = 0` y no el desglose real** (decisión 2026-08-10): mandar el
     * desglose obliga a confiar en cómo AngelPay interpreta la resta, y esa ruta **sólo
     * la ejercitan los comercios tipo restaurante** — los retail la rechazan con `C208` y
     * caen al fallback. O sea que era una ruta que no podíamos probar en ningún banco de
     * pruebas y que se estrenaba en producción con dinero real, justo como pasó. Mandando
     * el total con `tipCents = 0` hay **un solo camino, y es el que está probado en
     * hardware** (cobros reales verificados el 2026-08-10, incluido uno en producción:
     * venta $1.00 + propina $0.20 → cobrado $1.20, auth 904174). Un comercio nuevo dado de
     * alta como restaurante queda protegido desde el primer cobro, sin depender de que
     * alguien le cambie el perfil.
     *
     * Es además el mismo contrato que Blumon/PAX (`calculateTotal(amount, tip)` → SaleIcc)
     * y el que ya usan todos los comercios de AngelPay hoy. Avoqado conserva el desglose
     * venta/propina de su lado; lo único que se pierde es que la propina aparezca en la
     * columna de propina de los reportes de AngelPay en vez de dentro del importe.
     *
     * 🔴 El invariante: **el cobro NUNCA puede ser menor al total registrado en Avoqado.**
     * Guardado por `AngelPaySdkGatewayTest`.
     */
    fun buildPaymentRequest(
        subtotal: BigDecimal,
        tip: BigDecimal,
        waiter: String?,
        reference: String?,
    ): PaymentRequest {
        return PaymentRequest(
            amountCents = toCents(subtotal.add(tip)),
            latitude = 0.0,
            longitude = 0.0,
            reference = reference,
            // 🔴 SIEMPRE 0 — ver el KDoc. La propina ya va dentro de `amountCents`.
            tipCents = 0L,
            waiter = waiter,
            msi = null,
            isCheckIn = false,
            checkInId = null,
            allowSwipe = true,
            allowChip = true,
            allowContactless = true,
            // 🔑 integratorReference is the field AngelPay echoes back in the
            // webhook AND the trigger that makes their backend fire the webhook
            // at all (confirmed by AngelPay 2026-05-28: "mandar integrator_reference
            // dispara el webhook siempre que haya endpoint registrado + terminal
            // activa"). The SDK ignores our `reference` (it generates its own), so
            // WITHOUT this the SDK logs `ref_int=null` and no webhook is ever sent.
            // We use the same value as `reference` (the TPV's paymentAttemptId /
            // idempotencyKey) so the webhook receiver can match it to the Payment row.
            integratorReference = reference,
            approvedResultDisplayMillis = APPROVED_RESULT_DISPLAY_MILLIS,
            errorResultDisplayMillis = ERROR_RESULT_DISPLAY_MILLIS,
        )
    }

    fun buildQaTipFallbackRequest(
        subtotal: BigDecimal,
        tip: BigDecimal,
        waiter: String?,
        reference: String?,
    ): PaymentRequest {
        val total = subtotal.add(tip)
        return PaymentRequest(
            amountCents = toCents(total),
            latitude = 0.0,
            longitude = 0.0,
            reference = reference,
            tipCents = 0L,
            waiter = waiter,
            msi = null,
            isCheckIn = false,
            checkInId = null,
            allowSwipe = true,
            allowChip = true,
            allowContactless = true,
            // See buildPaymentRequest — integratorReference triggers + is echoed
            // in the AngelPay webhook. Must be set on the tip-fallback path too.
            integratorReference = reference,
            approvedResultDisplayMillis = APPROVED_RESULT_DISPLAY_MILLIS,
            errorResultDisplayMillis = ERROR_RESULT_DISPLAY_MILLIS,
        )
    }

    fun isTipUnsupportedError(throwable: Throwable): Boolean {
        val msg = throwable.message?.lowercase().orEmpty()
        return msg.contains("c208") || msg.contains("propina no soportada")
    }

    private fun toCents(amount: BigDecimal): Long {
        return amount
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .toLong()
    }

    private fun List<MerchantOption>.findByAffiliation(affiliation: String): MerchantOption? {
        val expected = affiliation.onlyDigits()
        if (expected.isBlank()) return null

        return firstOrNull { merchant ->
            merchant.afiliationNumber.onlyDigits() == expected
        }
    }

    private fun String.onlyDigits(): String = filter { it.isDigit() }

    // --- Multi-merchant runtime switch (SDK 1.0.5 — spec §6.5, §18.1) ----------------------
    //
    // `getUserMerchants` and `switchMerchant` are the runtime primitives the dashboard
    // exposes to the cashier so a logged-in user can swap active merchant without
    // re-authenticating. They are *separate* from `selectMerchant` above (which handles
    // the initial selection during `MerchantSelectionRequired` flow). The SDK internally
    // fetches a fresh JWT during `switchMerchant`.
    //
    // We categorize SDK errors into typed exceptions (AuthExpired / Network / generic)
    // so the AngelPayAuthRepository (Task 30) can drive the retry / re-auth state machine.

    /** Returns the merchants the authenticated user can switch between (with `isActive` flag). */
    suspend fun getUserMerchants(): Result<List<MerchantSummary>> = withContext(Dispatchers.IO) {
        // Same rationale as `switchMerchant` — keep the SDK's blocking work off the main thread.
        AngelPaySDK.getUserMerchants().fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(mapSdkError(it)) },
        )
    }

    /** Switches the active merchant without re-authenticating (SDK fetches new JWT internally). */
    suspend fun switchMerchant(merchantId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        // ⚠️ MUST run off the main thread. `AngelPaySDK.switchMerchant` internally does
        // blocking Nexgo device-info reads (getModel / serial CSN·DSN via SPI / firmware)
        // BEFORE its HTTP calls, and its ktor client resumes on Dispatchers.Unconfined —
        // so when this was invoked from `viewModelScope` (Main), the synchronous reads froze
        // the UI thread ~8.5s at startup (503 skipped frames) and tripped the caller's 8s
        // `withTimeoutOrNull` watchdog with a *false* SwitchTimeoutError, even though the SDK
        // switch actually completed in the background. (Nexgo N86, 2026-07-15.)
        AngelPaySDK.switchMerchant(merchantId).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(mapSdkError(it)) },
        )
    }

    /**
     * Finalize the FIRST merchant selection after `AuthenticateSimpleResult.MerchantSelectionRequired`.
     * Distinct from `switchMerchant`: this consumes the `temporaryToken` issued by
     * `authenticateSimple` and establishes the initial active session.
     *
     * Wraps `AngelPaySDK.selectMerchant` with the same error categorization as
     * `getUserMerchants`/`switchMerchant` so Task 30's AuthRepository can react uniformly.
     */
    suspend fun selectMerchant(merchantId: Int, temporaryToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        // Same rationale as `switchMerchant` — keep the SDK's blocking work off the main thread.
        AngelPaySDK.selectMerchant(
            merchantId = merchantId,
            temporaryToken = temporaryToken,
        ).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(mapSdkError(it)) },
        )
    }

    // --- Low-level auth primitives (Task 30 — AngelPayAuthRepository state machine) ----------
    //
    // The repository drives `Authenticating → Authenticated | SelectingMerchant | AuthError`
    // itself, so it needs the raw `AuthenticateSimpleResult` instead of the auto-selecting
    // wrapper in [ensureAuthenticated]. Errors are categorized through [mapSdkError] so
    // the auth state machine can react uniformly (AuthExpired / Network / generic).

    /**
     * Raw `authenticateSimple` passthrough — returns the SDK's discriminated result so the
     * caller (Task 30's AuthRepository) can decide how to handle MerchantSelectionRequired.
     */
    suspend fun authenticateSimple(
        email: String,
        pin: String,
    ): Result<AuthenticateSimpleResult> = withContext(Dispatchers.IO) {
        // Same rationale as `switchMerchant`: authenticateSimple does the SDK's blocking
        // device-info reads + `initKeys`/post-auth HTTP; keep it off the main thread.
        timber.log.Timber.tag("AngelPaySdkGateway").i("AngelPaySDK.authenticateSimple(email=$email) — calling SDK")
        AngelPaySDK.authenticateSimple(email = email, password = pin).fold(
            onSuccess = {
                timber.log.Timber.tag("AngelPaySdkGateway").i("SDK authenticateSimple → Success (type=${it::class.simpleName})")
                Result.success(it)
            },
            onFailure = {
                val mapped = mapSdkError(it)
                timber.log.Timber.tag("AngelPaySdkGateway").e(it, "SDK authenticateSimple → FAILURE: ${it.message} (mapped to ${mapped::class.simpleName})")
                Result.failure(mapped)
            },
        )
    }

    /** Returns the active SDK session info (post-authenticate), or null if not authenticated. */
    fun getSessionInfo(): SessionInfo? = AngelPaySDK.getSessionInfo()

    /** Forces SDK logout — clears the in-memory JWT + session. Idempotent. */
    fun logout() {
        AngelPaySDK.logout()
    }

    private fun mapSdkError(error: Throwable): Throwable {
        val msg = error.message.orEmpty().lowercase()
        return when {
            msg.contains("auth") ||
                msg.contains("401") ||
                msg.contains("unauthorized") -> AngelPayAuthExpiredError(cause = error)
            msg.contains("network") ||
                msg.contains("timeout") ||
                msg.contains("connection") -> AngelPayNetworkError(cause = error)
            else -> error
        }
    }
}

/**
 * AngelPay SDK reported the active session/JWT is no longer valid. Task 30's
 * AuthRepository reacts by triggering a silent re-auth before surfacing failure
 * to the cashier.
 */
class AngelPayAuthExpiredError(cause: Throwable? = null) :
    RuntimeException("AngelPay auth expired or invalid", cause)

/**
 * AngelPay SDK reported a transport-level failure (timeout, connection drop). Task 30
 * surfaces this as a transient error and lets the cashier retry without re-auth.
 */
class AngelPayNetworkError(cause: Throwable? = null) :
    RuntimeException("AngelPay network error", cause)
