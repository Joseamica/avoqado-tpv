package com.avoqado.cobropropio.libreta

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * «Ya no hay nada que hacerle» (Codex r1 #6): la lista CERRADA de valores terminales conocidos. Lo que no cae aquí (también un
 * valor desconocido en `estado`, `reverso` o `reporte`) es trabajo abierto. Un valor nuevo de esos enums se agrega aquí.
 */
private const val CERRADA = """estado IN ('APROBADO', 'RECHAZADO', 'NO_SALIO', 'NO_SE', 'NO_SE_COBRO', 'CANCELADA')
    AND reverso IN ('NINGUNO', 'CONFIRMADO', 'RECHAZADO', 'NO_CONCLUYENTE')
    AND NOT (reverso = 'NO_CONCLUYENTE' AND pudoEntrar = 1 AND reporte = 'NO_APLICA')
    AND reporte IN ('NO_APLICA', 'PENDIENTE', 'ENTREGADO', 'CONCILIAR', 'FINAL_SIN_ENTREGAR')"""

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

    /** Sólo los `rowid`: cada fila se lee después, aparte, por `Libreta.legible` (I3). */
    @Query("SELECT rowid FROM intentos WHERE reporte = 'PENDIENTE' ORDER BY rowid")
    fun rowidsPendientesDeReporte(): List<Long>

    @Query(
        // Un ENVIADO cuenta, igual que un reverso abierto, sólo hasta su t0 + techo (r1 R1-5 b); el motor pasa el techo del
        // reverso más una vuelta (r2 familia E).
        """SELECT EXISTS(SELECT 1 FROM intentos WHERE procesoId = :proceso AND enviadoEnMs + :techo > :ahora AND
        (estado = 'ENVIADO' OR reverso IN ('COMPROMETIDO', 'RECLAMADO', 'NO_CONCLUYENTE')))""",
    )
    fun hayQuePuedenNecesitarReverso(proceso: String, ahora: Long, techo: Long): Boolean

    /**
     * De otro proceso, o de éste sin intento vivo (r1 R1-5 c), que no esté [CERRADA]. Con valores conocidos es el mismo filtro
     * de antes; un valor desconocido entra, y `Libreta.legible` lo salta (I3).
     */
    @Query("SELECT rowid FROM intentos WHERE (procesoId != :proceso OR attemptId NOT IN (:vivos)) AND NOT ($CERRADA) ORDER BY rowid")
    fun rowidsPorRecuperar(proceso: String, vivos: List<String>): List<Long>

    @Query("SELECT * FROM intentos WHERE rowid = :rowid")
    fun porRowid(rowid: Long): IntentoEntity?

    @Query("SELECT * FROM contadores WHERE ksnBaseHex = :base")
    fun contador(base: String): ContadorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun guardarContador(c: ContadorEntity)
}
