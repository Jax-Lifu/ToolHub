import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val buildDateTime: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use(::load)
}

android {

    signingConfigs {
        getByName("debug") {
            val ksPath = System.getenv("TOOLHUB_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
                ?: keystoreProps.getProperty("storeFile")?.takeIf { it.isNotBlank() }
            val ksFile = ksPath?.let { rootProject.file(it) }
            if (ksFile != null && ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("TOOLHUB_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: keystoreProps.getProperty("storePassword")
                keyAlias = (System.getenv("TOOLHUB_KEY_ALIAS") ?: System.getenv("TOOLHUB_KEYSTORE_ALIAS"))
                    ?.takeIf { it.isNotBlank() }
                    ?: keystoreProps.getProperty("keyAlias")
                keyPassword = System.getenv("TOOLHUB_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: keystoreProps.getProperty("keyPassword")
            }
        }
    }

    namespace = "com.qytech.toolhub"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.qytech.toolhub"
        minSdk = 31
        targetSdk = 37
        versionCode = 10005
        versionName = "0.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }

        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")

        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        localeFilters += listOf("zh", "en", "ja", "ko")
    }
    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.version",
                "META-INF/*.properties",
                "META-INF/version-control-info.textproto",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set("toolhub-${variant.name}-$buildDateTime.apk")
            }
        }
    }
}

dependencies {
    // lifecycles
     implementation(libs.androidx.compose.material.icons.extended)
//    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}