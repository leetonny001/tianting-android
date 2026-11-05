# 协议模型靠 kotlinx.serialization 生成的序列化器，反射取 Companion。
-keepclassmembers class com.tianting.sdk.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tianting.sdk.**$$serializer { *; }
-keepattributes *Annotation*, InnerClasses

# release 里连 AppLog 之外也不留 Log 调用（AC-UI-90-12）。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
