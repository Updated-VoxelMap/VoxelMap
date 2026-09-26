plugins {
    id("idea")
    id("net.neoforged.moddev")
    id("com.gradleup.shadow")
    id("java-library")
}

val minecraftVersion: String by rootProject.extra
val neoForgeVersion: String by rootProject.extra
val voxelConfigVersion: String by rootProject.extra

val fullVersion: String by rootProject.extra

base {
    archivesName = "voxelmap-neoforge"
}

sourceSets {

}

repositories {
    mavenLocal()
    maven("https://maven.su5ed.dev/releases")
    maven("https://maven.neoforged.net/releases/")
}

val serviceJar: Jar by tasks.creating(Jar::class) {
    from(rootDir.resolve("LICENSE.md"))
    manifest.attributes["FMLModType"] = "LIBRARY"
    archiveClassifier = "service"
}

configurations {
    create("serviceConfig") {
        isCanBeConsumed = true
        isCanBeResolved = false
        outgoing {
            artifact(serviceJar)
        }
    }
}

val shade: Configuration by configurations.creating

configurations.named("compileOnly") {
    extendsFrom(shade)
}

// In dev, VoxelConfig must be loaded as part of the voxelmap mod: on the plain
// classpath it ends up in the app classloader and cannot see Minecraft classes.
// Mods can only be grouped from source sets, so it is unpacked into one.
val extractVoxelConfig by tasks.registering(Sync::class) {
    from({ shade.map { zipTree(it) } })
    into(layout.buildDirectory.dir("voxelconfig"))
    exclude("META-INF/**")
}

val voxelConfig: SourceSet by sourceSets.creating {
    (output.classesDirs as ConfigurableFileCollection).from(extractVoxelConfig)
}

tasks.named(voxelConfig.classesTaskName) {
    dependsOn(extractVoxelConfig)
}

dependencies {
    jarJar(project(":neoforge", "serviceConfig"))

    shade("de.voxelmap:voxelconfig:${voxelConfigVersion}")
}

tasks.jar {
    archiveClassifier.set("slim")

    val commonMain = project.project(":common").sourceSets.getByName("main")
    val serverCommonMain = project.project(":server-common").sourceSets.getByName("main")
    from(commonMain.output.classesDirs) {
        exclude("/voxelmap.refmap.json")
    }
    from(commonMain.output.resourcesDir)
    from(serverCommonMain.output.classesDirs)
    from(serverCommonMain.output.resourcesDir)

    from(rootDir.resolve("LICENSE.md"))

    filesMatching("neoforge.mods.toml") {
        expand(mapOf("version" to fullVersion))
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    destinationDirectory = rootDir.resolve("build").resolve("libs")
    configurations.set(listOf(shade))
    relocate("de.voxelmap.voxelconfig", "com.mamiyaotaru.voxelmap.shadow.voxelconfig")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(tasks.jar.map { zipTree(it.archiveFile) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

neoForge {
    // Specify the version of NeoForge to use.
    version = neoForgeVersion

    runs {
        create("client") {
            client()
        }
        create("server") {
            server()
        }
    }

    mods {
        create("voxelmap") {
            sourceSet(sourceSets.main.get())
            sourceSet(project.project(":common").sourceSets.main.get())
            sourceSet(project.project(":server-common").sourceSets.main.get())
            sourceSet(voxelConfig)
        }
    }
}

tasks.named("compileTestJava").configure {
    enabled = false
}

dependencies {
    compileOnly(project.project(":common").sourceSets.main.get().output)
    compileOnly(project.project(":server-common").sourceSets.main.get().output)
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)
