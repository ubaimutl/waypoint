plugins { id("com.android.application") }
android {
 namespace = "dev.ubai.waypoint"
 compileSdk = 36
 defaultConfig { applicationId = "dev.ubai.waypoint"; minSdk = 31; targetSdk = 36; versionCode = 8; versionName = "1.3.4"; testInstrumentationRunner = "dev.ubai.waypoint.DeviceCheck" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildTypes { release { isMinifyEnabled = false } }
}

dependencies { implementation("org.unifiedpush.android:connector:3.3.5"); implementation("com.squareup.okhttp3:okhttp:4.12.0") }
