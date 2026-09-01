import org.gradle.internal.os.OperatingSystem

plugins {
    java
    id("org.beryx.jlink")
}

tasks.register<Zip>("distZip2") {
    dependsOn(tasks.jar)
    from(configurations.runtimeClasspath) {
        into("lib")
    }
    from(tasks.jar)
    from("dist") {
        include("*.bat")
        include("*.sh")
        filePermissions {
            unix("0755")
        }
    }
    archiveFileName.set(base.archivesName.get() + "-" + project.version + ".zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

tasks.named("assemble") {
    dependsOn("distZip2")
}

jlink {
    options.set(listOf("--strip-debug", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "jtrrntzip"
    }
    jpackage {
        skipInstaller = true
        imageName = "Jtrrntzip"
        if (OperatingSystem.current().isWindows) {
            imageOptions.set(listOf("--win-console"))
        }
    }
}
