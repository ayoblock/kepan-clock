// 插件版本唯一声明处（子项目只写 id，不写版本，避免 Kotlin 插件多次加载警告）。
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.android.library") version "9.5.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("jacoco")
}
