package com.jaac.avoqado_campo.version

import android.util.Log
import com.jaac.avoqado_campo.AlcanceDeLaApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** En qué va «Actualizar» (sólo en memoria: al reabrir la app empieza en [Quieto]). */
sealed interface PasoDeActualizacion {
    object Quieto : PasoDeActualizacion
    object Bajando : PasoDeActualizacion
    object Instalando : PasoDeActualizacion
    object FaltaPermiso : PasoDeActualizacion
    data class Fallo(val texto: String) : PasoDeActualizacion
}

internal const val NO_SE_PUDO_COMPROBAR = "No se pudo comprobar la versión nueva. Avisa a tu supervisor"
internal const val NO_SE_PUDO_BAJAR = "No se pudo bajar la versión nueva. Intenta otra vez con señal."

/**
 * «Actualizar»: bajar → comprobar → instalar. Lo que no pasa (una liga mala o un archivo que no es de la app) se marca
 * inválido y no se instala, y la obligatoria sigue (D-3). Sin «Permitir de esta fuente», al regresar de Ajustes con el
 * permiso instala el archivo ya comprobado, sin volver a bajarlo; si lo negó, no insiste y el aviso sigue.
 */
@Singleton
class Actualizador @Inject constructor(
    private val revisor: RevisorDeVersion,
    private val descargador: Descargador,
    private val comprobador: Comprobador,
    private val instalador: Instalador,
    @AlcanceDeLaApp private val alcance: CoroutineScope,
) {
    private val _paso = MutableStateFlow<PasoDeActualizacion>(PasoDeActualizacion.Quieto)
    val paso: StateFlow<PasoDeActualizacion> = _paso
    private val candado = Mutex()
    /** El archivo que ya pasó la revisión, y de qué versión (para instalar al regresar de Ajustes). */
    private var comprobado: Pair<Int, File>? = null

    /** Para la pantalla: corre en el alcance de la app (salir del Inicio no corta la bajada). */
    fun empezar() {
        alcance.launch { actualizar() }
    }

    suspend fun actualizar() {
        if (!candado.tryLock()) return // ya hay una en curso
        try {
            val v = revisor.nueva.value ?: return
            _paso.value = PasoDeActualizacion.Bajando
            val archivo = comprobado?.takeIf { it.first == v.versionCode && it.second.exists() }?.second ?: bajarYComprobar(v) ?: return
            _paso.value = when (val r = instalador.instalar(archivo)) {
                ResultadoInstalacion.FaltaPermiso -> PasoDeActualizacion.FaltaPermiso
                ResultadoInstalacion.Enviado -> PasoDeActualizacion.Instalando
                is ResultadoInstalacion.Fallo -> PasoDeActualizacion.Fallo("No se instaló: ${r.motivo}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "actualizar", e)
            _paso.value = PasoDeActualizacion.Fallo(NO_SE_PUDO_BAJAR)
        } finally {
            candado.unlock()
        }
    }

    /** El archivo comprobado, o null (y el paso ya dice por qué). */
    private suspend fun bajarYComprobar(v: VersionNueva): File? {
        val archivo = when (val b = descargador.bajar(v)) {
            is Bajada.Lista -> b.archivo
            Bajada.SinRed -> {
                _paso.value = PasoDeActualizacion.Fallo(NO_SE_PUDO_BAJAR)
                return null
            }
            is Bajada.Mala -> return invalida(v, b.motivo)
        }
        return when (val c = comprobador.comprobar(archivo, v)) {
            is Bajada.Mala -> {
                archivo.delete()
                invalida(v, c.motivo)
            }
            else -> archivo.also { comprobado = v.versionCode to it }
        }
    }

    private fun invalida(v: VersionNueva, motivo: String): File? {
        Log.w(TAG, "la versión ${v.versionCode} no pasó la revisión: $motivo")
        revisor.marcarInvalida(v.versionCode)
        _paso.value = PasoDeActualizacion.Fallo(NO_SE_PUDO_COMPROBAR)
        return null
    }

    /** Al volver a la app (de Ajustes o del diálogo de Android). */
    fun alRegresar() {
        when (_paso.value) {
            PasoDeActualizacion.FaltaPermiso -> if (instalador.puedeInstalar()) empezar()
            // El diálogo de Android se cerró sin instalar (si instaló, esta app ya no estaría corriendo).
            PasoDeActualizacion.Instalando -> _paso.value = PasoDeActualizacion.Quieto
            else -> Unit
        }
    }

    fun alFallarLaInstalacion(f: ResultadoInstalacion.Fallo) {
        _paso.value = PasoDeActualizacion.Fallo("No se instaló: ${f.motivo}")
    }

    /** Al arrancar el proceso: lo que quedó a medias de una instalación o una bajada anterior se descarta. */
    fun alArrancar() {
        instalador.abandonarLasAbiertas()
        descargador.limpiar()
    }

    private companion object { const val TAG = "Actualizador" }
}
