-keep class com.google.mlkit.** { *; }
-dontwarn org.tensorflow.lite.**
# LiteRT builds these classes and throws its exceptions from native code, so R8 must keep them intact.
-keep class com.google.ai.edge.litert.** { *; }
