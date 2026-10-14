plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.ttxz.base.common"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // 公共 API 暴露 StateFlow / SharedFlow，并继承 AndroidX ViewModel。
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation(libs.junit)
}
