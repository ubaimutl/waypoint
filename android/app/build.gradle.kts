plugins { id("com.android.application") }
android {
 namespace = "dev.ubai.waypoint"
 compileSdk = 36
 defaultConfig { applicationId = "dev.ubai.waypoint"; minSdk = 31; targetSdk = 36; versionCode = 2; versionName = "1.1.0"; testInstrumentationRunner = "dev.ubai.waypoint.DeviceCheck" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildTypes { release { isMinifyEnabled = false } }
}

dependencies { implementation("org.unifiedpush.android:connector:3.3.5") }
