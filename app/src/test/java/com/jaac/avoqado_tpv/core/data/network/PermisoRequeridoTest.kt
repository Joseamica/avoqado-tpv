package com.jaac.avoqado_tpv.core.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermisoRequeridoTest {
    private fun cuerpo(required: String?, overridable: Boolean? = null): String {
        val r = required?.let { ""","required":"$it"""" } ?: ""
        val o = overridable?.let { ""","overridable":$it""" } ?: ""
        return """{"error":"Forbidden","message":"Permission required","userRole":"CASHIER"$r$o}"""
    }

    @Test
    fun `403_orders_comp_overridable`() {
        val e = permisoRequeridoDe(403, cuerpo("orders:comp", true), "antes")
        assertNotNull(e)
        assertEquals("orders:comp", e!!.required)
        assertTrue(e.overridable)
        assertEquals("Esta cortesía la da un gerente con su sesión", e.message)
    }

    @Test
    fun `403_discounts_apply_texto`() {
        val e = permisoRequeridoDe(403, cuerpo("discounts:apply"), "antes")!!
        assertFalse(e.overridable)
        assertEquals("Este descuento lo da alguien con permiso de descuentos", e.message)
        assertEquals(
            "Este cupón lo canjea alguien con permiso de cupones",
            textoDePermiso("coupons:redeem", "antes"),
        )
    }

    @Test
    fun `403_sin_required_conserva_texto_de_antes`() {
        val e = permisoRequeridoDe(403, cuerpo(null), "antes")!!
        assertNull(e.required)
        assertEquals("antes", e.message)
        assertEquals("antes", textoDePermiso("otra:cosa", "antes"))
    }

    @Test
    fun `no_403_es_null`() {
        assertNull(permisoRequeridoDe(500, cuerpo("orders:comp"), "antes"))
    }

    @Test
    fun `cuerpo_no_json_no_truena`() {
        assertEquals("antes", permisoRequeridoDe(403, "<html>no</html>", "antes")!!.message)
        assertEquals("antes", permisoRequeridoDe(403, null, "antes")!!.message)
        assertEquals("antes", permisoRequeridoDe(403, "", "antes")!!.message)
    }
}
