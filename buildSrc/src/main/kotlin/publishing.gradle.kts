import org.gradle.api.publish.maven.MavenPublication

plugins {
    java
    `maven-publish`
}

publishing {
    repositories {
        maven {
            name = "jtrrntzip"
            url = uri("https://maven.pkg.github.com/optyfr-org/Jtrrntzip")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("USERNAME")).getOrNull()
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("TOKEN")).getOrNull()
            }
        }
    }
    publications {
        create<MavenPublication>("gpr") {
            artifactId = "jtrrntzip"
            from(components["java"])
            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
            pom {
                name.set("JTrrntzip")
                description.set("Java version of trrntzip Based on C# code from TrrntzipDN by GordonJ")
                url.set("https://github.com/optyfr-org/Jtrrntzip")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://raw.githubusercontent.com/optyfr-org/Jtrrntzip/master/LICENSE")
                    }
                }
                developers {
                    developer {
                        id.set("optyfr")
                        name.set("optyfr")
                        email.set("17027109+optyfr@users.noreply.github.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/optyfr-org/Jtrrntzip.git")
                    developerConnection.set("scm:git:git@github.com:optyfr-org/Jtrrntzip.git")
                    url.set("https://github.com/optyfr-org/Jtrrntzip.git")
                }
            }
        }
    }
}
