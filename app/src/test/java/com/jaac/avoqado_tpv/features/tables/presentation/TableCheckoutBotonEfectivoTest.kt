package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.tables.data.SaldoSinRed
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import org.junit.Test
import java.math.BigDecimal

/**
 * D2 (PAX, 16-nov): en «Cobrar» de una mesa del SERVIDOR sin red y sin la cuenta leída, el botón de Efectivo salía en gris
 * con «$118.00», el TOTAL del plano con que arranca la sesión, cuando a la mesa le faltaban $78. Sin cuenta leída el botón no
 * enseña monto, igual que el subtítulo dice «Restante: sin conexión».
 */
class TableCheckoutBotonEfectivoTest {

    private val totalDelPlano = BigDecimal("118.00")

    @Test
    fun D2_mesa_del_servidor_sin_red_y_sin_cuenta_el_efectivo_no_ensena_el_total_del_plano() {
        val state = TableCheckoutState(isLoading = false, isOnline = false, remaining = totalDelPlano)
        assertThat(state.efectivoBloqueadoPor).isNotNull()

        assertThat(montoDelBotonEfectivo(state, totalDelPlano, cashSupportsThisSplit = true)).isNull()
    }

    @Test
    fun D2_cargando_la_cuenta_el_efectivo_tampoco_ensena_monto() {
        val state = TableCheckoutState(isLoading = true, remaining = totalDelPlano)

        assertThat(montoDelBotonEfectivo(state, totalDelPlano, cashSupportsThisSplit = true)).isNull()
    }

    @Test
    fun D2_con_la_cuenta_leida_el_efectivo_ensena_lo_que_falta() {
        val falta = BigDecimal("78.00")
        val state = TableCheckoutState(
            isLoading = false,
            check = OrderDetail(id = "orden-1", total = totalDelPlano, amountLeft = falta),
            remaining = falta,
        )

        assertThat(montoDelBotonEfectivo(state, falta, cashSupportsThisSplit = true)).isEqualTo(falta)
    }

    @Test
    fun D2_con_la_cuenta_leida_y_algo_sin_subir_el_monto_se_sigue_viendo_como_hoy() {
        val falta = BigDecimal("78.00")
        val state = TableCheckoutState(
            isLoading = false,
            check = OrderDetail(id = "orden-1", amountLeft = falta),
            remaining = falta,
            pendienteSinSubir = "Hay un cobro sin subir",
        )
        assertThat(state.efectivoBloqueadoPor).isNotNull()

        assertThat(montoDelBotonEfectivo(state, falta, cashSupportsThisSplit = true)).isEqualTo(falta)
    }

    @Test
    fun D2_mesa_abierta_sin_red_aqui_con_su_saldo_conocido_ensena_el_monto() {
        val state = TableCheckoutState(
            isLoading = false,
            isOnline = false,
            isProvisional = true,
            saldoSinRed = SaldoSinRed.Conocido(rondas = BigDecimal("65.00"), cobrado = BigDecimal("20.00")),
            remaining = BigDecimal("45.00"),
        )
        assertThat(state.efectivoBloqueadoPor).isNull()

        assertThat(montoDelBotonEfectivo(state, BigDecimal("45.00"), cashSupportsThisSplit = true))
            .isEqualTo(BigDecimal("45.00"))
    }

    @Test
    fun D2_por_producto_el_efectivo_no_ensena_monto_como_hoy() {
        val state = TableCheckoutState(
            isLoading = false,
            check = OrderDetail(id = "orden-1", amountLeft = BigDecimal("78.00")),
            splitType = CheckoutSplitType.PERPRODUCT,
        )

        assertThat(montoDelBotonEfectivo(state, BigDecimal("30.00"), cashSupportsThisSplit = false)).isNull()
    }
}
