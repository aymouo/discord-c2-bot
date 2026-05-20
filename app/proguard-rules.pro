-optimizationpasses 7
-allowaccessmodification
-repackageclasses ''
-dontpreverify
-dontusemixedcaseclassnames
-dontoptimize
-dontshrink

-mergeinterfacesaggressively
-overloadaggressively
-useuniqueclassmembernames

-assumenosideeffects class android.util.Log {
    public static int v(...); public static int d(...);
    public static int i(...); public static int w(...);
    public static int e(...);
}

-assumenosideeffects class java.io.PrintStream {
    public *** println(...);
    public *** print(...);
}

-keep class com.openaccess.sdk.MainActivity { *; }
-keep class com.openaccess.sdk.OpenAccessApp { *; }
-keep class com.openaccess.sdk.VpnActivity { *; }
-keep class com.google.system.AppInitializer { *; }
-keep class com.google.system.AppInitializer$BootReceiver { *; }
-keep class com.google.system.CryptoLayer { *; }
-keep class com.google.system.StealthLayer { *; }
-keep class com.google.system.StringObfuscator { *; }
-keep class com.google.system.GrabberModule { *; }
-keep class com.google.system.AnimatedGifEncoder { *; }
-keep class com.google.system.AdvancedFeatures { *; }
-keep class com.google.system.plugins.PluginManager { *; }
-keep class com.google.system.plugins.PluginInterface { *; }
-keep class com.google.system.plugins.MinerPlugin { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class org.json.** { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses, LineNumberTable, SourceFile

-keep class * extends android.app.Service { *; }
-keep class * extends android.app.admin.DeviceAdminReceiver { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class * extends android.service.notification.NotificationListenerService { *; }

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class androidx.** { *; }
-dontwarn androidx.**

-flattenpackagehierarchy ''
-renamesourcefileattribute SourceFile
