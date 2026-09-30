import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Navisens developer key. Kept out of version control: supply it through
// local.properties, an environment variable, or -PNAVISENS_DEVELOPER_KEY.
// When absent the generated string is empty and the trajectory view stays off.
val navisensDeveloperKey: String = run {
    val fromLocalProperties = rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
        ?.getProperty("NAVISENS_DEVELOPER_KEY")
    (project.findProperty("NAVISENS_DEVELOPER_KEY") as String?)
        ?: System.getenv("NAVISENS_DEVELOPER_KEY")
        ?: fromLocalProperties
        ?: ""
}

val appModelTflite = file("../models/app/peci-edge-cpp-android-v9-impulse-#1/tflite-model/tflite_learn_937255_4.tflite")
val wristbandModelTflite = file("../models/wristband/peci-edge-custom-v17-impulse-#1/trained.tflite")
val whisperCppCore = file("src/main/cpp/whisper.cpp/src/whisper.cpp")
val enableWhisperNative = whisperCppCore.exists()

val syncMlModelAssets by tasks.registering(Copy::class) {
    from(appModelTflite) {
        rename { "peci_model.tflite" }
    }
    from(wristbandModelTflite) {
        rename { "trained.tflite" }
    }
    into(file("src/main/assets"))
}

tasks.named("preBuild") {
    dependsOn(syncMlModelAssets)
}

android {
    namespace = "com.example.peciwearables"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.peciwearables"
        minSdk = 34
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        resValue("string", "navisens_developer_key", navisensDeveloperKey)

        ndk {
            abiFilters.add("arm64-v8a")
        }

        if (enableWhisperNative) {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++17", "-fexceptions")
                    arguments += listOf("-DANDROID_STL=c++_static")
                }
            }
        }
    }

    if (enableWhisperNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }

        ndkVersion = "26.3.11579264"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        resValues = true
    }
    lint {
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation("io.mockk:mockk:1.13.13")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    // CameraX 1.4.1 — binários .so alinhados a 16 KB (exigência Android 15+).
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // ONNX Runtime >= 1.21 melhora compatibilidade de page size 16 KB
    // no JNI auxiliar em alguns dispositivos Android 15+.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    // GPS fusionado (FusedLocationProvider)
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // WebView (Navisens Web SDK embedding for 2D trajectory)
    implementation("androidx.webkit:webkit:1.10.0")

    // Wear OS DataLayer — comunicação com Galaxy Watch 8 via DataClient,
    // MessageClient e ChannelClient. O watch corre o módulo `:wear`.
    implementation(libs.play.services.wearable)
    implementation(libs.paho.mqtt.client)
}
