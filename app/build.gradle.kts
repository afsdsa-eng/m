plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CIのSecretsに署名鍵があるときだけ release 署名を有効にする(任意)
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.example.mouseremap"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.mouseremap"
        minSdk = 28
        targetSdk = 34
        // CIでは -PversionCode=<run_number> を渡す(更新時にShizuku側サービスを作り直させるため)
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "2.0"
    }

    buildFeatures { aidl = true }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = keystorePath.let { file(it) }
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // UserServiceのクラス名を難読化しない
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

// ネイティブのデーモン(assets/mouseremapd/<abi>/mouseremapd)が無いままビルドするのを防ぐ
val checkNativeBinaries = tasks.register("checkNativeBinaries") {
    doLast {
        if (!file("src/main/assets/mouseremapd").exists()) {
            throw GradleException("先に scripts/build-native.sh を実行してください(NDKでネイティブをビルドします)")
        }
    }
}
tasks.configureEach {
    if (name == "preBuild") dependsOn(checkNativeBinaries)
}
