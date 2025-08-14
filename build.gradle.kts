import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("java")
    // This plugin helps package your dependencies into the final JAR.
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

// Project information
group = "com.gogless"
version = "1.0.0"

repositories {
    mavenCentral()
    // PaperMC's repository for the server API
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // The Paper API for version 1.21. We use 'compileOnly' because the server provides this at runtime.
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
}

tasks {
    // Configure the Java compilation to use Java 21, which is recommended for modern Minecraft.
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    // This task processes the plugin.yml file, allowing you to use variables like ${project.version}
    processResources {
        filesMatching("plugin.yml") {
            expand(project.properties)
        }
    }

    // Configure the shadowJar task to set the output JAR name.
    withType<ShadowJar> {
        archiveBaseName.set("PersonalLoot")
        archiveClassifier.set("") // This removes the '-all' suffix from the JAR name
        archiveVersion.set(project.version.toString())
    }

    // Make the 'build' task depend on 'shadowJar' so it always creates the final plugin file.
    build {
        dependsOn(shadowJar)
    }
}
