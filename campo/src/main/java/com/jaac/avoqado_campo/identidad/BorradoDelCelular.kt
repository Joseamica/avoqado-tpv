package com.jaac.avoqado_campo.identidad

import com.jaac.avoqado_campo.cola.MutexDeSubida
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.gps.ControlDeGps
import com.jaac.avoqado_campo.gps.EstadoDelGps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** [ESPERA]: algo que la persona ya tocó sigue en vuelo después del tope, u otro borrado está en curso; no se borró nada. */
enum class ResultadoDelBorrado { BORRADO, QUEDA_ALGO, ESPERA }

/**
 * El ÚNICO camino para volver a activar (rediseño v7): deja el celular vacío, como recién instalado. r3: los tres que
 * borran (tras «Desvincular», «Activar con un código nuevo» y DESCONOCIDO) pasan por [borrar], que sólo borra a la persona
 * que se decidió borrar y vuelve a contar la cola CON la marca puesta. Si el proceso muere ya confirmado, `limpiando`
 * sigue puesto y [terminarSiQuedoAMedias] lo termina al arrancar.
 */
class BorradoDelCelular(
    private val quienSoy: QuienSoy,
    private val mutexDeSubida: MutexDeSubida,
    private val mutexDeSesion: MutexDeSesion,
    private val gps: ControlDeGps,
    /** `BaseDeCampo.clearAllTables()`: piezas, turno y pings. */
    private val vaciarBase: () -> Unit,
    private val dao: PiezaDao,
    private val carpetaCola: File,
    private val copia: RepositorioDeCopia,
    private val pinLocal: PinLocal,
    private val estadoDelGps: EstadoDelGps,
    /** `cacheDir/fotos`: las fotos tomadas y todavía no registradas (de clientes y de la persona). */
    private val carpetaFotos: File,
) {
    /**
     * r3 #1: el SERVIDOR ya desvinculó a la persona de [generacion]. Se borra sólo si sigue sin nada por subir; si mientras
     * volaba se guardó algo (la checada que esperaba el GPS), NO se borra: queda DESVINCULADO y lo nuevo sube por la gracia.
     */
    suspend fun trasDesvincular(generacion: String): ResultadoDelBorrado =
        borrar(generacion, { dao.cuantasPorSubirAhora() }, esperadas = 0) { marcarDesvinculado(null) }

    /**
     * «Activar con un código nuevo»: `DESVINCULADO`/`DADO_DE_BAJA` sólo con la cola vacía (B6). «Borrar este celular»:
     * `DESCONOCIDO` sólo con [confirmadas] = lo sin subir que la persona vio en el aviso; si ya es otro número, no se borra y
     * la pantalla vuelve a preguntar (lo que queda se pierde, así que se borra exactamente lo que se confirmó).
     */
    suspend fun activarDeNuevo(confirmadas: Int? = null): ResultadoDelBorrado {
        val yo = quienSoy.instantanea()
        if (yo.limpiando) return ResultadoDelBorrado.ESPERA
        return when (yo.estado) {
            EstadoDelCelular.DESVINCULADO, EstadoDelCelular.DADO_DE_BAJA -> borrar(yo.generacion, { dao.cuantasVivas() }, esperadas = 0)
            EstadoDelCelular.DESCONOCIDO ->
                if (confirmadas == null) ResultadoDelBorrado.QUEDA_ALGO else borrar(yo.generacion, { dao.cuantasPorSubirAhora() }, confirmadas)
            else -> ResultadoDelBorrado.QUEDA_ALGO
        }
    }

    /** Lo llama la app al arrancar, antes de mostrar nada (Tarea 13). */
    suspend fun terminarSiQuedoAMedias() {
        if (quienSoy.limpiezaConfirmada) vaciar()
        // Murió antes de revisar la cola: no se sabe si quedó algo vivo, así que no se borra (la persona lo vuelve a pedir).
        else if (quienSoy.limpiando) quienSoy.marcarLimpiando(false)
    }

    /**
     * Sólo BORRADO borra. QUEDA_ALGO: la cola ya no tiene [esperadas]; ESPERA: algo tocado sigue en vuelo tras el tope, u
     * otra persona u otro borrado ganó desde [generacion]. [siQuedaAlgo] corre en los dos si sigue siendo la misma persona.
     */
    private suspend fun borrar(
        generacion: String, contar: suspend () -> Int, esperadas: Int, siQuedaAlgo: QuienSoy.() -> Unit = {},
    ): ResultadoDelBorrado = withContext(NonCancellable) {
        // 1 · r4: lo que la persona ya tocó y todavía no llega a Room (la checada que espera el GPS) también está vivo: se
        // espera a que llegue (tope EN_VUELO_MS), se cuenta (la revisión de afuera evita estrenar generación sin motivo) y se
        // pone la marca, que `empezarLimpiezaSi` niega si en medio se reservó otra cosa (entonces se vuelve a esperar).
        val paso1 = withTimeoutOrNull(EN_VUELO_MS) {
            var r: ResultadoDelBorrado? = null
            while (r == null) {
                quienSoy.enVuelo.first { it == 0 }
                r = when {
                    contar() != esperadas -> ResultadoDelBorrado.QUEDA_ALGO
                    quienSoy.empezarLimpiezaSi(generacion) -> ResultadoDelBorrado.BORRADO
                    quienSoy.instantanea().let { it.generacion != generacion || it.limpiando } -> ResultadoDelBorrado.ESPERA
                    else -> null // se reservó otra cosa entre la cuenta y la marca: se vuelve a esperar
                }
            }
            r
        } ?: ResultadoDelBorrado.ESPERA
        if (paso1 != ResultadoDelBorrado.BORRADO) {
            quienSoy.guardarSi(generacion) { siQuedaAlgo() } // otra persona: no se toca
            return@withContext paso1
        }
        // 2 · Con la marca (y una generación nueva: toda respuesta en vuelo ya no escribe, R8-1, I2) se cuenta otra vez. Room
        // pone en fila las transacciones: un encolado ya confirmado se ve aquí, y uno posterior ve `limpiando` y lanza.
        var n = -1
        dao.enTransaccion { n = contar() }
        if (n != esperadas) {
            quienSoy.siQuedaAlgo() // la marca es nuestra: nadie activó a otra persona
            quienSoy.marcarLimpiando(false)
            return@withContext ResultadoDelBorrado.QUEDA_ALGO
        }
        quienSoy.confirmarLimpieza()
        vaciar()
        ResultadoDelBorrado.BORRADO
    }

    private suspend fun vaciar() = withContext(NonCancellable) {
        // 2 · Espera a que termine un vaciado; y ningún `activar`/`entrar`/`renovar` en vuelo (mismo orden que el Subidor).
        mutexDeSubida.mutex.withLock {
            mutexDeSesion.mutex.withLock {
                gps.detener()
                withContext(Dispatchers.IO) {
                    vaciarBase() // antes que los archivos: una transacción a medias no deja un archivo sin fila
                    carpetaCola.deleteRecursively()
                    carpetaFotos.deleteRecursively()
                }
                copia.borrar()
                pinLocal.reiniciar()
                estadoDelGps.reiniciar()
                quienSoy.borrarTodo() // generación y aparatoId nuevos, y la activación pendiente
                copia.borrar() // R9-1: por si una descarga alcanzó a escribir entre los pasos
                quienSoy.marcarLimpiando(false)
            }
        }
    }

    private companion object {
        /** Mayor que la espera del GPS de la checada (10 s, B9). */
        const val EN_VUELO_MS = 20_000L
    }
}
