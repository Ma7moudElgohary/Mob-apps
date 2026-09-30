-keep class com.google.mlkit.** { *; }
-dontwarn org.tensorflow.lite.**
# LiteRT builds these classes and throws its exceptions from native code, so R8 must keep them intact.
-keep class com.google.ai.edge.litert.** { *; }
# ML Kit's segmentation client talks to its Play services module; keep its internal classes as Google ships them.
-keep class com.google.android.gms.internal.mlkit_vision_subject_segmentation.** { *; }
