plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "com.crackedcode"
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

tasks.register("installCcode") {
    group = "distribution"
    description = "Builds the installable ccode distribution."
    dependsOn(":agent-cli:installDist")
}

tasks.register("ccodeDistZip") {
    group = "distribution"
    description = "Builds the ccode zip distribution."
    dependsOn(":agent-cli:distZip")
}

tasks.register("ccodeDistTar") {
    group = "distribution"
    description = "Builds the ccode tar distribution."
    dependsOn(":agent-cli:distTar")
}

tasks.register<Exec>("verifyCcodeInstallDist") {
    group = "verification"
    description = "Builds the ccode distribution and verifies the generated launcher can run a non-interactive command."
    dependsOn(":agent-cli:installDist")
    doFirst {
        val launcher = rootDir.resolve("agent-cli/build/install/ccode/bin/ccode")
        commandLine(launcher.absolutePath, "tools")
    }
}
