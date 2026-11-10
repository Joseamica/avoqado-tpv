package com.jaac.avoqado_campo.identidad

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Clave-valor donde `escribir` es UNA escritura atómica (`commit`); `null` borra la clave. */
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

    override fun escribir(cambios: Map<String, String?>) {
        val e = prefs.edit()
        cambios.forEach { (k, v) -> if (v == null) e.remove(k) else e.putString(k, v) }
        e.commit()
    }
}
