package com.jaac.avoqado_campo.identidad

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * Clave-valor donde `escribir` es UNA escritura atómica (`commit`); `null` borra la clave.
 *
 * r11 · R-D (fail-stop): si una escritura no queda confirmada en disco, la app se DETIENE en ese momento. Android ya cambió
 * su copia en memoria, y seguir con ella sería trabajar con un estado que desaparece al reiniciar. Al volver a abrir,
 * memoria = disco, y las recuperaciones del arranque (`BorradoDelCelular.terminarSiQuedoAMedias`,
 * `Encolador.completarApartadas`) terminan lo que quedó. Por eso aquí no hay excepción que atrapar.
 * r12: **en el arranque se detiene con pantalla; después, mata el proceso.** Mientras corre la recuperación del arranque
 * ([Arranque.recuperar]) matar el proceso haría un bucle (sin espacio, cada reapertura vuelve a fallar). Ahí se lanza
 * [ArranqueSinGuardar] y la app queda DETENIDA en una pantalla honesta, sin correr ni escribir nada más.
 */
interface AlmacenSeguro {
    fun leer(clave: String): String?
    fun escribir(cambios: Map<String, String?>)
}

class AlmacenSeguroCifrado(context: Context, private val arranque: Arranque) : AlmacenSeguro {
    private val prefs = EncryptedSharedPreferences.create(
        context, "quien_soy",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun leer(clave: String): String? = prefs.getString(clave, null)

    override fun escribir(cambios: Map<String, String?>) = guardar(prefs.edit(), cambios) { arranque.alNoGuardar(it, ::detenerLaApp) }
}

/** r12: una escritura de la recuperación del arranque no se guardó. La atrapa sólo [Arranque.recuperar]. */
class ArranqueSinGuardar(motivo: String) : Exception(motivo)

/**
 * r12 · R-D en el arranque. Durante [recuperar] (antes de cualquier pantalla, WorkManager o GPS) una escritura que no se guarda
 * no mata el proceso: deja la app [detenido], que sólo muestra «Tu celular no tiene espacio o no pudo guardar…» con
 * «Reintentar». «Reintentar» abre un proceso nuevo (memoria = disco) que vuelve a correr la recuperación; reintentar en este
 * mismo proceso no sería seguro, porque la copia en memoria ya quedó distinta del disco.
 */
@Singleton
class Arranque @Inject constructor() {
    @Volatile var enCurso = false
        private set
    @Volatile var detenido = false
        private set

    /** Lo que hace un almacén cuando no guardó: en el arranque [ArranqueSinGuardar]; después, [detener] (matar el proceso). */
    fun alNoGuardar(motivo: String, detener: (String) -> Nothing): Nothing =
        if (enCurso) throw ArranqueSinGuardar(motivo) else detener(motivo)

    /** null = DETENIDO (una escritura no se guardó); si no, lo que devuelve [bloque]. */
    suspend fun <T> recuperar(bloque: suspend () -> T): T? {
        enCurso = true
        return try {
            bloque().also { detenido = false }
        } catch (e: ArranqueSinGuardar) {
            detenido = true
            null
        } finally {
            enCurso = false
        }
    }
}

/** UNA escritura con `commit()`. Si no guardó (o la escritura lanzó), [detener]: nada de lo que sigue corre (R-D). */
internal fun guardar(e: SharedPreferences.Editor, cambios: Map<String, String?>, detener: (String) -> Nothing) {
    val guardo = try {
        cambios.forEach { (k, v) -> if (v == null) e.remove(k) else e.putString(k, v) }
        e.commit()
    } catch (t: Throwable) {
        false
    }
    if (!guardo) detener("no se guardó en disco: ${cambios.keys}") // sólo las claves: los valores pueden ser secretos
}

/**
 * R-D: `killProcess` y no una excepción, porque un `runCatching`, un `catch (e: Throwable)` o WorkManager atraparían una
 * excepción y la app seguiría con la copia en memoria. `exitProcess` por si la señal tarda.
 */
internal fun detenerLaApp(motivo: String): Nothing {
    Log.e("AlmacenSeguro", "R-D · la app se detiene: $motivo")
    Process.killProcess(Process.myPid())
    exitProcess(10)
}
