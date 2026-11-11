package com.jaac.avoqado_campo.identidad

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * r10: `commit()` dijo que no guardó en disco. Android ya cambió la copia en memoria (el proceso ve el valor nuevo y la
 * siguiente escritura que sí guarde lo lleva a disco), pero lo que necesita estar en disco antes de seguir (la marca del
 * borrado) se entera aquí. Cada llamador decide: reintentar después o decir «No se pudo guardar, intenta otra vez».
 */
class AlmacenNoGuardo : Exception("El celular no pudo guardar el dato")

/** Clave-valor donde `escribir` es UNA escritura atómica (`commit`); `null` borra la clave. Lanza [AlmacenNoGuardo]. */
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

    override fun escribir(cambios: Map<String, String?>) = guardar(prefs.edit(), cambios)
}

/** UNA escritura con `commit()`; si no guardó, [AlmacenNoGuardo] (antes se ignoraba el `false`). */
internal fun guardar(e: SharedPreferences.Editor, cambios: Map<String, String?>) {
    cambios.forEach { (k, v) -> if (v == null) e.remove(k) else e.putString(k, v) }
    if (!e.commit()) throw AlmacenNoGuardo()
}
