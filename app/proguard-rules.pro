-keep public class com.hd.tvpro.** { *; }
-keep public class com.pngcui.skyworth.dlna.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn org.greenrobot.eventbus.**
-dontwarn com.lzy.okgo.**
-dontwarn androidx.media3.**