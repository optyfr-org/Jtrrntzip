plugins {
    java
    application
    id("org.gradlex.extra-java-module-info")
    id("quality")
    id("publishing-config")
    id("native")
    id("eclipse-config")
    id("distribution-config")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withJavadocJar()
    withSourcesJar()
}

version = providers.gradleProperty("releaseVersion")
    .orElse(providers.environmentVariable("RELEASE_VERSION"))
    .getOrElse("2.0.0")
group = "com.github.optyfr"

base {
    archivesName.set("Jtrrntzip")
}

val versionStr = version.toString()
val manifestAttributes = mapOf(
    "Manifest-Version" to "1.0",
    "Specification-Title" to "Jtrrntzip",
    "Specification-Version" to versionStr.substring(0, versionStr.lastIndexOf('.')),
    "Implementation-Title" to "JTrrntzip",
    "Implementation-Version" to versionStr.substring(versionStr.lastIndexOf('.') + 1),
    "Main-Class" to "jtrrntzip.Program",
)

repositories {
    mavenCentral()
}

application {
    mainClass.set("jtrrntzip.Program")
}

extraJavaModuleInfo {
    failOnMissingModuleInfo.set(false)
    automaticModule("jcommander-3.0.jar", "jcommander")
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(libs.commons.io)
    implementation(libs.jcommander)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    manifest {
        attributes(manifestAttributes)
    }
    archiveFileName.set(base.archivesName.get() + ".jar")
}

tasks.named<Jar>("sourcesJar") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
