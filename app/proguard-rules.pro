# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep our vision/model classes intact (no reflection needed, but keep names for stack traces)
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
