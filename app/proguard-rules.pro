# ===================================================
# Avoqado TPV - ProGuard Rules
# Maximum Code Protection (Without DexGuard Cost)
# Date: 2025-11-05
#
# Purpose: Protect business logic and Blumon integration
# from reverse engineering and decompilation.
#
# CRITICAL: These rules are MANDATORY for release builds
# ===================================================

# ==========================================
# 1. BLUMON SDK - MUST KEEP (CRITICAL!)
# ==========================================
# Blumon SDK uses reflection and dynamic loading
# If these classes are obfuscated, SDK initialization will FAIL

# ⚠️ CRITICAL: Production SDK uses com.blumonpay package
# The SDK performs DEX INTEGRITY CHECK - ANY modification causes crash
# Must completely exclude from ALL optimizations
-keep,allowoptimization class com.blumonpay.** { *; }
-keep,allowoptimization interface com.blumonpay.** { *; }
-keepclassmembers class com.blumonpay.** { *; }
-keepnames class com.blumonpay.**
-keepnames interface com.blumonpay.**
-dontwarn com.blumonpay.**

# lib_services AAR (PAX payment processing)
-keep,allowoptimization class com.example.clean_lib_services.** { *; }
-keep,allowoptimization interface com.example.clean_lib_services.** { *; }
-keepclassmembers class com.example.clean_lib_services.** { *; }
-keepnames class com.example.clean_lib_services.**
-dontwarn com.example.clean_lib_services.**

# PAX SDK modules - complete exclusion
-keep,allowoptimization class com.paxsz.** { *; }
-keep,allowoptimization class com.neptune.** { *; }
-keepclassmembers class com.paxsz.** { *; }
-keepclassmembers class com.neptune.** { *; }
-keepnames class com.paxsz.**
-keepnames class com.neptune.**
-dontwarn com.paxsz.**
-dontwarn com.neptune.**

# PAX native libraries used by SDK
-keep class com.pax.** { *; }
-keepclassmembers class com.pax.** { *; }
-dontwarn com.pax.**

# Keep Blumon callback classes
-keepclassmembers class * {
    @com.example.clean_lib_services.** *;
}

# ==========================================
# 2. AGGRESSIVE OBFUSCATION
# ==========================================
# Obfuscate class names, methods, fields
# Attackers see: a.b.c.A.a() instead of PaymentViewModel.processPayment()

-keep,allowobfuscation class com.jaac.avoqado_tpv.features.payment.** { *; }
-keep,allowobfuscation class com.jaac.avoqado_tpv.core.domain.** { *; }
-keep,allowobfuscation class com.jaac.avoqado_tpv.features.authorization.** { *; }

# Repackage everything to generic names
# EXCEPT payment SDKs which do DEX integrity checks
-repackageclasses 'a.b.c'
-allowaccessmodification

# Aggressive optimization - but exclude payment SDK classes
-optimizationpasses 7
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!class/unboxing/enum

# CRITICAL: Exclude Blumon/PAX SDK from all optimizations
# These SDKs perform DEX integrity verification
-keep,allowshrinking class com.blumonpay.** { *; }
-keep,allowshrinking class com.pax.** { *; }
-keep,allowshrinking class com.paxsz.** { *; }
-keep,allowshrinking class com.neptune.** { *; }

# ==========================================
# 3. REMOVE ALL LOGS (SECURITY CRITICAL!)
# ==========================================
# Logs contain sensitive business logic and flow
# MUST be removed in release builds

# Remove debug/verbose/info logs in release, but KEEP warn/error for Crashlytics
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

-assumenosideeffects class timber.log.Timber$Tree {
    public *** d(...);
    public *** v(...);
    public *** i(...);
}

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# ==========================================
# 4. HIDE SOURCE CODE METADATA
# ==========================================
# Remove debugging information that helps decompilers

-keepattributes !SourceFile,!LineNumberTable
-renamesourcefileattribute ""

# Keep only annotations for runtime (Hilt, etc.)
-keepattributes *Annotation*,Signature,Exception

# ==========================================
# 5. JETPACK COMPOSE - REQUIRED
# ==========================================
# Compose needs certain classes for UI rendering

-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keepclassmembers class androidx.compose.** {
    <init>(...);
}

# ==========================================
# 6. HILT / DAGGER - DEPENDENCY INJECTION
# ==========================================
# Hilt uses annotation processing, needs certain classes

-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ApplicationComponentManager { *; }

-keepclasseswithmembernames class * {
    @dagger.** *;
}

-keepclasseswithmembernames class * {
    @javax.inject.** *;
}

# ==========================================
# 7. KOTLIN COROUTINES
# ==========================================

-keep class kotlinx.coroutines.** { *; }
-keep class kotlin.coroutines.** { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ==========================================
# 8. RETROFIT & OKHTTP
# ==========================================
# Network layer needs reflection for API interfaces

-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# Keep generic types for Retrofit
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# ==========================================
# 9. GSON / KOTLINX SERIALIZATION
# ==========================================
# JSON serialization needs class names

-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.** *;
}

# ==========================================
# 10. ENCRYPTED SHARED PREFERENCES
# ==========================================

-keep class androidx.security.crypto.** { *; }
-keepclassmembers class * extends androidx.security.crypto.** {
    <init>(...);
}

# ==========================================
# 11. REMOVE UNUSED CODE
# ==========================================

-dontskipnonpubliclibraryclasses
-dontpreverify

# ==========================================
# 12. ENUM OPTIMIZATION
# ==========================================
# Keep enum names for debugging, but obfuscate methods

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ==========================================
# 13. NATIVE CODE (JNI)
# ==========================================
# If using native libraries

-keepclasseswithmembernames class * {
    native <methods>;
}

# ==========================================
# 14. PARCELABLE
# ==========================================

-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ==========================================
# 15. CUSTOM RULES FOR SPECIFIC CLASSES
# ==========================================

# Keep data classes structure (used in API responses)
-keepclassmembers class com.jaac.avoqado_tpv.**.data.** {
    <init>(...);
    <fields>;
}

# Keep domain models
-keepclassmembers class com.jaac.avoqado_tpv.**.domain.model.** {
    <init>(...);
    <fields>;
}

# ==========================================
# 16. SOCKET.IO
# ==========================================

-keep class io.socket.** { *; }
-keep class io.socket.emitter.** { *; }
-dontwarn io.socket.**

# ==========================================
# 17. FINAL OPTIMIZATIONS
# ==========================================

# Merge classes to reduce DEX count
# NOTE: -mergeinterfacesaggressively removed because it breaks Blumon SDK DEX integrity check
-allowaccessmodification

# Remove debug info
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
}

# ==========================================
# 18. WARNINGS TO IGNORE
# ==========================================

-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.**
-dontwarn java.lang.invoke.**

# ==========================================
# ANGELPAY SDK - kotlinx.serialization & transitive deps
# ==========================================
# AngelPay SDK (fat AAR) bundles kotlinx.serialization, snakeyaml,
# and references kotlinx.parcelize. These aren't resolvable by R8
# in the consumer classpath — suppress the warnings.
-dontwarn kotlinx.serialization.json.Json
-dontwarn kotlinx.serialization.json.JsonBuilder
-dontwarn kotlinx.parcelize.Parcelize
# snakeyaml uses java.beans (not available on Android, only used on JVM server-side)
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn org.slf4j.impl.StaticLoggerBinder

# ==========================================
# END OF PROGUARD RULES
# ==========================================

# NOTE: After adding these rules, test release build thoroughly
# to ensure SDK still works correctly.
#
# To verify obfuscation:
# 1. Build release APK: ./gradlew assembleRelease
# 2. Decompile with jadx: jadx app-release.apk
# 3. Check that class names are obfuscated (a.b.c.A)
# 4. Verify no API URLs or serial numbers are visible
# 5. Confirm Timber logs are removed

# AngelPay SDK (1.0.4) bundles Sentry but its consumer-proguard.txt only
# covers angelpaysdk + nexgo packages, not Sentry. Without keep rules R8
# strips Sentry classes that the SDK initializes at payment time, causing
# the dialog "Failed to initialize Sentry's SDK" mid-cobro. -dontwarn
# silences references to optional integrations that aren't on our classpath.
-keep class io.sentry.** { *; }
-keepclassmembers class io.sentry.** { *; }
-dontwarn io.sentry.android.fragment.FragmentLifecycleIntegration
-dontwarn io.sentry.android.replay.R$id
-dontwarn io.sentry.android.timber.SentryTimberIntegration
-dontwarn io.sentry.compose.gestures.ComposeGestureTargetLocator
-dontwarn io.sentry.compose.viewhierarchy.ComposeViewHierarchyExporter
-dontwarn io.sentry.ndk.DebugImage
-dontwarn io.sentry.ndk.INativeScope
-dontwarn io.sentry.ndk.NativeModuleListLoader
-dontwarn io.sentry.ndk.NativeScope
-dontwarn io.sentry.ndk.NdkHandlerStrategy
-dontwarn io.sentry.ndk.NdkOptions
-dontwarn io.sentry.ndk.SentryNdk

# ════════════════════════════════════════════════════════════════════════════
#  ANGELPAY SDK — DEFENSIVE KEEP RULES (Ktor / kotlinx.serialization / binaryfoo)
# ════════════════════════════════════════════════════════════════════════════
# Status (2026-05-25): AngelPay SDK 1.0.8's official `consumer-proguard.txt`
# now bundles equivalent keep rules INSIDE the AAR, so these manual rules
# below are NO LONGER STRICTLY NECESSARY when consuming v1.0.8+. R8 already
# inherits the right rules automatically from the AAR.
#
# However we KEEP them on purpose for defense-in-depth:
#   1. Survives downgrades. If someone reverts the AAR to 1.0.5 or 1.0.7
#      (which had a near-empty consumer-proguard.txt), the app still works.
#   2. Survives upstream regressions. If AngelPay accidentally ships a future
#      SDK with a broken consumer-proguard.txt, R8 still keeps the right
#      classes via these rules.
#   3. Operational history. The dated comments below explain WHY each rule
#      exists (the exact bug each one prevents). Deleting them loses context
#      that took multiple production crashes to discover.
#
# Duplicate `-keep` entries are idempotent — R8 takes the union. Zero APK
# size cost (keep rules don't add bytes; they only prevent stripping).
#
# Safe to delete on a future cleanup pass if/when we're confident no one
# will revert the SDK version. Until then, leave as-is.
# ════════════════════════════════════════════════════════════════════════════

# AngelPay SDK bundles Ktor + AtomicFU for its HTTP client. Ktor's
# DefaultPool / ChannelJob / AtomicFU classes use reflection on `volatile`
# fields (e.g. `top` in DefaultPool) — R8 minify renames those fields and
# AtomicFU's `<clinit>` throws NoSuchFieldException, leaving the AngelPay
# auth flow hung in "Autenticando..." forever (no exception bubbles up
# because the Ktor HTTP client thread silently dies). Confirmed via
# Crashlytics 2026-05-20 on `2.1.0-nexgo-prod`:
#   io.ktor.utils.io.pool.DefaultPool.<clinit>
#   java.lang.NoSuchFieldException: No field top in class La/b/c/u10
# Keep all Ktor + AtomicFU classes + their volatile field names.
# ⚠ Covered by AAR 1.0.8+ consumer-proguard.txt — kept defensively.
-keep class io.ktor.** { *; }
-keepnames class io.ktor.** { *; }
-keepclassmembers class io.ktor.** {
    volatile <fields>;
}
-keep class kotlinx.atomicfu.** { *; }
-keepclassmembers class kotlinx.atomicfu.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.coroutines.internal.** {
    volatile <fields>;
}
-dontwarn io.ktor.**
-dontwarn kotlinx.atomicfu.**

# AngelPay SDK 1.0.7 EMV processing — keep io.github.binaryfoo.* classes.
# The SDK looks up `io.github.binaryfoo.decoders.CryptogramInformationDecoder`
# (and likely other decoders) via `Class.forName(...)` at chip-payment time.
# R8 marks these classes dead because no direct refs exist in our code → app
# crashes mid-cobro with `ClassNotFoundException`. Confirmed 2026-05-21 on
# Nexgo SPRD N86 with v2.2.0-nexgo-prod after auth succeeded.
# ⚠ Covered by AAR 1.0.8+ consumer-proguard.txt — kept defensively.
-keep class io.github.binaryfoo.** { *; }
-keepclassmembers class io.github.binaryfoo.** { *; }
-dontwarn io.github.binaryfoo.**

# kotlinx.serialization runtime — REQUIRED for AngelPay SDK auth in release builds.
# The SDK 1.0.5 serializes its HTTP request bodies (auth payloads, transaction
# payloads, etc.) using @Serializable data classes. R8 strips the generated
# $Companion + $$serializer classes by default because they appear unreferenced
# (the runtime accesses them via reflection). Without these rules, every release
# build silently sends empty/garbled JSON to AngelPay's backend → AngelPay
# responds with generic "Error en la autenticación" because the credentials
# field arrives empty. nexgoDebug works because debug builds skip R8.
# Reproduced 2026-05-21 on AVQD-N860W173570 with v2.1.1-nexgo-prod (66):
# auth always failed in release, always succeeded in debug.
# ⚠ Covered by AAR 1.0.8+ consumer-proguard.txt — kept defensively.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# kotlinx.serialization generic keep rules — preserve Companion + $$serializer
# for every @Serializable class (consumer rules pattern from kotlinx-serialization).
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# AngelPay SDK internal @Serializable models — historically the official
# consumer-proguard.txt only covered public `com.angelpay.angelpaysdk.models.**`,
# but the wire layer lives under `internal.data.**` and was NOT covered.
# Without these keeps, AuthRequest, CardInformation, ChargePlanTransaction,
# etc. get obfuscated property names → AngelPay backend can't parse the JSON
# → auth fails.
# ⚠ AAR 1.0.8+ improved consumer-proguard.txt to apply generic @Serializable
#   keep rules that cover `internal.data.**` indirectly — kept defensively
#   in case any future internal model is added without the @Serializable
#   annotation but accessed via reflection.
-keep class com.angelpay.angelpaysdk.internal.data.remote.** { *; }
-keep class com.angelpay.angelpaysdk.internal.data.local.entity.** { *; }
-keep class com.angelpay.angelpaysdk.internal.data.params.** { *; }
-keep class com.angelpay.angelpaysdk.internal.data.dto.** { *; }
-keepclassmembers class com.angelpay.angelpaysdk.internal.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.angelpay.angelpaysdk.internal.**$$serializer { *; }
-dontwarn com.angelpay.angelpaysdk.internal.**
