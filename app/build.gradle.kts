import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Versión: en CI la fijan VERSION_CODE y VERSION_NAME (ver .github/workflows/android.yml).
// En local se usan los valores por defecto.
val appVersionCode = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1
val appVersionName = providers.environmentVariable("VERSION_NAME").orNull
    ?: "${providers.gradleProperty("moxlite.version").getOrElse("0.1")}-dev"

// Firma de release. Con las variables ANDROID_KEYSTORE_* (secretos de GitHub) se usa una clave fija,
// necesaria para que cada versión nueva se instale encima de la anterior. Sin ellas, se firma con la
// clave debug (vale para probar, pero cambia entre máquinas).
val releaseKeystoreBase64 = providers.environmentVariable("ANDROID_KEYSTORE_BASE64").orNull?.takeIf { it.isNotBlank() }
val releaseKeystoreFile = releaseKeystoreBase64?.let { encoded ->
    layout.buildDirectory.file("release.keystore").get().asFile.apply {
        parentFile.mkdirs()
        writeBytes(Base64.getMimeDecoder().decode(encoded))
    }
}

android {
    namespace = "es.moxlite"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.moxlite"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)
    implementation(libs.webkit)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
