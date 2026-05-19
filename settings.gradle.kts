pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":chunksync-exploit")
project(":chunksync-exploit").projectDir = file("exploits/chunksync")
