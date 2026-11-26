# ==========================================
# R8 / ProGuard Hardening (Anti-Debug / Anti-Decompile)
# ==========================================

# Strip all Log.* calls in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
    public static int println(...);
}

# Flatten package names and obfuscate aggressively
-repackageclasses 'o'
-allowaccessmodification

# Hide original source file names and local variable / parameter names from decompilers
-renamesourcefileattribute 'SourceFile'
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ==========================================
# ML Runtimes & QuickJS Keep Rules
# ==========================================

-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.common.flogger.**

-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

-keep class com.dokar.quickjs.** { *; }
-keepclassmembers class com.dokar.quickjs.** { *; }
-dontwarn com.dokar.quickjs.**

# ==========================================
# Media3 Transformer / ExoPlayer Keep Rules
# ==========================================

-keep class androidx.media3.transformer.** { *; }
-keep class androidx.media3.effect.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }

# ==========================================
# OkHttp & Kotlinx Serialization
# ==========================================

-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepclassmembers,allowobfuscation class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class com.vineyard.editoraPRO.app.data.model.**$$serializer { *; }
-keepclassmembers class com.vineyard.editoraPRO.app.data.model.** {
    *** Companion;
}

# ==========================================
# Security / JNI Guard
# ==========================================

-keep class com.vineyard.editoraPRO.app.security.SecurityGuard { *; }