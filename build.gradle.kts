plugins {
    id("java-library")
    id("net.neoforged.moddev") version "2.0.140"
}

fun propOrNull(key: String) = project.findProperty(key) as String?
fun prop(key: String) = propOrNull(key) ?: error("Property `$key` not set.")

version = prop("mod_version")
group = prop("mod_group_id")

base {
    archivesName = prop("mod_id")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // Phase 0: Xaero devアーティファクト検証後に追加
    // maven("https://chocolateminecraft.com/maven") { name = "Xaero's Maven" }
}

neoForge {
    version = prop("neoforge_version")

    mods {
        create(prop("mod_id")) {
            sourceSet(sourceSets["main"])
        }
    }

    runs {
        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces", prop("mod_id"))
        }
        create("client") {
            client()
        }
        create("server") {
            server()
        }
    }
}

dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.7:processor")
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = mapOf(
        "minecraft_version" to prop("minecraft_version"),
        "neoforge_loader_version_range" to prop("neoforge_loader_version_range"),
        "mod_id" to prop("mod_id"),
        "mod_name" to prop("mod_name"),
        "mod_version" to prop("mod_version"),
        "mod_authors" to prop("mod_authors"),
        "mod_description" to prop("mod_description"),
        "mod_license" to prop("mod_license")
    )

    inputs.properties(replaceProperties)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(replaceProperties)
    }
}

tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn("prepareClientRun")
}
