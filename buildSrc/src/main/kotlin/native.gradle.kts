import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

plugins {
    id("org.graalvm.buildtools.native")
}

val os = DefaultNativePlatform.getCurrentOperatingSystem()
val arch = DefaultNativePlatform.getCurrentArchitecture()

graalvmNative {
    toolchainDetection.set(false)
    binaries {
        named("main") {
            mainClass.set("jtrrntzip.Program")
            imageName.set("jtrrntzip")
            if (arch.isAmd64) {
                buildArgs.add("-march=x86-64-v2")
            }
            buildArgs.add("-O3")
            buildArgs.add("--future-defaults=all")
            if (os.isLinux && arch.isAmd64) {
                buildArgs.add("--gc=G1")
            }
            buildArgs.add("-H:+AddAllCharsets")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            configurationFileDirectories.from(layout.projectDirectory.dir("gradle/native-image"))
            configurationFileDirectories.from(layout.buildDirectory.dir("native/agent-config"))
        }
        all {
            resources.autodetect()
        }
    }
    metadataRepository {
        enabled.set(true)
    }
    agent {
        defaultMode.set("standard")
        enabled.set(true)

        trackReflectionMetadata.set(true)

        metadataCopy {
            inputTaskNames.add("test")
            outputDirectories.add("build/native/agent-config")
            mergeWithExisting.set(true)
        }
    }
}
