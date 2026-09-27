plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mob8n"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mob8n"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        vectorDrawables.useSupportLibrary = true
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        // ponytail: no R8 until the Anthropic SDK (Jackson reflection) has verified keep rules; upgrade = enable minify + test proguard-rules.pro
        release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        // DESIGN6 D17 / §7.1: release-like for frame measurement and daily use; debug keystore so `adb install -r` replaces the debug
        // build and keeps data. Not debuggable -> `run-as com.mob8n` fails; switch back to debug for DB/prefs inspection.
        create("profile") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/versions/9/module-info.class", "module-info.class",
            )
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.anthropic.java)                 // Claude; transitive OkHttp + Jackson accepted (DESIGN D7/D8)
    implementation(libs.mlkit.genai.prompt)             // Gemini Nano via AICore (beta)
    coreLibraryDesugaring(libs.desugar.jdk.libs)        // java.time/Optional/streams inside anthropic-java + Jackson on API 26-33
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
