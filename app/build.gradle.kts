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
        // targetSdk 32 de proposito: a AUSBC 3.2.7 registra o receptor USB
        // com a API antiga (sem flags de exportacao), o que o Android
        // bloqueia com SecurityException em apps com targetSdk 33+ - o app
        // fechava sozinho ao abrir. Nenhuma funcao do DX Studio depende
        // de comportamentos de targetSdk mais novo.
        targetSdk = 32
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
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7") {
        // Dependencias transitivas da libausbc que so existiam no jcenter
        // (desligado) e sao usadas apenas pelas telas de demonstracao da
        // biblioteca - o DX Studio usa so o motor de camera.
        exclude(group = "com.gyf.immersionbar", module = "immersionbar")
        exclude(group = "com.zlc.glide", module = "webpdecoder")
    }
    // A libausbc so expoe o libuvc (onde mora com.serenegiant.usb.USBMonitor,
    // usado nas assinaturas publicas dela) em runtime; declarar direto pra
    // ele entrar no classpath de compilacao.
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.7")
}
