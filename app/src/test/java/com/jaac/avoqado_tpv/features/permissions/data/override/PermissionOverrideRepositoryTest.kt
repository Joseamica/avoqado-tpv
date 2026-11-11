package com.jaac.avoqado_tpv.features.permissions.data.override

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class PermissionOverrideRepositoryTest {
    private val api = mockk<PermissionOverrideApi>()
    private val repo = PermissionOverrideRepository(api)

    private fun error(code: Int, json: String) =
        Response.error<TokenResponse>(code, json.toResponseBody("application/json".toMediaType()))

    private fun falla(r: Response<TokenResponse>): ErrorDeAutorizacion {
        coEvery { api.crear(any(), any()) } returns r
        val res = runBlocking { repo.pedirToken("v1", "1234", "orders:comp") }
        assertThat(res.isFailure).isTrue()
        return res.exceptionOrNull() as ErrorDeAutorizacion
    }

    @Test fun token_201_devuelve_el_token() {
        coEvery { api.crear(any(), any()) } returns Response.success(
            201, TokenResponse(true, TokenData("tok-1", "2026-11-06T10:00:00Z", AutorizadoPor("s1", "Ana"))),
        )
        val res = runBlocking { repo.pedirToken("v1", "1234", "orders:comp") }
        assertThat(res.getOrNull()).isEqualTo("tok-1")
    }

    @Test fun pin_incorrecto_401_OVERRIDE_INVALID_PIN() {
        assertThat(falla(error(401, """{"code":"OVERRIDE_INVALID_PIN","message":"x"}""")))
            .isEqualTo(ErrorDeAutorizacion.PinIncorrecto)
    }

    @Test fun sin_este_permiso_403_OVERRIDE_INSUFFICIENT() {
        assertThat(falla(error(403, """{"code":"OVERRIDE_INSUFFICIENT"}"""))).isEqualTo(ErrorDeAutorizacion.SinEstePermiso)
    }

    @Test fun apagado_403_OVERRIDE_DISABLED() {
        assertThat(falla(error(403, """{"code":"OVERRIDE_DISABLED"}"""))).isEqualTo(ErrorDeAutorizacion.PinApagado)
    }

    @Test fun demasiados_intentos_429() {
        assertThat(falla(error(429, """{"message":"slow down"}"""))).isEqualTo(ErrorDeAutorizacion.DemasiadosIntentos)
    }

    @Test fun sin_red_IOException() {
        coEvery { api.crear(any(), any()) } throws IOException("sin red")
        val res = runBlocking { repo.pedirToken("v1", "1234", "orders:comp") }
        assertThat(res.exceptionOrNull()).isEqualTo(ErrorDeAutorizacion.SinConexion)
    }

    @Test fun `401_sin_code`() {
        assertThat(falla(error(401, """{"message":"jwt expired"}"""))).isEqualTo(ErrorDeAutorizacion.SesionVencida)
    }

    @Test fun otro_error_usa_el_message_del_servidor() {
        val e = falla(error(500, """{"message":"boom"}"""))
        assertThat(e).isInstanceOf(ErrorDeAutorizacion.Otro::class.java)
        assertThat(e.texto).isEqualTo("boom")
        assertThat(falla(error(500, "")).texto).isEqualTo("No se pudo autorizar. Intenta otra vez")
    }

    @Test fun manda_permission_orders_comp() {
        coEvery { api.crear(any(), any()) } returns error(401, """{"code":"OVERRIDE_INVALID_PIN"}""")
        runBlocking { repo.pedirToken("v9", "4321", "orders:comp") }
        coVerify { api.crear("v9", PedirTokenBody(pin = "4321", permission = "orders:comp")) }
    }

    @Test fun manda_operationKey_cuando_viene() {
        coEvery { api.crear(any(), any()) } returns error(401, """{"code":"OVERRIDE_INVALID_PIN"}""")
        runBlocking { repo.pedirToken("v9", "4321", "discounts:apply", "descuento-cliente:c1:d1") }
        coVerify {
            api.crear("v9", PedirTokenBody(pin = "4321", permission = "discounts:apply", operationKey = "descuento-cliente:c1:d1"))
        }
    }

    // El esquema del servidor acepta `operationKey` ausente, no `null`: sin llave el cuerpo es el de siempre (el converter usa Gson()).
    @Test fun sin_operationKey_el_cuerpo_no_la_lleva() {
        val gson = com.google.gson.Gson()
        assertThat(gson.toJson(PedirTokenBody("4321", "orders:comp"))).isEqualTo("""{"pin":"4321","permission":"orders:comp"}""")
        assertThat(gson.toJson(PedirTokenBody("4321", "discounts:apply", "descuento-cliente:c1:d1")))
            .isEqualTo("""{"pin":"4321","permission":"discounts:apply","operationKey":"descuento-cliente:c1:d1"}""")
    }

    private fun settings(json: String) = Response.success(
        com.google.gson.Gson().fromJson(json, VenueSettingsResponse::class.java),
    )

    @Test fun pinPrendido_true() {
        coEvery { api.settings("v1") } returns settings("""{"data":{"managerPinOverrideEnabled":true,"componentsStatus":{"promotionsPanel":"ok"}}}""")
        assertThat(runBlocking { repo.pinPrendido("v1") }).isTrue()
    }

    @Test fun pinPrendido_false() {
        coEvery { api.settings("v1") } returns settings("""{"data":{"managerPinOverrideEnabled":false,"componentsStatus":{"promotionsPanel":"ok"}}}""")
        assertThat(runBlocking { repo.pinPrendido("v1") }).isFalse()
    }

    @Test fun pinPrendido_null_si_panel_failed_campo_ausente_red_o_http() {
        coEvery { api.settings("v1") } returns settings("""{"data":{"managerPinOverrideEnabled":false,"componentsStatus":{"promotionsPanel":"failed"}}}""")
        assertThat(runBlocking { repo.pinPrendido("v1") }).isNull()
        coEvery { api.settings("v1") } returns settings("""{"data":{"componentsStatus":{"promotionsPanel":"ok"}}}""")
        assertThat(runBlocking { repo.pinPrendido("v1") }).isNull()
        coEvery { api.settings("v1") } throws IOException("sin red")
        assertThat(runBlocking { repo.pinPrendido("v1") }).isNull()
        coEvery { api.settings("v1") } returns Response.error(500, "".toResponseBody("application/json".toMediaType()))
        assertThat(runBlocking { repo.pinPrendido("v1") }).isNull()
    }
}
