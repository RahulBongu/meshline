# Meshline release keep rules (D1).
#
# The release build is minified + resource-shrunk. These rules pin the libraries
# that resolve classes by name at runtime. CameraX and ZXing also ship their own
# consumer rules inside the AARs; the entries below are the extra entry points
# Meshline uses directly.

# Strip debug logging from release builds (keys/messages must never be logged).
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int d(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int i(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int i(java.lang.String, java.lang.String);
}
# Keep D1 privacy checks from R8-shortening away the keystore aliases.
-keep class com.meshline.app.security.** { *; }

# --- SQLCipher (net.zetetic:sqlcipher-android) ------------------------------
# Room opens the DB through SupportOpenHelperFactory; the native bridge is
# loaded by name (System.loadLibrary("sqlcipher")) and the helper classes are
# looked up reflectively by the SQLCipher runtime.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
-keepclasseswithmembernames class net.zetetic.database.** {
    native <methods>;
}
-dontwarn net.zetetic.database.**
-dontwarn net.sqlcipher.**

# --- BouncyCastle (Ed25519 / X25519) ---------------------------------------
# Identity and AnnounceVerifier use the lightweight BC API directly; BC also
# registers algorithm implementations through provider metadata. Keep the
# crypto/math packages and provider entry points (no JCA Ed25519 on API < 33).
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-dontwarn org.bouncycastle.**

# --- ZXing (QR generation + decode) ----------------------------------------
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# --- CameraX ----------------------------------------------------------------
# CameraX discovers its camera provider config by class name; keep the
# camera2 provider and the view/analysis entry points used by ScanQRScreen.
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }
-dontwarn androidx.camera.**

# --- Room -------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**
