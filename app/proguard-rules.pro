# R8/ProGuard : ML Kit Pose Detection charge ses modèles depuis les assets natifs.
# Les keep ci-dessous protègent la sérialisation de l'API publique ML Kit en release.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.mlkit.**
