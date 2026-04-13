package com.jaac.avoqado_tpv.core.di

import javax.inject.Qualifier

/**
 * Qualifier for payment-only network stack.
 *
 * Used to inject shorter timeout clients for backend payment recording
 * without affecting the shared app networking client.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PaymentClient

