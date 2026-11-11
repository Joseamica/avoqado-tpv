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
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import java.io.File

/** [ESPERA]: otra persona u otro borrado ganó (p. ej. un segundo toque); no se borró nada. */
enum class ResultadoDelBorrado { BORRADO, QUEDA_ALGO, ESPERA }

/**
 * r6 · INVARIANTES (la familia «lo que la persona ya hizo, en vuelo, contra un borrado»):
 * 1. Un borrado que se niega deja TODO como estaba, incluida la generación (`QuienSoy.cancelarLimpieza`).
 * 2. Una checada APARTADA sólo desaparece de dos formas: se completa (`Encolador.completarChecada`), o la borra un borrado
 *    que SÍ siguió hasta el final ([vaciar]): DESCONOCIDO confirmado con su número, o activar/desvincular con la cola vacía
 *    (y con una APARTADA viva la cola no está vacía). Ningún error al completarla la elimina ni borra su foto.
 * 3. Por 1 y 2, una APARTADA con una generación distinta de la vigente no puede existir: la generación sólo cambia de verdad
 *    en [vaciar] (que vacía Room antes) o al activar (que pide la cola vacía).
 *
 * r9 · REGLAS ante una falla del almacenamiento local a media ventana:
 * R-A. Ninguna espera es infinita: la checada que espera la marca tiene tope, y deja de esperar si la marca está CONFIRMADA
 *      (ese borrado siguió: invariante 2). Al agotarse se queda APARTADA y la termina el arranque.
 * R-B. Toda la ventana NO confirmada (desde que la marca pudo escribirse hasta `confirmarLimpieza`) se deshace en UN catch:
 *      `cancelarLimpieza(marca)`. Si deshacer también falla, la marca sin confirmar la deshace el arranque. Ya confirmada, un
 *      fallo de [vaciar] se reintenta una vez; si vuelve a fallar, lo termina el arranque (`terminarSiQuedoAMedias`).
 * R-C. Todo llamador del borrado atiende el error (Ayuda, «Activar con un código nuevo», DESCONOCIDO, el arranque): texto
 *      honesto, sin cerrar la app y sin borrar nada.
 * R-D (r11). Fail-stop del almacén cifrado (`AlmacenSeguro`): si una escritura no se confirma en disco, la app se DETIENE
 *      ahí mismo. Nadie sigue con la copia en memoria; al reabrir, memoria = disco y el arranque termina lo que quedó (la
 *      marca «borrando» se termina, la «revisando» se deshace, la APARTADA se completa). Por eso R-B y el reintento de
 *      [vaciar] sólo ven fallas de Room o de archivos, y un borrado confirmado a medias sólo aparece al ARRANCAR.
 *      r12: en el arranque se detiene con pantalla; después, mata el proceso (`Arranque`: DETENIDO con «Reintentar»).
 *
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
        // Murió antes de revisar la cola: no se sabe si quedó algo vivo, así que no se borra (la persona lo vuelve a pedir), y
        // como todo borrado que se niega, devuelve la generación (r6): una checada APARTADA de antes se sigue completando.
        else if (quienSoy.limpiando) quienSoy.cancelarLimpieza()
    }

    /**
     * Sólo BORRADO borra. QUEDA_ALGO: la cola ya no tiene [esperadas] (r5: cuenta también la checada APARTADA que espera el
     * GPS); ESPERA: otra persona u otro borrado ganó desde [generacion]. [siQuedaAlgo] corre si sigue siendo la misma persona.
     */
    private suspend fun borrar(
        generacion: String, contar: suspend () -> Int, esperadas: Int, siQuedaAlgo: QuienSoy.() -> Unit = {},
    ): ResultadoDelBorrado = withContext(NonCancellable) {
        // 1 · Antes de la marca. La revisión de afuera evita estrenar generación sin motivo; una falla aquí no cambió nada.
        // r5: lo que la persona ya tocó está en Room desde el toque (la checada APARTADA), así que contar basta.
        if (contar() != esperadas) {
            quienSoy.guardarSi(generacion) { siQuedaAlgo() } // otra persona: no se toca
            return@withContext ResultadoDelBorrado.QUEDA_ALGO
        }
        // 2 · La ventana NO confirmada (R-B): la marca (con una generación nueva: toda respuesta en vuelo ya no escribe, R8-1,
        // I2) y la segunda cuenta. Room pone en fila las transacciones: un encolado ya confirmado se ve aquí, y uno posterior
        // ve `limpiando` y lanza. Cualquier falla, también la de la escritura de la marca o de `siQuedaAlgo`, la deshace.
        val marca = UUID.randomUUID().toString()
        try {
            if (!quienSoy.empezarLimpiezaSi(generacion, marca)) return@withContext ResultadoDelBorrado.ESPERA // otra persona u otro borrado
            var n = -1
            dao.enTransaccion { n = contar() }
            if (n != esperadas) {
                quienSoy.siQuedaAlgo() // la marca es nuestra: nadie activó a otra persona
                quienSoy.cancelarLimpieza(marca) // r6: se niega → TODO como estaba, también la generación
                return@withContext ResultadoDelBorrado.QUEDA_ALGO
            }
            quienSoy.confirmarLimpieza()
        } catch (e: Throwable) {
            // R-B: TODO como estaba. Si deshacer también falla, la marca sin confirmar la deshace el arranque y la checada que
            // la espera no pasa de su tope (R-A).
            runCatching { quienSoy.cancelarLimpieza(marca) }
            throw e
        }
        // 3 · Confirmada: el borrado sigue (aquí ya no se devuelve nada). Una falla de Room o de archivos se reintenta una vez
        // (una del almacén detiene la app, R-D); si vuelve a fallar, lo termina el arranque, y la checada que esperaba deja de
        // esperar al verla confirmada (R-A). Nadie más vacía en paralelo: activar se niega con la marca puesta.
        try {
            vaciar()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            vaciar()
        }
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

}
