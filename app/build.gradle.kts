plugins {
    id("com.android.application")
}

android {
    namespace = "com.mclan.server"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mclan.server"
        minSdk = 26
        // 28 是刻意固定的：Android 10+ 只对 targetSdk<=28 的应用允许执行私有目录中的可执行文件，
        // 而 Minecraft 服务端必须靠 JDK 才能启动（Termux 出于同样原因也用 28）。
        targetSdk = 28
        versionCode = 22
        versionName = "2.2.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore.jks")
            storePassword = "mclan123"
            keyAlias = "mclan"
            keyPassword = "mclan123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        // 本应用走侧载分发，且必须固定 targetSdk 28 才能执行 JDK
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 纯 Java 的 xz 解压，用来在手机上解开 Termux 的 .deb（约 100 KB）
    implementation("org.tukaani:xz:1.10")
}

// 本机验证：用真实 deb 跑解包算法（只编译进 debug 变体）
//   MCLAN_TEST_DEB=<deb 路径> ./gradlew :app:verifyDeb
tasks.register<JavaExec>("verifyDeb") {
    val deb = System.getenv("MCLAN_TEST_DEB").orEmpty()
    onlyIf { deb.isNotBlank() }
    dependsOn("compileDebugKotlin")
    mainClass.set("com.mclan.server.tools.DebTestMain")
    classpath = files(
        layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
        layout.buildDirectory.dir("intermediates/runtime_library_classes_jar/debug").map { it.asFile },
        configurations.getByName("debugRuntimeClasspath")
    )
    args(deb, System.getProperty("java.io.tmpdir") + "/mc-deb-verify")
}
