package com.jaac.avoqado_tpv.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * Cached currency formatter to avoid repeated Locale lookups.
 *
 * NumberFormat.getCurrencyInstance(Locale) does a locale lookup each call,
 * which is expensive when called from Compose recompositions (21+ call sites).
 * This object caches a single instance and synchronizes access (NumberFormat is not thread-safe).
 */
object CurrencyFormatter {
    private val format: NumberFormat by lazy {
        NumberFormat.getCurrencyInstance(Locale("es", "MX"))
    }

    fun format(amount: BigDecimal): String = synchronized(format) {
        format.format(amount)
    }

    fun format(amount: Double): String = synchronized(format) {
        format.format(amount)
    }

    fun format(amount: Number): String = synchronized(format) {
        format.format(amount)
    }
}

/**
 * Composable helper: remembered currency format instance for use in @Composable scope.
 * Use this when you need the NumberFormat object directly (e.g., for custom formatting).
 */
@Composable
fun rememberCurrencyFormat(): NumberFormat = remember {
    NumberFormat.getCurrencyInstance(Locale("es", "MX"))
}
