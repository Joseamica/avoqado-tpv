package com.jaac.avoqado_tpv.features.giftcards.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.parseBackendErrorCode
import com.jaac.avoqado_tpv.core.data.network.parseBackendErrorMessage
import com.jaac.avoqado_tpv.core.util.CurrencyFormatter
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Cómo se identificó la tarjeta: el código o la liga del QR. Nunca se imprime (ni en logs). */
sealed interface PruebaDeSaldo {
    data class Codigo(val codigo: String) : PruebaDeSaldo { override fun toString() = "Codigo(****)" }
    data class Liga(val pageToken: String) : PruebaDeSaldo { override fun toString() = "Liga(****)" }
}

/** Lo que dice la consulta: los últimos 4 de la tarjeta, el saldo que se puede usar HOY (centavos) y, si no sirve, por qué. */
data class ConsultaDeSaldo(val last4: String, val saldoCents: Long, val usable: Boolean, val razon: String?)

/** Una consulta que no llegó a saldo: el texto para la cajera y si fue por falta de red. */
class FalloDeSaldo(val texto: String, val sinRed: Boolean = false) : Exception(texto)

/**
 * El cobro con saldo de un folio, tal como se guardó ANTES de mandarlo: [cuerpo] son los bytes EXACTOS del POST (llave, prueba,
 * monto y propina). Vive cifrado en `EncryptedSharedPreferences` y sólo se borra con una respuesta definitiva.
 */
data class SolicitudDeSaldo(
    val llave: String,
    val venueId: String,
    val orderId: String,
    val cuerpo: String,
    val creadaEn: Long,
    /** D12: el folio que ve la cajera («W494E»), para avisar sin red. null en las guardadas antes de D12. */
    val folio: String? = null,
) {
    /** D12: el monto del cuerpo, en centavos; null si no se puede leer. Sin campo propio: también sirve en las guardadas antes de D12. */
    val montoCents: Long?
        get() = try {
            JsonParser.parseString(cuerpo).asJsonObject.get("amount")?.takeUnless { it.isJsonNull }?.asBigDecimal?.centavos()
        } catch (e: Exception) {
            null
        }
    /** El cuerpo trae la prueba (el código o la liga): nunca sale en un log. */
    override fun toString() = "SolicitudDeSaldo(llave=$llave, venueId=$venueId, orderId=$orderId, cuerpo=****)"
}

/** Full-testing H4: por qué el folio ya no admite cobro desde esta pantalla. */
enum class FolioCerrado { VENCIDO, PAGADO }

sealed interface DesenlaceDeSaldo {
    /** El SERVIDOR registró el cobro: [restanteCents] es lo que la cuenta todavía debe (0 = pagada). */
    data class Cobrado(val restanteCents: Long, val montoCents: Long, val last4: String?, val saldoRestanteCents: Long?) : DesenlaceDeSaldo
    /**
     * No se cobró nada (o ni se mandó): [texto] dice por qué. Ya no hay solicitud guardada de este intento. [motivo] no nulo = el folio ya no
     * se puede cobrar desde aquí (venció o ya lo pagó otra caja): «Cobrar» queda en gris.
     */
    data class NoSalio(val texto: String, val motivo: FolioCerrado? = null) : DesenlaceDeSaldo
    /** No se sabe: la solicitud sigue guardada y ese folio no se cobra con nada hasta comprobarla. */
    data object Incierto : DesenlaceDeSaldo
}

/** Textos del cobro con saldo en la TPV (mockups v2 2e–2e·4 y `TextosDeTarjetas` de ANDROID). */
object TextosDeSaldo {
    const val TITULO = "Saldo o tarjeta de regalo"
    const val ESCANEAR = "Escanear"
    const val TECLEAR = "Teclear código"
    const val CODIGO = "Código"
    const val ESCANEAR_QR = "Escanear el QR"
    const val AYUDA = "Escanea el QR de la clienta o teclea el código de su tarjeta de regalo."
    const val CONSULTANDO = "Consultando el saldo…"
    const val COMPROBANDO = "Comprobando el cobro con saldo…"
    const val CODIGO_NO_VALIDO = "Código no válido: revísalo con la clienta."
    const val TARJETA_NO_ENCONTRADA = "No encontramos esa tarjeta de regalo en este negocio. Revisa el código."
    const val SIN_RED = "Sin conexión: las tarjetas de regalo necesitan internet"
    const val SIN_PERMISO_COBRAR = "No tienes permiso para cobrar con tarjeta de regalo. Pídeselo a tu gerente."
    const val NO_SE_PUDO_GUARDAR = "No se pudo guardar en el aparato; no se mandó nada. Inténtalo de nuevo."
    const val NO_SE_PUDO_PREPARAR = "No se pudo preparar el cobro. Intenta otra vez."
    const val NO_SE_PUDO_CONSULTAR = "No se pudo consultar la tarjeta. Inténtalo de nuevo."
    const val NO_SE_COBRO = "No se cobró con saldo. Inténtalo de nuevo."
    const val FOLIO_NO_ENCONTRADO = "No encontramos este folio. Vuelve a la lista."
    /** Full-testing H4 (mockup 2c·4). */
    const val FOLIO_VENCIDO = "Este pedido ya venció. Cóbralo como venta normal en «Cobrar»."
    /** Full-testing H4 (mockup 2c·5, sin hora: el rechazo del SERVIDOR no la trae). */
    const val FOLIO_YA_PAGADO = "Este folio ya se pagó. Lo cobró otra caja; ya está en «Por entregar»."
    /** Full-testing H5: «Cobrar» en gris no enseña un monto que no se va a cobrar. */
    const val COBRAR = "Cobrar"
    /** Full-testing H5: el escáner de la tarjeta (QR de la liga o código de barras del certificado). */
    const val INSTRUCCION_DEL_ESCANER = "Coloca el QR o el código de la tarjeta dentro del área"
    const val CANCELAR = "Cancelar"
    const val EN_DUDA_TITULO = "No pudimos confirmar el cobro con saldo"
    const val EN_DUDA_CUERPO = "Revisa la conexión. No cobres este folio otra vez hasta comprobarlo: el saldo pudo haberse cobrado."
    const val VOLVER_A_COMPROBAR = "Volver a comprobar"
    const val SALIR = "Salir"
    const val PAGADO = "Pagado con saldo"
    const val LISTO = "Listo"
    const val LIGA_DE_LA_TARJETA = "Liga de la tarjeta"
    const val REMOTO_COBRANDO_CON_SALDO = "La terminal está cobrando con saldo de tarjeta de regalo"
    const val REMOTO_SALDO_SIN_CONFIRMAR = "Esta cuenta tiene un cobro con saldo sin confirmar en la terminal"
    fun cobrar(cents: Long) = "Cobrar ${pesos(cents)}"
    fun alcanza(saldoCents: Long, montoCents: Long) = "Saldo ${pesos(saldoCents)}: alcanza para ${pesos(montoCents)}"
    fun noAlcanza(saldoCents: Long) = "Saldo ${pesos(saldoCents)}: no alcanza"
    fun cobraYFalta(cobraCents: Long, faltaCents: Long) =
        "Cobra ${pesos(cobraCents)} con saldo; los ${pesos(faltaCents)} que faltan, con tarjeta o efectivo."
    fun noSirve(last4: String, razon: String) = "Tarjeta •••• $last4: $razon"
    fun tarjetaYSaldo(last4: String?, saldoCents: Long?) = listOfNotNull(
        last4?.let { "Tarjeta •••• $it" },
        saldoCents?.let { "le quedan ${pesos(it)}" },
    ).joinToString(" · ")

    /** El mismo mapa que ANDROID (`TextosDeTarjetas.razon`). */
    fun razon(reason: String?): String? = when (reason) {
        "PENDING" -> "Todavía no se cobra"
        "BLOCKED" -> "Bloqueada"
        "EXPIRED" -> "Vencida"
        "DEACTIVATED" -> "Cancelada"
        "EMPTY" -> "Sin saldo"
        else -> null
    }

    fun pesos(cents: Long): String = CurrencyFormatter.format(BigDecimal.valueOf(cents, 2))
}

/**
 * Tienda híbrida, parte 2 (P1): consultar y cobrar con saldo o tarjeta de regalo un folio de la tienda, con el patrón de ANDROID
 * (`giftcards/data/CobroConSaldoCoordinator.kt`):
 *  - el cuerpo COMPLETO del POST (llave `[A-Za-z0-9_-]{8,64}`, prueba, monto, propina) se guarda cifrado ANTES de mandarlo; si no se
 *    puede guardar, no se manda nada;
 *  - se borra sólo con una respuesta definitiva (2xx, o un 4xx que no sea `ORDER_PAYMENT_CONFLICT`, `ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE`,
 *    401, 403, 408, 426 ni 429; nunca un 5xx ni la falta de respuesta). Esos salen ANTES del candado de la llave: no prueban que otra
 *    copia del mismo envío (el reintento de OkHttp, o el envío de antes de morir la app) no cobró;
 *  - [resolver] pregunta por la llave (`GET payments/{key}`): `found:true` es el cobro; `found:false` NO prueba que no se cobró (el
 *    primer POST puede seguir vivo), así que se REENVÍA EL MISMO cuerpo con LA MISMA llave: el SERVIDOR espera al primero bajo el candado
 *    de la llave (`giftCardPayKey.ts`) y responde su cobro, o lo hace él una sola vez. Nunca se genera otra llave para ese intento;
 *  - mientras un folio tenga su solicitud, [cobrar] no arranca otra (con la misma ni con otra tarjeta).
 *
 * Todo pasa bajo un candado (una operación a la vez), fuera del hilo de la pantalla y en `NonCancellable`: salir de la pantalla no deja un
 * envío a medias sin anotar.
 */
@Singleton
class CobroConSaldoRepository @Inject constructor(
    private val api: GiftCardsTpvApi,
    private val apiDeCobro: CobroConSaldoApi,
    private val storage: SecureStorage,
    private val deviceInfoManager: DeviceInfoManager,
) {
    private val candado = Mutex()
    private val gson = Gson()

    /** La consulta de saldo (no mueve dinero). Falla con [FalloDeSaldo]; nunca lanza otra cosa salvo la cancelación. */
    suspend fun consultar(prueba: PruebaDeSaldo): Result<ConsultaDeSaldo> {
        val venueId = storage.getVenueId()?.takeIf { it.isNotBlank() } ?: return Result.failure(FalloDeSaldo(TextosDeSaldo.NO_SE_PUDO_CONSULTAR))
        val cuerpo = when (prueba) {
            is PruebaDeSaldo.Codigo -> mapOf("code" to prueba.codigo)
            is PruebaDeSaldo.Liga -> mapOf("pageToken" to prueba.pageToken)
        }
        val r = try {
            api.consultar(venueId, serie(), cuerpo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Result.failure(FalloDeSaldo(TextosDeSaldo.SIN_RED, sinRed = true))
        } catch (e: Exception) {
            Timber.w(e, "🎁 [Saldo] La consulta falló")
            return Result.failure(FalloDeSaldo(TextosDeSaldo.NO_SE_PUDO_CONSULTAR))
        }
        if (!r.isSuccessful) {
            val crudo = r.errorBody()?.string()
            val texto = when {
                r.code() == 403 -> TextosDeSaldo.SIN_PERMISO_COBRAR
                r.code() == 404 -> parseBackendErrorMessage(crudo, "").takeIf { it.isNotBlank() && parseBackendErrorCode(crudo) != null }
                    ?: TextosDeSaldo.TARJETA_NO_ENCONTRADA
                else -> parseBackendErrorMessage(crudo, "").takeIf { it.isNotBlank() && it != "Sin detalle del servidor" }
                    ?: TextosDeSaldo.NO_SE_PUDO_CONSULTAR
            }
            return Result.failure(FalloDeSaldo(texto))
        }
        val d = r.body()?.data ?: return Result.failure(FalloDeSaldo(TextosDeSaldo.NO_SE_PUDO_CONSULTAR))
        val tarjeta = d.cards?.firstOrNull { it != null }
        val saldo = (d.usableBalance ?: BigDecimal.ZERO).centavos()
        val usable = tarjeta?.usable == true && saldo > 0
        return Result.success(
            ConsultaDeSaldo(
                last4 = tarjeta?.last4.orEmpty(),
                saldoCents = if (usable) saldo else 0,
                usable = usable,
                razon = if (usable) null else TextosDeSaldo.razon(tarjeta?.notUsableReason ?: "EMPTY"),
            ),
        )
    }

    /**
     * Codex r2 #1: el cobro remoto del POS no se admite mientras esta terminal cobra o comprueba un saldo (le arrancaría la pantalla), ni
     * sobre una cuenta con un cobro con saldo sin confirmar (podría cobrarse dos veces). null = puede entrar.
     */
    fun motivoParaNoCobrarRemoto(orderId: String?): String? = when {
        candado.isLocked -> TextosDeSaldo.REMOTO_COBRANDO_CON_SALDO
        orderId != null && pendienteDe(orderId) != null -> TextosDeSaldo.REMOTO_SALDO_SIN_CONFIRMAR
        else -> null
    }

    /** Full-testing H2: los folios de ESTE negocio con un cobro con saldo guardado sin desenlace (una sola lectura del almacén). */
    fun foliosPendientes(): Set<String> = pendientes().mapTo(HashSet()) { it.orderId }

    /** El cobro con saldo de ESTE negocio para [orderId] que todavía no tiene desenlace; null si no hay. */
    fun pendienteDe(orderId: String): SolicitudDeSaldo? {
        val venueId = storage.getVenueId() ?: return null
        return leer().firstOrNull { it.venueId == venueId && it.orderId == orderId }
    }

    /** D12: los cobros con saldo de ESTE negocio guardados sin desenlace (una sola lectura del almacén), para el aviso del Inicio sin red. */
    fun pendientes(): List<SolicitudDeSaldo> {
        val venueId = storage.getVenueId() ?: return emptyList()
        return leer().filter { it.venueId == venueId }
    }

    suspend fun cobrar(orderId: String, prueba: PruebaDeSaldo, montoCents: Long, folio: String? = null): DesenlaceDeSaldo {
        if (!candado.tryLock()) return DesenlaceDeSaldo.NoSalio(TextosDeSaldo.COMPROBANDO)
        try {
            return withContext(NonCancellable + Dispatchers.IO) {
                val venueId = storage.getVenueId()?.takeIf { it.isNotBlank() }
                    ?: return@withContext DesenlaceDeSaldo.NoSalio(TextosDeSaldo.NO_SE_PUDO_PREPARAR)
                if (montoCents <= 0) return@withContext DesenlaceDeSaldo.NoSalio(TextosDeSaldo.NO_SE_PUDO_PREPARAR)
                if (pendienteDe(orderId) != null) return@withContext DesenlaceDeSaldo.NoSalio(TextosDeSaldo.COMPROBANDO)
                val serie = serie() ?: return@withContext DesenlaceDeSaldo.NoSalio(TextosDeSaldo.NO_SE_PUDO_PREPARAR)
                val llave = UUID.randomUUID().toString()
                val s = SolicitudDeSaldo(llave, venueId, orderId, cuerpo(llave, prueba, montoCents), System.currentTimeMillis(), folio)
                if (!guardar(s)) return@withContext DesenlaceDeSaldo.NoSalio(TextosDeSaldo.NO_SE_PUDO_GUARDAR)
                enviar(s, serie, trasDuda = false)
            }
        } finally {
            candado.unlock()
        }
    }

    /** null = ese folio no tiene cobro con saldo guardado. Ocupado (otro cobro o comprobación en curso) = [DesenlaceDeSaldo.Incierto]. */
    suspend fun resolver(orderId: String): DesenlaceDeSaldo? {
        if (pendienteDe(orderId) == null) return null
        if (!candado.tryLock()) return DesenlaceDeSaldo.Incierto
        try {
            return withContext(NonCancellable + Dispatchers.IO) {
                val s = pendienteDe(orderId) ?: return@withContext null
                val r = try {
                    api.cobroDeLaLlave(s.venueId, s.llave)
                } catch (e: IOException) {
                    return@withContext DesenlaceDeSaldo.Incierto
                } catch (e: Exception) {
                    Timber.w(e, "🎁 [Saldo] No se pudo consultar la llave del cobro de %s", orderId)
                    return@withContext DesenlaceDeSaldo.Incierto
                }
                val d = r.body()?.data
                when {
                    !r.isSuccessful || d == null -> DesenlaceDeSaldo.Incierto
                    d.found == true -> d.payment?.let { confirmar(s, it) } ?: DesenlaceDeSaldo.Incierto
                    // found:false NO es «no se cobró»: el primer POST puede seguir vivo. Se reenvía lo MISMO con la MISMA llave.
                    d.found == false -> serie()?.let { enviar(s, it, trasDuda = true) } ?: DesenlaceDeSaldo.Incierto
                    else -> DesenlaceDeSaldo.Incierto
                }
            }
        } finally {
            candado.unlock()
        }
    }

    /**
     * Manda [s] tal cual: los mismos bytes y la misma llave, siempre. [trasDuda] = antes salió otra copia cuyo desenlace no se supo.
     *
     * Un 4xx sólo prueba que no se cobró si NINGUNA otra copia de esta llave puede seguir viva (la familia de Codex r1, r3, r4 y r5):
     *  - Primer envío: no hay otra. El cliente de esta API no reenvía solo (`NetworkModule.clienteDeTarjetasDeRegalo`: sin
     *    `retryOnConnectionFailure`, sin redirecciones y sin el reintento de un 503 con `Retry-After: 0`); el `TokenAuthenticator` sólo repite
     *    tras un 401, que sale antes de tocar el dinero. Aun así no borran las puertas que pudieron frenar a ésta y no a otra ([NO_PRUEBAN_NADA]).
     *  - Tras una duda: NINGÚN 4xx borra la solicitud. No hay evidencia de que la copia anterior terminó (el SERVIDOR no le pone plazo y el reloj
     *    del aparato no lo prueba; Codex r5 F1 tumbó el plazo de 5 min de r4). Sólo la borra un cobro confirmado (2xx o `found:true`).
     */
    private suspend fun enviar(s: SolicitudDeSaldo, serie: String, trasDuda: Boolean): DesenlaceDeSaldo {
        repeat(INTENTOS) { intento ->
            val r: Response<SobreEnvelope<CobroConSaldoDto>> = try {
                apiDeCobro.cobrar(s.venueId, s.orderId, serie, s.cuerpo.toRequestBody(JSON))
            } catch (e: Exception) {
                Timber.w("🎁 [Saldo] Sin respuesta del cobro con saldo de %s: se queda guardado para comprobarlo", s.orderId)
                return DesenlaceDeSaldo.Incierto
            }
            if (r.isSuccessful) return r.body()?.data?.let { confirmar(s, it) } ?: DesenlaceDeSaldo.Incierto
            val crudo = r.errorBody()?.string()
            val codigo = parseBackendErrorCode(crudo)
            val http = r.code()
            when {
                codigo == "IDEMPOTENCY_KEY_REUSED" -> return consultarLlave(s) ?: DesenlaceDeSaldo.Incierto
                codigo == "ORDER_PAYMENT_CONFLICT" -> if (intento < INTENTOS - 1) delay(PAUSA_MS * (intento + 1)) else return DesenlaceDeSaldo.Incierto
                !esDefinitivo(http, codigo, trasDuda) -> return DesenlaceDeSaldo.Incierto
                else -> {
                    quitar(s.llave)
                    val cerrado = folioCerrado(codigo, crudo)
                    return DesenlaceDeSaldo.NoSalio(texto(http, codigo, crudo, cerrado), cerrado)
                }
            }
        }
        return DesenlaceDeSaldo.Incierto
    }

    private suspend fun consultarLlave(s: SolicitudDeSaldo): DesenlaceDeSaldo? = try {
        api.cobroDeLaLlave(s.venueId, s.llave).body()?.data?.takeIf { it.found == true }?.payment?.let { confirmar(s, it) }
    } catch (e: Exception) {
        null
    }

    private fun confirmar(s: SolicitudDeSaldo, p: CobroConSaldoDto): DesenlaceDeSaldo {
        // La llave es de este folio: un cobro de otra cuenta con ella sería un error del SERVIDOR. No se borra nada.
        if (p.orderId != null && p.orderId != s.orderId) return DesenlaceDeSaldo.Incierto
        val restante = p.orderRemaining?.centavos() ?: return DesenlaceDeSaldo.Incierto
        quitar(s.llave)
        val canje = p.redeemed?.firstOrNull { it != null }
        return DesenlaceDeSaldo.Cobrado(
            restanteCents = restante.coerceAtLeast(0),
            montoCents = p.amount?.centavos() ?: 0,
            last4 = canje?.last4,
            saldoRestanteCents = canje?.balanceAfter?.centavos(),
        )
    }

    /** Full-testing H4: el folio venció (o se canceló) o ya lo pagó otra caja. */
    private fun folioCerrado(codigo: String?, crudo: String?): FolioCerrado? = when {
        codigo == "ORDER_CANCELLED_NO_NEW_CHARGE" || codigo == "ORDER_CANCELLED_NO_EDIT" -> FolioCerrado.VENCIDO
        codigo == null && parseBackendErrorMessage(crudo, "") == "Order is already paid" -> FolioCerrado.PAGADO
        else -> null
    }

    private fun texto(http: Int, codigo: String?, crudo: String?, cerrado: FolioCerrado?): String {
        val delServidor = parseBackendErrorMessage(crudo, "").takeIf { it.isNotBlank() && it != "Sin detalle del servidor" }
        return when {
            cerrado == FolioCerrado.VENCIDO -> TextosDeSaldo.FOLIO_VENCIDO
            cerrado == FolioCerrado.PAGADO -> TextosDeSaldo.FOLIO_YA_PAGADO
            http == 403 -> TextosDeSaldo.SIN_PERMISO_COBRAR
            codigo == "GIFT_CARD_NOT_FOUND" -> delServidor ?: TextosDeSaldo.TARJETA_NO_ENCONTRADA
            // Sin código y en inglés: los dos de la cuenta que ANDROID ya traduce.
            codigo == null && http == 404 -> TextosDeSaldo.FOLIO_NO_ENCONTRADO
            else -> delServidor ?: TextosDeSaldo.NO_SE_COBRO
        }
    }

    private fun cuerpo(llave: String, prueba: PruebaDeSaldo, montoCents: Long): String = JsonObject().apply {
        addProperty("idempotencyKey", llave)
        add(
            "proof",
            JsonObject().apply {
                when (prueba) {
                    is PruebaDeSaldo.Codigo -> { addProperty("kind", "CODE"); addProperty("code", prueba.codigo) }
                    is PruebaDeSaldo.Liga -> { addProperty("kind", "PAGE_TOKEN"); addProperty("pageToken", prueba.pageToken) }
                }
            },
        )
        // Pesos con sus centavos exactos, como número JSON (el SERVIDOR valida el texto del número: nunca pasa por Double).
        addProperty("amount", BigDecimal.valueOf(montoCents, 2))
        addProperty("tip", BigDecimal.ZERO)
    }.toString()

    private fun serie(): String? = try {
        deviceInfoManager.getSerialNumber().takeIf { it.isNotBlank() }
    } catch (e: SecurityException) {
        null
    }

    private fun leer(): List<SolicitudDeSaldo> {
        val crudo = storage.getString(LLAVE_DEL_ALMACEN) ?: return emptyList()
        return try {
            gson.fromJson(crudo, Array<SolicitudDeSaldo>::class.java)?.filter { s ->
                // Gson no respeta la nulabilidad de Kotlin: una fila incompleta no se toma por buena.
                listOf<Any?>(s.llave, s.venueId, s.orderId, s.cuerpo).none { it == null }
            }.orEmpty()
        } catch (e: Exception) {
            Timber.e(e, "🎁 [Saldo] No se pudieron leer los cobros con saldo guardados")
            emptyList()
        }
    }

    private fun guardar(s: SolicitudDeSaldo): Boolean = escribir(leer() + s)



    private fun quitar(llave: String) {
        if (!escribir(leer().filterNot { it.llave == llave })) {
            // Sigue guardada: la próxima comprobación la encuentra por su llave (found:true) y la vuelve a cerrar.
            Timber.e("🎁 [Saldo] No se pudo borrar el cobro con saldo ya resuelto; se volverá a comprobar")
        }
    }

    private fun escribir(lista: List<SolicitudDeSaldo>): Boolean = try {
        storage.putStringDurably(LLAVE_DEL_ALMACEN, gson.toJson(lista))
        true
    } catch (e: Exception) {
        Timber.e(e, "🎁 [Saldo] No se pudo escribir en el almacén seguro")
        false
    }

    private companion object {
        const val LLAVE_DEL_ALMACEN = "tienda_hibrida_cobros_con_saldo"
        const val INTENTOS = 3
        const val PAUSA_MS = 400L
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** Lo que NO prueba que el cobro no pasó: se conserva la solicitud y se vuelve a comprobar con la misma llave. */
        val REINTENTABLES = setOf("ORDER_PAYMENT_CONFLICT", "ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE")

        /**
         * Lo que contestan las puertas de antes del candado de la llave según la sesión, el permiso, la versión o el ritmo, y no según el
         * cuerpo: 401 y 403 (sesión, permiso, negocio, mesa ajena), 408, 426 (actualización obligatoria, `tpv-version-gate`) y 429. Una copia
         * de un mismo envío (el reintento de OkHttp, o el envío de antes de morir la app) puede recibir uno de éstos mientras la otra sí
         * cobró: nunca prueban que no pasó (Codex r1 #1 y r3 #1; el SERVIDOR deja fuera de lo definitivo los mismos en el saldo a favor,
         * `giftCardErrors.ts`). Los demás 4xx de antes del candado dependen sólo del cuerpo (esquema, tarjeta, cuenta), así que la otra
         * copia recibió lo mismo.
         */
        val NO_PRUEBAN_NADA = setOf(401, 403, 408, 426, 429)

        fun esDefinitivo(http: Int, codigo: String?, trasDuda: Boolean): Boolean = when {
            // Tras una duda nada prueba que la copia anterior terminó: ningún rechazo la borra (Codex r5 F1). Sale con un cobro confirmado.
            trasDuda -> false
            http !in 400..499 -> false
            codigo in REINTENTABLES -> false
            http in NO_PRUEBAN_NADA -> false
            else -> true
        }
    }
}

/** Pesos (número JSON) → centavos exactos, sin pasar por Double. */
private fun BigDecimal.centavos(): Long = setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
