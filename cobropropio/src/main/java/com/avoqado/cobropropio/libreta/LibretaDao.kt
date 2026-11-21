package com.avoqado.cobropropio.libreta

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** Sólo lo usa [Libreta], siempre dentro de sus transacciones. */
@Dao
internal interface LibretaDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertar(i: IntentoEntity): Long

    @Update
    fun actualizar(i: IntentoEntity): Int

    @Query("SELECT * FROM intentos WHERE attemptId = :id")
    fun buscar(id: String): IntentoEntity?

    @Query("SELECT * FROM intentos WHERE operacion = 'CANCELACION' AND operacionOriginal = :original")
    fun cancelacionesDe(original: String): List<IntentoEntity>

    /** Ronda 2 · S: ¿alguna OTRA fila (venta o cancelación, de cualquier venta) ya trae esa lectura? */
    @Query("SELECT EXISTS(SELECT 1 FROM intentos WHERE huellaLectura = :huella AND attemptId != :salvo)")
    fun existeLectura(huella: String, salvo: String): Boolean

    @Query("SELECT * FROM intentos WHERE reporte = 'PENDIENTE' ORDER BY rowid")
    fun pendientesDeReporte(): List<IntentoEntity>

    @Query(
        // Un ENVIADO cuenta, igual que un reverso abierto, sólo hasta su t0 + techo (r1 R1-5 b); el motor pasa el techo del
        // reverso más una vuelta (r2 familia E).
        """SELECT EXISTS(SELECT 1 FROM intentos WHERE procesoId = :proceso AND enviadoEnMs + :techo > :ahora AND
        (estado = 'ENVIADO' OR reverso IN ('COMPROMETIDO', 'RECLAMADO', 'NO_CONCLUYENTE')))""",
    )
    fun hayQuePuedenNecesitarReverso(proceso: String, ahora: Long, techo: Long): Boolean

    @Query(
        // De otro proceso, o de éste sin intento vivo (r1 R1-5 c).
        """SELECT * FROM intentos WHERE (procesoId != :proceso OR attemptId NOT IN (:vivos)) AND (estado IN ('ANOTADO', 'ENVIADO') OR
        reverso IN ('COMPROMETIDO', 'RECLAMADO') OR (reverso = 'NO_CONCLUYENTE' AND pudoEntrar = 1 AND reporte = 'NO_APLICA'))
        ORDER BY rowid""",
    )
    fun porRecuperar(proceso: String, vivos: List<String>): List<IntentoEntity>

    @Query("SELECT * FROM contadores WHERE ksnBaseHex = :base")
    fun contador(base: String): ContadorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun guardarContador(c: ContadorEntity)
}
