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
