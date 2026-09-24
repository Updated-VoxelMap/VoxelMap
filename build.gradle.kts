plugins {
    id("java")
    id("net.fabricmc.fabric-loom") version ("1.18-SNAPSHOT") apply (false)
    id("net.minecraftforge.gradle") version ("7.0.40") apply (false)
    id("net.neoforged.moddev") version ("2.0.147") apply (false)
}

val minecraftVersion by extra { "26.3" }
val forgeVersion by extra { "66.0.3" }
val neoForgeVersion by extra { "26.3.0.16-beta" }
val fabricVersion by extra { "0.19.5" }
val fabricApiVersion by extra { "0.161.0+26.3" }
val modMenuVersion by extra { "21.0.0" }
val paperApiVersion by extra { "[26.3.build,)" }
val voxelConfigVersion by extra { "1.0.2" }
val geckolibVersion by extra { "5.5.7" }
val voxelMapVersion by extra { "1.16.13" }

val fullVersion by extra { "${minecraftVersion}-${voxelMapVersion}" }

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    enabled = false
}

subprojects {
    apply(plugin = "maven-publish")

    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            name = "papermc"
            url = uri("https://repo.papermc.io/repository/maven-public/")
        }
        maven {
            name = "Geckolib Maven"
            url = uri("https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/")
        }
        maven {
            name = "Brokkonaut"
            url = uri("https://www.iani.de/nexus/content/groups/public/")
        }
        maven { url = uri("https://api.modrinth.com/maven") }
    }

    java.toolchain.languageVersion = JavaLanguageVersion.of(25)

    tasks.processResources {
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(mapOf("version" to fullVersion))
        }
    }

    version = fullVersion
    group = "com.mamiyaotaru"

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    tasks.withType<GenerateModuleMetadata>().configureEach {
        enabled = false
    }
}
