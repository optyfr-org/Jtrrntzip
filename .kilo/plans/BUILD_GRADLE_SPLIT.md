# Split build.gradle into gradle scripts + version catalog

Scope: Groovy `apply from:` split plus a version catalog with pinned versions. Do not implement W1/W2 from `.kilo/IMPROVEMENT_PLAN.md`. Do not convert to Kotlin DSL or add buildSrc.

## Decisions

- Scripts under `gradle/` plus `gradle/libs.versions.toml`.
- Pin exact library and plugin versions. Resolve libraries at implementation with `./gradlew.bat dependencies --configuration runtimeClasspath` and `./gradlew.bat buildEnvironment`. Do not keep `1.+`, `2.+`, `3.+`, `5.10.+`, or `latest.release`.
- Sonar plugin: pin **7.3.0.8198**, replace `sonarqube { }` with `sonar { }`. Plugin id stays `org.sonarqube`. CI already runs `./gradlew sonar`.
- Drop unused `commons-lang3`.
- Keep `dist/ver.properties` as version source. Use `project.version` (drop `def theVersion`).
- Apply `id 'application'` so the existing `application { mainClass }` block and Graal `metadataCopy.inputTaskNames.add("run")` have a real `run` task.
- Leave `.kilo/IMPROVEMENT_PLAN.md` unchanged.

## Target layout

```
build.gradle                 # plugins, java/toolchain, version/manifest, repos, deps, jar, apply from
settings.gradle              # rootProject.name only
gradle.properties            # unchanged
gradle/libs.versions.toml
gradle/quality.gradle        # sonar {}, jacoco {}, test {}, jacocoTestReport {}
gradle/publishing.gradle     # publishing {}
gradle/native.gradle         # graalvmNative {}
gradle/eclipse.gradle        # eclipse {} + isConGradle()
gradle/distribution.gradle   # distZip2 + assemble.dependsOn
```

## Ordered tasks

1. Resolve library versions; write `gradle/libs.versions.toml` (sketch below). Use a JUnit BOM so jupiter and platform-launcher cannot drift.
2. Rewrite root `plugins {}`: built-ins + `application` + `alias(libs.plugins.sonarqube|jlink|graalvm)`. Merge the two `java {}` blocks. Keep version load from `dist/ver.properties`, `group`, `base.archivesName`, `jar` manifest, `sourcesJar` duplicatesStrategy, `repositories`, `dependencies` via `libs.*`.
3. Extract scripts using the move map. Apply them last with `apply from: "$rootDir/gradle/<name>.gradle"`.
4. In `gradle/quality.gradle`, use `sonar { properties { ... } }` with the same property keys as today. Jacoco `toolVersion` from catalog (`libs.versions.jacoco.get()`). If type-safe `libs` fails inside an `apply from:` script, fall back to `project.extensions.getByType(VersionCatalogsExtension).named("libs")`.
5. In `gradle/native.gradle`, move `DefaultNativePlatform` import and `graalvmNative {}`. Drop unused `arch`. Keep `os.isLinux()` G1 GC arg.
6. In `gradle/distribution.gradle`, `archiveFileName = base.archivesName.get() + '-' + project.version + '.zip'`.
7. Strip `pluginManagement { plugins { id "org.sonarqube" version "3.2.0" } }` from `settings.gradle`. Keep `rootProject.name = 'Jtrrntzip'`.
8. Keep applying `org.beryx.jlink` with no `jlink {}` block (none exists today).

## Catalog sketch

```toml
[versions]
commons-codec = "REPLACE"      # from runtimeClasspath resolution
commons-io = "REPLACE"
junit = "REPLACE"              # junit-jupiter version; use as BOM
jacoco = "0.8.14"
sonarqube = "7.3.0.8198"
jlink = "REPLACE"              # from buildEnvironment
graalvm-native = "REPLACE"

[libraries]
commons-codec = { module = "commons-codec:commons-codec", version.ref = "commons-codec" }
commons-io = { module = "commons-io:commons-io", version.ref = "commons-io" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }

[plugins]
sonarqube = { id = "org.sonarqube", version.ref = "sonarqube" }
jlink = { id = "org.beryx.jlink", version.ref = "jlink" }
graalvm = { id = "org.graalvm.buildtools.native", version.ref = "graalvm-native" }
```

Root dependencies:

```
testImplementation platform(libs.junit.bom)
testImplementation libs.junit.jupiter
testRuntimeOnly libs.junit.platform.launcher
```

Groovy catalog accessors: hyphens become dots (`libs.commons.codec`, `libs.junit.bom`).

## Constraints

- `plugins {}` only in root `build.gradle`. Applied scripts cannot declare plugins.
- `def` locals in root are invisible to applied scripts. Use `project.version`, `base.archivesName`, `layout.buildDirectory`.
- Configuration cache is on. Do not add `afterEvaluate`. Move the existing `TestListener` as-is.
- Do not invent a `jlink {}` block.

## Out of scope

Dependabot, wrapper-validation CI, Graal agent metadata path, README, Kotlin DSL, buildSrc, publishing coordinates, toolchain (Java 25).

## Risks

- Sonar 7.3.0.8198 has reports of `sonar.java.binaries` failures; CI already calls `sonar`. If analysis fails after the bump, try 7.3.1.8318 (bugfix) without other DSL changes.
- `apply from:` + type-safe `libs` is supported on the same Project; use `VersionCatalogsExtension` if accessors are missing in a script.

## Validation

1. `./gradlew.bat tasks` lists `sonar` (not only `sonarqube`), `run`, `distZip2`, `jacocoTestReport`.
2. `./gradlew.bat build` green; `build/distributions/Jtrrntzip-<version>.zip` exists.
3. `./gradlew.bat jar` manifest: Main-Class `jtrrntzip.Program`; spec/impl versions from `dist/ver.properties`.
4. Second `./gradlew.bat build` reuses configuration cache (warnings OK, same as today).
5. `./gradlew.bat dependencies --configuration runtimeClasspath` shows pinned versions, no `commons-lang3`.
6. `./gradlew.bat sonar` configures (needs `SONAR_TOKEN` for a full upload; at least the task must configure without a missing-extension error).
