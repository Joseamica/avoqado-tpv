package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Hilt bindings for AngelPay multi-merchant runtime (spec §6.6, §18.1).
 *
 * Binds [PaymentStateProvider] (read-only contract used by
 * [AngelPayMerchantRepository] to gate switches during in-flight payments)
 * to the concrete [PaymentStateHolder] singleton that the
 * `AngelPayPaymentViewModel` writes to.
 *
 * Other AngelPay collaborators (`AngelPaySdkGateway`,
 * `AngelPayMerchantRepository`, `PaymentStateHolder`) are constructor-injected
 * `@Singleton`s and don't need explicit `@Provides`. The DAO is provided by
 * `DatabaseModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AngelPayBindingsModule {

    @Binds
    @Singleton
    abstract fun bindPaymentStateProvider(impl: PaymentStateHolder): PaymentStateProvider

    companion object {
        /**
         * Provides the BuildConfig-backed legacy credential loader used by
         * [AngelPayCredentialResolver]. Dagger doesn't honor Kotlin default
         * parameters for constructor-injected functional types, so the resolver's
         * `legacyCredsLoader = ::defaultLegacyCredsLoader` default would fail
         * graph validation as soon as anything in the live tree requests it
         * (Task 31 surfaced this when `AngelPayPaymentViewModel` started injecting
         * `AngelPayAuthRepository`). Unit tests still pass a fake directly via
         * the constructor — this @Provides only feeds production Hilt graph.
         */
        @Provides
        @Singleton
        fun provideLegacyAngelPayCredsLoader(): () -> LegacyAngelPayCreds? = ::defaultLegacyCredsLoader

        /**
         * Provides the singleton [FirebaseCrashlytics] instance for AngelPay
         * collaborators (`AngelPayCredentialResolver`, `AngelPayConfigValidator`,
         * `AngelPayAuthRepository`). The TPV historically used
         * `FirebaseCrashlytics.getInstance()` directly throughout, so no global
         * `@Provides` existed — Task 31 surfaced the gap once `AngelPayAuthRepository`
         * entered the live Hilt graph.
         */
        @Provides
        @Singleton
        fun provideFirebaseCrashlytics(): FirebaseCrashlytics = FirebaseCrashlytics.getInstance()

        /**
         * Production Retrofit binding for [AngelPayReportApiRetrofit] — created
         * off the same singleton `Retrofit` used by `ApiService` so JWT/tenant
         * interceptors apply automatically and the `/api/v1/` prefix is shared.
         */
        @Provides
        @Singleton
        fun provideAngelPayReportApiRetrofit(retrofit: Retrofit): AngelPayReportApiRetrofit =
            retrofit.create(AngelPayReportApiRetrofit::class.java)

        /**
         * Production binding for the backend reporter used by
         * [AngelPayAuthRepository]. Previously a null stub (Task 30 / Task 31)
         * while the Phase 1 endpoint reporter was deferred. The Option B
         * workaround needs real delivery for `report-discovered-merchants`, so
         * this now hands back [AngelPayReportApiImpl] (a Retrofit-backed
         * adapter that wraps every call in `runCatching`).
         *
         * Return type stays nullable so the constructor parameter on
         * [AngelPayAuthRepository] (`reportApi: AngelPayReportApi? = null`) and
         * existing unit tests that pass `null` keep their exact prior shape.
         */
        @Provides
        @Singleton
        fun provideAngelPayReportApi(impl: AngelPayReportApiImpl): AngelPayReportApi? = impl
    }
}
