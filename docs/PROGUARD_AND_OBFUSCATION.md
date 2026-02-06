# ProGuard / R8 Configuration & Obfuscation

Reference for the Avoqado TPV release build obfuscation pipeline.

## Configuration Files

| File | Purpose | Minify |
|------|---------|--------|
| `app/proguard-rules.pro` | **Primary rules** (306 lines). All keep/obfuscation/stripping logic. | N/A |
| `app/build.gradle.kts` | Enables R8 for `release` buildType. | `isMinifyEnabled = true`, `isShrinkResources = true` |
| `commonlib/proguard-rules.pro` | Empty (default template). Library module, `minifyEnabled false`. | No |
| `emv/proguard-rules.pro` | Empty (default template). Library module, `minifyEnabled false`. | No |
| `sdk/proguard-rules.pro` | Empty (default template). Library module, `minifyEnabled false`. | No |

Only the `:app` module runs R8. The three library modules (`sdk`, `commonlib`, `emv`) are consumed as-is; their code is processed by R8 when bundled into the final APK.

## Release Build Type Configuration

From `app/build.gradle.kts` lines 90-97:

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

Two ProGuard configs are merged at build time:
1. **`proguard-android-optimize.txt`** -- Android SDK default with optimizations enabled (vs. the non-optimize variant used by `commonlib`).
2. **`proguard-rules.pro`** -- Project-specific rules documented below.

Debug builds have `isMinifyEnabled = false` (no R8 processing).

## Blumon / PAX SDK Exclusion Rules (Section 1)

The Blumon SDK and PAX SDK perform **DEX integrity verification** at runtime. If R8 modifies their bytecode (renaming, inlining, merging), the SDK initialization crashes.

```proguard
# Blumon SDK -- complete exclusion from renaming/shrinking
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

# PAX SDK modules
-keep,allowoptimization class com.paxsz.** { *; }
-keep,allowoptimization class com.neptune.** { *; }
-keep class com.pax.** { *; }
```

Additionally, at the bottom of the file (line 274):

```proguard
# -mergeinterfacesaggressively removed because it breaks Blumon SDK DEX integrity check
```

**Why `allowoptimization` but not renaming?** The SDK's DEX integrity check validates class/method names and structure. R8 can still apply safe optimizations (dead code within methods, constant folding) without changing the class signature. But renaming or merging classes breaks the check.

Redundant safety net (lines 76-79) adds `allowshrinking` variants for the same packages to ensure no dead-code removal of SDK classes even if referenced indirectly.

## Aggressive Obfuscation (Section 2)

App code is aggressively obfuscated. The goal: `PaymentViewModel.processPayment()` becomes `a.b.c.A.a()`.

```proguard
# Repackage all app classes into flat namespace
-repackageclasses 'a.b.c'
-allowaccessmodification

# 7 optimization passes (default is 5)
-optimizationpasses 7
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!class/unboxing/enum
```

Key app packages use `allowobfuscation` (structure kept, names mangled):

```proguard
-keep,allowobfuscation class com.jaac.avoqado_tpv.features.payment.** { *; }
-keep,allowobfuscation class com.jaac.avoqado_tpv.core.domain.** { *; }
-keep,allowobfuscation class com.jaac.avoqado_tpv.features.authorization.** { *; }
```

This means: keep the classes from being removed (they may appear unused due to DI), but rename them freely.

## Log Stripping (Section 3)

All logging is stripped from release builds as a security measure:

| Library | Methods removed |
|---------|----------------|
| `timber.log.Timber` | `d`, `v`, `i`, `w`, `e` |
| `timber.log.Timber$Tree` | `d`, `v`, `i`, `w`, `e` |
| `android.util.Log` | `d`, `v`, `i`, `w`, `e` |

```proguard
-assumenosideeffects class timber.log.Timber { public static *** d(...); ... }
-assumenosideeffects class android.util.Log { public static *** d(...); ... }
```

Kotlin null-check intrinsics are also removed (line 278-281):

```proguard
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
}
```

## Source Metadata Removal (Section 4)

```proguard
-keepattributes !SourceFile,!LineNumberTable
-renamesourcefileattribute ""
```

This removes source file names and line numbers from stack traces. **Trade-off**: crash reports from Firebase Crashlytics will show obfuscated names unless a mapping file is uploaded (see below).

Runtime annotations are preserved for Hilt/Retrofit:

```proguard
-keepattributes *Annotation*,Signature,Exception
```

## Keep Rules Summary

| Section | Package / Class | Rule Type | Reason |
|---------|----------------|-----------|--------|
| 1 | `com.blumonpay.**` | `-keep` (full) | DEX integrity check |
| 1 | `com.example.clean_lib_services.**` | `-keep` (full) | PAX payment AAR, reflection |
| 1 | `com.paxsz.**`, `com.neptune.**`, `com.pax.**` | `-keep` (full) | PAX SDK + native JNI |
| 5 | `androidx.compose.runtime.**`, `androidx.compose.ui.**` | `-keep` (full) | Compose runtime reflection |
| 6 | `dagger.hilt.**`, `javax.inject.**` | `-keep` (full) | Hilt DI annotation processing |
| 7 | `kotlinx.coroutines.**`, `kotlin.coroutines.**` | `-keep` (full) | Coroutine internal dispatchers |
| 8 | Retrofit/OkHttp interfaces | `-keep` (partial) | HTTP method annotations via reflection |
| 9 | Gson `@SerializedName` fields | `-keepclassmembers` | JSON field name mapping |
| 10 | `androidx.security.crypto.**` | `-keep` (full) | EncryptedSharedPreferences |
| 12 | All enums | `-keepclassmembers` | `values()` / `valueOf()` required by JVM |
| 13 | Native methods | `-keepclasseswithmembernames` | JNI bridge |
| 14 | `Parcelable` implementors | `-keep` CREATOR | Android parceling |
| 15 | `**.data.**`, `**.domain.model.**` | `-keepclassmembers` | API response / domain model constructors and fields |
| 16 | `io.socket.**` | `-keep` (full) | Socket.IO uses reflection |

## Suppressed Warnings (`-dontwarn`)

```
com.blumonpay.**, com.example.clean_lib_services.**, com.paxsz.**, com.neptune.**, com.pax.**
okhttp3.**, okio.**, javax.annotation.**, org.conscrypt.**
org.bouncycastle.**, org.openjsse.**, javax.**, java.lang.invoke.**
io.socket.**
```

## StringObfuscator (Runtime Complement)

Beyond R8 obfuscation, `app/src/main/java/com/jaac/avoqado_tpv/core/security/StringObfuscator.kt` provides XOR-based string encryption for sensitive literals (API URLs, config values). This prevents strings from appearing in plaintext in the DEX. R8 obfuscation renames the class itself (e.g., `a.b.c.A`), adding a second layer.

Security level: low (XOR with fixed key `0xAB`). Sufficient against casual `strings` / `jadx` inspection, not against determined reverse engineering.

## Mapping File for Crash Reports

R8 generates a mapping file at build time:

```
app/build/outputs/mapping/productionRelease/mapping.txt
```

This file maps obfuscated names back to original names. **Without it, Firebase Crashlytics stack traces are unreadable.**

The project includes the Firebase Crashlytics Gradle plugin (`com.google.firebase.crashlytics` in `build.gradle.kts`), which **automatically uploads `mapping.txt`** during the `assembleProductionRelease` task.

To manually retrace a stack trace:

```bash
# Using bundled retrace tool
$ANDROID_HOME/tools/proguard/bin/retrace.sh \
  app/build/outputs/mapping/productionRelease/mapping.txt \
  stacktrace.txt
```

**Keep mapping files for every release version.** Store alongside the signed APK in iCloud:

```
~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Blumon/APK/<version>/mapping.txt
```

## Verification Checklist

After building a release APK, verify obfuscation:

```bash
# 1. Build release
./gradlew assembleProductionRelease

# 2. Decompile with jadx
jadx app/build/outputs/apk/production/release/app-production-release-unsigned.apk

# 3. Verify:
#    - Class names are obfuscated (a.b.c.A, not PaymentViewModel)
#    - No API URLs visible as plaintext strings
#    - No Timber/Log calls present
#    - Blumon SDK classes UNCHANGED (com.blumonpay.* intact)
#    - PAX SDK classes UNCHANGED (com.paxsz.*, com.neptune.*, com.pax.*)
```

## Key Constraints

| Constraint | Detail |
|-----------|--------|
| Blumon DEX integrity | SDK validates its own bytecode at init. Any R8 modification = crash. |
| `-mergeinterfacesaggressively` | Explicitly removed. Breaks Blumon SDK. |
| `armeabi` only | NDK filter in `build.gradle.kts`. PAX SDK native libs are armeabi only. |
| Library modules not minified | `commonlib`, `emv`, `sdk` have `minifyEnabled false`. R8 processes them only as part of `:app`. |
| 7 optimization passes | Higher than default (5). Increases build time but produces smaller/faster bytecode. |
| Source/line info stripped | Makes crash debugging harder without mapping file. Always preserve `mapping.txt`. |
