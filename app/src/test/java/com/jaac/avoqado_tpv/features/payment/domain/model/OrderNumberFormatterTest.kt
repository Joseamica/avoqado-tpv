package com.jaac.avoqado_tpv.features.payment.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tienda híbrida (P3, fix 1): el folio «W7K3P» salía como «73» en el ticket, en «Orden #» del éxito y en Pagos, porque el
 * formateador sólo dejaba los dígitos. El folio se deja tal cual; las órdenes numéricas no cambian.
 */
class OrderNumberFormatterTest {

    @Test
    fun `un folio de la tienda en linea se deja intacto`() {
        assertEquals("W7K3P", OrderNumberFormatter.display("W7K3P"))
        assertEquals("W7K3P", OrderNumberFormatter.reference("W7K3P"))
        assertEquals("W7K3P", OrderNumberFormatter.buildReference("W7K3P"))
        assertEquals("WABCD", OrderNumberFormatter.display("WABCD"))
    }

    @Test
    fun `las ordenes numericas no cambian`() {
        assertEquals("123456", OrderNumberFormatter.display("ORD-00123456"))
        assertEquals("00123456", OrderNumberFormatter.reference("ORD-00123456"))
        assertEquals("42", OrderNumberFormatter.display("42"))
        assertEquals("00396", OrderNumberFormatter.display("SN00396"))
        assertEquals(null, OrderNumberFormatter.display(null))
        assertEquals("ABC", OrderNumberFormatter.display("ABC"))
    }
}
