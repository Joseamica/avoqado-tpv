package com.jaac.avoqado_campo.version

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.jaac.avoqado_campo.BuildConfig
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.Llamada
import com.jaac.avoqado_campo.red.RespuestaCheckUpdate
import com.jaac.avoqado_campo.red.llamarSeguro
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pregunta al SERVIDOR por la versión nueva del carril de la app de campo (como mucho cada 30 min, salvo [revisar] con
 * `forzar`) y recuerda la última respuesta para arrancar sin red.
 *
 * - [obligatoria] = el `minVersionCode` pendiente es mayor que la instalada (Codex r1 #3). Ese mínimo se guarda con
 *   `commit()` en el momento (Codex r2 #3): si la app muere justo después y arranca sin red, sigue obligada. Si el `commit()`
 *   no guarda, se registra y el candado sigue en memoria: relaja un candado, no pierde datos (no es la R-D de identidad).
 * - Al cargar se descarta lo ya instalado (`versionCode <= instalada` y un mínimo ya cumplido): tras actualizar, un proceso
 *   nuevo sin red no vuelve a pedir «Actualiza» (Codex r1 #6).
 * - [marcarInvalida] (D-3): esa versión ya no se ofrece para bajar, aunque el SERVIDOR la siga mandando, pero la obligación
 *   sigue; se arregla en el panel (desactivar la fila mala o subir una buena con código mayor).
 */
class RevisorDeVersion(
    private val api: CampoApi,
    private val prefs: SharedPreferences,
    private val instalada: Int = BuildConfig.VERSION_CODE,
    private val ambiente: String = BuildConfig.AMBIENTE_DE_VERSIONES,
    private val monotonico: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _nueva = MutableStateFlow<VersionNueva?>(null)
    val nueva: StateFlow<VersionNueva?> = _nueva
    private val _obligatoria = MutableStateFlow(false)
    val obligatoria: StateFlow<Boolean> = _obligatoria

    private val candado = Mutex()
    private var ultimaConsulta: Long? = null
    private var guardada: VersionNueva? = null

    // Sólo lee (no escribe: también corre con la app DETENIDA, r12). Lo ya instalado se ignora aquí y la siguiente respuesta
    // del SERVIDOR lo reemplaza en disco.
    init {
        _obligatoria.value = prefs.getInt(MINIMO, 0) > instalada
        val code = prefs.getInt(CODE, 0)
        guardada = if (code <= instalada) null else VersionNueva(
            code, prefs.getString(NOMBRE, null).orEmpty(), prefs.getString(URL, null).orEmpty(), prefs.getString(SHA, null).orEmpty(),
            prefs.getLong(TAMANO, 0), _obligatoria.value, prefs.getString(NOTAS, null),
        )
        publicar()
    }

    /** true = el SERVIDOR contestó (y se aplicó); false = sin red, ilegible, error o todavía no toca. */
    suspend fun revisar(forzar: Boolean = false): Boolean = candado.withLock {
        val t = monotonico()
        if (!forzar && ultimaConsulta?.let { t - it < TREINTA_MIN } == true) return@withLock false
        val r = (llamarSeguro { api.checkUpdate(instalada, ambiente, PLATAFORMA) } as? Llamada.Contesto)?.r ?: return@withLock false
        val b = r.body()
        if (!r.isSuccessful || b == null) {
            r.errorBody()?.close()
            Log.w(TAG, "check-update contestó HTTP ${r.code()}")
            return@withLock false
        }
        ultimaConsulta = t
        aplicar(b)
        true
    }

    fun marcarInvalida(versionCode: Int) {
        val invalidas = prefs.getStringSet(INVALIDAS, emptySet()).orEmpty() + versionCode.toString()
        prefs.edit().putStringSet(INVALIDAS, invalidas).apply()
        publicar()
    }

    private fun aplicar(b: RespuestaCheckUpdate) {
        val u = b.update
        val minimo = b.minVersionCode?.takeIf { it > instalada }
        guardarMinimo(minimo)
        if (b.hasUpdate != true || u == null || u.versionCode!! <= instalada) {
            guardada = null
            olvidarLaGuardada()
        } else {
            val v = VersionNueva(
                u.versionCode, u.versionName!!, u.downloadUrl!!, u.checksum.orEmpty(),
                u.fileSize?.toLongOrNull() ?: 0, minimo != null, u.releaseNotes,
            )
            guardada = v
            // Sólo para mostrar el aviso sin red; perderlo no pierde nada (la siguiente consulta lo trae otra vez).
            prefs.edit().putInt(CODE, v.versionCode).putString(NOMBRE, v.versionName).putString(URL, v.url).putString(SHA, v.sha256)
                .putLong(TAMANO, v.tamano).putString(NOTAS, v.notas).apply()
        }
        publicar()
    }

    @SuppressLint("ApplySharedPref") // Codex r2 #3: el candado se guarda en el momento.
    private fun guardarMinimo(minimo: Int?) {
        _obligatoria.value = minimo != null
        if (minimo == prefs.getInt(MINIMO, 0).takeIf { it > 0 }) return
        val e = prefs.edit()
        val ok = if (minimo == null) e.remove(MINIMO).commit() else e.putInt(MINIMO, minimo).commit()
        if (!ok) Log.e(TAG, "no se guardó el mínimo obligatorio ($minimo): el candado sigue en memoria")
    }

    private fun olvidarLaGuardada() =
        prefs.edit().remove(CODE).remove(NOMBRE).remove(URL).remove(SHA).remove(TAMANO).remove(NOTAS).apply()

    private fun publicar() {
        val invalidas = prefs.getStringSet(INVALIDAS, emptySet()).orEmpty()
        _nueva.value = guardada?.takeIf { it.versionCode.toString() !in invalidas }?.copy(obligatoria = _obligatoria.value)
    }

    internal companion object {
        const val MINIMO = "minimo"
        const val PLATAFORMA = "ANDROID_CAMPO"
        private const val CODE = "v.code"
        private const val NOMBRE = "v.nombre"
        private const val URL = "v.url"
        private const val SHA = "v.sha256"
        private const val TAMANO = "v.tamano"
        private const val NOTAS = "v.notas"
        private const val INVALIDAS = "invalidas"
        private const val TREINTA_MIN = 30 * 60_000L
        private const val TAG = "RevisorDeVersion"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object VersionModule {
    @Provides @Singleton
    fun proveerRevisor(api: CampoApi, @ApplicationContext context: Context): RevisorDeVersion =
        RevisorDeVersion(api, context.getSharedPreferences("version_de_campo", Context.MODE_PRIVATE))

    @Provides @Singleton
    fun proveerDescargador(@ApplicationContext context: Context): Descargador = Descargador(File(context.cacheDir, "actualizacion"))

    @Provides @Singleton
    fun proveerComprobador(@ApplicationContext context: Context): Comprobador =
        Comprobador(context.packageName, firmasInstaladas(context), lectorDelSistema(context))
}
