package com.jaac.avoqado_campo.copia

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.jaac.avoqado_campo.BuildConfig
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.cola.normalizarSerie
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.GuardiaDeSesion
import com.jaac.avoqado_campo.red.Llamada
import com.jaac.avoqado_campo.red.ResultadoConSesion
import com.jaac.avoqado_campo.red.llamarSeguro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * La copia de «Mis SIMs» y de la configuración de la sucursal, en `filesDir/copia.json`. Todo lo que la lee y la escribe va
 * bajo UN candado propio (R8-1), que también toma el borrado del celular ([borrar]); y nadie escribe si el celular se está
 * borrando o cambió de activación mientras bajaba (R7-1, I2).
 */
class RepositorioDeCopia(
    context: Context,
    private val api: CampoApi,
    private val guardia: GuardiaDeSesion,
    private val dao: PiezaDao,
    private val quienSoy: QuienSoy,
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    private val archivo = File(context.filesDir, "copia.json")
    private val gson = Gson()
    private val candado = Mutex()

    private val _copia = MutableStateFlow(leerArchivo()?.takeIf { vigente(it) })
    val copia: StateFlow<Copia?> = _copia

    /** La copia vigente; la de otra versión de la app, otra activación u otra sucursal se borra y da null. */
    suspend fun leer(): Copia? = candado.withLock {
        val c = leerArchivo()
        if (c == null || !vigente(c)) {
            archivo.delete()
            _copia.value = null
            return@withLock null
        }
        _copia.value = c
        c
    }

    /**
     * Baja `mis-sims` (con la sesión) y `tpv/modules` (sin token, B2). Las marcas locales salen de lo que sigue por subir en
     * la cola. false = no se escribió nada (sin red, sin sesión de esa sucursal, o el celular cambió mientras bajaba).
     */
    suspend fun actualizar(venueId: String): Boolean {
        val generacion = quienSoy.generacion
        if (quienSoy.limpiando) return false
        val misSims = (guardia.conSesion(venueId) { api.misSims(it, venueId) } as? ResultadoConSesion.Respuesta)
            ?.r?.takeIf { it.isSuccessful }?.body() ?: return false
        val modulos = (llamarSeguro { api.modulos(venueId) } as? Llamada.Contesto)
            ?.r?.takeIf { it.isSuccessful }?.body() ?: return false

        return candado.withLock {
            // Las marcas se leen bajo el candado: un `marcar` de una venta recién encolada espera y cae sobre esta copia.
            val marcas = marcasDeLaCola()
            if (!puedeEscribir(generacion)) return@withLock false
            val sims = misSims.items.orEmpty().filterNotNull().map { it.aSim(marcas[normalizarSerie(it.serialNumber!!)]) }
            publicar(Copia(BuildConfig.VERSION_CODE, venueId, generacion, sims, configDe(modulos), ahora()))
            true
        }
    }

    /** La pantalla, justo después de encolar la venta o la custodia de [serial]. Sin copia vigente no hace nada. */
    suspend fun marcar(serial: String, marca: String) = candado.withLock {
        val c = _copia.value ?: return@withLock
        if (!puedeEscribir(c.generacion)) return@withLock
        val s = normalizarSerie(serial)
        publicar(c.copy(sims = c.sims.map { if (normalizarSerie(it.serialNumber) == s) it.copy(localmente = marca) else it }))
    }

    /** Lo llama el borrado del celular (R8-1, R9-1): archivo y `StateFlow`, bajo el mismo candado que las escrituras. */
    suspend fun borrar() = candado.withLock {
        archivo.delete()
        _copia.value = null
    }

    private fun puedeEscribir(generacion: String) = !quienSoy.limpiando && quienSoy.generacion == generacion

    private fun publicar(c: Copia) {
        val tmp = File(archivo.parentFile, "copia.json.tmp")
        tmp.writeText(gson.toJson(c))
        if (!tmp.renameTo(archivo)) { archivo.delete(); tmp.renameTo(archivo) }
        _copia.value = c
    }

    private fun leerArchivo(): Copia? =
        if (!archivo.exists()) null else runCatching { gson.fromJson(archivo.readText(), Copia::class.java) }.getOrNull()

    // Gson no respeta los no-nulos de Kotlin: un archivo a medias trae null donde el tipo dice que no.
    @Suppress("SENSELESS_COMPARISON")
    private fun vigente(c: Copia) = c.sims != null && c.config != null &&
        c.versionApp == BuildConfig.VERSION_CODE && c.generacion == quienSoy.generacion && c.venueId == quienSoy.sesion?.venueId

    /** Serie normalizada → marca, de las ventas y custodias que siguen por subir (las HECHA ya están en `mis-sims`). */
    private suspend fun marcasDeLaCola(): Map<String, String> {
        val m = mutableMapOf<String, String>()
        // La venta gana: una SIM aceptada y luego vendida sin señal ya es «vendida».
        for (p in dao.conSimsPorSubir().sortedBy { if (it.tipo == TipoPieza.VENTA) 1 else 0 }) {
            val cuerpo = runCatching { JsonParser.parseString(p.cuerpoJson).asJsonObject }.getOrNull() ?: continue
            when (p.tipo) {
                TipoPieza.VENTA -> cuerpo.serie()?.let { m[it] = MarcaLocal.VENDIDA_EN_EL_CELULAR }
                TipoPieza.CUSTODIA_RECHAZAR -> cuerpo.serie()?.let { m[it] = MarcaLocal.RECHAZADA_EN_EL_CELULAR }
                TipoPieza.CUSTODIA_ACEPTAR -> cuerpo["sims"]?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { e ->
                    e.serie()?.let { m[it] = MarcaLocal.ACEPTADA_EN_EL_CELULAR }
                }
            }
        }
        return m
    }

    private fun JsonElement.serie(): String? = runCatching {
        asJsonObject["serialNumber"]?.asString?.let(::normalizarSerie)
    }.getOrNull()
}
