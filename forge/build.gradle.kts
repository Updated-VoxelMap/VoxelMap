plugins {
    id("idea")
    id("net.minecraftforge.gradle")
    id("java-library")
}

val minecraftVersion: String by rootProject.extra
val forgeVersion: String by rootProject.extra
val voxelConfigVersion: String by rootProject.extra
val geckolibVersion: String by rootProject.extra

val fullVersion: String by rootProject.extra

base {
    archivesName.set("voxelmap-forge")
}

sourceSets {
    all {
        val buildDir = layout.buildDirectory.dir("sourcesSets/${this.name}")
        output.setResourcesDir(buildDir)
        java.destinationDirectory.set(buildDir)
    }
}

repositories {
    minecraft.mavenizer(this)
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)

    maven { url = uri("https://maven.minecraftforge.net/") }
}

val shade: Configuration by configurations.creating

configurations.named("implementation") {
    extendsFrom(shade)
}

dependencies {
    implementation(minecraft.dependency("net.minecraftforge:forge:${minecraftVersion}-${forgeVersion}"))
    compileOnly(project.project(":common").sourceSets.main.get().output)
    compileOnly(project.project(":server-common").sourceSets.main.get().output)

    shade("de.voxelmap:voxelconfig:${voxelConfigVersion}")
    compileOnly("com.geckolib:geckolib-common-${minecraftVersion}:${geckolibVersion}")
}

minecraft {
    accessTransformers = files("src/main/resources/META-INF/accesstransformer.cfg")

    runs {
        register("client") {
            workingDir.set(file("run"))
            args("--mixin.config=mixin.voxelmap.json", "--mixin.config=mixin.voxelmap.forge.json")

            mods {
                create("voxelmap") {
                    source(sourceSets.main.get())
                    source(project.project(":common").sourceSets.main.get())
                    source(project.project(":server-common").sourceSets.main.get())
                }
            }
        }

        register("server") {
            workingDir.set(file("run-server"))
            args("--mixin.config=mixin.voxelmap.json", "--mixin.config=mixin.voxelmap.forge.json")

            mods {
                create("voxelmap") {
                    source(sourceSets.main.get())
                    source(project.project(":common").sourceSets.main.get())
                    source(project.project(":server-common").sourceSets.main.get())
                }
            }
        }
    }
}

tasks {
    named<JavaCompile>("compileJava") {
        val commonMain = project(":common").sourceSets.main.get()
        val serverCommonMain = project(":server-common").sourceSets.main.get()
        source(commonMain.java.srcDirs)
        source(serverCommonMain.java.srcDirs)
    }

    processResources {
        val commonMain = project(":common").sourceSets.main.get()
        val serverCommonMain = project(":server-common").sourceSets.main.get()
        from(commonMain.resources.srcDirs) {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
        from(serverCommonMain.resources.srcDirs) {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }

        inputs.property("version", fullVersion)
        filesMatching("META-INF/mods.toml") {
            expand(mapOf("version" to fullVersion))
        }
    }

    jar {
        manifest {
            attributes["MixinConfigs"] = "mixin.voxelmap.json,mixin.voxelmap.forge.json"
        }

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(shade.map { if (it.isDirectory) it else zipTree(it) }) {
            exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
        }

        from(rootDir.resolve("LICENSE.md"))
    }

    jar.get().destinationDirectory = rootDir.resolve("build").resolve("libs")

    compileTestJava {
        enabled = false
    }

    test {
        enabled = false
    }
}
