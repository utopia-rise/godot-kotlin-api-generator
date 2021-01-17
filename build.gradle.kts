plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

buildscript {
    repositories {
        jcenter()
        gradlePluginPortal()
    }
}
repositories {
    jcenter()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("apiGeneratorPlugin") {
            id = "com.utopia-rise.api-generator"
            implementationClass = "godot.gradle.ApiGeneratorPlugin"
        }
    }
}

dependencies {
    implementation(kotlin("gradle-plugin", version = "1.4.20"))
    implementation("com.squareup:kotlinpoet:1.5.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.11.0")
}