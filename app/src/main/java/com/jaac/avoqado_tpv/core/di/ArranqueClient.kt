package com.jaac.avoqado_tpv.core.di

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier

/**
 * El cliente de red de las DOS preguntas que hace el logo de «Avoqado TPV» antes de dejar vender: «¿sigo activada?» y
 * «¿qué módulos tengo?» (SplashScreen de AppNavigation). Encargo 097e01, 21-nov-2026.
 *
 * **Por qué existe:** con el SERVIDOR mudo (Wi-Fi bien, nadie contesta) el logo esperaba cada pregunta con el tope del
 * cliente general (25 s) — 62 s medidos en la PAX antes de poder vender en efectivo. Y ese tope de OkHttp empieza cuando
 * la llamada SALE de la fila del `Dispatcher`: en el arranque, la configuración, el latido y la cola de cobros piden al
 * mismo servidor a la vez (5 por servidor), y medimos 4 s de fila.
 *
 * **Qué cambia:** sólo el tope y la fila de esas dos preguntas. Si el servidor no contesta en [ConsultasDelArranque.TOPE_S]
 * segundos, la pregunta falla como cualquier falta de red y el logo sigue el camino «sin red» que ya existía (confía en la
 * activación y los módulos guardados en el aparato). No hay nada que siga vivo al fondo: una respuesta que no llegó a tiempo
 * no existe, igual que hoy después de 25 s.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ArranqueClient

object ConsultasDelArranque {
    /** Lo más que el logo espera CADA pregunta (son dos, una tras otra). */
    const val TOPE_S = 3L

    /**
     * El cliente general con su MISMA configuración (cabeceras, sesión, versión, pin de certificados), pero con su propia
     * fila (`Dispatcher` nuevo: no espera detrás de otras consultas al mismo servidor) y tope de [TOPE_S] segundos en total.
     */
    fun cliente(general: OkHttpClient): OkHttpClient = general.newBuilder()
        .dispatcher(Dispatcher())
        .connectTimeout(TOPE_S, TimeUnit.SECONDS)
        .readTimeout(TOPE_S, TimeUnit.SECONDS)
        .writeTimeout(TOPE_S, TimeUnit.SECONDS)
        .callTimeout(TOPE_S, TimeUnit.SECONDS)
        .build()
}
