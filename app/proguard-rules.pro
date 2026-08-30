# =============================================================
# قواعد R8 شاملة
# =============================================================

# ---- عامة ----
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ---- Room ----
-keep class com.smartassistant.app.data.local.entity.** { *; }
-keep class com.smartassistant.app.data.local.dao.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---- Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---- Lifecycle + ViewModel ----
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# ---- Navigation ----
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

# ---- Coil ----
-keep class coil.** { *; }
-dontwarn coil.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ---- DataStore ----
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ---- PDFBox + BouncyCastle + اختياري ----
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.apache.pdfbox.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.gemalto.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.apache.**
-dontwarn com.gemalto.**
-dontwarn javax.xml.bind.**
-dontwarn java.awt.**
-dontwarn java.security.**

# ---- ML Kit OCR ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ---- Splash Screen ----
-keep class androidx.core.splashscreen.** { *; }

# ---- Material Icons ----
-keep class androidx.compose.material.icons.** { *; }
