plugins {
    id("com.android.library")
}

android {
    namespace = "cn.workbuddy.kepanclock.wallpaper"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 硬约束（§9-3）：本模块纯原生 Canvas，禁止引入 Compose 与 Activity 依赖。
dependencies {
    implementation(project(":domain-core"))
    implementation(project(":render-core"))
}
