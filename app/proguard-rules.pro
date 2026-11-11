# Project specific ProGuard / R8 rules for BiometricPass

# Room KSP generates precision keep rules for DAOs & Database; explicitly preserve Entity fields
-keep class com.biometricpass.app.data.local.CredentialEntity { *; }

# CameraX bundles its own consumer ProGuard rules; only suppress optional vendor warnings
-dontwarn androidx.camera.**

# Keep ZXing QR code core
-dontwarn com.google.zxing.**

# Strip all debug diagnostic log calls in Release builds
-assumenosideeffects class com.biometricpass.app.util.AppLog {
    public void d(...);
    public void w(...);
}
