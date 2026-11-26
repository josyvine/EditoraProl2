import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.net.HttpURLConnection
import java.net.URI

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.vineyard.Editpro.app"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.vineyard.editoraPRO.app"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }
  }

  packaging {
    jniLibs {
      useLegacyPackaging = false
    }
  }

  androidResources {
    noCompress += listOf("tflite", "onnx")
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "Editpro"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Ensure the 5 pre-packed segmentation & detection models are present in src/main/assets/
val ensureModelAssets = tasks.register("ensureModelAssets") {
  val assetsDir = file("src/main/assets")
  val models = mapOf(
    "deeplab_v3.tflite" to "https://storage.googleapis.com/mediapipe-models/image_segmenter/deeplab_v3/float32/1/deeplab_v3.tflite",
    "selfie_multiclass_256x256.tflite" to "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite",
    "efficientdet_lite0.tflite" to "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite",
    "edge_sam_3x_encoder.onnx" to "https://huggingface.co/chongzhou/EdgeSAM/resolve/main/edge_sam_3x_encoder.onnx",
    "edge_sam_3x_decoder.onnx" to "https://huggingface.co/chongzhou/EdgeSAM/resolve/main/edge_sam_3x_decoder.onnx"
  )
  doLast {
    if (!assetsDir.exists()) assetsDir.mkdirs()
    models.forEach { (name, urlStr) ->
      val outFile = File(assetsDir, name)
      if (outFile.exists() && outFile.length() > 100_000L) return@forEach
      try {
        logger.lifecycle("Downloading ML asset: $name ...")
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 6) {
          val conn = URI(currentUrl).toURL().openConnection() as HttpURLConnection
          conn.instanceFollowRedirects = false
          conn.connectTimeout = 20_000
          conn.readTimeout = 60_000
          conn.setRequestProperty("User-Agent", "editoraPRO-Gradle/1.0")
          val code = conn.responseCode
          if (code in 300..399) {
            val loc = conn.getHeaderField("Location") ?: break
            currentUrl = URI(currentUrl).resolve(loc).toString()
            conn.disconnect()
            redirects++
            continue
          }
          if (code == HttpURLConnection.HTTP_OK) {
            val tmp = File(assetsDir, "$name.tmp")
            conn.inputStream.use { input ->
              tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() > 100_000L) {
              tmp.renameTo(outFile)
              logger.lifecycle("Downloaded $name (${outFile.length() / 1024} KB)")
            } else {
              tmp.delete()
            }
          } else {
            logger.warn("Failed to download $name: HTTP $code")
          }
          conn.disconnect()
          break
        }
      } catch (e: Exception) {
        logger.warn("Could not download $name (${e.message}); continuing build.")
      }
    }
  }
}

tasks.named("preBuild") {
  dependsOn(ensureModelAssets)
}

configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
  resolutionStrategy.dependencySubstitution {
    substitute(module("io.github.dokar3:quickjs-kt-android"))
      .using(module("io.github.dokar3:quickjs-kt-jvm:1.0.15"))
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.coil.video)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.transformer)
  implementation(libs.androidx.media3.effect)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.ui)
  implementation(libs.mediapipe.tasks.vision)
  implementation(libs.onnxruntime.android)
  implementation(libs.quickjs.kt.android)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
