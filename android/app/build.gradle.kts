plugins { id("com.android.application") }
val releaseKeystore = providers.environmentVariable("WAYPOINT_KEYSTORE_PATH").orNull
android {
 namespace = "dev.ubai.waypoint"
 compileSdk = 36
 defaultConfig { applicationId = "dev.ubai.waypoint"; minSdk = 31; targetSdk = 36; versionCode = 9; versionName = "1.0.0"; testInstrumentationRunner = "dev.ubai.waypoint.DeviceCheck" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 signingConfigs {
  if (!releaseKeystore.isNullOrBlank()) create("release") {
   storeFile = file(releaseKeystore)
   storePassword = providers.environmentVariable("WAYPOINT_KEYSTORE_PASSWORD").get()
   keyAlias = providers.environmentVariable("WAYPOINT_KEY_ALIAS").get()
   keyPassword = providers.environmentVariable("WAYPOINT_KEY_PASSWORD").get()
  }
 }
 buildTypes { release { isMinifyEnabled = false; isDebuggable = false; signingConfig = signingConfigs.findByName("release") } }
}

dependencies { implementation("org.unifiedpush.android:connector:3.3.5"); implementation("com.squareup.okhttp3:okhttp:4.12.0") }
