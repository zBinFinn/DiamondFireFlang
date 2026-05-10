plugins {
    id("flang.intellij-plugin-conventions")
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":lsp"))

    intellijPlatform {
        create("IU", "2026.1")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.zbinfinn.diamondfire-flang"
        name = "DiamondFire Flang"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "261"
        }
    }
}

tasks.named("buildSearchableOptions") {
    enabled = false
}
