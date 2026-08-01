import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.sms"
    compileSdk = 36

    defaultConfig {
        // 注意：不能用 com.example.* —— ColorOS / OriginOS / MIUI 会把它判定为
        // 「测试应用」直接拦截安装，提示「应用未安装」。
        applicationId = "com.evelorion.messages"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.1-preview.3"
        vectorDrawables { useSupportLibrary = true }
    }

    // 三个套件 App 必须使用同一份正式证书。证书不同不仅不能访问
    // signature 权限，声明同名权限时安装也会直接失败。
    val keystore = rootProject.file("signing/shared.jks")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val signingKeyAlias = System.getenv("KEY_ALIAS")
    val signingKeyPassword = System.getenv("KEY_PASSWORD")
    val hasSharedSigning = keystore.exists() &&
        !keystorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()
    val officialCertSha256 = "127d2f23c90868b267016c66f01a4b5550e2a04c4a2cb25c6000467cf6611b4b"
    val releaseRequested = gradle.startParameter.taskNames.any {
        it.contains("release", ignoreCase = true)
    }
    if (releaseRequested && !hasSharedSigning) {
        throw GradleException(
            "Release builds require signing/shared.jks and KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD. " +
                "Unsigned or temporary-signed release APKs are forbidden."
        )
    }
    if (hasSharedSigning) {
        val keyStore = KeyStore.getInstance("PKCS12")
        keystore.inputStream().use { input ->
            keyStore.load(input, keystorePassword!!.toCharArray())
        }
        val certificate = keyStore.getCertificate(signingKeyAlias)
            ?: throw GradleException("The configured KEY_ALIAS does not exist in signing/shared.jks.")
        val actualCertSha256 = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        if (actualCertSha256 != officialCertSha256) {
            throw GradleException(
                "Refusing to build with a non-official certificate (SHA-256 $actualCertSha256)."
            )
        }
        signingConfigs {
            create("shared") {
                storeFile = keystore
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            if (hasSharedSigning) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSharedSigning) signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.accompanist.permissions)
    implementation(libs.material.kolor)
}
