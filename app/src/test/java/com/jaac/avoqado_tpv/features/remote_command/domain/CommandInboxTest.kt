package com.jaac.avoqado_tpv.features.remote_command.domain

import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.dto.PendingCommandDto
import com.jaac.avoqado_tpv.core.data.network.dto.toTpvCommand
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * D665 r2: la bandeja de comandos se guarda en JSON y se relee en cada entrega. Un mismo comando que llega otra vez del
 * SERVIDOR (cada 60 s mientras espera) trae sus números como `LazilyParsedNumber`, y el guardado los devuelve como `Double`.
 * La identidad tiene que compararse por valor, no por el tipo de Java del número.
 */
class CommandInboxTest {

    private val guardado = mutableMapOf<String, String>()
    private val almacen = mockk<SecureStorage>(relaxed = true).also {
        every { it.getString(any(), any()) } answers { guardado[firstArg()] }
        every { it.putStringDurably(any(), any()) } answers { guardado[firstArg()] = secondArg(); Unit }
    }

    private fun delServidor(payloadJson: String) = PendingCommandDto(
        commandId = "cmd-1", correlationId = "corr-1", type = "MAINTENANCE_MODE",
        payload = JsonParser.parseString(payloadJson).asJsonObject, priority = "NORMAL", requiresPin = false,
        expiresAt = null, requestedBy = "sa", requestedByName = null, createdAt = Instant.now().toString(),
    ).toTpvCommand()!!

    @Test
    fun `P1 D665 el mismo comando con números no cambia de identidad al releerse del almacén`() {
        val payload = """{"_deliveryProtocol":2,"intentos":3,"monto":12.5,"reason":"rescate"}"""
        assertTrue(CommandInbox(almacen).remember(delServidor(payload)))
        // Otra entrega (otro proceso o el siguiente latido) relee la bandeja: los números vuelven como Double.
        assertTrue(CommandInbox(almacen).remember(delServidor(payload)))
        assertEquals(1, CommandInbox(almacen).pending().size)
    }

    @Test
    fun `P1 D665 la revisión de identidad sigue - el mismo id con otro contenido se rechaza`() {
        CommandInbox(almacen).remember(delServidor("""{"_deliveryProtocol":2,"intentos":3}"""))
        assertThrows(IllegalStateException::class.java) {
            CommandInbox(almacen).remember(delServidor("""{"_deliveryProtocol":2,"intentos":4}"""))
        }
    }
}
