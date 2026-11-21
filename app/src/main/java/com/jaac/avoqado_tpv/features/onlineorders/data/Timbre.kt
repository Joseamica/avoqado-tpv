package com.jaac.avoqado_tpv.features.onlineorders.data

import android.media.AudioManager
import android.media.ToneGenerator
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D661: el timbre de «Pedido pagado en línea» (lo toca [VigiaDeFolios] cada 4 s mientras haya uno por abrir). Un tono del sistema en
 * el volumen de notificaciones: con ese volumen en cero la terminal respeta el silencio y queda el aviso en pantalla (como el POS,
 * founder 24-oct «A»). Si el aparato no da el tono, no truena: lo dice el log y el aviso sigue en pantalla.
 */
@Singleton
class Timbre @Inject constructor() {

    private val tono: ToneGenerator? by lazy {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME)
        } catch (e: RuntimeException) {
            Timber.w(e, "🔔 [Folios] Esta terminal no da el tono del aviso")
            null
        }
    }

    fun sonar() {
        tono?.startTone(ToneGenerator.TONE_PROP_BEEP2, DURACION_MS)
    }

    private companion object {
        const val DURACION_MS = 400
    }
}
