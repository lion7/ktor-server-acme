import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `maven-publish`
    kotlin("jvm") version "1.8.10"
    id("org.jetbrains.dokka") version "1.7.20"
    id("org.ajoberstar.grgit") version "5.0.0"
}

group = "com.github.lion7"
version = grgit.describe()

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-host-common-jvm:2.2.3")
    implementation("io.ktor:ktor-server-jetty:2.2.3")
    implementation("io.ktor:ktor-server-core-jvm:2.2.3")
    implementation("io.ktor:ktor-server-auth-jwt:2.2.3")
    implementation("org.shredzone.acme4j:acme4j-client:2.15")
    implementation("org.shredzone.acme4j:acme4j-utils:2.15")

    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("org.testcontainers:testcontainers:1.17.6")
    testImplementation("org.testcontainers:junit-jupiter:1.17.6")
    testImplementation("ch.qos.logback:logback-classic:1.4.5")
}

java {
    withSourcesJar()
}

tasks {
    wrapper {
        gradleVersion = "7.6"
        distributionType = DistributionType.ALL
    }

    withType<JavaCompile> {
        options.compilerArgs = listOf("-parameters", "-Werror")
        options.encoding = "UTF-8"

        sourceCompatibility = "11"
        targetCompatibility = "11"
    }

    withType<KotlinCompile> {
        kotlinOptions {
            freeCompilerArgs = listOf("-java-parameters", "-Xjsr305=strict", "-Werror")
            jvmTarget = "11"
        }
    }

    test {
        useJUnitPlatform()
        jvmArgs("-Djava.security.egd=file:/dev/urandom")

        testLogging {
            events(*TestLogEvent.values())
        }
    }

    register<Jar>("javadocJar") {
        dependsOn(dokkaJavadoc)
        archiveClassifier.set("javadoc")
        from(dokkaJavadoc.get().outputDirectory)
    }
}

publishing {
    publications {
        create<MavenPublication>("default") {
            from(components["java"])
            artifact(tasks["javadocJar"])
        }
    }
}
