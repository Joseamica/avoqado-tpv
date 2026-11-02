package com.jaac.avoqado_tpv

import android.app.Application
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MainActivityRestoreReplayTest {
    private val storage = mockk<SecureStorage>(relaxed = true)

    @Before fun setup() {
        mockkObject(HeartbeatScheduler, PaymentSyncScheduler)
        every { HeartbeatScheduler.start(any()) } returns Unit
        every { PaymentSyncScheduler.start(any()) } returns Unit
        every { storage.isTerminalActivated() } returns true
        every { storage.isAuthenticated() } returns true
        every { storage.getStaffId() } returns "staff"
        every { storage.getVenueId() } returns "venue"
    }

    @After fun cleanup() = unmockkAll()

    private fun restoredActivity() {
        // Exercise the shared post-permission startup without onCreate, Hilt injection or either payment SDK.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.secureStorage = storage
        MainActivity::class.java.getDeclaredMethod("startHeartbeatIfActivated").apply {
            isAccessible = true
        }.invoke(activity)
    }

    @Test fun `restored staff session wakes durable replay`() {
        every { storage.getIsKioskMode() } returns false
        restoredActivity()
        verify(exactly = 1) { PaymentSyncScheduler.start(any()) }
    }

    @Test fun `restored kiosk session wakes the same durable replay`() {
        every { storage.getIsKioskMode() } returns true
        restoredActivity()
        verify(exactly = 1) { PaymentSyncScheduler.start(any()) }
    }

    @Test fun `activation alone does not wake replay before login`() {
        every { storage.isAuthenticated() } returns false
        restoredActivity()
        verify(exactly = 0) { PaymentSyncScheduler.start(any()) }
    }

    @Test fun `missing actor does not wake replay`() {
        every { storage.getStaffId() } returns null
        restoredActivity()
        verify(exactly = 0) { PaymentSyncScheduler.start(any()) }
    }

    @Test fun `missing venue does not wake replay`() {
        every { storage.getVenueId() } returns null
        restoredActivity()
        verify(exactly = 0) { PaymentSyncScheduler.start(any()) }
    }

    @Test fun `unactivated terminal does not wake replay`() {
        every { storage.isTerminalActivated() } returns false
        restoredActivity()
        verify(exactly = 0) { PaymentSyncScheduler.start(any()) }
    }

    @Test fun `scheduling failure does not crash the shared startup`() {
        every { PaymentSyncScheduler.start(any()) } throws IllegalStateException("Scheduler unavailable")
        restoredActivity()
        verify(exactly = 1) { HeartbeatScheduler.start(any()) }
    }

    @Test fun `startup cancellation still propagates`() {
        every { PaymentSyncScheduler.start(any()) } throws kotlinx.coroutines.CancellationException("Cancelled")
        try {
            restoredActivity()
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (error: java.lang.reflect.InvocationTargetException) {
            org.junit.Assert.assertTrue(error.cause is kotlinx.coroutines.CancellationException)
        }
    }
}
