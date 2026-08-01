import java.security.KeyStore
import java.security.MessageDigest

// AGP 9 起内置 Kotlin 支持，**不要**再加 org.jetbrains.kotlin.android ——
// 加了会直接报「plugin is no longer required since AGP 9.0」编译失败。
// Compose 编译器插件仍然要单独声明，它不属于 AGP 内置的那部分。
plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.evelorion.phone"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.evelorion.phone"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 15
        versionName = "1.0.1-preview.15"

        ksp { arg("room.schemaLocation", "$projectDir/schemas") }

        // 只打 arm64 的原生库。
        //
        // SQLCipher 的 .so 每种架构 4~6 MB，四种一起打进去就是 21 MB ——
        // 占了整个包的一半以上，而其中至少三份永远用不到：
        // 2019 年之后的安卓手机全是 arm64，x86/x86_64 只有模拟器在用。
        //
        // 包大小直接决定每次把包送到手机上要多久，这是这个项目里最实在的瓶颈。
        // 代价说清楚：这个包**装不进 x86 模拟器**，也不支持 32 位老机器。
        // 真要发布给不确定的设备，改成按 ABI 拆分（splits.abi）而不是删掉。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // 必须和通讯录用**同一把证书**。
    //
    // 这不是为了省事：
    //   · 两个 App 声明同名的自定义权限时，签名不一致会直接
    //     INSTALL_FAILED_DUPLICATE_PERMISSION，装都装不上
    //   · 「私密联系人只有自家 App 能读」靠的就是 signature 级权限 +
    //     调用方签名校验，证书不同就等于两个陌生 App
    //
    // debug 也用它，否则开发期间签名校验永远不通过。
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
            }
        }
    }

    buildTypes {
        debug {
            // 和通讯录的 debug 保持一致的后缀规则，两个 debug 包才能装在一起
            applicationIdSuffix = ".debug"

            // debug **不开** R8。
            //
            // 一度开过，理由是想裁掉 material-icons-extended 里几千个用不到的图标。
            // 实测下来 R8 只省了 30 KB —— 包大是因为 SQLCipher 的原生库占了
            // 四种 CPU 架构共 21 MB，真正把 41 MB 降到 9.5 MB 的是 abiFilters。
            //
            // 而代价很实在：构建机只有 961 MB 内存，R8 每次要跑 6 到 10 分钟，
            // 一轮改错就是十分钟。为了 30 KB 付这个代价不划算。
            // release 仍然开着（见下面），发布包该压还是要压。
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

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// 通讯录工程里踩过：某些传递依赖会把老的 support 库拖进来，和 AndroidX 撞类
configurations.configureEach {
    exclude(group = "com.android.support")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)

    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.sqlite)

    implementation(libs.okhttp)
    // 没有 argon2：那是从主口令派生密钥用的，而电话 App **不接触主口令**。
    // 依赖留着就意味着有人能顺手在这里加个登录框，边界就没了。
    // 也没有 sqlcipher：本地通话记录库是明文的（见 CallDatabase 里的说明），
    // 加密发生在上传之前。
}
