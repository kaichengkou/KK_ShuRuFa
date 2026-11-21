plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ABIs to build native code for. Default: the phone (arm64) only; pass -PbuildAbi=all for every ABI.
val abis = (findProperty("buildAbi") as String?)?.let {
    if (it == "all") listOf("arm64-v8a", "armeabi-v7a", "x86_64") else it.split(",")
} ?: listOf("arm64-v8a")

android {
    namespace = "com.kk.shurufa"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.kk.shurufa"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "0.9.7"
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // A fixed debug key checked into the repo keeps CI builds installable over each other.
        getByName("debug") {
            storeFile = rootProject.file("keys/debug.p12")
            storePassword = "kkshurufa"
            keyAlias = "kk"
            keyPassword = "kkshurufa"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/rimeAssets"))
    packaging {
        jniLibs { useLegacyPackaging = false }
        // GraalVM native-image metadata from the Claude SDK: unused on Android
        resources { excludes += listOf("META-INF/native-image/**") }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // dictionaries are copied out of the APK once, so they can stay compressed (about 3:1)
    androidResources { noCompress += listOf("gram") }
}

// Copy the Rime schema and dictionaries (rime-ice) into the APK assets.
val prepareRimeData by tasks.registering(Sync::class) {
    from(rootProject.file("rime-data/rime-ice")) {
        include("*.yaml", "custom_phrase.txt", "cn_dicts/**", "en_dicts/**", "opencc/**", "lua/**", "rime.lua")
        exclude("cn_dicts/41448.dict.yaml")
        exclude("*.custom.yaml", "squirrel.yaml", "weasel.yaml")
        into("rime")
    }
    from(rootProject.file("rime-data/overlay")) { into("rime") }
    from(rootProject.file("rime-data/packs")) { into("rime") }
    from(file("src/main/jni/librime/deps/opencc/data/dictionary")) {
        include("STCharacters.txt", "STPhrases.txt", "TSCharacters.txt", "TSPhrases.txt")
        into("rime/opencc")
    }
    into(layout.buildDirectory.dir("generated/rimeAssets"))
}
// Word-association index (联想) and the pinyin tone table (拼音模式), generated from the data sources.
val extraAssets = layout.buildDirectory.dir("generated/extraAssets")
val prepareExtraData by tasks.registering(Exec::class) {
    val out = extraAssets.get().asFile
    inputs.dir(rootProject.file("rime-data/rime-ice/cn_dicts"))
    inputs.file(rootProject.file("rime-data/pinyin-data/pinyin.txt"))
    inputs.files(rootProject.file("scripts/gen_predict.py"), rootProject.file("scripts/gen_tones.py"))
    outputs.dir(out)
    commandLine("sh", "-c",
        "mkdir -p '$out/data' && " +
        "python3 '${rootProject.file("scripts/gen_predict.py")}' '${rootProject.file("rime-data/rime-ice")}' '$out/data/predict_zh.txt' && " +
        "python3 '${rootProject.file("scripts/gen_tones.py")}' '${rootProject.file("rime-data/pinyin-data/pinyin.txt")}' '$out/data/pinyin_tones.txt'")
}
android.sourceSets["main"].assets.srcDir(extraAssets)
tasks.named("preBuild") { dependsOn(prepareRimeData, prepareExtraData) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.snakeyaml:snakeyaml-engine:2.9")
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    // 翻译: on-device ML Kit models (downloaded once per language)
    implementation("com.google.mlkit:translate:17.0.3")
    // 语音: SenseVoice and Silero VAD run on ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // AI 改写: the official Claude SDK, with the user's own API key
    implementation("com.anthropic:anthropic-java:3.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
    // desktop ONNX Runtime, so the speech pipeline can be checked end to end on the build machine
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
