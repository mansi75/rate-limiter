plugins {
    `java-library`
    `maven-publish`
}

// Matches the Java package, and the coordinates the README tells people to depend on.
// Publishing this groupId to Maven Central requires proving ownership of ratelimit.com;
// if that is not on the cards, switch both this and the README to io.github.<user>.
group = "com.ratelimit"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withSourcesJar()
    withJavadocJar()
}

// No `implementation` and no `api` entries, on purpose: nothing enters a consumer's classpath but
// this library. Everything below is test-only. See CONTRIBUTING.md.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:all,-missing", "-quiet")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "rate-limiter"
                description = "Six rate limiting algorithms behind one interface, " +
                    "with no runtime dependencies and no background threads."
                url = "https://github.com/mansi/rate-limiter"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                scm {
                    url = "https://github.com/mansi/rate-limiter"
                    connection = "scm:git:https://github.com/mansi/rate-limiter.git"
                }
            }
        }
    }
}
