plugins {
    id("com.android.library")
}

android {
    namespace = "cn.workbuddy.kepanclock.render"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":domain-core"))
    // settings 包（KepanConfig/ConfigRepository）随 P4-R2 迁入本模块，
    // 供 :wallpaper 共享同一 DataStore 实例（同进程单例）
    api("androidx.datastore:datastore-preferences:1.1.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    // Gradle 9 不再自动注入 launcher，须显式提供
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
