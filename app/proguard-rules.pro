# =============================================================
# قواعد R8 شاملة — لا تحذف أي ميزة، فقط تخبر R8 بكيفية التعامل
# =============================================================

# ---- قواعد عامة ----
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ---- Room (حفظ Entities + DAOs) ----
-keep class com.smartassistant.app.data.local.entity.** { *; }
-keep class com.smartassistant.app.data.local.dao.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---- Kotlin Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---- Jetpack Lifecycle + ViewModel ----
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# ---- Navigation Compose ----
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

# ---- Coil (تحميل الصور) ----
-keep class coil.** { *; }
-dontwarn coil.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ---- DataStore ----
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ---- PDFBox + كل الاعتمادات الاختيارية (الحل الدائم) ----
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.apache.pdfbox.** { *; }
-keep class com.gemalto.jp2.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.apache.**
-dontwarn com.gemalto.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.bind.**
-dontwarn java.awt.**
-dontwarn java.security.**

# ---- ML Kit (OCR للاستيراد) ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ---- Splash Screen ----
-keep class androidx.core.splashscreen.** { *; }

# ---- Google Material Icons ----
-keep class androidx.compose.material.icons.** { *; }
