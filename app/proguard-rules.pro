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

-keep class com.example.clean_lib_services.** { *; }
-keep class com.paxsz.module.** { *; }
-keep class com.neptune.sdk.** { *; }
-keep interface com.example.clean_lib_services.** { *; }
-dontwarn com.paxsz.module.**
-dontwarn com.example.clean_lib_services.**

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
-repackageclasses 'a.b.c'
-allowaccessmodification

# Aggressive optimization
-optimizationpasses 7
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# ==========================================
# 3. REMOVE ALL LOGS (SECURITY CRITICAL!)
# ==========================================
# Logs contain sensitive business logic and flow
# MUST be removed in release builds

-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

-assumenosideeffects class timber.log.Timber$Tree {
    public *** d(...);
    public *** v(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
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
-allowaccessmodification
-mergeinterfacesaggressively

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
