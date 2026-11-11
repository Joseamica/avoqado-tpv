package com.jaac.avoqado_campo.cola

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.google.gson.JsonParser
import com.jaac.avoqado_campo.red.PorSubir
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PiezaDao {
    @Insert abstract suspend fun insertar(p: Pieza): Long
    @Update abstract suspend fun actualizar(p: Pieza)

    @Query("SELECT * FROM piezas WHERE llave = :llave") abstract suspend fun porLlave(llave: String): Pieza?
    @Query("SELECT * FROM piezas WHERE id = :id") abstract suspend fun porId(id: Long): Pieza?

    @Query("SELECT * FROM piezas WHERE estado IN ('PENDIENTE','RETENIDA') ORDER BY id")
    abstract suspend fun siguientesParaSubir(): List<Pieza>

    @Query("SELECT * FROM piezas WHERE estado NOT IN ('HECHA','DESCARTADA') ORDER BY id")
    abstract fun pendientesDeVer(): Flow<List<Pieza>>

    /** Lo mismo que [pendientesDeVer], contado (PENDIENTE, RETENIDA, RECHAZADA, AVISO): «la cola vacía» de B6. */
    @Query("SELECT COUNT(*) FROM piezas WHERE estado NOT IN ('HECHA','DESCARTADA')")
    abstract suspend fun cuantasVivas(): Int

    /**
     * Lo sin subir, de una vez: lo que «Desvincular» y el aviso de DESCONOCIDO le enseñan a la persona. r5: también la checada
     * APARTADA (todavía no se manda, pero ya es de la persona y un borrado la perdería).
     */
    @Query("SELECT COUNT(*) FROM piezas WHERE estado IN ('APARTADA','PENDIENTE','RETENIDA','RECHAZADA')")
    abstract suspend fun cuantasPorSubirAhora(): Int

    @Query("SELECT * FROM piezas WHERE estado = 'APARTADA' ORDER BY id") abstract suspend fun apartadas(): List<Pieza>
    @Query("DELETE FROM piezas WHERE id = :id AND estado = 'APARTADA'") abstract suspend fun quitarApartada(id: Long)

    @Query(
        """SELECT COUNT(*) AS total,
           COALESCE(SUM(tipo = 'VENTA'), 0) AS ventas,
           COALESCE(SUM(tipo = 'EVIDENCIA'), 0) AS fotos,
           COALESCE(SUM(tipo IN ('ENTRADA','SALIDA')), 0) AS checadas,
           COALESCE(SUM(tipo IN ('CUSTODIA_ACEPTAR','CUSTODIA_RECHAZAR')), 0) AS custodia
           FROM piezas WHERE estado IN ('PENDIENTE','RETENIDA','RECHAZADA')"""
    )
    abstract fun cuantasPorSubir(): Flow<PorSubir>

    // huellasHasta, ventasVivas y fotosVivas son públicas porque Room lo exige; los de fuera usan sus envolturas.
    @Query(
        "SELECT huella FROM piezas WHERE tipo != 'EVIDENCIA' AND huella IS NOT NULL " +
            "AND estado NOT IN ('HECHA','DESCARTADA') ORDER BY id LIMIT :limite"
    )
    abstract suspend fun huellasHasta(limite: Int): List<String>

    suspend fun huellasPorSubir(limite: Int = 500): List<String> = huellasHasta(limite)

    @Query("SELECT * FROM piezas WHERE tipo = 'VENTA' AND estado NOT IN ('HECHA','DESCARTADA') ORDER BY id")
    abstract suspend fun ventasVivas(): List<Pieza>

    /** VENTA no terminal con ese `serialNumber` (comparado ya normalizado, como va en el cuerpo). */
    suspend fun ventaConSerieEnCola(serie: String): Pieza? {
        val s = normalizarSerie(serie)
        return ventasVivas().firstOrNull { campo(it.cuerpoJson, "serialNumber") == s }
    }

    /** Ventas y custodias que todavía pueden subir: de aquí salen las marcas locales de la copia (Tarea 8). */
    @Query(
        "SELECT * FROM piezas WHERE tipo IN ('VENTA','CUSTODIA_ACEPTAR','CUSTODIA_RECHAZAR') " +
            "AND estado IN ('PENDIENTE','RETENIDA','RECHAZADA') ORDER BY id"
    )
    abstract suspend fun conSimsPorSubir(): List<Pieza>

    /** Hijos que todavía pueden subir (los AVISO ya los aceptó el SERVIDOR y no se tocan). */
    @Query("SELECT * FROM piezas WHERE dependeDe = :llave AND estado IN ('PENDIENTE','RETENIDA','RECHAZADA') ORDER BY id")
    abstract suspend fun hijosPorSubir(llave: String): List<Pieza>

    /** P12 + R2-5: lo terminal viejo, salvo si un hijo vivo depende de él o es la entrada del turno. */
    @Query(
        """DELETE FROM piezas WHERE estado IN ('HECHA','DESCARTADA') AND creadaAt < :ms
           AND NOT EXISTS (SELECT 1 FROM piezas h WHERE h.dependeDe = piezas.llave AND h.estado NOT IN ('HECHA','DESCARTADA'))
           AND llave NOT IN (SELECT llaveEntrada FROM turno WHERE llaveEntrada IS NOT NULL)"""
    )
    abstract suspend fun purgarTerminalesAntesDe(ms: Long)

    @Query("SELECT fotosJson FROM piezas WHERE estado NOT IN ('HECHA','DESCARTADA')")
    abstract suspend fun fotosVivas(): List<String>

    suspend fun archivosVivos(): List<String> = fotosVivas().flatMap { fotosDe(it) }.map { it.archivo }

    @Query("SELECT * FROM turno WHERE id = 1") abstract fun turno(): Flow<TurnoLocal?>
    @Query("SELECT * FROM turno WHERE id = 1") abstract suspend fun turnoAhora(): TurnoLocal?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun guardarTurno(t: TurnoLocal)

    /** Lo que el `Encolador` hace en UNA transacción (también mover la foto: si falla, se deshace todo). */
    @Transaction open suspend fun enTransaccion(bloque: suspend () -> Unit) = bloque()

    /** Quien llama pasa `cuerpoEfectivo(cuerpo)` (R7-4). La ENTRADA del turno deja su `timeEntryId` en él (R2-5). */
    @Transaction
    open suspend fun marcarHecha(pieza: Pieza, resultadoJson: String?, estado: String) {
        require(estado == EstadoPieza.HECHA || estado == EstadoPieza.AVISO) { "marcarHecha sólo con HECHA o AVISO" }
        actualizar(pieza.copy(estado = estado, resultadoJson = resultadoJson))
        if (pieza.tipo != TipoPieza.ENTRADA) return
        val t = turnoAhora() ?: return
        val id = campo(resultadoJson, "timeEntryId")
        if (t.llaveEntrada == pieza.llave && id != null) guardarTurno(t.copy(timeEntryId = id))
    }

    /** R2-2: el padre DESCARTADA y cada hijo vivo RECHAZADA con su motivo; la ENTRADA del turno lo cierra. */
    @Transaction
    open suspend fun descartarConHijos(id: Long, motivoHijos: String) {
        val texto = requireNotNull(TEXTO_DEL_HIJO[motivoHijos]) { "Motivo desconocido: $motivoHijos" }
        val p = porId(id) ?: return
        actualizar(p.copy(estado = EstadoPieza.DESCARTADA))
        hijosPorSubir(p.llave).forEach {
            actualizar(it.copy(estado = EstadoPieza.RECHAZADA, rechazoCode = motivoHijos, rechazoMensaje = texto, rechazoDatosJson = null))
        }
        val t = turnoAhora()
        if (p.tipo == TipoPieza.ENTRADA && t?.llaveEntrada == p.llave) guardarTurno(t.copy(llaveEntrada = null, timeEntryId = null))
    }

    private companion object {
        val TEXTO_DEL_HIJO = mapOf(
            "SU_VENTA_SE_DESCARTO" to "Su venta ya no se va a subir",
            "SU_ENTRADA_SE_DESCARTO" to "Su entrada ya no se va a subir",
        )

        fun campo(json: String?, nombre: String): String? = runCatching {
            JsonParser.parseString(json).asJsonObject[nombre]?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()
    }
}
