package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.tables.data.CUENTA_SE_ACTUALIZA
import com.jaac.avoqado_tpv.features.tables.data.MESA_COBRO_POR_REVISAR
import com.jaac.avoqado_tpv.features.tables.data.MESA_COBRO_SIN_SINCRONIZAR
import com.jaac.avoqado_tpv.features.tables.data.SALDO_NO_RECUPERADO
import com.jaac.avoqado_tpv.features.tables.data.SaldoSinRed
import com.jaac.avoqado_tpv.features.tables.data.TARJETA_REGISTRANDOSE
import com.jaac.avoqado_tpv.features.tables.data.TARJETA_SIN_REGISTRAR
import com.jaac.avoqado_tpv.features.tables.data.TARJETA_SUBIENDOSE
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import org.junit.Test
import java.math.BigDecimal

/**
 * D17 (PAX del laboratorio, 17-nov): en «Cobrar» de una mesa, con un cobro con tarjeta de esa cuenta todavía en la cola,
 * el pie enseñaba DOS veces seguidas «Un cobro con tarjeta de esta cuenta todavía no sube al servidor…»: una como el aviso
 * (con «Reintentar») y otra como la razón de «Tarjeta no disponible». Los dos salen de `pendienteSinSubir`. Una sola razón a
 * la vez: si la de la tarjeta es la misma que el aviso, no se repite. Cuándo se bloquean los botones no cambia.
 */
class TableCheckoutAvisosDelPieTest {

    private val falta = BigDecimal("78.00")
    private val cuentaLeida = OrderDetail(id = "orden-1", total = BigDecimal("118.00"), amountLeft = falta)

    private fun conAlgoSinSubir(motivo: String) = TableCheckoutState(
        isLoading = false,
        check = cuentaLeida,
        remaining = falta,
        pendienteSinSubir = motivo,
    )

    @Test
    fun D17_con_un_cobro_con_tarjeta_subiendose_el_aviso_sale_una_sola_vez_y_con_reintentar() {
        val state = conAlgoSinSubir(TARJETA_SUBIENDOSE)

        val pie = avisosDelPie(state, cardSupportsThisSplit = true)

        assertThat(pie.aviso).isEqualTo(TARJETA_SUBIENDOSE)
        assertThat(pie.puedeReintentar).isTrue()
        assertThat(pie.razonDeLaTarjeta).isNull()
        // Los dos botones siguen apagados, igual que antes.
        assertThat(state.cardPaymentEnabled).isFalse()
        assertThat(state.efectivoBloqueadoPor).isEqualTo(TARJETA_SUBIENDOSE)
    }

    @Test
    fun D17_ningun_otro_motivo_de_algo_sin_subir_se_repite_en_el_pie() {
        listOf(TARJETA_REGISTRANDOSE, TARJETA_SIN_REGISTRAR, MESA_COBRO_SIN_SINCRONIZAR, MESA_COBRO_POR_REVISAR).forEach { motivo ->
            val state = conAlgoSinSubir(motivo)

            val pie = avisosDelPie(state, cardSupportsThisSplit = true)

            assertThat(pie.aviso).isEqualTo(motivo)
            assertThat(pie.puedeReintentar).isTrue()
            assertThat(pie.razonDeLaTarjeta).isNull()
            assertThat(state.cardPaymentEnabled).isFalse()
        }
    }

    @Test
    fun D17_con_un_descuento_en_camino_la_cuenta_se_actualiza_sale_una_sola_vez() {
        val state = TableCheckoutState(isLoading = false, check = cuentaLeida, remaining = falta, cambiosEnCamino = 1)

        val pie = avisosDelPie(state, cardSupportsThisSplit = true)

        assertThat(pie.aviso).isEqualTo(CUENTA_SE_ACTUALIZA)
        assertThat(pie.puedeReintentar).isFalse()
        assertThat(pie.razonDeLaTarjeta).isNull()
        assertThat(state.cardPaymentEnabled).isFalse()
    }

    @Test
    fun D17_mientras_se_vuelve_a_leer_la_cuenta_el_motivo_se_sigue_viendo_una_vez() {
        // El aviso espera a que termine la lectura; mientras, la razón de la tarjeta lo dice (sin Reintentar), como hoy.
        val state = conAlgoSinSubir(TARJETA_SUBIENDOSE).copy(isLoading = true)

        val pie = avisosDelPie(state, cardSupportsThisSplit = true)

        assertThat(pie.aviso).isNull()
        assertThat(pie.razonDeLaTarjeta).isEqualTo(TARJETA_SUBIENDOSE)
    }

    @Test
    fun D17_razones_distintas_se_siguen_viendo_las_dos() {
        val state = conAlgoSinSubir(TARJETA_SUBIENDOSE).copy(isOnline = false)

        val pie = avisosDelPie(state, cardSupportsThisSplit = true)

        assertThat(pie.aviso).isEqualTo(TARJETA_SUBIENDOSE)
        assertThat(pie.razonDeLaTarjeta).isEqualTo("Necesita conexión")
    }

    @Test
    fun D17_la_tarjeta_que_no_admite_la_division_sigue_diciendo_por_que() {
        val state = TableCheckoutState(isLoading = false, check = cuentaLeida, remaining = falta)

        val pie = avisosDelPie(state, cardSupportsThisSplit = false)

        assertThat(pie.aviso).isNull()
        assertThat(pie.razonDeLaTarjeta).isEqualTo("División no disponible en este equipo — cobra el total o usa efectivo")
    }

    @Test
    fun D17_tras_entregar_una_tarjeta_el_aviso_de_revisar_sale_y_la_tarjeta_no_agrega_nada() {
        val state = TableCheckoutState(
            isLoading = false,
            check = cuentaLeida,
            remaining = falta,
            saldoPorConfirmar = true,
            revisandoSaldo = false,
        )

        val pie = avisosDelPie(state, cardSupportsThisSplit = true)

        assertThat(pie.aviso).isEqualTo(state.avisoDeSaldoPorConfirmar)
        assertThat(pie.puedeReintentar).isTrue()
        assertThat(pie.razonDeLaTarjeta).isNull()
    }

    @Test
    fun D19_mesa_abierta_sin_red_cuyo_saldo_no_se_pudo_leer_ofrece_reintentar() {
        // Codex r2 #4: tras un cobro rechazado o en duda, la mesa abierta sin red recalcula su saldo de la cola; si la cola no se
        // pudo leer, quedaba en gris sin salida.
        val state = TableCheckoutState(isLoading = false, isProvisional = true, saldoSinRed = SaldoSinRed.EnGris(SALDO_NO_RECUPERADO))

        val pie = avisosDelPie(state, cardSupportsThisSplit = true)

        assertThat(pie.aviso).isEqualTo(SALDO_NO_RECUPERADO)
        assertThat(pie.puedeReintentar).isTrue()
    }

    @Test
    fun D19_mesa_abierta_sin_red_por_revisar_no_ofrece_reintentar() {
        // Releer no la arregla: la resuelve un gerente en «Acciones rechazadas».
        val state = TableCheckoutState(isLoading = false, isProvisional = true, saldoSinRed = SaldoSinRed.EnGris(MESA_COBRO_POR_REVISAR))

        assertThat(avisosDelPie(state, cardSupportsThisSplit = true).puedeReintentar).isFalse()
    }
}
