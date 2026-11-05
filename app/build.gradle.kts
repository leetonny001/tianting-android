import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 服务端地址、演示登录都在构建时决定。这里没有真实凭证，也不要放。
val devServer = providers.gradleProperty("tianting.devServer").getOrElse("http://10.0.2.2:18280") // 模拟器访问本机
val releaseServer = providers.gradleProperty("tianting.serverUrl").getOrElse("https://im.tianting.invalid")
val releaseDemoLogin = providers.gradleProperty("tianting.demoLogin").getOrElse("false").toBoolean()
val buildId = providers.gradleProperty("tianting.buildId").orElse(
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD"); isIgnoreExitValue = true }.standardOutput.asText.map { it.trim().ifEmpty { "dev" } },
).get()

android {
    namespace = "com.tianting.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tianting.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BUILD_ID", "\"$buildId\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "SERVER_URL", "\"$devServer\"")
            buildConfigField("boolean", "DEMO_LOGIN", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "SERVER_URL", "\"$releaseServer\"")
            buildConfigField("boolean", "DEMO_LOGIN", "$releaseDemoLogin")
        }
        // 和 release 同样的 R8、同样不可调试，只是用调试签名、允许连本机测试服务端：跑 release Logcat 检查（AC-UI-90-12）用。
        create("logcheck") {
            initWith(getByName("release"))
            applicationIdSuffix = ".logcheck"
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "SERVER_URL", "\"$devServer\"")
            buildConfigField("boolean", "DEMO_LOGIN", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { resources.excludes += setOf("META-INF/**", "win32-x86*/**") }

    testOptions { unitTests.isIncludeAndroidResources = true }

    lint {
        abortOnError = true
        checkDependencies = false
    }
}

kotlin { jvmToolchain(21) }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation("com.tianting:sdk-android")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.serialization.json)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlin.test)
    debugImplementation(libs.compose.ui.test.manifest)
}

// ---- 设计 token：design/tokens.json（tianting-web 的副本，scripts/sync-tokens.sh 同步）生成 Tokens.gen.kt ----

fun renderTokens(json: File): String {
    @Suppress("UNCHECKED_CAST")
    val t = JsonSlurper().parse(json) as Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    val colors = t["color"] as Map<String, Map<String, String>>
    fun ident(k: String) = k.split('.').mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar(Char::uppercase) }.joinToString("")
    fun color(hex: String): String {
        val h = hex.removePrefix("#")
        val argb = if (h.length == 8) h.substring(6) + h.substring(0, 6) else "FF$h"
        return "Color(0x$argb)"
    }
    fun px(v: Any) = v.toString().removeSuffix("px")
    val sb = StringBuilder()
    sb.appendLine("// 生成文件，不要手改：./gradlew :app:generateTokens（来源 design/tokens.json）。")
    sb.appendLine("package com.tianting.app.ui.theme\n")
    sb.appendLine("import androidx.compose.runtime.Immutable")
    sb.appendLine("import androidx.compose.ui.graphics.Color\n")
    sb.appendLine("@Immutable\ninternal data class TtColors(")
    for (k in colors.keys) sb.appendLine("    val ${ident(k)}: Color,")
    sb.appendLine(")\n")
    for ((mode, name) in listOf("light" to "LightTokens", "dark" to "DarkTokens")) {
        sb.appendLine("internal val $name = TtColors(")
        for ((k, v) in colors) sb.appendLine("    ${ident(k)} = ${color(v.getValue(mode))},")
        sb.appendLine(")\n")
    }
    @Suppress("UNCHECKED_CAST")
    val avatar = t["avatar"] as List<String>
    sb.appendLine("internal val AvatarPalette = listOf(${avatar.joinToString(", ") { color(it) }})\n")
    sb.appendLine("/** PRD-ui 6.3 节的值：字号是 sp，其余是 dp（3.5 节 1:1 换算）。 */")
    sb.appendLine("internal object TtDimen {")
    @Suppress("UNCHECKED_CAST")
    for ((group, prefix) in listOf("font" to "font", "space" to "space", "radius" to "radius", "size" to "size")) {
        for ((k, v) in t[group] as Map<String, Any>) {
            if (!v.toString().endsWith("px")) continue
            val name = prefix + k.split('.').joinToString("") { it.replaceFirstChar(Char::uppercase) }
            sb.appendLine("    const val $name = ${px(v)}")
        }
    }
    sb.appendLine("}")
    return sb.toString()
}

val tokensJson = rootProject.file("design/tokens.json")
val tokensKt = file("src/main/kotlin/com/tianting/app/ui/theme/Tokens.gen.kt")

tasks.register("generateTokens") {
    group = "tianting"
    description = "由 design/tokens.json 生成 Tokens.gen.kt"
    inputs.file(tokensJson)
    outputs.file(tokensKt)
    doLast { tokensKt.writeText(renderTokens(tokensJson)) }
}

// ---- 代码规则（PRD-ui 3.6 节）：跑在 check 和 lint 之前，CI 不需要设备 ----

tasks.register("checkTianting") {
    group = "verification"
    description = "主题文件外无颜色字面量、Tokens.gen.kt 与 tokens.json 一致、只有偏好文件能用 SharedPreferences、不直接写 Log、无禁用品牌词"
    inputs.files(fileTree("src"), tokensJson)
    outputs.upToDateWhen { false }
    doLast {
        val problems = checkSources(file("src/main/kotlin"), tokensKt, renderTokens(tokensJson))
        check(problems.isEmpty()) { "代码规则检查失败：\n" + problems.joinToString("\n") }
    }
}

fun checkSources(root: File, tokens: File, expected: String): List<String> {
    val out = ArrayList<String>()
    if (!tokens.isFile || tokens.readText() != expected) out += "Tokens.gen.kt 与 design/tokens.json 不一致：运行 ./gradlew :app:generateTokens"
    val colorLiteral = Regex("""Color\(\s*(0x|red\s*=|[0-9])|Color\.(Red|Blue|Green|Black|White|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray)\b|parseColor\(|colorResource\(""")
    val brand = Regex("微信|wechat|weixin", RegexOption.IGNORE_CASE)
    root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
        val rel = f.relativeTo(root).path
        val inTheme = rel.startsWith("com/tianting/app/ui/theme/")
        f.readLines().forEachIndexed { i, line ->
            val at = "$rel:${i + 1}"
            if (!inTheme && colorLiteral.containsMatchIn(line)) out += "$at: 主题文件外的颜色字面量（AC-UI-22-3）：${line.trim()}"
            if (Regex("""\bLog\.[vdiwe]\(|println\(|printStackTrace\(""").containsMatchIn(line) && !rel.endsWith("core/AppLog.kt")) out += "$at: 只能用 AppLog 写日志（AC-UI-90-12）：${line.trim()}"
            if ("getSharedPreferences(" in line && !rel.endsWith("core/UiPrefs.kt")) out += "$at: SharedPreferences 只能在 UiPrefs 里用（AC-UI-90-2）"
            if (brand.containsMatchIn(line)) out += "$at: 禁用的品牌词（AC-UI-90-15）"
            if ("http://" in line && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")) out += "$at: 代码里不写 http 地址（NF-UI-15）"
        }
    }
    return out
}

tasks.named("preBuild") { dependsOn("checkTianting") }
