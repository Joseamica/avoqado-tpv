package com.jaac.avoqado_campo.identidad

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlin.system.exitProcess

/**
 * Clave-valor donde `escribir` es UNA escritura atómica (`commit`); `null` borra la clave.
 *
 * r11 · R-D (fail-stop): si una escritura no queda confirmada en disco, la app se DETIENE en ese momento. Android ya cambió
 * su copia en memoria, y seguir con ella sería trabajar con un estado que desaparece al reiniciar. Al volver a abrir,
 * memoria = disco, y las recuperaciones del arranque (`BorradoDelCelular.terminarSiQuedoAMedias`,
 * `Encolador.completarApartadas`) terminan lo que quedó. Por eso aquí no hay excepción que atrapar.
 */
interface AlmacenSeguro {
    fun leer(clave: String): String?
    fun escribir(cambios: Map<String, String?>)
}

class AlmacenSeguroCifrado(context: Context) : AlmacenSeguro {
    private val prefs = EncryptedSharedPreferences.create(
        context, "quien_soy",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun leer(clave: String): String? = prefs.getString(clave, null)

    override fun escribir(cambios: Map<String, String?>) = guardar(prefs.edit(), cambios, ::detenerLaApp)
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
