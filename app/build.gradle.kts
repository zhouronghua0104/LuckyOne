plugins {
  id("com.android.application") version "8.7.3"
  id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
  namespace = "com.example.videoplayer"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.example.videoplayer"
    minSdk = 21
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }

  buildFeatures {
    viewBinding = true
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("androidx.appcompat:appcompat:1.7.0")

  // Media3 (ExoPlayer)
  implementation("androidx.media3:media3-exoplayer:1.5.1")
  implementation("androidx.media3:media3-ui:1.5.1")
}

