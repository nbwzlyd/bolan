-keep public class com.hd.tvpro.** { *; }
-keep public class com.pngcui.skyworth.dlna.** { *; }
-keep class com.pdy.tvpro.security.SecuLoader { *; }
-keep class com.pdy.tvpro.security.SecurityGate {
    public static boolean blocked;
    *;
}
-keep class com.pdy.tvpro.security.Dex2C
-keepclassmembers class com.pdy.tvpro.app.App {
    int securityPing();
}
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
-keepclassmembers class * {
    native <methods>;
}
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn org.greenrobot.eventbus.**
-dontwarn com.lzy.okgo.**
-dontwarn androidx.media3.**