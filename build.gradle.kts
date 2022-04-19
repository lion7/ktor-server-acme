import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `maven-publish`
    kotlin("jvm") version "1.6.20"
    id("org.jetbrains.dokka") version "1.6.20"
    id("org.ajoberstar.grgit") version "5.0.0"
}

group = "com.github.lion7"
version = grgit.describe()

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:2.0.0")
    implementation("org.shredzone.acme4j:acme4j-client:2.12")

    testImplementation("org.junit.jupiter:junit-jupiter:5.8.2")
//    testImplementation("ch.qos.logback:logback-classic:1.2.10")
}

java {
    withSourcesJar()
}

tasks {
    wrapper {
        gradleVersion = "7.4.2"
        distributionType = DistributionType.ALL
    }

    withType<JavaCompile> {
        options.compilerArgs = listOf("-parameters", "-Werror")
        options.encoding = "UTF-8"

        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    withType<KotlinCompile> {
        kotlinOptions {
            freeCompilerArgs = listOf("-java-parameters", "-Xjsr305=strict", "-Werror")
            jvmTarget = "1.8"
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
