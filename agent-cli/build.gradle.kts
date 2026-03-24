plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "com.crackedcode.agent.cli.MainKt"
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
