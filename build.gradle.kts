import com.xuncorp.spw.workshop.gradle.PluginPermission

plugins {
    kotlin("jvm") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "io.github.localplaylist"
version = "1.0.0"
kotlin { jvmToolchain(25) }

tasks.processResources {
    from("LICENSE") { into("META-INF") }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    // 本插件采用 Swing，不需要解析和分发整套 Compose UI 依赖。
    compileOnly("com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev22") { isTransitive = false }
    compileOnly("org.pf4j:pf4j:3.12.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

spmod {
    PluginClass = "io.github.localplaylist.TxtPlaylistPlugin"
    PluginId = "io.github.localplaylist.txtimport"
    PluginName = "按文件导入歌单"
    PluginDescription = "按 TXT 路径清单的行顺序新建或追加本地歌单"
    PluginVersion = "1.0.0"
    PluginProvider = "Local Playlist"
    PluginHasConfig = true
    PluginPermissions = listOf(PluginPermission.LIBRARY_READ, PluginPermission.CLASS_TRANSFORM)
}

tasks.register<JavaExec>("hostIntegration") {
    dependsOn(tasks.testClasses)
    val hostHome = providers.gradleProperty("spwHome")
    val sandbox = layout.buildDirectory.dir("host-test/${System.currentTimeMillis()}")
    mainClass.set("io.github.localplaylist.HostIntegrationKt")
    doFirst {
        require(hostHome.isPresent) { "请用 -PspwHome=<SPW 安装目录> 指定本机 SPW 1.19.0" }
        val host = file(hostHome.get())
        classpath = files(host.resolve("app/ffmpeg-x64.dll")) + sourceSets.test.get().runtimeClasspath
        val root = sandbox.get().asFile.apply { mkdirs() }
        args(root.absolutePath)
        environment("APPDATA", root.resolve("profile/AppData").apply { mkdirs() }.absolutePath)
        environment("LOCALAPPDATA", root.resolve("profile/LocalAppData").apply { mkdirs() }.absolutePath)
        systemProperty("user.home", root.resolve("profile").absolutePath)
        systemProperty("java.io.tmpdir", root.resolve("tmp").apply { mkdirs() }.absolutePath)
        systemProperty("jpackage.app-version", "1.19.0")
        systemProperty("java.util.prefs.PreferencesFactory", "io.github.localplaylist.MemoryPreferencesFactory")
        systemProperty("compose.application.resources.dir", host.resolve("app/resources").absolutePath)
        systemProperty("skiko.library.path", host.resolve("app").absolutePath)
        systemProperty("test.bass.path", host.resolve("app/resources/bass.dll").absolutePath)
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED")
    }
}

tasks.register<JavaExec>("uiVerification") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.localplaylist.UiVerification")
    args(layout.buildDirectory.dir("ui-verification").get().asFile.absolutePath)
}

tasks.register<JavaExec>("previewTxt") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.localplaylist.PreviewLoved")
    doFirst { args(providers.gradleProperty("txtFile").get()) }
}
