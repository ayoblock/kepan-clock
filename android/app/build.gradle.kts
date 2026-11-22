import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("jacoco")
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

/** 版本号唯一来源：defaultConfig、产物命名、发版文档共用。 */
val appVersionName = "1.6.8"
val appVersionCode = 20

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "cn.workbuddy.kepanclock"
    compileSdk = 36

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "cn.workbuddy.kepanclock"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            if (!abi.isNullOrEmpty()) {
                output.outputFileName.set("kepan-clock-$abi-release-$appVersionName.apk")
            }
        }
    }
}

dependencies {
    implementation(project(":domain-core"))
    implementation(project(":render-core"))
    implementation(project(":wallpaper"))

    // Compose 依赖仅存在于 :app（§9-3 硬约束）
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/** domain 层单测由 :domain-core 工程承担，此处聚合执行与报告。 */
tasks.register<JacocoReport>("jacocoDomainReport") {
    dependsOn(":domain-core:test")
    reports {
        xml.required = true
        html.required = true
    }
    val domainProject = project(":domain-core")
    classDirectories.setFrom(
        files(domainProject.layout.buildDirectory.dir("classes/kotlin/main")),
    )
    sourceDirectories.setFrom(files(domainProject.layout.projectDirectory.dir("src/main/kotlin")))
    executionData.setFrom(
        fileTree(domainProject.layout.buildDirectory) { include("jacoco/test.exec") },
    )
}

/** 覆盖门禁：domain 层行覆盖 ≥ 80%，低于门槛直接失败。 */
tasks.register("coverageGate") {
    dependsOn("jacocoDomainReport")
    // jacocoDomainReport 定义在 :app，报告输出落在 :app 的报告目录下（实证）。
    val report = layout.buildDirectory
        .file("reports/jacoco/jacocoDomainReport/jacocoDomainReport.xml")
    inputs.file(report)
    doLast {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val xml = factory.newDocumentBuilder().parse(report.get().asFile)
        val packages = xml.getElementsByTagName("package")
        var covered = 0
        var missed = 0
        for (i in 0 until packages.length) {
            val pkg = packages.item(i) as org.w3c.dom.Element
            val children = pkg.childNodes
            for (j in 0 until children.length) {
                val c = children.item(j) as? org.w3c.dom.Element ?: continue
                if (c.tagName != "counter" || c.getAttribute("type") != "LINE") continue
                covered += c.getAttribute("covered").toInt()
                missed += c.getAttribute("missed").toInt()
            }
        }
        val total = covered + missed
        val pct = if (total == 0) 0.0 else covered * 100.0 / total
        logger.lifecycle("domain 行覆盖 $covered/$total = " + "%.1f".format(pct) + "%（门槛 80%）")
        if (total == 0) throw GradleException("domain 覆盖报告为空，检查 JaCoCo class/exec 路径")
        if (pct < 80.0) throw GradleException("domain 层覆盖 $pct% 低于 80% 门槛")
    }
}

/** 包体预算：每个 release APK ≤ 12 MB。 */
tasks.register("verifyReleaseBudget") {
    dependsOn("assembleRelease")
    doLast {
        val dir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apks = dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        if (apks.isEmpty()) throw GradleException("未找到 release APK")
        val budget = 12L * 1024 * 1024
        apks.forEach { apk ->
            logger.lifecycle("包体 ${apk.name} = ${apk.length() / (1024.0 * 1024.0)} MB")
            if (apk.length() > budget) throw GradleException("${apk.name} 超过 12MB 预算")
        }
    }
}

/** 发版交付：release APK 汇入 android/dist/ 并清旧版，只留本次版本。 */
tasks.register("stageRelease") {
    dependsOn("assembleRelease", "verifyReleaseBudget")
    doLast {
        val src = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val dist = rootProject.layout.projectDirectory.dir("dist").asFile
        dist.mkdirs()
        dist.listFiles { f -> f.isFile && f.extension == "apk" }?.forEach { it.delete() }
        val apks = src.listFiles { f -> f.isFile && f.extension == "apk" }.orEmpty()
        apks.forEach { apk ->
            val target = File(dist, apk.name)
            apk.copyTo(target, overwrite = true)
            logger.lifecycle("已交付 ${target.absolutePath}")
        }
        if (apks.isEmpty()) throw GradleException("未找到可交付的 release APK")
    }
}
