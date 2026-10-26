plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

// The engine runs inside the wallpaper process, so it only uses platform APIs
// (OpenGL ES, MediaCodec, sensors) plus the small JSON library for scene files.
android {
  namespace = "io.github.rdilipkumar.livewallpapermaker.engine"
  compileSdk { version = release(36) }

  defaultConfig {
    minSdk = 29
    consumerProguardFiles("consumer-rules.pro")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.junit)
  androidTestImplementation(libs.junit)
}
