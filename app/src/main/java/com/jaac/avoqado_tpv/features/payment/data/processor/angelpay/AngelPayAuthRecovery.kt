package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Qué despertó a la recuperación. Viaja en el reporte al servidor y en los logs. */
enum class DisparadorRecuperacion {
    RED_RECUPERADA,
    CONFIG_ACTUALIZADA,
    SERVIDOR_ALCANZABLE,
    MANUAL,
}

/** Desenlace de [AngelPayAuthRecovery.recoverIfStuck] — para logs y pruebas. */
enum class ResultadoRecuperacion {
    RECUPERADA,
    FALLIDA,
    OMITIDA_NO_ATORADA,
    /** Un cobro es dueño de la sesión o la pantalla de cobro está trabajando. */
    OMITIDA_COBRO_EN_CURSO,
    OMITIDA_ENFRIAMIENTO,
    OMITIDA_EN_CURSO,
    /** Venue con varias cuentas y ninguna conocida: queda «requiere auth» para el cobro. */
    OMITIDA_SIN_CUENTA,
}

/** Reloj inyectable (ms de época). Sólo para medir cuánto lleva atorada y el enfriamiento. */
fun interface AngelPayClock {
    fun nowMs(): Long

    companion object {
        val SISTEMA: AngelPayClock = AngelPayClock { System.currentTimeMillis() }
    }
}

/**
 * Excepción TIPADA que va a Crashlytics cuando la auth de AngelPay falla (T26). Agrupa
 * todos los atascos en un solo issue y lleva la causa real (UnknownHost, PIN rechazado…),
 * que antes se perdía en un `Exception(msg)` sin causa.
 */
class AngelPayAuthFailure(
    val kind: AuthErrorKind,
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)

/** Lo que [AngelPayAuthRepository.recuperarSiSigueAtorada] decidió, ya con el núcleo tomado. */
sealed class IntentoDeRecuperacion {
    object NoAtorada : IntentoDeRecuperacion()
    object CobroEnCurso : IntentoDeRecuperacion()
    object SinCuentaSegura : IntentoDeRecuperacion()
    data class Hecho(
        val resultado: Result<Unit>,
        val tipo: String,
        val atoradaDesdeMs: Long?,
    ) : IntentoDeRecuperacion()
}

/** A qué cuenta vuelve la recuperación (nunca «la primaria» por default en multicuenta). */
sealed class ObjetivoDeRecuperacion {
    data class Cuenta(val accountId: String) : ObjetivoDeRecuperacion()
    /** El venue tiene UNA cuenta: el genérico es seguro. */
    object UnicaCuenta : ObjetivoDeRecuperacion()
    /** Varias cuentas (o no se sabe cuántas) y ninguna conocida: no se autentica. */
    object Ninguno : ObjetivoDeRecuperacion()

    override fun toString(): String = when (this) {
        is Cuenta -> "Cuenta($accountId)"
        UnicaCuenta -> "UnicaCuenta"
        Ninguno -> "Ninguno"
    }
}

private const val TAG = "AngelPayAuthRecovery"

/**
 * T26 (Testarudo, 2026-09-11) — la Nexgo recupera su autenticación de AngelPay SOLA cuando
 * vuelve la red.
 *
 * Antes la auth corría UNA vez al crear Home: sin red fallaba y nada la reintentaba; la N86
 * pasó ~4 h sin poder cobrar con tarjeta hasta que alguien reinició la app. Los disparadores
 * (red recuperada, config leída, servidor alcanzable y el botón «Reintentar» del banner)
 * llaman a [recoverIfStuck]; los candados viven aquí y en el repositorio:
 *  - Sólo actúa si la auth está atorada por algo recuperable y el SDK NO tiene sesión — una
 *    sesión viva nunca se toca desde el fondo (ni `switchAccount` ni logout).
 *  - 🔴 Entra con `tryLock` sobre [AngelPayAuthRepository.candadoDeSesion]: si un cobro es
 *    dueño de la sesión («auth → alineación → lanzamiento»), NO entra — así nunca corre
 *    después de la auth del cobro dejando la sesión en otra cuenta (incidente de Amaena).
 *    Y revalida ADENTRO del candado: sin sesión y sin cobro.
 *  - Cobro en curso = `isCharging` o cualquier estado de la pantalla de cobro distinto de
 *    Idle/Error/Success/Queued/Cancelled. Única excepción: el «Reintentar» del banner,
 *    que se toca desde la pantalla de método de pago quieta ([pantallaDeCobroQuieta]).
 *  - Una sola corrida a la vez; dos disparadores juntos no duplican la auth.
 *  - Enfriamiento de [ENFRIAMIENTO_MS] entre corridas de fondo (el botón no espera): con la
 *    red intermitente no se hace una tormenta de logins contra AngelPay.
 *  - Vuelve a la última cuenta conocida o a la del último comercio activo; en un venue con
 *    varias cuentas y ninguna conocida NO autentica (el cobro lo hará con la cuenta elegida).
 *  - Mientras autentica, el estado es [AngelPayAuthState.Recuperando], no `Authenticating`:
 *    apaga la Tarjeta pero no el Efectivo.
 *
 * Sin `BuildConfig`: el candado de sabor lo pone quien llama (Home), así una PAX nunca
 * resuelve esta clase.
 */
@Singleton
class AngelPayAuthRecovery @Inject constructor(
    private val authRepository: AngelPayAuthRepository,
    private val paymentStateProvider: PaymentStateProvider,
    private val clock: AngelPayClock,
) {
    companion object {
        const val ENFRIAMIENTO_MS = 30_000L
    }

    private val enCurso = AtomicBoolean(false)

    @Volatile
    private var ultimoIntentoMs: Long? = null

    /**
     * @param pantallaDeCobroQuieta true SÓLO cuando lo pide el «Reintentar» del banner desde la
     *   pantalla de método de pago sin nada en vuelo: ahí el estado de la pantalla no es un
     *   cobro. Todo lo demás (isCharging, dueño de la sesión) se sigue exigiendo.
     * @param cuentaDelComercioElegido la cuenta del comercio que el cajero tiene elegido en
     *   pantalla (sólo desde el botón): la mejor señal de a qué cuenta volver en multicuenta.
     */
    suspend fun recoverIfStuck(
        trigger: DisparadorRecuperacion,
        pantallaDeCobroQuieta: Boolean = false,
        cuentaDelComercioElegido: String? = null,
    ): ResultadoRecuperacion {
        if (hayCobro(pantallaDeCobroQuieta)) return ResultadoRecuperacion.OMITIDA_COBRO_EN_CURSO
        if (!enCurso.compareAndSet(false, true)) return ResultadoRecuperacion.OMITIDA_EN_CURSO
        try {
            if (!authRepository.estaAtorada()) return ResultadoRecuperacion.OMITIDA_NO_ATORADA
            val ahora = clock.nowMs()
            val ultimo = ultimoIntentoMs
            // Un reloj que retrocede (NTP) no bloquea la recuperación: sólo cuenta un lapso >= 0.
            if (trigger != DisparadorRecuperacion.MANUAL && ultimo != null && (ahora - ultimo) in 0 until ENFRIAMIENTO_MS) {
                Timber.tag(TAG).d("Recuperación omitida por enfriamiento (trigger=$trigger)")
                return ResultadoRecuperacion.OMITIDA_ENFRIAMIENTO
            }
            // 🔴 Dueño de la sesión: si un cobro la tiene (auth → alineación → lanzamiento), no se entra.
            val candado = authRepository.candadoDeSesion
            if (!candado.tryLock()) {
                Timber.tag(TAG).i("Recuperación omitida: un cobro es dueño de la sesión (trigger=$trigger)")
                return ResultadoRecuperacion.OMITIDA_COBRO_EN_CURSO
            }
            val intento = try {
                // Revalida ADENTRO del candado: entre el chequeo de arriba y el tryLock pudo abrirse un cobro.
                if (hayCobro(pantallaDeCobroQuieta)) return ResultadoRecuperacion.OMITIDA_COBRO_EN_CURSO
                authRepository.recuperarSiSigueAtorada(pantallaDeCobroQuieta, cuentaDelComercioElegido)
            } finally {
                candado.unlock()
            }
            val hecho = when (intento) {
                IntentoDeRecuperacion.NoAtorada -> return ResultadoRecuperacion.OMITIDA_NO_ATORADA
                IntentoDeRecuperacion.CobroEnCurso -> return ResultadoRecuperacion.OMITIDA_COBRO_EN_CURSO
                IntentoDeRecuperacion.SinCuentaSegura -> {
                    Timber.tag(TAG).w("Recuperación omitida: varias cuentas y ninguna conocida — el cobro autenticará la elegida")
                    return ResultadoRecuperacion.OMITIDA_SIN_CUENTA
                }
                is IntentoDeRecuperacion.Hecho -> intento
            }
            ultimoIntentoMs = ahora
            if (hecho.resultado.isFailure) {
                Timber.tag(TAG).w(hecho.resultado.exceptionOrNull(), "Recuperación de la auth falló (trigger=$trigger)")
                return ResultadoRecuperacion.FALLIDA
            }
            val segundos = hecho.atoradaDesdeMs?.let { ((clock.nowMs() - it) / 1_000L).coerceAtLeast(0L) }
            authRepository.reportarAtoradaRecuperada(
                "atorada ${segundos ?: "?"}s tipo=${hecho.tipo} trigger=${trigger.name}",
            )
            return ResultadoRecuperacion.RECUPERADA
        } finally {
            enCurso.set(false)
        }
    }

    private fun hayCobro(pantallaDeCobroQuieta: Boolean): Boolean =
        paymentStateProvider.isCharging() ||
            (!pantallaDeCobroQuieta && paymentStateProvider.isChargeAttemptActive())

    /**
     * Despierta la recuperación cuando `hasServer` SUBE de false a true. El valor inicial
     * (true por default en ConnectionState) no cuenta: no es un regreso de la red.
     */
    suspend fun observarServidor(hasServer: Flow<Boolean>) {
        var anterior: Boolean? = null
        hasServer.distinctUntilChanged().collect { actual ->
            val subio = anterior == false && actual
            anterior = actual
            if (subio) recoverIfStuck(DisparadorRecuperacion.SERVIDOR_ALCANZABLE)
        }
    }
}
