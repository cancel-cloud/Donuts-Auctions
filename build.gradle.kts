import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("fabric-loom") version "1.15.3"
    kotlin("jvm") version "2.3.10"
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/releases/")
    maven("https://maven.shedaniel.me/")
    mavenCentral()
}

val sqliteJdbcVersion = project.property("sqlite_jdbc_version") as String
val sqliteJdbcDependency = "org.xerial:sqlite-jdbc:$sqliteJdbcVersion"

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")

    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("fabric_kotlin_version")}")
    modImplementation("com.terraformersmc:modmenu:${project.property("modmenu_version")}")
    modApi("me.shedaniel.cloth:cloth-config-fabric:${project.property("cloth_config_version")}") {
        exclude(group = "net.fabricmc.fabric-api")
    }

    implementation(sqliteJdbcDependency)
    include(sqliteJdbcDependency)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.processResources {
    val properties = mapOf(
        "version" to project.version,
        "minecraft_version" to project.property("minecraft_version"),
        "loader_version" to project.property("loader_version"),
        "fabric_kotlin_version" to project.property("fabric_kotlin_version"),
        "modmenu_version" to project.property("modmenu_version")
    )

    inputs.properties(properties)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(properties)
    }
}

tasks.test {
    useJUnitPlatform()
}

val slimBundledSqliteJar by tasks.registering(Zip::class) {
    group = "build"
    description = "Strips rarely used sqlite-jdbc native targets from the nested jar."

    dependsOn(tasks.named("processIncludeJars"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    val bundledSqliteJar = layout.buildDirectory.file("processIncludeJars/sqlite-jdbc-$sqliteJdbcVersion.jar")
    val keptNativeTargets = listOf(
        "org/sqlite/native/Windows/x86_64/**",
        "org/sqlite/native/Mac/x86_64/**",
        "org/sqlite/native/Mac/aarch64/**",
        "org/sqlite/native/Linux/x86_64/**",
        "org/sqlite/native/Linux/aarch64/**"
    )

    from(bundledSqliteJar.map { zipTree(it) }) {
        exclude("org/sqlite/native/**")
    }
    from(bundledSqliteJar.map { zipTree(it) }) {
        include(keptNativeTargets)
    }

    destinationDirectory.set(layout.buildDirectory.dir("tmp"))
    archiveFileName.set("sqlite-jdbc-$sqliteJdbcVersion-slimmed.jar")

    doLast {
        val source = archiveFile.get().asFile
        val target = bundledSqliteJar.get().asFile
        source.copyTo(target, overwrite = true)
    }
}

tasks.named("jar") {
    dependsOn(slimBundledSqliteJar)
}

val prismLauncherModsDir = file("/Users/cancelcloud/Library/Application Support/PrismLauncher/instances/FO-1-21-10/minecraft/mods/")

tasks.register<Copy>("runlocal") {
    group = "deployment"
    description = "Builds the remapped jar and copies it to the local PrismLauncher mods folder."

    dependsOn(tasks.named("remapJar"))
    from(tasks.named<Jar>("remapJar").flatMap { it.archiveFile })
    into(prismLauncherModsDir)

    doFirst {
        prismLauncherModsDir.mkdirs()
    }
}
