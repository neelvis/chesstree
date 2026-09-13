plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.chesstree.server.MainKt")
}

dependencies {
    implementation(projects.gameDomain)
    implementation(projects.onlineContract)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.argon2.jvm)
    implementation(libs.postgresql)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.websockets)
    testRuntimeOnly(libs.h2)
}

tasks.test {
    useJUnitPlatform()
}
