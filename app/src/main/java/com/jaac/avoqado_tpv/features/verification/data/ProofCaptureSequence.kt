package com.jaac.avoqado_tpv.features.verification.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contador de capturas de comprobante + id de instalación. El SERVIDOR descarta, por (captureDeviceId, captureSeq),
 * las capturas más viejas de una venta.
 *
 * El "aparato" es un id de INSTALACIÓN, no el serial: si se borran los datos de la app, el contador vuelve a 0 y el id
 * cambia junto con él, así que el servidor no confunde capturas nuevas con viejas. Se escribe con commit() (síncrono):
 * el número ya entregado no se repite aunque el proceso muera justo después.
 */
@Singleton
class ProofCaptureSequence @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("proof_capture", Context.MODE_PRIVATE)

    @Synchronized
    fun next(): Long {
        val n = prefs.getLong("seq", 0L) + 1
        prefs.edit().putLong("seq", n).commit()
        return n
    }

    @Synchronized
    fun deviceId(): String =
        prefs.getString("install_id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("install_id", it).commit() }
}
