package com.jaac.avoqado_tpv.core.observability

import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ObservabilityTester - Herramienta para testing del sistema de observabilidad
 *
 * Úsalo para verificar que los logs lleguen al backend y que el health monitoring funcione.
 *
 * ## Uso:
 * ```kotlin
 * @Inject lateinit var observabilityTester: ObservabilityTester
 *
 * // Ejecutar test completo
 * viewModelScope.launch {
 *     observabilityTester.runFullTest()
 * }
 * ```
 */
@Singleton
class ObservabilityTester @Inject constructor(
    private val observability: ObservabilityManager
) {

    /**
     * Test completo del sistema de observabilidad
     *
     * Ejecuta una secuencia de logs de prueba para verificar que todo funcione.
     * Verifica en el backend que los logs lleguen correctamente.
     */
    suspend fun runFullTest() {
        Timber.d("🧪 Iniciando test de observabilidad...")

        // Test 1: Log INFO
        Timber.d("📝 Test 1/5: Log INFO")
        observability.logInfo("ObservabilityTest", "Test INFO log", mapOf(
            "testNumber" to 1,
            "type" to "info"
        ))
        delay(1000)

        // Test 2: Log WARNING
        Timber.d("📝 Test 2/5: Log WARNING")
        observability.logWarning("ObservabilityTest", "Test WARNING log", mapOf(
            "testNumber" to 2,
            "type" to "warning"
        ))
        delay(1000)

        // Test 3: Log ERROR
        Timber.d("📝 Test 3/5: Log ERROR")
        observability.logError(
            tag = "ObservabilityTest",
            message = "Test ERROR log",
            error = TestException("This is a test exception"),
            metadata = mapOf(
                "testNumber" to 3,
                "type" to "error"
            )
        )
        delay(1000)

        // Test 4: Log CRITICAL
        Timber.d("📝 Test 4/5: Log CRITICAL")
        observability.logCritical(
            tag = "ObservabilityTest",
            message = "Test CRITICAL log",
            error = TestException("This is a critical test exception"),
            metadata = mapOf(
                "testNumber" to 4,
                "type" to "critical",
                "severity" to "high"
            )
        )
        delay(1000)

        // Test 5: Breadcrumbs
        Timber.d("📝 Test 5/5: Breadcrumbs")
        observability.addBreadcrumb("Navigation", "Test breadcrumb 1", mapOf("screen" to "test"))
        observability.addBreadcrumb("Payment", "Test breadcrumb 2", mapOf("amount" to "100.50"))
        delay(1000)

        // Flush para asegurar que todos los logs se envíen
        Timber.d("📤 Flushing logs...")
        observability.flush()

        Timber.d("✅ Test completado. Verifica en el backend:")
        Timber.d("   1. Backend logs: [Terminal xxx] [ObservabilityTest] Test...")
        Timber.d("   2. PostgreSQL: SELECT * FROM \"TerminalLog\" WHERE tag = 'ObservabilityTest' ORDER BY timestamp DESC")
        Timber.d("   3. Firebase Crashlytics (5-10 min): Test CRITICAL log")
    }

    /**
     * Test rápido - Solo 1 log crítico
     */
    suspend fun quickTest() {
        Timber.d("🧪 Test rápido de observabilidad...")

        observability.logCritical(
            tag = "QuickTest",
            message = "Quick test from terminal",
            error = TestException("Quick test exception"),
            metadata = mapOf(
                "timestamp" to System.currentTimeMillis(),
                "test" to "quick"
            )
        )

        observability.flush()

        Timber.d("✅ Test enviado. Verifica backend en 5 segundos.")
    }

    /**
     * Test de payment flow simulado
     */
    suspend fun testPaymentFlow() {
        Timber.d("🧪 Test de payment flow...")

        // Simular pago exitoso
        observability.logInfo("Payment", "Payment initiated (TEST)", mapOf(
            "amount" to "150.00",
            "orderId" to "test-order-123"
        ))
        delay(500)

        observability.addBreadcrumb("Payment", "Card detected")
        delay(500)

        observability.logInfo("Payment", "Payment successful (TEST)", mapOf(
            "amount" to "150.00",
            "transactionId" to "tx-test-456",
            "approvalCode" to "ABC123"
        ))

        Timber.d("✅ Payment flow test completado")
    }

    /**
     * Test de error de payment
     */
    suspend fun testPaymentError() {
        Timber.d("🧪 Test de payment error...")

        observability.logInfo("Payment", "Payment initiated (TEST)", mapOf(
            "amount" to "200.00"
        ))
        delay(500)

        observability.logCritical(
            tag = "Payment",
            message = "Payment failed (TEST)",
            error = PaymentTestException("NA_002", "Invalid serial number"),
            metadata = mapOf(
                "amount" to "200.00",
                "errorCode" to "NA_002",
                "blumonMessage" to "Invalid serial number"
            )
        )

        observability.flush()

        Timber.d("✅ Payment error test completado")
    }

    // Exception classes para testing
    private class TestException(message: String) : Exception(message)
    private class PaymentTestException(val errorCode: String, message: String) : Exception(message)
}
