// AGP 9 起内置 Kotlin 支持，**不要**再加 org.jetbrains.kotlin.android ——
// 加了会直接报「plugin is no longer required since AGP 9.0」编译失败。
plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.evelorion.contacts"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.evelorion.contacts"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 4
        versionName = "1.0.1-preview.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    // 两个 App（通讯录和电话）必须用同一把证书签名 —— 这是 signature 级权限的地基，
    // 也是「私密联系人只有自家 App 能读」这条的前提。
    // debug 也用它，这样开发期间证书指纹钉扎才生效。
    val keystore = rootProject.file("signing/shared.jks")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val signingKeyAlias = System.getenv("KEY_ALIAS")
    val signingKeyPassword = System.getenv("KEY_PASSWORD")
    val hasSharedSigning = keystore.exists() &&
        !keystorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()
    if (hasSharedSigning) {
        signingConfigs {
            create("shared") {
                storeFile = keystore
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            if (hasSharedSigning) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSharedSigning) signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.valueOf(libs.versions.javaVersion.get())
        targetCompatibility = JavaVersion.valueOf(libs.versions.javaVersion.get())
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        // argon2kt 和 sqlcipher 都带 .so
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }

    sourceSets.getByName("main") {
        kotlin.srcDirs("src/main/kotlin")
    }
}

/**
 * commons 的传递依赖里带着 AndroidX 之前的老 support 库（com.android.support:*），
 * 它和 androidx.core 有一批同名类（android.support.v4.os.ResultReceiver 等），
 * 会在 checkDebugDuplicateClasses 阶段直接失败。
 *
 * AndroidX 是 support 库的官方继任者，两者不该同时存在，
 * 所以全局排掉老的那套。
 */
configurations.configureEach {
    exclude(group = "com.android.support")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)

    // 数据层。**只用它读写系统通讯录和做 vCard**，
    // 它的 Activity、View、主题一概不碰 —— 那正是上一版 UI 做不出来的原因。
    implementation(libs.fossify.commons)

    // 加密同步
    implementation(libs.okhttp)
    implementation(libs.argon2kt)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)
    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
