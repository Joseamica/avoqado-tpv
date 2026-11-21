package com.avoqado.cobropropio.blumon

import com.avoqado.cobropropio.blumon.Clasificador.Esperado
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.red.Respuesta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Los sobres son los LITERALES del piloto que cobró en producción (`lectorlab/BlumonPilotTest.kt:51-83`); los demás son
 * variaciones de esos mismos sobres.
 */
class ClasificadorTest {

    private val catalogo = CatalogoDeRechazos.BLUMON
    private val pan = "4111111111111111"
    private val intento = AttemptId("0f8fad5bd9cb469fa165b0867728950e")
    private val esperado = Esperado(intento, 100, pan)
    private val hora = Instant.parse("2026-11-18T12:00:00Z")

    // Literales del piloto.
    private val VENTA_APROBADA = """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":false}}"""
    private val VENTA_RECHAZADA_PILOTO = """{"status":false,"error":{"code":"TX_051"}}"""
    private val CANCELACION_OK = """{"status":true,"id":12346,"dataResponse":{"simulation":false}}"""
    private val CANCELACION_RECHAZADA_PILOTO = """{"status":false,"error":{"code":"TX_052"}}"""
    /**
     * R14-1: el «sí» sin bloque `error`. El literal viejo del piloto (`{"status":true,"error":{"code":"TX_024"}}`) era un doble
     * de `BlumonPilotTest.kt:51`, no un sobre medido; con la regla nueva es un «no».
     */
    private val PREFLIGHT_SI = """{"status":true}"""
    private val PREFLIGHT_SI_DEL_DOBLE_DEL_PILOTO = """{"status":true,"error":{"code":"TX_024"}}"""
    private val PREFLIGHT_NO = """{"status":false,"error":{"code":"TX_024"}}"""
    private val REVERSO_OK = """{"status":true,"id":12347,"dataResponse":{"description":"REVERSADA","simulation":false}}"""
    private val REVERSO_SIN_SIMULACION = """{"status":true,"id":12347,"dataResponse":{"description":"REVERSADA"}}"""
    private val REVERSO_TX_007 =
        """{"status":false,"id":12347,"error":{"code":"TX_007","description":"LA TRANSACCION NO ESTA DENTRO DEL HORARIO PERMITIDO"}}"""
    private val REVERSO_NUMERICO = """{"status":false,"error":{"code":"123456789012"}}"""

    /**
     * T8 arreglos 2: un éxito sólo vale con el eco de nuestro folio. Para que cada prueba vieja siga probando SU regla, este
     * ayudante le pone el eco (`dataResponse.batch` = el nuestro) a todo sobre con `dataResponse` que no traiga `batch`. Las
     * pruebas del eco usan [sinEco]. Un `dataResponse` vacío recibe el eco sin coma (T9: con coma el JSON no se leía y dos
     * pruebas pasaban por la razón equivocada).
     */
    private fun http(codigo: Int, cuerpo: String): Respuesta.Http {
        val eco = "\"batch\":\"${intento.valor}\""
        return Respuesta.Http(
            codigo,
            when {
                "\"batch\"" in cuerpo -> cuerpo
                "\"dataResponse\":{}" in cuerpo -> cuerpo.replaceFirst("\"dataResponse\":{}", "\"dataResponse\":{$eco}")
                else -> cuerpo.replaceFirst("\"dataResponse\":{", "\"dataResponse\":{$eco,")
            },
        )
    }

    private fun sinEco(codigo: Int, cuerpo: String) = Respuesta.Http(codigo, cuerpo)
    private fun error(codigo: String) = """{"status":false,"error":{"code":"$codigo"}}"""
    private fun venta(r: Respuesta, e: Esperado = esperado) = Clasificador.venta(r, e, catalogo)
    private fun cancelacion(r: Respuesta) = Clasificador.cancelacion(r, esperado, catalogo)
    private fun reverso(r: Respuesta) = Clasificador.reverso(r, esperado, catalogo) { hora }

    // ---------- Venta, paso por paso ----------

    @Test fun `venta paso 1 NoEnviada es NoEnviada`() {
        assertEquals(DesenlaceDeVenta.NoEnviada, venta(Respuesta.NoEnviada))
    }

    @Test fun `venta paso 2 sin respuesta y 408 y 5xx son NoSe`() {
        assertEquals(DesenlaceDeVenta.NoSe, venta(Respuesta.SinRespuesta("TIEMPO")))
        for (codigo in listOf(408, 500, 502, 503, 504)) assertEquals("$codigo", DesenlaceDeVenta.NoSe, venta(http(codigo, VENTA_APROBADA)))
    }

    @Test fun `401 403 y 429 son NoSe`() {
        for (codigo in listOf(401, 403, 429)) {
            assertEquals("$codigo", DesenlaceDeVenta.NoSe, venta(http(codigo, VENTA_APROBADA)))
            assertEquals("$codigo", DesenlaceDeVenta.NoSe, venta(http(codigo, error("51"))))
        }
    }

    @Test fun `500 con codigo del catalogo es NoSe`() {
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(500, error("51"))))
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(500, error("TX_024"))))
    }

    @Test fun `venta paso 3 cuerpo que no parsea es NoSe`() {
        for (cuerpo in listOf("<html>", "", "[]", "{\"status\":true", "{status:true}", "{} {}")) {
            assertEquals(cuerpo, DesenlaceDeVenta.NoSe, venta(http(200, cuerpo)))
        }
    }

    /** R5-2: sólo las formas crudas del catálogo se normalizan (`51`, `51.0`, `05`, `N0`, `TX_024`); lo demás es «no sé». */
    @Test fun `venta paso 4 los codigos se normalizan`() {
        assertEquals(DesenlaceDeVenta.Rechazada("51", "FONDOS INSUFICIENTES"), venta(http(200, error("51.0"))))
        assertEquals(DesenlaceDeVenta.Rechazada("05", "TRANSACCIÓN INVALIDA"), venta(http(200, error("5.0"))))
        assertEquals(DesenlaceDeVenta.Rechazada("05", "TRANSACCIÓN INVALIDA"), venta(http(200, error("05"))))
        assertEquals(DesenlaceDeVenta.Rechazada("N0", "REINTENTE"), venta(http(200, error("N0"))))
        // Número de JSON en vez de texto.
        assertEquals(DesenlaceDeVenta.Rechazada("51", "FONDOS INSUFICIENTES"),
            venta(http(200, """{"status":false,"error":{"code":51.0}}""")))
    }

    /** R5-2: una forma cruda que no es del catálogo nunca se vuelve válida al normalizar. */
    @Test fun `un codigo con forma cruda invalida nunca es rechazo`() {
        for (c in listOf("000000000000051", "51 ", " 51.0 ", "5１", "051", "n0", "5", "51.00", "+51", "tx_024", "TX_0241")) {
            assertEquals(c, DesenlaceDeVenta.NoSe, venta(http(200, error(c))))
            assertTrue(c, reverso(http(200, error(c))).let { it is ClaseDelReverso.Reintentar && !it.pruebaQueNoEntro })
        }
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, """{"status":false,"error":{"code":51.5}}""")))
    }

    /** R5-2, la pasada: folio, autorización, referencia, importe y descripción también se validan crudos. */
    @Test fun `folio autorizacion referencia importe y descripcion se validan crudos`() {
        for (id in listOf("\"+12345\"", "\" 12345\"", "\"012345\"", "12345.0")) {
            val c = """{"status":true,"id":$id,"dataResponse":{"authorization":"ABC123","simulation":false}}"""
            assertEquals(c, DesenlaceDeVenta.NoSe, venta(http(200, c)))
        }
        val autorizacion = """{"status":true,"id":12345,"dataResponse":{"authorization":" ABC123","simulation":false}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, autorizacion)))
        for (monto in listOf("\"+1.00\"", "\"1.000\"", "1e0", "\" 1.00\"", "\"01.00\"")) {
            val c = """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":false,"amount":$monto}}"""
            assertEquals(c, DesenlaceDeVenta.NoSe, venta(http(200, c)))
        }
        // Pasada final m1: fuera de `error` (donde la regla X no frena antes), para que pruebe la validación cruda.
        val referencia = """{"status":false,"reference":" ${intento.valor}","error":{"code":"TX_014"}}"""
        assertEquals(ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(http(200, referencia)))
        for (d in listOf("reversada", " REVERSADA", "REVERSADA ")) {
            val c = """{"status":true,"id":12347,"dataResponse":{"description":"$d"}}"""
            assertEquals(d, ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, c)))
        }
    }

    @Test fun `venta paso 5 un codigo no se gana sobre status true`() {
        for (codigo in listOf("68", "9", "94", "90", "91", "92", "96", "R9", "RQ_006", "TX_033", "TX_035")) {
            val aprobadoConCodigo = """{"status":true,"id":12345,"codeResponse":"$codigo","dataResponse":{"authorization":"ABC123","simulation":false}}"""
            assertEquals(codigo, DesenlaceDeVenta.NoSe, venta(http(200, aprobadoConCodigo)))
            assertEquals(codigo, DesenlaceDeVenta.NoSe, venta(http(200, error(codigo))))
        }
    }

    @Test fun `68 es NoSe`() {
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, error("68"))))
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, error("68.0"))))
    }

    @Test fun `status true con iso 91 es NoSe`() {
        val cuerpo = """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":false,"codeResponse":"91"}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, cuerpo)))
    }

    @Test fun `aprobado con iso 10 es DebeReversar`() {
        val parcial = """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":false,"codeResponse":"10","amount":"0.60"}}"""
        assertEquals(DesenlaceDeVenta.DebeReversar(12345, 60), venta(http(200, parcial)))
        // Sin folio ni importe con forma, igual se reversa: la evidencia que falta va en null.
        val sinFolio = """{"status":true,"id":"x","dataResponse":{"codeResponse":"10.0"}}"""
        assertEquals(DesenlaceDeVenta.DebeReversar(null, null), venta(http(200, sinFolio)))
    }

    @Test fun `venta paso 7 aprobada con el sobre literal del piloto`() {
        assertEquals(DesenlaceDeVenta.Aprobada(12345, "ABC123"), venta(http(200, VENTA_APROBADA)))
        val conCodigoAprobado = """{"status":true,"id":12345,"codeResponse":"00","dataResponse":{"authorization":"ABC123","simulation":false,"reference":"${intento.valor}","amount":1.00}}"""
        assertEquals(DesenlaceDeVenta.Aprobada(12345, "ABC123"), venta(http(201, conCodigoAprobado)))
    }

    @Test fun `venta paso 7 status true que no cumple todo es NoSe`() {
        for (cuerpo in listOf(
            """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123"}}""",
            """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":"false"}}""",
            """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":null}}""",
            """{"status":true,"dataResponse":{"authorization":"ABC123","simulation":false}}""",
            """{"status":true,"id":0,"dataResponse":{"authorization":"ABC123","simulation":false}}""",
            """{"status":true,"id":10000000000,"dataResponse":{"authorization":"ABC123","simulation":false}}""",
            """{"status":true,"id":12345,"dataResponse":{"simulation":false}}""",
            """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC-123","simulation":false}}""",
            """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":false,"amount":"2.00"}}""",
            """{"status":true,"id":12345,"codeResponse":"51","dataResponse":{"authorization":"ABC123","simulation":false}}""",
            """{"status":true,"id":12345,"error":{"code":"TX_024"},"dataResponse":{"authorization":"ABC123","simulation":false}}""",
        )) {
            assertEquals(cuerpo, DesenlaceDeVenta.NoSe, venta(http(200, cuerpo)))
        }
    }

    @Test fun `simulation true nunca es Aprobada ni Confirmado`() {
        val simulada = """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":true}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, simulada)))
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, """{"status":true,"id":12346,"dataResponse":{"simulation":true}}""")))
        val reversoSimulado = """{"status":true,"id":12347,"dataResponse":{"description":"REVERSADA","simulation":true}}"""
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, reversoSimulado)))
    }

    /** R1-2: un sobre de ERROR con `simulation=true` tampoco da un veredicto definitivo, en ninguna operación. */
    @Test fun `simulation true en un sobre de error nunca es veredicto definitivo`() {
        fun simulado(codigo: String) = """{"status":false,"id":12347,"error":{"code":"$codigo"},"dataResponse":{"simulation":true}}"""
        // Reverso: TX_013 y TX_014 no confirman, TX_007 no rechaza, y TX_008 no prueba que no entró ⇒ pudo entrar.
        for (codigo in listOf("TX_013", "TX_014", "TX_007", "TX_008")) {
            assertEquals(codigo, ClaseDelReverso.Reintentar(codigo, false, hora), reverso(http(200, simulado(codigo))))
        }
        // La misma negativa sin simulación sí es definitiva: la diferencia es sólo `simulation`.
        assertEquals(DesenlaceDeVenta.Rechazada("51", "FONDOS INSUFICIENTES"), venta(http(200, error("51"))))
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, simulado("51"))))
        assertEquals(DesenlaceDeCancelacion.Rechazada("TX_024", "TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN"),
            cancelacion(http(200, error("TX_024"))))
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, simulado("TX_024"))))
    }

    @Test fun `422 con status true y folio es NoSe`() {
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(422, VENTA_APROBADA)))
    }

    @Test fun `aprobado con reference de otro intento es NoSe`() {
        val otro = """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":false,"reference":"1111aaaa2222bbbb3333cccc4444dddd"}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, otro)))
        val raiz = """{"status":true,"id":12345,"reference":"1111aaaa2222bbbb3333cccc4444dddd","dataResponse":{"authorization":"ABC123","simulation":false}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, raiz)))
        // Un rechazo de otro intento tampoco es definitivo.
        val rechazoAjeno = """{"status":false,"reference":"1111aaaa2222bbbb3333cccc4444dddd","error":{"code":"51"}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, rechazoAjeno)))
    }

    @Test fun `pan en campos permitidos se rechaza`() {
        val folioPan = """{"status":true,"id":$pan,"dataResponse":{"authorization":"ABC123","simulation":false}}"""
        val autorizacionPan = """{"status":true,"id":12345,"dataResponse":{"authorization":"$pan","simulation":false}}"""
        val codigoConPan = error(pan.take(12))
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, folioPan)))
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, autorizacionPan)))
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, codigoConPan)))
        // Lo que coincide con la tarjeta de la lectura nunca sale, aunque tenga la forma: autorización «411111» sí puede ser
        // (6 dígitos del BIN), pero una referencia con 12 dígitos del PAN no.
        val referenciaConPan = """{"status":false,"reference":"x$pan","error":{"code":"51"}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, referenciaConPan)))
    }

    @Test fun `venta paso 8 rechazo definitivo en 2xx o 4xx de negocio`() {
        for (codigo in listOf(200, 400, 402, 404, 409, 422)) {
            assertEquals("$codigo", DesenlaceDeVenta.Rechazada("51", "FONDOS INSUFICIENTES"), venta(http(codigo, error("51"))))
        }
        for (iso in listOf("01", "03", "04", "05", "07", "12", "13", "14", "15", "41", "43", "51", "54", "55", "57", "58", "59", "61", "62", "65", "75", "N0")) {
            assertTrue(iso, venta(http(200, error(iso))) is DesenlaceDeVenta.Rechazada)
        }
        // Una negativa sin leyenda en el catálogo lleva la genérica, nunca un texto del procesador.
        assertEquals(DesenlaceDeVenta.Rechazada("07", "RECHAZADA"),
            venta(http(200, """{"status":false,"error":{"code":"07","description":"TEXTO CRUDO"}}""")))
        // 405 no es un 4xx de negocio.
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(405, error("51"))))
    }

    @Test fun `venta paso 9 un codigo del catalogo fuera de la lista es NoSe`() {
        for (codigo in listOf("RQ_004", "NF_001", "TX_007", "TX_024", "00", "11", "DV_002")) {
            assertEquals(codigo, DesenlaceDeVenta.NoSe, venta(http(200, error(codigo))))
        }
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, VENTA_RECHAZADA_PILOTO)))
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, """{"status":false}""")))
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, """{"dataResponse":{}}""")))
    }

    // ---------- Cancelación ----------

    @Test fun `cancelacion pasos 1 a 5`() {
        assertEquals(DesenlaceDeCancelacion.NoEnviada, cancelacion(Respuesta.NoEnviada))
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(Respuesta.SinRespuesta("RED")))
        for (codigo in listOf(401, 403, 408, 429, 500)) assertEquals("$codigo", DesenlaceDeCancelacion.NoSe, cancelacion(http(codigo, CANCELACION_OK)))
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, "<html>")))
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, error("94.0"))))
        val conNoSe = """{"status":true,"id":12346,"codeResponse":"68","dataResponse":{"simulation":false}}"""
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, conNoSe)))
    }

    @Test fun `cancelacion exitosa sin autorizacion es Cancelada`() {
        assertEquals(DesenlaceDeCancelacion.Cancelada(12346), cancelacion(http(200, CANCELACION_OK)))
        val conAutorizacion = """{"status":true,"id":12346,"dataResponse":{"authorization":"XYZ9","simulation":false}}"""
        assertEquals(DesenlaceDeCancelacion.Cancelada(12346), cancelacion(http(200, conAutorizacion)))
    }

    @Test fun `cancelacion paso 7 exito que no cumple todo es NoSe`() {
        for (cuerpo in listOf(
            """{"status":true,"id":12346,"dataResponse":{}}""",
            """{"status":true,"dataResponse":{"simulation":false}}""",
            """{"status":true,"id":12346,"dataResponse":{"authorization":"$pan","simulation":false}}""",
            """{"status":true,"id":12346,"dataResponse":{"simulation":false,"reference":"1111aaaa2222bbbb3333cccc4444dddd"}}""",
            """{"status":true,"id":12346,"codeResponse":"10","dataResponse":{"simulation":false}}""",
        )) {
            assertEquals(cuerpo, DesenlaceDeCancelacion.NoSe, cancelacion(http(200, cuerpo)))
        }
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(422, CANCELACION_OK)))
    }

    @Test fun `cancelacion paso 8 TX_024 en un 200 es Rechazada`() {
        assertEquals(DesenlaceDeCancelacion.Rechazada("TX_024", "TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN"),
            cancelacion(http(200, error("TX_024"))))
        assertEquals(DesenlaceDeCancelacion.Rechazada("51", "FONDOS INSUFICIENTES"), cancelacion(http(409, error("51"))))
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, CANCELACION_RECHAZADA_PILOTO)))
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, error("RQ_004"))))
    }

    @Test fun `preflight de cancelacion del piloto sin simulation pasa`() {
        assertEquals(true, Clasificador.preflightDeCancelacion(http(200, PREFLIGHT_SI)))
        assertEquals(false, Clasificador.preflightDeCancelacion(http(200, PREFLIGHT_NO)))
        // Ronda 2 · Q: un HTTP de transporte dudoso no es un «no»: es sin respuesta.
        for (codigo in listOf(500, 503, 401, 403, 408, 429)) assertNull("$codigo", Clasificador.preflightDeCancelacion(http(codigo, PREFLIGHT_SI)))
        assertEquals(false, Clasificador.preflightDeCancelacion(http(404, PREFLIGHT_SI)))
        assertEquals(false, Clasificador.preflightDeCancelacion(http(200, "<html>")))
        assertEquals(false, Clasificador.preflightDeCancelacion(http(200, """{"status":true,"error":{"code":"68"}}""")))
        assertNull("sin respuesta no es un «no» (r1 B)", Clasificador.preflightDeCancelacion(Respuesta.SinRespuesta("RED")))
        assertNull(Clasificador.preflightDeCancelacion(Respuesta.NoEnviada))
    }

    /**
     * T9, medido en el sandbox el 20-nov: `validateCancelation` de una venta aprobada un segundo antes contestó HTTP 200 con
     * `status:false` y `TX_024` (descripción de 41 caracteres, `error.simulation:false`, sin `dataResponse`). El valor de
     * `httpStatusCode` y los textos son inventados con la forma medida.
     */
    @Test fun `preflight negativo real del sandbox no pasa`() {
        val medido = """{"status":false,"requestId":"a1234567-1a23-1a23-1a23-12a1a1a12a12","date":"2026-11-20","time":"07:57:43",""" +
            """"error":{"httpStatusCode":400,"code":"TX_024","description":"TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN","simulation":false}}"""
        assertEquals(false, Clasificador.preflightDeCancelacion(sinEco(200, medido), Ambiente.SANDBOX))
        assertEquals(false, Clasificador.preflightDeCancelacion(sinEco(200, medido), Ambiente.PRODUCCION))
        // R14-1: el mismo sobre con `status:true` sigue siendo un «no» (trae bloque `error`).
        assertEquals(false, Clasificador.preflightDeCancelacion(sinEco(200, medido.replace("\"status\":false", "\"status\":true")), Ambiente.SANDBOX))
        assertEquals(true, Clasificador.preflightDeCancelacion(sinEco(200, PREFLIGHT_SI), Ambiente.SANDBOX))
    }

    /** R14-1: un sobre de ÉXITO con cualquier valor bajo `error` nunca es un éxito definitivo; `null` o `{}` cuentan como ausente. */
    @Test fun `un exito con bloque error nunca es definitivo`() {
        val conError = ",\"error\":{\"httpStatusCode\":400}}"
        fun mas(s: String, cola: String) = s.dropLast(1) + cola
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, mas(aprobadaDelSandbox, conError)), enSandbox))
        assertEquals(DesenlaceDeVenta.Aprobada(84678, "1A2B34"), venta(http(200, mas(aprobadaDelSandbox, ",\"error\":null}")), enSandbox))
        assertEquals(DesenlaceDeVenta.Aprobada(84678, "1A2B34"), venta(http(200, mas(aprobadaDelSandbox, ",\"error\":{}}")), enSandbox))
        val parcial = """{"status":true,"id":777,"dataResponse":{"codeResponse":"10","amount":"0.50","simulation":false}}"""
        assertEquals(DesenlaceDeVenta.DebeReversar(777, 50), venta(http(200, parcial)))
        assertEquals(DesenlaceDeVenta.DebeReversar(null, null), venta(http(200, mas(parcial, conError))))
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, mas(CANCELACION_OK, conError))))
        assertTrue(Clasificador.reverso(http(200, mas(reversadaDelSandbox, conError)), enSandbox, catalogo) { hora } is ClaseDelReverso.Reintentar)
        assertEquals(false, Clasificador.preflightDeCancelacion(http(200, mas(PREFLIGHT_SI, conError)), Ambiente.SANDBOX))
        assertEquals(false, Clasificador.preflightDeCancelacion(http(200, PREFLIGHT_SI_DEL_DOBLE_DEL_PILOTO), Ambiente.SANDBOX))
        // Los sobres de error no cambian.
        assertEquals(DesenlaceDeVenta.Rechazada("51", "FONDOS INSUFICIENTES"), venta(http(200, error("51"))))
        // Ola final · 1: un TX_014 limpio ya no confirma (sin eco); sigue siendo duda con su código.
        assertEquals(ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(sinEco(200, error("TX_014"))))
    }

    /**
     * Ronda 2 · P1 (éxito puro): un definitivo de éxito exige NADA bajo `error` (ausente = sólo `null`, `{}` y `[]`) y todos los
     * códigos de éxito, en las cinco operaciones (venta, parcial, cancelación, `REVERSADA` y el «sí» del preflight).
     */
    @Test fun `P1 un exito con cualquier cosa bajo error nunca es definitivo`() {
        val parcial = """{"status":true,"id":777,"dataResponse":{"codeResponse":"10","amount":"0.50","simulation":false}}"""
        fun mas(s: String, error: String) = s.dropLast(1) + ",\"error\":$error}"
        for (sucio in listOf("\"x\"", "400", "true", "[1]", "{\"a\":{}}")) {
            assertEquals(sucio, DesenlaceDeVenta.NoSe, venta(http(200, mas(aprobadaDelSandbox, sucio)), enSandbox))
            assertEquals(sucio, DesenlaceDeVenta.DebeReversar(null, null), venta(http(200, mas(parcial, sucio))))
            assertEquals(sucio, DesenlaceDeCancelacion.NoSe, cancelacion(http(200, mas(CANCELACION_OK, sucio))))
            assertTrue(sucio, Clasificador.reverso(http(200, mas(reversadaDelSandbox, sucio)), enSandbox, catalogo) { hora } is ClaseDelReverso.Reintentar)
            assertEquals(sucio, false, Clasificador.preflightDeCancelacion(http(200, mas(PREFLIGHT_SI, sucio)), Ambiente.SANDBOX))
        }
        for (limpio in listOf("null", "{}", "[]")) {
            assertEquals(limpio, DesenlaceDeVenta.Aprobada(84678, "1A2B34"), venta(http(200, mas(aprobadaDelSandbox, limpio)), enSandbox))
            assertEquals(limpio, DesenlaceDeVenta.DebeReversar(777, 50), venta(http(200, mas(parcial, limpio))))
            assertEquals(limpio, DesenlaceDeCancelacion.Cancelada(12346), cancelacion(http(200, mas(CANCELACION_OK, limpio))))
            assertEquals(limpio, ClaseDelReverso.Confirmado(hora), Clasificador.reverso(http(200, mas(reversadaDelSandbox, limpio)), enSandbox, catalogo) { hora })
            assertEquals(limpio, true, Clasificador.preflightDeCancelacion(http(200, mas(PREFLIGHT_SI, limpio)), Ambiente.SANDBOX))
        }
        // R14-4: un «sí» del preflight con un código que no es de éxito es un «no»; con `00`, sí.
        assertEquals(false, Clasificador.preflightDeCancelacion(http(200, """{"status":true,"codeResponse":"TX_024"}"""), Ambiente.SANDBOX))
        assertEquals(true, Clasificador.preflightDeCancelacion(http(200, """{"status":true,"codeResponse":"00"}"""), Ambiente.SANDBOX))
    }

    /**
     * Ronda 2 · P2 (error puro): un definitivo de ERROR (rechazos de venta y cancelación; `TX_007` y «no entró» del reverso;
     * `TX_013`/`TX_014` ya no son definitivos desde la ola final) exige nada bajo `dataResponse` y un `error.httpStatusCode` ausente o de negocio (2xx o 4xx de negocio).
     */
    @Test fun `P2 un error con datos de exito o con un 5xx adentro es duda`() {
        // R15-3: rechazo TX_024 con dataResponse y un 504 adentro.
        val r153 = """{"status":false,"error":{"code":"TX_024","httpStatusCode":504},"dataResponse":{"simulation":false}}"""
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(sinEco(200, r153)))
        assertEquals(DesenlaceDeVenta.NoSe, venta(sinEco(200, """{"status":false,"error":{"code":"05"},"dataResponse":{"authorization":"X1"}}""")))
        for (c in listOf("TX_014", "TX_013", "TX_007", "TX_008")) {
            val conDatos = """{"status":false,"error":{"code":"$c"},"dataResponse":{"description":"x"}}"""
            assertEquals(c, ClaseDelReverso.Reintentar(c, false, hora), reverso(sinEco(200, conDatos)))
            val con504 = """{"status":false,"error":{"code":"$c","httpStatusCode":504}}"""
            assertEquals(c, ClaseDelReverso.Reintentar(c, false, hora), reverso(sinEco(200, con504)))
        }
        // Sólo el httpStatusCode de transporte (o uno que no es número) vuelve duda al rechazo; el de negocio no.
        for (raro in listOf("504", "401", "\"409\"", "true")) {
            assertEquals(raro, DesenlaceDeVenta.NoSe, venta(sinEco(200, """{"status":false,"error":{"code":"51","httpStatusCode":$raro}}""")))
        }
        for (negocio in listOf("409", "400", "200")) {
            assertEquals(negocio, DesenlaceDeVenta.Rechazada("51", "FONDOS INSUFICIENTES"),
                venta(sinEco(200, """{"status":false,"error":{"code":"51","httpStatusCode":$negocio}}""")))
            // Ola final · 1: TX_014 ya no confirma; el control del httpStatusCode de negocio lo lleva TX_007.
            assertEquals(negocio, ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(sinEco(200, """{"status":false,"error":{"code":"TX_014","httpStatusCode":$negocio}}""")))
            assertEquals(negocio, ClaseDelReverso.Rechazado("TX_007", hora), reverso(sinEco(200, """{"status":false,"error":{"code":"TX_007","httpStatusCode":$negocio}}""")))
        }
        // `dataResponse` en null, {} o [] cuenta como ausente; los limpios siguen igual.
        for (vacio in listOf("null", "{}", "[]")) {
            assertEquals(vacio, DesenlaceDeCancelacion.Rechazada("TX_024", "TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN"),
                cancelacion(sinEco(200, """{"status":false,"error":{"code":"TX_024"},"dataResponse":$vacio}""")))
        }
        assertEquals(DesenlaceDeCancelacion.Rechazada("TX_024", "TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN"), cancelacion(sinEco(200, error("TX_024"))))
        assertEquals(ClaseDelReverso.Rechazado("TX_007", hora), reverso(sinEco(200, error("TX_007"))))
        assertEquals(ClaseDelReverso.Reintentar("TX_008", true, hora), reverso(sinEco(200, error("TX_008"))))
    }

    /**
     * Ronda 3 · T: «vacío» es sólo la clave ausente, `null`, `{}` o `[]`. Todo contenedor con al menos un miembro cuenta como
     * PRESENTE, también si sólo trae nulos o contenedores vacíos. Vale para `error` en un éxito y para `dataResponse` en un error.
     */
    @Test fun `T vacio es solo ausente null objeto vacio o lista vacia`() {
        val vacios = listOf("null", "{}", "[]")
        val presentes = listOf("\"x\"", "\"\"", "400", "0", "true", "false", "{\"a\":null}", "[null]", "{\"a\":{}}", "[[]]", "[{}]",
            "{\"a\":{\"b\":null}}", "{\"a\":0}", "[false]", "[{\"code\":\"x\"}]")
        val parcial = """{"status":true,"id":777,"dataResponse":{"codeResponse":"10","amount":"0.50","simulation":false}}"""
        fun conError(s: String, v: String) = s.dropLast(1) + ",\"error\":$v}"
        for (v in vacios) {
            assertEquals(v, DesenlaceDeVenta.Aprobada(84678, "1A2B34"), venta(http(200, conError(aprobadaDelSandbox, v)), enSandbox))
            assertEquals(v, DesenlaceDeVenta.DebeReversar(777, 50), venta(http(200, conError(parcial, v))))
            assertEquals(v, DesenlaceDeCancelacion.Cancelada(12346), cancelacion(http(200, conError(CANCELACION_OK, v))))
            assertEquals(v, ClaseDelReverso.Confirmado(hora), Clasificador.reverso(http(200, conError(reversadaDelSandbox, v)), enSandbox, catalogo) { hora })
            assertEquals(v, true, Clasificador.preflightDeCancelacion(http(200, conError(PREFLIGHT_SI, v)), Ambiente.SANDBOX))
            assertEquals(v, DesenlaceDeVenta.Rechazada("51", "FONDOS INSUFICIENTES"), venta(sinEco(200, """{"status":false,"error":{"code":"51"},"dataResponse":$v}""")))
            assertEquals(v, DesenlaceDeCancelacion.Rechazada("TX_024", "TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN"),
                cancelacion(sinEco(200, """{"status":false,"error":{"code":"TX_024"},"dataResponse":$v}""")))
            // Ola final · 1: TX_014 ya no confirma; el control de «dataResponse vacío» lo lleva TX_007.
            assertEquals(v, ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(sinEco(200, """{"status":false,"error":{"code":"TX_014"},"dataResponse":$v}""")))
            assertEquals(v, ClaseDelReverso.Rechazado("TX_007", hora), reverso(sinEco(200, """{"status":false,"error":{"code":"TX_007"},"dataResponse":$v}""")))
        }
        for (v in presentes) {
            assertEquals(v, DesenlaceDeVenta.NoSe, venta(http(200, conError(aprobadaDelSandbox, v)), enSandbox))
            assertEquals(v, DesenlaceDeVenta.DebeReversar(null, null), venta(http(200, conError(parcial, v))))
            assertEquals(v, DesenlaceDeCancelacion.NoSe, cancelacion(http(200, conError(CANCELACION_OK, v))))
            assertTrue(v, Clasificador.reverso(http(200, conError(reversadaDelSandbox, v)), enSandbox, catalogo) { hora } is ClaseDelReverso.Reintentar)
            assertEquals(v, false, Clasificador.preflightDeCancelacion(http(200, conError(PREFLIGHT_SI, v)), Ambiente.SANDBOX))
            assertEquals(v, DesenlaceDeVenta.NoSe, venta(sinEco(200, """{"status":false,"error":{"code":"51"},"dataResponse":$v}""")))
            assertEquals(v, DesenlaceDeCancelacion.NoSe, cancelacion(sinEco(200, """{"status":false,"error":{"code":"TX_024"},"dataResponse":$v}""")))
            assertEquals(v, ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(sinEco(200, """{"status":false,"error":{"code":"TX_014"},"dataResponse":$v}""")))
        }
    }

    /**
     * Ronda 3 · V y U: el `error` de un definitivo de error es PLANO (sólo escalares o `null` directamente bajo `error`), y
     * `error.httpStatusCode`, si la clave viene, es un número de negocio. Si no viene, vale.
     */
    @Test fun `V el error de un definitivo de error es plano y su httpStatusCode de negocio`() {
        val escondido = """{"status":false,"error":{"code":"TX_014","httpStatusCode":409,"detalle":[{"httpStatusCode":504}]}}"""
        assertEquals(ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(sinEco(200, escondido)))
        for (adentro in listOf("{}", "[]", "{\"a\":1}", "[504]")) {
            assertEquals(adentro, DesenlaceDeVenta.NoSe, venta(sinEco(200, """{"status":false,"error":{"code":"05","x":$adentro}}""")))
            assertEquals(adentro, ClaseDelReverso.Reintentar("TX_013", false, hora), reverso(sinEco(200, """{"status":false,"error":{"code":"TX_013","x":$adentro}}""")))
        }
        // El TX_024 medido en el sandbox (plano, 409; textos inventados con su forma) sigue siendo un rechazo de la cancelación.
        val medido = """{"status":false,"requestId":"a1234567-1a23-1a23-1a23-12a1a1a12a12","date":"2026-11-20","time":"07:57:43",""" +
            """"error":{"httpStatusCode":409,"code":"TX_024","description":"TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN","simulation":false}}"""
        assertEquals(DesenlaceDeCancelacion.Rechazada("TX_024", "TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN"), cancelacion(sinEco(200, medido)))
        // U: las formas de `error.httpStatusCode`.
        assertEquals(DesenlaceDeVenta.Rechazada("51", "FONDOS INSUFICIENTES"), venta(sinEco(200, error("51"))))
        assertEquals(DesenlaceDeVenta.Rechazada("51", "FONDOS INSUFICIENTES"), venta(sinEco(200, """{"status":false,"error":{"code":"51","httpStatusCode":409}}""")))
        for (forma in listOf("null", "\"409\"", "409.0", "true", "{}", "405", "302", "504")) {
            assertEquals(forma, DesenlaceDeVenta.NoSe, venta(sinEco(200, """{"status":false,"error":{"code":"51","httpStatusCode":$forma}}""")))
            assertEquals(forma, ClaseDelReverso.Reintentar("TX_014", false, hora),
                reverso(sinEco(200, """{"status":false,"error":{"code":"TX_014","httpStatusCode":$forma}}""")))
        }
    }

    /** Ronda 3 · W: una clave conocida de la raíz con otra grafía (`Error`, `DataResponse`, `Status`) ensucia el sobre: duda. */
    @Test fun `W una clave conocida con otra grafia es duda`() {
        fun mas(s: String, cola: String) = s.dropLast(1) + ",$cola}"
        for (cola in listOf("\"Error\":{\"code\":\"05\"}", "\"ERROR\":\"x\"", "\"DataResponse\":{}", "\"Status\":false")) {
            assertEquals(cola, DesenlaceDeVenta.NoSe, venta(http(200, mas(aprobadaDelSandbox, cola)), enSandbox))
            assertEquals(cola, DesenlaceDeCancelacion.NoSe, cancelacion(http(200, mas(CANCELACION_OK, cola))))
            assertEquals(cola, false, Clasificador.preflightDeCancelacion(http(200, mas(PREFLIGHT_SI, cola)), Ambiente.SANDBOX))
            assertEquals(cola, DesenlaceDeVenta.NoSe, venta(sinEco(200, mas(error("51"), cola))))
            assertTrue(cola, reverso(sinEco(200, mas(error("TX_014"), cola))).let { it is ClaseDelReverso.Reintentar && !it.pruebaQueNoEntro })
        }
        // Una clave ajena con otra grafía no ensucia nada.
        assertEquals(DesenlaceDeVenta.Rechazada("51", "FONDOS INSUFICIENTES"), venta(sinEco(200, mas(error("51"), "\"Detalle\":\"x\""))))
    }

    /**
     * Ronda 4 · X: en un definitivo de ERROR, toda clave directa bajo `error` es una de las cuatro medidas (`httpStatusCode`,
     * `code`, `description`, `simulation`), escrita exactamente así. No hace falta que vengan las cuatro.
     */
    @Test fun `X bajo error de un definitivo de error solo las claves medidas`() {
        // El sobre de Codex r17: otra grafía de httpStatusCode con un 504, con y sin el 409 bien escrito al lado.
        for (sobre in listOf(
            """{"status":false,"error":{"code":"TX_014","HTTPStatusCode":504}}""",
            """{"status":false,"error":{"code":"TX_014","httpStatusCode":409,"HTTPStatusCode":504}}""",
        )) assertEquals(sobre, ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(sinEco(200, sobre)))
        for (ajena in listOf("\"detalle\":\"x\"", "\"http_status_code\":504", "\"reference\":null")) {
            assertEquals(ajena, DesenlaceDeVenta.NoSe, venta(sinEco(200, """{"status":false,"error":{"code":"05",$ajena}}""")))
            assertEquals(ajena, DesenlaceDeCancelacion.NoSe, cancelacion(sinEco(200, """{"status":false,"error":{"code":"TX_024",$ajena}}""")))
        }
        // Pasada final m4: TX_007 y «no entró» con una clave ajena bajo `error` tampoco dan su definitivo.
        assertEquals(ClaseDelReverso.Reintentar("TX_007", false, hora), reverso(sinEco(200, """{"status":false,"error":{"code":"TX_007","detalle":"x"}}""")))
        assertEquals(ClaseDelReverso.Reintentar("TX_008", false, hora), reverso(sinEco(200, """{"status":false,"error":{"code":"TX_008","detalle":"x"}}""")))
        // `{"code":"05"}` solo y el TX_024 medido (sus cuatro claves) siguen igual.
        assertEquals(DesenlaceDeVenta.Rechazada("05", "TRANSACCIÓN INVALIDA"), venta(sinEco(200, error("05"))))
        val medido = """{"status":false,"requestId":"a1234567-1a23-1a23-1a23-12a1a1a12a12","date":"2026-11-20","time":"07:57:43",""" +
            """"error":{"httpStatusCode":409,"code":"TX_024","description":"TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN","simulation":false}}"""
        assertEquals(DesenlaceDeCancelacion.Rechazada("TX_024", "TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN"), cancelacion(sinEco(200, medido)))
        // Ola final · 1: TX_014 con las claves medidas ya no confirma; el control de las claves medidas lo lleva TX_007.
        assertEquals(ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(sinEco(200, """{"status":false,"error":{"code":"TX_014","description":"x","simulation":false}}""")))
        assertEquals(ClaseDelReverso.Rechazado("TX_007", hora), reverso(sinEco(200, """{"status":false,"error":{"code":"TX_007","description":"x","simulation":false}}""")))
    }

    /**
     * Ronda 4 · Y: una clave que el clasificador busca por nombre exacto, escrita con otra grafía a CUALQUIER profundidad,
     * deja el sobre mal formado: duda en los dos lados. Las claves ajenas no ensucian.
     */
    @Test fun `Y una clave conocida con otra grafia a cualquier profundidad es duda`() {
        for ((de, a) in listOf(
            "\"batch\"" to "\"Batch\"", "\"simulation\"" to "\"Simulation\"", "\"total\"" to "\"Total\"",
            "\"authorization\"" to "\"AUTHORIZATION\"", "\"renewKey\"" to "\"RenewKey\"", "\"amountInfo\"" to "\"AmountInfo\"",
        )) {
            val sucio = aprobadaDelSandbox.replace(de, a)
            assertTrue(a, sucio != aprobadaDelSandbox)
            assertEquals(a, DesenlaceDeVenta.NoSe, venta(sinEco(200, sucio), enSandbox))
        }
        assertTrue(Clasificador.reverso(sinEco(200, reversadaDelSandbox.replace("\"batch\"", "\"BATCH\"")), enSandbox, catalogo) { hora } is ClaseDelReverso.Reintentar)
        // Pasada final m2: casos que SÓLO frena Y (fuera de `error`, que ya cuida X), también dentro de una lista.
        assertEquals(DesenlaceDeVenta.NoSe, venta(sinEco(200, """{"status":false,"error":{"code":"51"},"extra":{"Code":"x"}}""")))
        assertEquals(DesenlaceDeVenta.NoSe, venta(sinEco(200, aprobadaDelSandbox.dropLast(1) + ",\"extra\":[{\"Simulation\":true}]}"), enSandbox))
        assertEquals(DesenlaceDeVenta.Rechazada("51", "FONDOS INSUFICIENTES"), venta(sinEco(200, """{"status":false,"error":{"code":"51"},"extra":[{"otra":true}]}""")))
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(sinEco(200, """{"status":false,"error":{"code":"TX_024"},"x":{"Description":"y"}}""")))
        // Claves ajenas, aunque cambie su grafía, no ensucian; el sobre real sigue igual.
        val ajenas = aprobadaDelSandbox.replace("\"brand\"", "\"Brand\"").replace("\"requestId\"", "\"RequestID\"")
        assertEquals(DesenlaceDeVenta.Aprobada(84678, "1A2B34"), venta(sinEco(200, ajenas), enSandbox))
        assertEquals(DesenlaceDeVenta.Aprobada(84678, "1A2B34"), venta(sinEco(200, aprobadaDelSandbox), enSandbox))
    }

    /** T9: el preflight sigue la regla de `simulation` de la venta: `true` sólo vale en el sandbox; en producción, nunca. */
    @Test fun `preflight con simulation true solo pasa en el sandbox`() {
        val simulado = """{"status":true,"dataResponse":{"simulation":true}}"""
        assertEquals(false, Clasificador.preflightDeCancelacion(http(200, simulado)))
        assertEquals(false, Clasificador.preflightDeCancelacion(http(200, simulado), Ambiente.PRODUCCION))
        assertEquals(true, Clasificador.preflightDeCancelacion(http(200, simulado), Ambiente.SANDBOX))
        assertEquals(true, Clasificador.preflightDeCancelacion(http(200, simulado.replace("true}", "false}")), Ambiente.PRODUCCION))
    }

    /** T9: un éxito de la cancelación exige el eco del folio de ESTA cancelación, como la venta (R12-1). */
    @Test fun `cancelacion sin el eco de su folio es NoSe`() {
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(sinEco(200, CANCELACION_OK)))
        assertEquals(DesenlaceDeCancelacion.Cancelada(12346), cancelacion(http(200, CANCELACION_OK)))
        // El eco de OTRO folio (p. ej. el de la venta original) no ata la respuesta a esta cancelación.
        val deOtroFolio = CANCELACION_OK.replace("\"dataResponse\":{", "\"dataResponse\":{\"batch\":\"1111aaaa2222bbbb3333cccc4444dddd\",")
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(sinEco(200, deOtroFolio)))
        // Los sobres de error no cambian: un rechazo no necesita eco.
        assertEquals(DesenlaceDeCancelacion.Rechazada("TX_024", "TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN"), cancelacion(sinEco(200, error("TX_024"))))
    }

    @Test fun `cancelacion con simulation true solo vale en el sandbox`() {
        val simulada = CANCELACION_OK.replace("\"simulation\":false", "\"simulation\":true")
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, simulada)))
        assertEquals(DesenlaceDeCancelacion.Cancelada(12346), Clasificador.cancelacion(http(200, simulada), enSandbox, catalogo))
    }

    // ---------- Reverso, paso por paso ----------

    @Test fun `reverso paso 1 NoEnviada es NoEnviado`() {
        assertEquals(ClaseDelReverso.NoEnviado, reverso(Respuesta.NoEnviada))
    }

    @Test fun `reverso paso 2 el transporte va primero aunque el cuerpo traiga codigo`() {
        val sinRespuesta = ClaseDelReverso.Reintentar("SIN_RESPUESTA", false, hora)
        assertEquals(sinRespuesta, reverso(Respuesta.SinRespuesta("TIEMPO")))
        for (codigo in listOf(401, 403, 408, 429, 500, 503)) {
            assertEquals("$codigo", sinRespuesta, reverso(http(codigo, error("TX_014"))))
            assertEquals("$codigo", sinRespuesta, reverso(http(codigo, REVERSO_TX_007)))
            assertEquals("$codigo", sinRespuesta, reverso(http(codigo, REVERSO_OK)))
        }
        assertEquals(sinRespuesta, reverso(http(200, "<html>TX_014</html>")))
    }

    @Test fun `reverso paso 3 exito con el sobre literal del piloto es Confirmado`() {
        assertEquals(ClaseDelReverso.Confirmado(hora), reverso(http(200, REVERSO_OK)))
        // Medido en producción (16-nov): un REVERSADA sin `simulation`.
        assertEquals(ClaseDelReverso.Confirmado(hora), reverso(http(200, REVERSO_SIN_SIMULACION)))
        // El éxito exige 2xx: un 4xx de negocio con REVERSADA no confirma.
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(400, REVERSO_OK)))
    }

    /**
     * Ola final · 1 (Codex r21): un sobre de ERROR no trae el eco de nuestro folio, así que `TX_014`/`TX_013` limpios nunca
     * confirman un reverso: son duda con su código guardado («pudo entrar»), como cualquier otro código que pudo entrar.
     */
    @Test fun `reverso paso 3 TX_014 y TX_013 limpios son duda, nunca Confirmado`() {
        for (codigo in listOf("TX_014", "TX_013")) {
            val sobre = """{"status":false,"id":12347,"error":{"code":"$codigo","description":"TEXTO"}}"""
            assertEquals(codigo, ClaseDelReverso.Reintentar(codigo, false, hora), reverso(http(200, sobre)))
            assertEquals(codigo, ClaseDelReverso.Reintentar(codigo, false, hora), reverso(http(400, sobre)))
            assertEquals(codigo, ClaseDelReverso.Reintentar(codigo, false, hora), reverso(sinEco(200, error(codigo))))
        }
    }

    @Test fun `TX_007 del fixture sin simulation es Rechazado`() {
        assertEquals(ClaseDelReverso.Rechazado("TX_007", hora), reverso(http(200, REVERSO_TX_007)))
    }

    @Test fun `reverso paso 3 codigos que prueban que no entro`() {
        for (codigo in listOf("TX_008", "TX_009", "RQ_005", "RQ_006")) {
            assertEquals(codigo, ClaseDelReverso.Reintentar(codigo, true, hora), reverso(http(200, error(codigo))))
        }
    }

    @Test fun `68 en el reverso no prueba que no entro`() {
        assertEquals(ClaseDelReverso.Reintentar("68", false, hora), reverso(http(200, error("68"))))
        assertEquals(ClaseDelReverso.Reintentar("06", false, hora), reverso(http(200, error("6.0"))))
        assertEquals(ClaseDelReverso.Reintentar("N0", false, hora), reverso(http(200, error("N0"))))
    }

    @Test fun `codigo del reverso fuera del catalogo se guarda DESCONOCIDO`() {
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, error("ZZ_999"))))
        // El código numérico largo del piloto (podría ser un PAN) nunca se guarda.
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, REVERSO_NUMERICO)))
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, """{"status":true}""")))
    }

    @Test fun `reverso paso 4 un sobre incoherente no prueba nada`() {
        val ajeno = """{"status":false,"reference":"1111aaaa2222bbbb3333cccc4444dddd","error":{"code":"TX_008"}}"""
        assertEquals(ClaseDelReverso.Reintentar("TX_008", false, hora), reverso(http(200, ajeno)))
        val otroImporte = """{"status":true,"dataResponse":{"description":"REVERSADA","amount":"5.00"}}"""
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, otroImporte)))
        val conOtroCodigo = """{"status":false,"codeResponse":"68","error":{"code":"TX_014"}}"""
        assertEquals(ClaseDelReverso.Reintentar("68", false, hora), reverso(http(200, conOtroCodigo)))
        val mismoImporte = """{"status":true,"dataResponse":{"description":"REVERSADA","amount":"1.00","reference":"${intento.valor}"}}"""
        assertEquals(ClaseDelReverso.Confirmado(hora), reverso(http(200, mismoImporte)))
    }

    @Test fun `REVERSADA con otro codigo en el sobre no confirma`() {
        // El reverso no tiene paso «no sé»: la única barrera es que el éxito no traiga más código que 00.
        val con68 = """{"status":true,"codeResponse":"68","dataResponse":{"description":"REVERSADA"}}"""
        assertEquals(ClaseDelReverso.Reintentar("68", false, hora), reverso(http(200, con68)))
        val con51 = """{"status":true,"dataResponse":{"description":"REVERSADA","codeResponse":"51","simulation":false}}"""
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, con51)))
    }

    @Test fun `REVERSADA con status false no confirma`() {
        val sobre = """{"status":false,"dataResponse":{"description":"REVERSADA","simulation":false}}"""
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, sobre)))
    }

    @Test fun `rechazo con otro codigo que no es negativa es NoSe`() {
        val con00 = """{"status":false,"codeResponse":"00","error":{"code":"51"}}"""
        val conTx024 = """{"status":false,"dataResponse":{"codeResponse":"TX_024"},"error":{"code":"51"}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, con00)))
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, conTx024)))
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, con00)))
        val cancelacionCon00 = """{"status":false,"error":{"code":"TX_024","responseCode":"00"}}"""
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, cancelacionCon00)))
    }

    @Test fun `la hora del reverso sale del reloj de pared al recibir`() {
        val otra = Instant.parse("2026-11-18T12:00:07Z")
        assertEquals(ClaseDelReverso.Confirmado(otra), Clasificador.reverso(http(200, REVERSO_OK), esperado, catalogo) { otra })
    }

    // ---------- Catálogo y privacidad ----------

    @Test fun `catalogo trae la leyenda por codigo normalizado`() {
        assertEquals("FONDOS INSUFICIENTES", catalogo.leyenda("51"))
        assertEquals("LLAME EMISOR", catalogo.leyenda("01"))
        assertEquals("REINTENTE", catalogo.leyenda("N0"))
        assertEquals("TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN", catalogo.leyenda("TX_024"))
        assertEquals(null, catalogo.leyenda("ZZ_999"))
    }

    @Test fun `Esperado no ensena la tarjeta`() {
        assertFalse(esperado.toString().contains("4111"))
        assertTrue(esperado.contieneDatoDeLaTarjeta(pan))
        assertTrue(esperado.contieneDatoDeLaTarjeta("x${pan.take(12)}x"))
        assertFalse(esperado.contieneDatoDeLaTarjeta("411111"))
    }

    // ---------- Codex rama r2 · Familia C: confianza en el sobre ----------

    /** R1-2 (otra vez): `simulation=true` en CUALQUIER objeto del JSON, a cualquier profundidad, quita todo veredicto definitivo. */
    @Test fun `simulation true en cualquier lugar del sobre nunca es veredicto definitivo`() {
        // Reverso (ola final, 2ª parte: con TX_007, el definitivo de error que queda, en las DOS ramas): la marca en la raíz o
        // junto al código ⇒ pudo entrar, nunca Rechazado; la misma sin simulación sí es Rechazado.
        val enLaRaiz = """{"status":false,"simulation":true,"id":12347,"error":{"code":"TX_007"}}"""
        val junto = """{"status":false,"id":12347,"error":{"code":"TX_007","simulation":true}}"""
        val hondo = """{"status":false,"id":12347,"error":{"code":"TX_007","detalle":{"simulation":true}}}"""
        val enUnaLista = """{"status":false,"id":12347,"error":{"code":"TX_007"},"extra":[{"simulation":true}]}"""
        for (c in listOf(enLaRaiz, junto, hondo, enUnaLista)) assertEquals(c, ClaseDelReverso.Reintentar("TX_007", false, hora), reverso(http(200, c)))
        assertEquals(ClaseDelReverso.Rechazado("TX_007", hora), reverso(http(200, error("TX_007"))))
        // Venta: un aprobado o una negativa con la marca en la raíz ⇒ NoSe.
        val aprobadaSimulada = """{"status":true,"simulation":true,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":false}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, aprobadaSimulada)))
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, """{"status":false,"error":{"code":"51","simulation":true}}""")))
        // Cancelación igual.
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, """{"status":true,"simulation":true,"id":12346,"dataResponse":{"simulation":false}}""")))
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, """{"status":false,"error":{"code":"TX_024","simulation":true}}""")))
    }

    /** Una marca de simulación que no es booleana, esté donde esté, ensucia el sobre: nunca se ignora. */
    @Test fun `simulation que no es booleana en cualquier lugar ensucia el sobre`() {
        // Ola final, 3ª parte: con TX_007 en las dos ramas (un TX_014 ya es duda aunque se ignore el tipo de `simulation`).
        for (v in listOf("\"true\"", "1", "null", "{}")) {
            val c = """{"status":false,"simulation":$v,"id":12347,"error":{"code":"TX_007"}}"""
            assertEquals(c, ClaseDelReverso.Reintentar("TX_007", false, hora), reverso(http(200, c)))
        }
        assertEquals(ClaseDelReverso.Rechazado("TX_007", hora), reverso(http(200, """{"status":false,"simulation":false,"id":12347,"error":{"code":"TX_007"}}""")))
    }

    /** El éxito sigue exigiendo `simulation=false` en `dataResponse`: un `false` sólo en la raíz no basta. */
    @Test fun `el exito sigue exigiendo simulation false en dataResponse`() {
        val soloRaiz = """{"status":true,"simulation":false,"id":12345,"dataResponse":{"authorization":"ABC123"}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, soloRaiz)))
        val enTodos = """{"status":true,"simulation":false,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":false}}"""
        assertEquals(DesenlaceDeVenta.Aprobada(12345, "ABC123"), venta(http(200, enTodos)))
    }

    /**
     * R2-4: el parcial `10` sólo trae folio e importe si el sobre es confiable (sin contar el importe, que en un parcial es
     * menor), `0 < parcial < pedido` y el importe no lleva dígitos del PAN. Si no, se reversa igual, sin evidencia.
     */
    @Test fun `el parcial solo lleva folio e importe cotejados`() {
        fun parcial(monto: String, extra: String = "") =
            """{"status":true,"id":12345$extra,"dataResponse":{"simulation":false,"codeResponse":"10","amount":$monto}}"""
        assertEquals(DesenlaceDeVenta.DebeReversar(12345, 60), venta(http(200, parcial("\"0.60\""))))
        for (malo in listOf(
            parcial("\"4111111111111.11\""), // el importe en centavos lleva 12 dígitos del PAN
            parcial("\"1.00\""), // igual al pedido
            parcial("\"2.00\""), // mayor al pedido
            parcial("\"0.00\""), // cero
            parcial("\"0.60\"", ",\"simulation\":true"), // simulado
            parcial("\"0.60\"", ",\"reference\":\"1111aaaa2222bbbb3333cccc4444dddd\""), // de otro intento
        )) {
            assertEquals(malo, DesenlaceDeVenta.DebeReversar(null, null), venta(http(200, malo)))
        }
    }

    // ---------- Piezas comunes · 3: el lector estricto (R1-2, R3-1, R3-8; v4-7, v5-8) ----------

    /** R1-2: Gson se quedaba con la última de dos claves iguales; ahora una clave repetida hace el sobre contradictorio. */
    @Test fun `simulation true y luego false en el mismo objeto no aprueba ni confirma el reverso`() {
        val venta = """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":true,"simulation":false}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, venta)))
        val tx014 = """{"status":false,"id":12347,"error":{"code":"TX_014"},"dataResponse":{"simulation":true,"simulation":false}}"""
        assertEquals(ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(http(200, tx014)))
        val cancelada = """{"status":true,"id":12346,"dataResponse":{"simulation":true,"simulation":false}}"""
        assertEquals(DesenlaceDeCancelacion.NoSe, cancelacion(http(200, cancelada)))
    }

    @Test fun `una clave repetida en cualquier objeto es contradictoria`() {
        val aprobada = """{"status":true,"id":12345,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":false}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, aprobada)))
        val hondo = """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","simulation":false,"x":[{"a":1,"a":1}]}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, hondo)))
        val rechazo = """{"status":false,"error":{"code":"51","code":"51"}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, rechazo)))
        assertEquals(ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(http(200, """{"status":false,"error":{"code":"TX_014","code":"TX_014"}}""")))
        val parcial = """{"status":true,"id":12345,"dataResponse":{"simulation":false,"codeResponse":"10","amount":"0.60","amount":"0.60"}}"""
        assertEquals(DesenlaceDeVenta.DebeReversar(null, null), venta(http(200, parcial)))
    }

    /** v5-8: un campo que decide PRESENTE con un tipo que no corresponde nunca cuenta como ausente. */
    @Test fun `un campo que decide con tipo invalido es contradictorio`() {
        for (c in listOf(
            // Pasada final m1: en la raíz, donde la regla X no frena antes del lector.
            """{"status":false,"id":12347,"reference":{"v":"x"},"error":{"code":"TX_014"}}""",
            """{"status":false,"id":12347,"reference":["${intento.valor}"],"error":{"code":"TX_014"}}""",
            """{"status":false,"id":12347,"amount":true,"error":{"code":"TX_014"}}""",
            """{"status":false,"id":12347,"simulation":[true],"error":{"code":"TX_014"}}""",
            """{"status":false,"id":{"n":12347},"error":{"code":"TX_014"}}""",
            """{"status":"false","id":12347,"error":{"code":"TX_014"}}""",
        )) {
            assertEquals(c, ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(http(200, c)))
        }
        val codigoBooleano = """{"status":false,"error":{"code":"51","codeResponse":true}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, codigoBooleano)))
        val autorizacionObjeto = """{"status":true,"id":12345,"dataResponse":{"authorization":{"v":"ABC123"},"simulation":false}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, autorizacionObjeto)))
    }

    /**
     * R3-1: la referencia (y el importe) de OTRA venta en un sobre de error también se cotejan. Pasada final m1: van en la raíz,
     * fuera de `error` (donde la regla X ya frena cualquier clave no medida) y de `dataResponse`, así que esto prueba el cotejo.
     */
    @Test fun `TX_007 con la referencia o el importe de otra venta en un sobre de error es pudo entrar`() {
        // Ola final, 2ª parte: con TX_007 en las DOS ramas (un TX_014 ya es duda en las dos y no probaba el cotejo).
        val ajena = """{"status":false,"id":12347,"reference":"1111aaaa2222bbbb3333cccc4444dddd","error":{"code":"TX_007"}}"""
        assertEquals(ClaseDelReverso.Reintentar("TX_007", false, hora), reverso(http(200, ajena)))
        val otroImporte = """{"status":false,"id":12347,"amount":"5.00","error":{"code":"TX_007"}}"""
        assertEquals(ClaseDelReverso.Reintentar("TX_007", false, hora), reverso(http(200, otroImporte)))
        val enUnaLista = """{"status":false,"error":{"code":"TX_007"},"extra":[{"reference":"1111aaaa2222bbbb3333cccc4444dddd"}]}"""
        assertEquals(ClaseDelReverso.Reintentar("TX_007", false, hora), reverso(http(200, enUnaLista)))
        // El control positivo: nuestra referencia y nuestro importe ⇒ Rechazado.
        val propia = """{"status":false,"id":12347,"reference":"${intento.valor}","amount":"1.00","error":{"code":"TX_007"}}"""
        assertEquals(ClaseDelReverso.Rechazado("TX_007", hora), reverso(http(200, propia)))
    }

    @Test fun `folio o status repetidos en otra ruta no aprueban`() {
        val folio = """{"status":true,"id":12345,"error":{"id":12345},"dataResponse":{"authorization":"ABC123","simulation":false}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, folio)))
        val status = """{"status":true,"id":12345,"dataResponse":{"status":true,"authorization":"ABC123","simulation":false}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, status)))
        val autorizacion = """{"status":true,"id":12345,"authorization":"ABC123","dataResponse":{"authorization":"ABC123","simulation":false}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, autorizacion)))
        val descripcion = """{"status":true,"id":12347,"error":{"description":"REVERSADA"},"dataResponse":{"description":"REVERSADA"}}"""
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, descripcion)))
    }

    /** R3-8: la rama `10` sólo guarda evidencia de un 2xx de éxito no contradictorio, sin otro código. */
    @Test fun `10 con otro codigo de rechazo o en un 302 reversa sin evidencia`() {
        val conRechazo = """{"status":true,"id":12345,"error":{"code":"51"},"dataResponse":{"simulation":false,"codeResponse":"10","amount":"0.60"}}"""
        assertEquals(DesenlaceDeVenta.DebeReversar(null, null), venta(http(200, conRechazo)))
        val bueno = """{"status":true,"id":12345,"dataResponse":{"simulation":false,"codeResponse":"10","amount":"0.60"}}"""
        assertEquals(DesenlaceDeVenta.DebeReversar(null, null), venta(http(302, bueno)))
        assertEquals(DesenlaceDeVenta.DebeReversar(null, null), venta(http(200, bueno.replace("\"status\":true", "\"status\":false"))))
        assertEquals(DesenlaceDeVenta.DebeReversar(null, null), venta(http(200, bueno.replace("\"simulation\":false,", ""))))
        val dosImportes = """{"status":true,"id":12345,"amount":"0.50","dataResponse":{"simulation":false,"codeResponse":"10","amount":"0.60"}}"""
        assertEquals(DesenlaceDeVenta.DebeReversar(null, null), venta(http(200, dosImportes)))
        assertEquals(DesenlaceDeVenta.DebeReversar(12345, 60), venta(http(200, bueno)))
    }

    /** R4-3: una clave literal con punto (un cuerpo aplanado) nunca ocupa la ruta de un campo anidado. */
    @Test fun `una clave con punto nunca acredita un reverso`() {
        val aplanado = """{"status":false,"id":12347,"error.code":"TX_014"}"""
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, aplanado))) // nunca Confirmado
        val descripcion = """{"status":true,"id":12347,"dataResponse.description":"REVERSADA","dataResponse.simulation":false}"""
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, descripcion)))
        val venta = """{"status":true,"id":12345,"dataResponse.authorization":"ABC123","dataResponse.simulation":false}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, venta)))
        val corchetes = """{"status":false,"error":{"code":"TX_014"},"extra[]":{"reference":"x"}}"""
        assertEquals(ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(http(200, corchetes)))
    }

    /** R5 familia 1: una ruta es una lista de segmentos desde la raíz; nada disfrazado ocupa la de un campo anidado. */
    @Test fun `ninguna ruta disfrazada acredita un reverso`() {
        val vacia = """{"status":false,"id":12347,"":{"error":{"code":"TX_014"}}}"""
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, vacia))) // pudo entrar
        for (c in listOf(
            """{"status":false,"id":12347,"a[0]":{"code":"TX_014"}}""",
            """{"status":false,"id":12347,"error[]":{"code":"TX_014"}}""",
            """{"status":false,"id":12347,"error":[{"code":"TX_014"}]}""",
            """{"status":false,"id":12347,"":{"code":"TX_014"}}""",
            """{"status":true,"id":12347,"":{"dataResponse":{"description":"REVERSADA"}}}""",
        )) {
            assertFalse(c, reverso(http(200, c)) is ClaseDelReverso.Confirmado)
        }
        val ventaVacia = """{"status":true,"":{"id":12345,"dataResponse":{"authorization":"ABC123","simulation":false}}}"""
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, ventaVacia)))
    }

    @Test fun `preflight con simulation true o una clave repetida no cancela`() {
        assertEquals(false, Clasificador.preflightDeCancelacion(http(200, """{"status":true,"simulation":true,"error":{"code":"TX_024"}}""")))
        assertEquals(false, Clasificador.preflightDeCancelacion(http(200, """{"status":true,"status":true,"error":{"code":"TX_024"}}""")))
        assertEquals(false, Clasificador.preflightDeCancelacion(http(200, """{"status":true,"error":{"code":"TX_024"},"x":{"status":false}}""")))
    }

    // ---------- Sobres REALES del sandbox (Tarea 8, 20-nov): sus nombres de campo y sus formas; los valores son inventados ----------

    /**
     * Como contesta el sandbox de verdad: `id` en texto; `dataResponse.reference` es la referencia de BLUMON (12 dígitos, como
     * el RRN de ISO 8583), no la nuestra (la nuestra regresa en `dataResponse.batch`); el importe viene en `amountInfo`, sin
     * `amount`; y todo trae `simulation: true`.
     */
    private fun sobreDelSandbox(campos: String) =
        """{"status":true,"requestId":"3f2b9c1d-0a4e-4b7f-9c2d-8e1f0a3b5c7d","id":"84678","date":"2026-11-20","time":"05:44:40",""" +
            """"dataResponse":{"amountInfo":{"taxAmount":"0.14","subtotal":"0.86","total":"1.00","taxes":null,"tipAmount":null},""" +
            """$campos,"arpc":"1A2345B6C78D9012","membership":"1234567","binInformation":{"bin":"555555","bank":"BANCOXX",""" +
            """"product":"PRUEBAS PRUEBAS PRUEBA","type":"DEBIT","brand":"MASTERCARD"},"reference":"123456789012",""" +
            """"batch":"${intento.valor}","simulation":true}}"""

    private val aprobadaDelSandbox = sobreDelSandbox(""""authorization":"1A2B34","renewKey":false,"description":"APROBADA"""")
    private val reversadaDelSandbox = sobreDelSandbox(""""description":"REVERSADA"""")
    private val enSandbox = Esperado(intento, 100, pan, Ambiente.SANDBOX)

    @Test fun `venta aprobada con el sobre real del sandbox`() {
        assertEquals(DesenlaceDeVenta.Aprobada(84678, "1A2B34"), venta(http(200, aprobadaDelSandbox), enSandbox))
        // En producción `simulation=true` sigue sin valer, con el mismo sobre.
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, aprobadaDelSandbox)))
        // La referencia de Blumon no es la nuestra: sin simulación, el mismo sobre aprueba también en producción.
        val sinSimulacion = aprobadaDelSandbox.replace("\"simulation\":true", "\"simulation\":false")
        assertEquals(DesenlaceDeVenta.Aprobada(84678, "1A2B34"), venta(http(200, sinSimulacion)))
    }

    @Test fun `reverso REVERSADA real del sandbox`() {
        assertEquals(ClaseDelReverso.Confirmado(hora), Clasificador.reverso(http(200, reversadaDelSandbox), enSandbox, catalogo) { hora })
        // En producción, `simulation=true` nunca confirma: pudo entrar.
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(http(200, reversadaDelSandbox)))
    }

    /** La excepción es angosta: sólo `dataResponse.reference` y sólo con los 12 dígitos medidos. */
    @Test fun `la referencia de Blumon solo vale con su forma y en su lugar`() {
        for (otra in listOf("12345678901", "1234567890123", "12345678901A", " 123456789012", "")) {
            assertEquals(otra, DesenlaceDeVenta.NoSe, venta(http(200, aprobadaDelSandbox.replace("\"123456789012\"", "\"$otra\"")), enSandbox))
        }
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, aprobadaDelSandbox.replace("\"123456789012\"", "123456789012")), enSandbox))
        // Fuera de `dataResponse`, una `reference` sigue siendo la nuestra: 12 dígitos ahí son una racha que ensucia.
        val enLaRaiz = aprobadaDelSandbox.replace("\"id\":\"84678\"", "\"id\":\"84678\",\"reference\":\"123456789012\"")
        assertEquals(DesenlaceDeVenta.NoSe, venta(http(200, enLaRaiz), enSandbox))
    }

    // ---------- Arreglos 1 de la Tarea 8: el importe y el `batch` del sobre real ----------

    /** B: `dataResponse.amountInfo.total` se coteja como `amount` (centavos, forma cruda); `subtotal` y `taxAmount`, no. */
    @Test fun `el total de amountInfo se coteja contra el importe pedido`() {
        assertEquals(DesenlaceDeVenta.Aprobada(84678, "1A2B34"), venta(http(200, aprobadaDelSandbox), enSandbox))
        for (total in listOf("\"2.00\"", "\"0.99\"", "\"1.000\"", "\"+1.00\"", "\"1,00\"", "true", "{}")) {
            val otro = aprobadaDelSandbox.replace("\"total\":\"1.00\"", "\"total\":$total")
            assertEquals(total, DesenlaceDeVenta.NoSe, venta(http(200, otro), enSandbox))
        }
        // `subtotal` y `taxAmount` no son el cobro: no se cotejan.
        val otroSubtotal = aprobadaDelSandbox.replace("\"subtotal\":\"0.86\"", "\"subtotal\":\"7.00\"")
        assertEquals(DesenlaceDeVenta.Aprobada(84678, "1A2B34"), venta(http(200, otroSubtotal), enSandbox))
        // El reverso tampoco confirma con otro total.
        val reversoOtroTotal = reversadaDelSandbox.replace("\"total\":\"1.00\"", "\"total\":\"2.00\"")
        assertTrue(Clasificador.reverso(http(200, reversoOtroTotal), enSandbox, catalogo) { hora } is ClaseDelReverso.Reintentar)
    }

    /** C: un `batch` con forma de `AttemptId` que no es el nuestro deja el sobre sin veredicto; otra forma no cambia nada. */
    @Test fun `un batch con forma de referencia nuestra se coteja`() {
        fun conBatch(b: String) = aprobadaDelSandbox.replace("\"batch\":\"${intento.valor}\"", "\"batch\":$b")
        assertEquals(DesenlaceDeVenta.Aprobada(84678, "1A2B34"), venta(sinEco(200, conBatch("\"${intento.valor}\"")), enSandbox))
        assertEquals(DesenlaceDeVenta.NoSe, venta(sinEco(200, conBatch("\"1111aaaa2222bbbb3333cccc4444dddd\"")), enSandbox))
        val reversoAjeno = reversadaDelSandbox.replace("\"batch\":\"${intento.valor}\"", "\"batch\":\"1111aaaa2222bbbb3333cccc4444dddd\"")
        assertTrue(Clasificador.reverso(sinEco(200, reversoAjeno), enSandbox, catalogo) { hora } is ClaseDelReverso.Reintentar)
    }

    // ---------- Arreglos 2 de la Tarea 8: sin el eco de nuestro folio no hay definitivo de éxito (R12-1) ----------

    @Test fun `sin el eco de nuestro folio la aprobada es NoSe`() {
        val sinBatch = aprobadaDelSandbox.replace(",\"batch\":\"${intento.valor}\"", "")
        assertEquals(DesenlaceDeVenta.NoSe, venta(sinEco(200, sinBatch), enSandbox))
        val lote = aprobadaDelSandbox.replace("\"batch\":\"${intento.valor}\"", "\"batch\":\"000123\"")
        assertEquals(DesenlaceDeVenta.NoSe, venta(sinEco(200, lote), enSandbox))
        assertEquals(DesenlaceDeVenta.Aprobada(84678, "1A2B34"), venta(sinEco(200, aprobadaDelSandbox), enSandbox))
        // El literal del piloto, tal cual (sin `batch` ni `reference`), ya no basta.
        assertEquals(DesenlaceDeVenta.NoSe, venta(sinEco(200, VENTA_APROBADA)))
    }

    @Test fun `sin el eco el REVERSADA pudo entrar y con el eco confirma`() {
        val sinBatch = reversadaDelSandbox.replace(",\"batch\":\"${intento.valor}\"", "")
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), Clasificador.reverso(sinEco(200, sinBatch), enSandbox, catalogo) { hora })
        assertEquals(ClaseDelReverso.Confirmado(hora), Clasificador.reverso(sinEco(200, reversadaDelSandbox), enSandbox, catalogo) { hora })
        assertEquals(ClaseDelReverso.Reintentar("DESCONOCIDO", false, hora), reverso(sinEco(200, REVERSO_OK)))
        // Ola final · 1: un sobre de ERROR no trae eco, así que TX_014 ya no confirma: es duda.
        assertEquals(ClaseDelReverso.Reintentar("TX_014", false, hora), reverso(sinEco(200, error("TX_014"))))
    }

    @Test fun `sin el eco el parcial no guarda evidencia`() {
        val parcial = """{"status":true,"id":777,"dataResponse":{"codeResponse":"10","amount":"0.50","simulation":false}}"""
        assertEquals(DesenlaceDeVenta.DebeReversar(null, null), venta(sinEco(200, parcial)))
        assertEquals(DesenlaceDeVenta.DebeReversar(777, 50), venta(http(200, parcial)))
    }

    /** Arreglos 2 (§2): un id con 11 dígitos seguidos (el tipo ya no admite 12) repetido en `reference` sí es el eco. */
    @Test fun `nuestro id en una reference es el eco`() {
        val id = AttemptId("abc12345678901def0123456789abcde")
        val e = Esperado(id, 100, pan)
        val c = """{"status":true,"id":12345,"reference":"${id.valor}","dataResponse":{"authorization":"ABC123","simulation":false}}"""
        assertEquals(DesenlaceDeVenta.Aprobada(12345, "ABC123"), Clasificador.venta(sinEco(200, c), e, catalogo))
    }

    /** D: `renewKey` sólo cuenta como booleano `true` en `dataResponse`. */
    @Test fun `renewKey true pide llaves nuevas y otra forma se ignora`() {
        assertFalse(Clasificador.pideLlavesNuevas(http(200, aprobadaDelSandbox)))
        assertTrue(Clasificador.pideLlavesNuevas(http(200, aprobadaDelSandbox.replace("\"renewKey\":false", "\"renewKey\":true"))))
        for (otra in listOf("\"true\"", "1", "null")) {
            assertFalse(otra, Clasificador.pideLlavesNuevas(http(200, aprobadaDelSandbox.replace("\"renewKey\":false", "\"renewKey\":$otra"))))
        }
        assertFalse(Clasificador.pideLlavesNuevas(http(200, """{"status":true,"renewKey":true}""")))
        assertFalse(Clasificador.pideLlavesNuevas(Respuesta.SinRespuesta("TIEMPO")))
    }
}
