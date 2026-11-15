package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import org.junit.Test
import java.math.BigDecimal

/**
 * D86 (encargo 3af794): el saldo con el que la TPV cobra en efectivo una mesa que ELLA abrió sin red. Todo sale de lo que esta
 * terminal le mandó (rondas, valuadas al mandarlas) menos el efectivo que ya encoló; lo que no se puede asegurar queda en gris.
 */
class SaldoDeMesaSinRedTest {

    private val cuenta = "local-1"
    private var seq = 0L

    private fun fila(tipo: String, payload: String, estado: String = SyncIntentEntity.STATUS_PENDING, id: String = "i${seq + 1}") =
        SyncIntentEntity(id = id, venueId = "v", seq = ++seq, type = tipo, payloadJson = payload, status = estado)

    private fun apertura() = fila(SyncIntentTypes.OPEN_TABLE, """{"tableId":"m1","covers":2,"localOrderId":"$cuenta"}""", id = "abrir")
    private fun ronda(id: String) = fila(SyncIntentTypes.ADD_ITEMS, """{"localOrderId":"$cuenta","items":[{"productId":"p","quantity":1}]}""", id = id)
    private fun cobro(id: String, centavos: Long, propina: Long = 0) =
        fila(SyncIntentTypes.PAY_CASH, """{"localOrderId":"$cuenta","amountCents":$centavos,"tipCents":$propina,"method":"CASH"}""", id = id)

    @Test
    fun rondas_menos_lo_cobrado_es_lo_que_falta() {
        val filas = listOf(apertura(), ronda("r1"), ronda("r2"), cobro("c1", 2000, propina = 500))
        val saldo = saldoDeCuentaProvisional(cuenta, filas, mapOf("r1" to BigDecimal("45.00"), "r2" to BigDecimal("20.50")))

        assertThat(saldo).isInstanceOf(SaldoSinRed.Conocido::class.java)
        saldo as SaldoSinRed.Conocido
        assertThat(saldo.rondas).isEqualTo(BigDecimal("65.50"))
        // La propina no paga la cuenta: sólo amountCents.
        assertThat(saldo.cobrado).isEqualTo(BigDecimal("20.00"))
        assertThat(saldo.porPagar).isEqualTo(BigDecimal("45.50"))
    }

    @Test
    fun cobrado_de_mas_no_da_negativo() {
        val saldo = saldoDeCuentaProvisional(cuenta, listOf(apertura(), ronda("r1"), cobro("c1", 5000)), mapOf("r1" to BigDecimal("45.00")))
        assertThat((saldo as SaldoSinRed.Conocido).porPagar).isEqualTo(BigDecimal("0.00"))
    }

    @Test
    fun sin_la_apertura_en_la_cola_queda_en_gris() {
        val saldo = saldoDeCuentaProvisional(cuenta, listOf(ronda("r1")), mapOf("r1" to BigDecimal("45.00")))
        assertThat(saldo).isEqualTo(SaldoSinRed.EnGris(SALDO_NO_RECUPERADO))
    }

    @Test
    fun una_ronda_en_la_cola_sin_su_valor_queda_en_gris() {
        // P. ej. la app se reinició: la cola sigue en disco, la libreta en memoria no.
        val saldo = saldoDeCuentaProvisional(cuenta, listOf(apertura(), ronda("r1")), emptyMap())
        assertThat(saldo).isEqualTo(SaldoSinRed.EnGris(SALDO_NO_RECUPERADO))
    }

    @Test
    fun un_valor_de_la_libreta_sin_su_ronda_pendiente_queda_en_gris() {
        // La ronda se descartó (rechazo en línea) o ya subió: la libreta ya no describe la cola.
        val saldo = saldoDeCuentaProvisional(cuenta, listOf(apertura()), mapOf("r1" to BigDecimal("45.00")))
        assertThat(saldo).isEqualTo(SaldoSinRed.EnGris(CUENTA_SE_ACTUALIZA))
    }

    @Test
    fun otro_cambio_sin_subir_de_la_cuenta_queda_en_gris() {
        val mover = fila(SyncIntentTypes.MOVE_ORDER, """{"localOrderId":"$cuenta","targetTableId":"m2"}""")
        val saldo = saldoDeCuentaProvisional(cuenta, listOf(apertura(), ronda("r1"), mover), mapOf("r1" to BigDecimal("45.00")))
        assertThat(saldo).isEqualTo(SaldoSinRed.EnGris(CAMBIOS_SIN_SINCRONIZAR))
    }

    @Test
    fun un_rechazo_de_la_cuenta_queda_en_gris() {
        val rechazada = fila(SyncIntentTypes.ADD_ITEMS, """{"localOrderId":"$cuenta","items":[]}""", estado = SyncIntentEntity.STATUS_REJECTED, id = "r9")
        val saldo = saldoDeCuentaProvisional(cuenta, listOf(apertura(), ronda("r1"), rechazada), mapOf("r1" to BigDecimal("45.00")))
        assertThat(saldo).isEqualTo(SaldoSinRed.EnGris(CAMBIOS_SIN_SINCRONIZAR))
    }

    @Test
    fun las_filas_de_otra_cuenta_no_cuentan() {
        val otra = fila(SyncIntentTypes.MOVE_ORDER, """{"localOrderId":"otra","targetTableId":"m2"}""")
        val otroCobro = fila(SyncIntentTypes.PAY_CASH, """{"orderId":"local-10","amountCents":999}""")
        val saldo = saldoDeCuentaProvisional(cuenta, listOf(apertura(), ronda("r1"), otra, otroCobro), mapOf("r1" to BigDecimal("45.00")))
        assertThat((saldo as SaldoSinRed.Conocido).porPagar).isEqualTo(BigDecimal("45.00"))
    }

    @Test
    fun un_cobro_con_importe_ilegible_queda_en_gris() {
        val raro = fila(SyncIntentTypes.PAY_CASH, """{"localOrderId":"$cuenta","amountCents":"veinte"}""")
        val saldo = saldoDeCuentaProvisional(cuenta, listOf(apertura(), ronda("r1"), raro), mapOf("r1" to BigDecimal("45.00")))
        assertThat(saldo).isEqualTo(SaldoSinRed.EnGris(SALDO_NO_RECUPERADO))
    }

    @Test
    fun la_libreta_guarda_rondas_por_cuenta_y_sigue_al_ascenso() {
        val libreta = SaldoDeMesaSinRed.Libreta()
        libreta.anotarRonda(cuenta, "r1", BigDecimal("45.00"))
        libreta.anotarRonda("otra", "r2", BigDecimal("10.00"))

        assertThat(libreta.rondasDe(cuenta)).containsExactly("r1", BigDecimal("45.00"))
        libreta.olvidar(cuenta)
        assertThat(libreta.rondasDe(cuenta)).isEmpty()
        assertThat(libreta.rondasDe("otra")).containsExactly("r2", BigDecimal("10.00"))
    }
}
