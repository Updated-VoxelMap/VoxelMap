plugins {
    id("java")
    id("idea")
    id("net.fabricmc.fabric-loom")
    id("com.gradleup.shadow")
}

val minecraftVersion: String by rootProject.extra
val fabricVersion: String by rootProject.extra
val fabricApiVersion: String by rootProject.extra
val modMenuVersion: String by rootProject.extra
val voxelConfigVersion: String by rootProject.extra

val fullVersion: String by rootProject.extra

base {
    archivesName.set("voxelmap-fabric")
}

val shade: Configuration by configurations.creating

configurations.named("localRuntime") {
    extendsFrom(shade)
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraftVersion}")

    implementation("net.fabricmc:fabric-loader:${fabricVersion}")
    implementation("net.fabricmc.fabric-api:fabric-api:${fabricApiVersion}")
    compileOnly("maven.modrinth:modmenu:${modMenuVersion}")

    shade("de.voxelmap:voxelconfig:${voxelConfigVersion}")

    implementation(project.project(":server-common").sourceSets.getByName("main").output)
    implementation(project.project(":common").sourceSets.getByName("main").output)
}

tasks.named("compileTestJava").configure {
    enabled = false
}

tasks.named("test").configure {
    enabled = false
}

tasks.named("validateAccessWidener") {
    mustRunAfter(project(":common").tasks.named("genSourcesWithVineflower"))
}

loom {
    if (project(":common").file("src/main/resources/voxelmap.accesswidener").exists())
        accessWidenerPath.set(project(":common").file("src/main/resources/voxelmap.accesswidener"))

    runs {
        named("client") {
            client()
            configName = "Fabric Client"
            ideConfigGenerated(true)
            runDir("run")
        }
    }
}

tasks {
    processResources {
        from(project.project(":common").sourceSets.main.get().resources)
        inputs.property("version", fullVersion)

        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to fullVersion))
        }
    }

    jar {
        archiveClassifier.set("slim")
    }

    shadowJar {
        archiveClassifier.set("")
        destinationDirectory = rootDir.resolve("build").resolve("libs")
        configurations.set(listOf(shade))
        relocate("de.voxelmap.voxelconfig", "com.mamiyaotaru.voxelmap.shadow.voxelconfig")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(project.project(":common").sourceSets.main.get().output.classesDirs)
        from(project.project(":server-common").sourceSets.main.get().output)
        from(rootDir.resolve("LICENSE.md"))
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    }

    assemble {
        dependsOn(shadowJar)
    }
}

publishing {
    publications {
        register("mavenJava", MavenPublication::class) {
            artifactId = base.archivesName.get()
            artifact(tasks.shadowJar)
        }
    }

    repositories {
        maven("file://${System.getenv("local_maven")}")
    }
}
