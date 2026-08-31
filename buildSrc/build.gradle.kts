plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    implementation("org.sonarqube:org.sonarqube.gradle.plugin:${libs.versions.sonarqube.get()}")
    implementation("org.beryx.jlink:org.beryx.jlink.gradle.plugin:${libs.versions.jlink.get()}")
    implementation("org.graalvm.buildtools:native-gradle-plugin:${libs.versions.graalvm.native.get()}")
    implementation("org.gradlex.extra-java-module-info:org.gradlex.extra-java-module-info.gradle.plugin:${libs.versions.extra.java.module.info.get()}")
}
