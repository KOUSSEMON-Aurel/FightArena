# R8/ProGuard : MediaPipe Tasks utilise la réflexion + charge des libs natives.
# Sans ces keep, la release MSG non shadées crashait en runtime au premier
# detectAsync (les générés/méta-classes sont supprimées par tree shaking).
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.mediapipe.tasks.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**