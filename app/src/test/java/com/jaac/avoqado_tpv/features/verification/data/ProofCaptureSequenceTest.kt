package com.jaac.avoqado_tpv.features.verification.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ProofCaptureSequenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        context.getSharedPreferences("proof_capture", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `next sube 1, 2, 3 y sigue tras recrear la clase`() {
        val a = ProofCaptureSequence(context)
        assertThat(listOf(a.next(), a.next(), a.next())).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(ProofCaptureSequence(context).next()).isEqualTo(4L)
    }

    @Test fun `deviceId es estable entre instancias`() {
        val id = ProofCaptureSequence(context).deviceId()
        assertThat(id).isNotEmpty()
        assertThat(ProofCaptureSequence(context).deviceId()).isEqualTo(id)
    }

    @Test fun `borrar las preferencias da otro deviceId y el contador empieza otra vez`() {
        val a = ProofCaptureSequence(context)
        a.next(); a.next()
        val viejo = a.deviceId()
        context.getSharedPreferences("proof_capture", Context.MODE_PRIVATE).edit().clear().commit()
        val b = ProofCaptureSequence(context)
        assertThat(b.deviceId()).isNotEqualTo(viejo)
        assertThat(b.next()).isEqualTo(1L)
    }

    /** Preferencias donde el disco está lleno: commit() dice false. */
    private fun sinEscritura(seq: Long = 5L, installId: String? = "id-viejo"): ProofCaptureSequence {
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putLong(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.commit() } returns false
        val prefs = mockk<SharedPreferences>()
        every { prefs.getLong("seq", any()) } returns seq
        every { prefs.getString("install_id", null) } returns installId
        every { prefs.edit() } returns editor
        val ctx = mockk<Context>()
        every { ctx.getSharedPreferences("proof_capture", Context.MODE_PRIVATE) } returns prefs
        return ProofCaptureSequence(ctx)
    }

    @Test fun `next no entrega un numero que no pudo guardar`() {
        val e = assertThrows(java.io.IOException::class.java) { sinEscritura().next() }
        assertThat(e.message).isEqualTo("No se pudo guardar el contador de capturas")
    }

    @Test fun `deviceId no entrega un id nuevo que no pudo guardar`() {
        val e = assertThrows(java.io.IOException::class.java) { sinEscritura(installId = null).deviceId() }
        assertThat(e.message).isEqualTo("No se pudo guardar el contador de capturas")
    }
}
