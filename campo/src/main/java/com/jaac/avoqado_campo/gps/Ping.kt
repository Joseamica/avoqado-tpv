package com.jaac.avoqado_campo.gps

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/** Una lectura del GPS del turno. No es una pieza de la cola (B4): se manda en lote, sin llave, y se tira a las 24 h. */
@Entity(tableName = "pings")
data class Ping(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val venueId: String,
    val lat: Double,
    val lon: Double,
    val precision: Float,
    val capturadoAt: Long,
)

@Dao
abstract class PingDao {
    @Insert abstract suspend fun insertar(p: Ping): Long

    @Query("SELECT * FROM pings WHERE venueId = :venueId ORDER BY id LIMIT :limite")
    abstract suspend fun deSucursal(venueId: String, limite: Int): List<Ping>

    @Query("SELECT * FROM pings ORDER BY id") abstract suspend fun todos(): List<Ping>
    @Query("DELETE FROM pings WHERE id = :id") abstract suspend fun borrar(id: Long)
    @Query("DELETE FROM pings WHERE capturadoAt < :antesDe") abstract suspend fun borrarAntesDe(antesDe: Long)
    @Query("DELETE FROM pings") abstract suspend fun borrarTodos()

    /** I2: [sigue] se revisa dentro de la transacción, justo antes de escribir; el borrado espera a que termine. */
    @Transaction
    open suspend fun guardarSi(p: Ping, sigue: () -> Boolean): Boolean {
        if (!sigue()) return false
        insertar(p)
        return true
    }
}
