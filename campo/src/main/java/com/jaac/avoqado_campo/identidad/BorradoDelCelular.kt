package com.jaac.avoqado_campo.identidad

import com.jaac.avoqado_campo.cola.MutexDeSubida
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.gps.ControlDeGps
import com.jaac.avoqado_campo.gps.EstadoDelGps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * El ÚNICO camino para volver a activar (rediseño v7): deja el celular vacío, como recién instalado. Si el proceso muere a
 * la mitad, `limpiando` sigue puesto y [terminarSiQuedoAMedias] lo repite entero al arrancar.
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
) {
    suspend fun borrarTodo() {
        borrar(exigirColaVacia = false)
    }

    /** false = con [exigirColaVacia] apareció algo vivo ya con la marca puesta: no se borra nada y se quita la marca. */
    private suspend fun borrar(exigirColaVacia: Boolean): Boolean = withContext(NonCancellable) {
        // 1 · La marca y una generación nueva ANTES de borrar nada: toda respuesta en vuelo ya no escribe (R8-1, I2).
        quienSoy.empezarLimpieza()
        if (exigirColaVacia) {
            // Room pone en fila las transacciones: un encolado ya confirmado se ve aquí, y uno posterior ve `limpiando` y lanza.
            var vivas = 0
            dao.enTransaccion { vivas = dao.cuantasVivas() }
            if (vivas > 0) {
                quienSoy.marcarLimpiando(false)
                return@withContext false
            }
        }
        // 2 · Espera a que termine un vaciado; y ningún `activar`/`entrar`/`renovar` en vuelo (mismo orden que el Subidor).
        mutexDeSubida.mutex.withLock {
            mutexDeSesion.mutex.withLock {
                gps.detener()
                withContext(Dispatchers.IO) {
                    vaciarBase() // antes que los archivos: una transacción a medias no deja un archivo sin fila
                    carpetaCola.deleteRecursively()
                }
                copia.borrar()
                pinLocal.reiniciar()
                estadoDelGps.reiniciar()
                quienSoy.borrarTodo() // generación y aparatoId nuevos, y la activación pendiente
                copia.borrar() // R9-1: por si una descarga alcanzó a escribir entre los pasos
                quienSoy.marcarLimpiando(false)
            }
        }
        true
    }

    /** Lo llama la app al arrancar, antes de mostrar nada (Tarea 13). */
    suspend fun terminarSiQuedoAMedias() {
        if (quienSoy.limpiando) borrarTodo()
    }

    /**
     * «Activar con un código nuevo» / «Borrar este celular y activarlo de nuevo». `DESVINCULADO`/`DADO_DE_BAJA` sólo con la
     * cola vacía (B6); `DESCONOCIDO` sólo con [confirmado] (lo que quede se pierde). true = se borró.
     */
    suspend fun activarDeNuevo(confirmado: Boolean): Boolean {
        return when (quienSoy.estado) {
            // La revisión de afuera evita estrenar generación sin motivo; la de adentro cierra el hueco hasta la marca.
            EstadoDelCelular.DESVINCULADO, EstadoDelCelular.DADO_DE_BAJA -> dao.cuantasVivas() == 0 && borrar(exigirColaVacia = true)
            EstadoDelCelular.DESCONOCIDO -> confirmado && borrar(exigirColaVacia = false)
            else -> false
        }
    }
}
