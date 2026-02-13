package com.jaac.avoqado_tpv.core.di

import android.content.Context
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for printer-related dependencies.
 *
 * **Provides:**
 * - PrinterManager singleton (app-wide)
 *
 * **Scope:** SingletonComponent (one instance per app)
 *
 * **Usage in ViewModel:**
 * ```kotlin
 * @HiltViewModel
 * class PaymentViewModel @Inject constructor(
 *     private val printerManager: PrinterManager
 * ) : ViewModel()
 * ```
 *
 * **Why Singleton:**
 * - Printer initialization is expensive (IPrinter.init())
 * - Reuse same printer instance across app
 * - Avoid multiple SDK connections
 *
 * **Dependencies:**
 * - Neptune SDK (PAX printer API)
 * - PrinterManager wrapper class
 */
@Module
@InstallIn(SingletonComponent::class)
object PrinterModule {

    /**
     * Provides PrinterManager singleton.
     *
     * **Configuration:**
     * - Lazy initialization of IDAL and IPrinter
     * - Singleton scope ensures one instance per app
     * - ApplicationContext prevents memory leaks
     *
     * @param context Application context (injected by Hilt)
     * @return PrinterManager singleton instance
     */
    @Provides
    @Singleton
    fun providePrinterManager(
        @ApplicationContext context: Context,
        secureStorage: SecureStorage
    ): PrinterManager {
        return PrinterManager(context, secureStorage)
    }
}
