package com.jaac.avoqado_tpv.core.presentation.components

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.presentation.screens.WelcomeScreenContent
import com.jaac.avoqado_tpv.features.permissions.di.PermissionsEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Run only in the isolated QA app: no login, payment ViewModels or business operations. */
class PosInfoDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun contactAndFunctionsAreReachableInLightTheme() = verifyDialog(darkTheme = false)

    @Test fun contactAndFunctionsAreReachableInDarkThemeWithLargeText() =
        verifyDialog(darkTheme = true, fontScale = 1.5f)

    @Test fun backDismissesTheDialog() {
        val visible = mutableStateOf(true)
        compose.setContent {
            AvoqadoTheme {
                if (visible.value) PosInfoDialog { visible.value = false }
            }
        }
        compose.onNodeWithText("Avoqado POS").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("Avoqado POS").assertDoesNotExist()
    }

    @Test fun realWelcomeOpensContactWhileOfflineAndShiftAndAttendanceBlockSales() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = SecureStorage(context)
        // Refuse a configured terminal rather than changing its session or cached settings.
        assertNull("Use the isolated QA app without a session", storage.getToken())
        assertNull("Use the isolated QA app without refresh credentials", storage.getRefreshToken())
        assertNull("Use the isolated QA app without a venue", storage.getVenueId())
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, PermissionsEntryPoint::class.java)
        assertTrue("This fixture exercises the normal menu", entryPoint.modulesRepository().modules.value.isEmpty())
        assertTrue("This fixture requires the default Pago rápido tile", entryPoint.tpvSettingsRepository().settings.value.showQuickPayment)
        var operationalActions = 0
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 360
                screenHeightDp = 640
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.5f),
            ) {
                AvoqadoTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(360.dp, 640.dp)) {
                        WelcomeScreenContent(
                            staffName = "Prueba local",
                            clockInTime = null,
                            currentShift = null,
                            isShiftSystemEnabled = true,
                            isOffline = true,
                            requireClockInToLogin = true,
                            onStartPaymentWithAmount = { operationalActions++ },
                            onNavigateToShifts = { operationalActions++ },
                            onNavigateToOrdering = { operationalActions++ },
                            onNavigateToReports = { operationalActions++ },
                            onNavigateToPayments = { operationalActions++ },
                            onNavigateToSupport = { operationalActions++ },
                            onNavigateToSettings = { operationalActions++ },
                            onNavigateToSuperAdmin = { operationalActions++ },
                            onLogout = { operationalActions++ },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Pago rápido").assertIsNotEnabled()
        val card = compose.onNodeWithText("Contrata punto de venta")
        card.performScrollTo().assertIsDisplayed().assertIsEnabled()
        saveScreenshot("pos-welcome-card-large-text")
        card.performClick()
        compose.onNodeWithText("+52 56 4007 0001").assertIsDisplayed()
        saveScreenshot("pos-welcome-dialog-large-text")
        compose.onNodeWithText("Cerrar").assertIsDisplayed().performClick()
        compose.onNodeWithText("Avoqado POS").assertDoesNotExist()
        card.assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, operationalActions) }
    }

    private fun verifyDialog(darkTheme: Boolean, fontScale: Float = 1f) {
        val visible = mutableStateOf(true)
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 360
                screenHeightDp = 640
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                AvoqadoTheme(darkTheme = darkTheme) {
                    Column(Modifier.requiredSize(360.dp, 640.dp)) {
                        Text("Sin conexión", Modifier.height(56.dp))
                        if (visible.value) PosInfoDialog { visible.value = false }
                    }
                }
            }
        }
        compose.onNodeWithText("+52 56 4007 0001").assertIsDisplayed()
        compose.onNodeWithText("Cerrar").assertIsDisplayed()
        val qr = compose.onNodeWithContentDescription("QR de WhatsApp de ventas")
        qr.performScrollTo().assertIsDisplayed()
        // Decode rendered pixels: a correct label with a wrong QR must fail this test.
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                val bitmap = qr.captureToImage().asAndroidBitmap()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                MultiFormatReader().decode(
                    BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
                ).text
            }.getOrNull() == "https://wa.me/525640070001"
        }
        saveScreenshot(if (darkTheme) "pos-info-dark-large-text-qr" else "pos-info-light-qr")
        compose.onNodeWithText("IA con ChatGPT y Claude").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cerrar").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(false, visible.value) }
        compose.onNodeWithText("Avoqado POS").assertDoesNotExist()
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
