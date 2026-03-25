import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "agent.cli.MainKt"
}

distributions {
    main {
        distributionBaseName = "agent"
    }
}

tasks.named<CreateStartScripts>("startScripts") {
    applicationName = "agent"
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("agent-version.txt") {
        expand("version" to project.version.toString())
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation(project(":agent-core"))
    implementation(project(":agent-provider-openai"))
    implementation(project(":agent-tools-local"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
