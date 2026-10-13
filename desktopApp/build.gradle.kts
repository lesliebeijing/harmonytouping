import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// 发版 CI 用 -PpackageVersion=<去 v 的 tag> 注入；本地开发默认 1.0.0
val appVersion = (project.findProperty("packageVersion") as? String)?.trim().orEmpty().ifEmpty { "1.0.0" }

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    // HOScrcpy 官方 SDK（fat jar：grpc/netty/protobuf/guava/fastjson 均已内置）
    // 取自官方 v1.0.16-beta 发布包（2026-08），内含支持 HarmonyOS 6.6/7.0 设备的 scrcpy server
    implementation(files("libs/hosScrcpy-1.0.18-beta.jar"))

    // FFmpeg：解码设备端推来的 H.265 视频流（与官方包一致的版本组合）
    implementation("org.bytedeco:javacpp:1.5.9")
    implementation("org.bytedeco:ffmpeg:6.0-1.5.9")
    // 各平台原生库（按需增减）
    implementation("org.bytedeco:ffmpeg:6.0-1.5.9:macosx-arm64")
    implementation("org.bytedeco:ffmpeg:6.0-1.5.9:macosx-x86_64")
    implementation("org.bytedeco:ffmpeg:6.0-1.5.9:windows-x86_64")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.lesliefang.harmonytouping.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "harmonytouping"
            packageVersion = appVersion
        }
    }
}
