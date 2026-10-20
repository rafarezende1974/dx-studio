plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.rafarezende.dxstudio"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.rafarezende.dxstudio"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    // AUSBC - motor UVC multi-camera (baseado em libuvc).
    // As tags 3.3.x e os snapshots do master estao quebrados no JitPack
    // (artefatos dos submodulos nao foram publicados); a 3.2.7 e a versao
    // mais nova completa la. O encoder de video e nosso (UvcEncoder.kt),
    // porque o captureVideoStart da 3.2.7 exige WRITE_EXTERNAL_STORAGE,
    // permissao impossivel de conceder no Android 13+.
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7")
}
