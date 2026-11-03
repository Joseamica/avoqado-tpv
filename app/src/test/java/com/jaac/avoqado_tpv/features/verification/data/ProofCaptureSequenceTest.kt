package com.jaac.avoqado_tpv.features.verification.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
}
