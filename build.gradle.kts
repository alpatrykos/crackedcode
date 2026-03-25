plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "agent"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

tasks.register("printVersion") {
    group = "help"
    description = "Prints the current project version."
    doLast {
        println(project.version)
    }
}

tasks.register("installAgent") {
    group = "distribution"
    description = "Builds the installable agent distribution."
    dependsOn(":agent-cli:installDist")
}

tasks.register("agentDistZip") {
    group = "distribution"
    description = "Builds the agent zip distribution."
    dependsOn(":agent-cli:distZip")
}

tasks.register("agentDistTar") {
    group = "distribution"
    description = "Builds the agent tar distribution."
    dependsOn(":agent-cli:distTar")
}

tasks.register<Exec>("verifyAgentInstallDist") {
    group = "verification"
    description = "Builds the agent distribution and verifies the generated launcher can run a non-interactive command."
    dependsOn(":agent-cli:installDist")
    doFirst {
        val launcher = rootDir.resolve("agent-cli/build/install/agent/bin/agent")
        commandLine(launcher.absolutePath, "tools")
    }
}
