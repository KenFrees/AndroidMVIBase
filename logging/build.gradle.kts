plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.ttxz.base.logging"
    compileSdk { version = release(37) }
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 强杀验收由 host 分两次运行；不能在普通 connected 测试中自动执行准备阶段。
        testInstrumentationRunnerArguments["notClass"] = "com.ttxz.base.logging.CrashRecoveryDeviceTest"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
