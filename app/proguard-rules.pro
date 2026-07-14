-keep public class com.pdy.tvpro.** { *; }
-keep public class com.pngcui.skyworth.dlna.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
-dontwarn org.greenrobot.eventbus.**
-dontwarn com.lzy.okgo.**
-dontwarn androidx.media3.**
-dontwarn com.pdy.tvpro.security.**