pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tianting-android"

include(":app")

// SDK 没有发布到 Maven：用 composite build 按源码依赖。默认找同级目录，CI 用 TIANTING_SDK_DIR 指定检出位置。
val sdkDir = file(System.getenv("TIANTING_SDK_DIR") ?: "../tianting-sdk-kotlin")
check(sdkDir.resolve("settings.gradle.kts").isFile) {
    "找不到 tianting-sdk-kotlin（试过 $sdkDir），请把它检出到同级目录，或设置 TIANTING_SDK_DIR"
}
// SDK 的 settings 只在有 Android SDK 时才包含 :sdk-android，判断依据是环境变量或它自己的 local.properties。
check(System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || sdkDir.resolve("local.properties").isFile) {
    "构建需要环境变量 ANDROID_HOME（tianting-sdk-kotlin 据此决定是否包含 :sdk-android）"
}
includeBuild(sdkDir) {
    dependencySubstitution {
        substitute(module("com.tianting:sdk-android")).using(project(":sdk-android"))
        substitute(module("com.tianting:sdk-core")).using(project(":sdk-core"))
    }
}
