import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.authentication.http.HttpHeaderAuthentication
import java.util.Base64

plugins {
    `java-library`
    `maven-publish`
    signing
}

// Deliberately not `com.ratelimit`, which is the Java package: Maven Central grants a groupId
// only on proof you control the matching domain, and io.github.<user> is the route that needs
// no domain purchase. Coordinates and package need not match, and here they do not.
group = "io.github.mansi75"
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

// Declared ahead of every use. Top-level `val`s in a Kotlin build script initialise
// in source order, so reading one from a block above its declaration yields null —
// which silently produced empty credentials and a 401 from Sonatype.
val centralUsername: String? =
    providers.gradleProperty("centralUsername").orNull ?: System.getenv("CENTRAL_USERNAME")
val centralPassword: String? =
    providers.gradleProperty("centralPassword").orNull ?: System.getenv("CENTRAL_PASSWORD")

val signingKey: String? =
    providers.gradleProperty("signingKey").orNull ?: System.getenv("SIGNING_KEY")
val signingPassword: String? =
    providers.gradleProperty("signingPassword").orNull ?: System.getenv("SIGNING_PASSWORD")

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // Maven Central rejects a POM missing any of name, description, url,
            // licences, developers or scm. All six are required, not merely good form.
            pom {
                name = "rate-limiter"
                description = "Six rate limiting algorithms behind one interface, " +
                    "with no runtime dependencies and no background threads."
                url = "https://github.com/mansi75/rate-limiter"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        id = "mansi75"
                        name = "Mansi Maurya"
                        url = "https://github.com/mansi75"
                    }
                }
                scm {
                    url = "https://github.com/mansi75/rate-limiter"
                    connection = "scm:git:https://github.com/mansi75/rate-limiter.git"
                    developerConnection = "scm:git:ssh://git@github.com/mansi75/rate-limiter.git"
                }
            }
        }
    }

    repositories {
        maven {
            name = "central"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")

            // Sonatype's OSSRH Staging API expects `Authorization: Bearer <base64 of
            // user:pass>`. Gradle's `credentials { username; password }` sends Basic
            // auth instead, which this endpoint answers with 401 — so the header is
            // built by hand. Base64 here is an encoding, not a secret: the token is
            // what must stay out of the repository.
            credentials(HttpHeaderCredentials::class) {
                name = "Authorization"
                val token = Base64.getEncoder()
                    .encodeToString("${centralUsername.orEmpty()}:${centralPassword.orEmpty()}"
                        .toByteArray(Charsets.UTF_8))
                value = "Bearer $token"
            }
            authentication {
                create<HttpHeaderAuthentication>("header")
            }
        }
    }
}

signing {
    // Signing is required to publish to Central and pointless everywhere else. Wiring
    // it only when a key is present keeps `build` and `publishToMavenLocal` working on
    // a machine with no GPG setup, which is every contributor's machine.
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        require(centralUsername != null && centralPassword != null) {
            "Publishing to Central needs credentials. Set CENTRAL_USERNAME and " +
                "CENTRAL_PASSWORD, or centralUsername/centralPassword in " +
                "~/.gradle/gradle.properties. Use publishToMavenLocal for local testing."
        }
        require(signingKey != null) {
            "Publishing to Central needs a GPG signature. Set SIGNING_KEY to an " +
                "ASCII-armoured private key and SIGNING_PASSWORD to its passphrase."
        }
    }
}
