# قواعد تُطبق على APK النهائي — تكمّل proguard-rules.pro
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @com.google.gson.annotations.SerializedName <fields>;
}
