# Changelog

All notable changes to JTrntZip will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

> **Requires:** GraalVM 25 · Gradle 9.7.1 (Kotlin DSL)

### Added
- **Concurrent archive processing** using Java 21 virtual threads — process multiple zip files in parallel with `--threads N` CLI flag. File discovery is separated from processing so a single file stays sequential while batches run on a virtual-thread executor. Per-task logs are buffered and flushed under a shared lock to prevent interleaved console output.
- **JCommander-based CLI argument parsing** with structured options, help text from Messages, and proper error handling for unrecognized flags. Native-image reachability metadata and an automatic module for the non-modular jar are included.
- **Extended test suite** — 4 new test classes (`CliOptionsTest`, `HelpPrinterTest`, `TorrentZipProcessTest`, `ConcurrentProcessingTest`) covering CLI option parsing, help output, torrentzip rule reporting under check-only mode, and 131 concurrent-processing tests (virtual-thread parallelism, thread-safe logging, engine isolation, resource leak detection, deterministic byte-identical output).

### Changed
- **Modernized Java codebase** to records, sealed types, and `HexFormat` — `ZippedFile` is now an immutable record, `ICompress` and `TorrentZipOptions` are sealed, and `commons-codec` is replaced with JDK `HexFormat`. Switch expressions and `Path.of` keep torrentzip bytes identical.
- **Extracted zip internals** into focused modules: `ZipEocdProcessor` (end-of-central-directory IO), `ZipExtraFieldProcessor` (extra-field parsing), `ZipNameCodec` (CP437/UTF-8 name encoding), and `ZipOpenFlow` (zip open helpers). `TorrentZipCheck` now walks duplicate and directory-marker lists backwards so removals need no index rewind.
- **Replaced `BigInteger` sizes** with `long` for Zip64; read/write streams return records instead of mutable holders. CLI flags parsed into a `CliOptions` record; `HelpPrinter` extracted.
- **Migrated Gradle build** to Kotlin DSL with `buildSrc` convention plugins. Enabled dependency locking and PGP verification. Centralized library and plugin versions in `libs.versions.toml`.
- **Modularized CI/CD** into reusable workflows: `ci.yml` delegates to `build.yml`, `test.yml`, `sonar.yml`; `release.yml` delegates to `release-library.yml`, `release-jpackage.yml`, `release-native.yml`.
- **JPMS module renamed** from `trrntzip` to `jtrrntzip`.
- Reformatted Java sources to space indent and same-line braces. Tests gain `@DisplayName` annotations and Javadoc across the engine, zip support, and module descriptor.

### Fixed
- Corrected ASCII sort order for zip entries (uses torrentzip ASCII lower-case comparator instead of unicode ignore-case).
- Fixed duplicate entry handling — identical duplicates are kept; conflicting ones are marked corrupt.
- Fixed zip64 entry count calculations — now emits zip64 when there are more than 65535 entries.

### Removed
- Dropped redundant `GITHUB_TOKEN` secret wiring in reusable workflow calls (automatically inherited).
- Removed `zipFileRollBack` and `deepScan` methods from `ICompress`.

---

## [1.4.0] — 2026-08-30

> **Requires:** Java 21 · Gradle 9.7.1 · GraalVM 25 (native image only)

### Added
- **GraalVM native-image support** — produces standalone native executables via `release-native.yml`. GraalVM native plus jlink replaces the old modularity plugin. Configuration cache is enabled. GraalVM 25 is used only for building native images; the library itself targets Java 21.
- **GitHub Actions CI pipeline** with Gradle tests, JaCoCo coverage, and SonarQube analysis. Replaces the standalone SonarCloud workflow with a multi-job CI that builds, tests, and analyzes on JDK 25. JUnit 5 and JaCoCo are enabled, with torrentzip fixtures and format specs.
- **Automated release publishing** — tagged builds are published to GitHub Packages via `release-library.yml` and `release-jpackage.yml`. Project version is driven from `RELEASE_VERSION` environment variable. jlink/jpackage images are built, and Graal x86-64-v2/G1 flags are limited to amd64 hosts.
- **Test suite** — 6 test classes (`ProgramTest`, `TorrentZipCheckTest`, `TorrentZipFileTest`, `TorrentZipRebuildTest`, `ZipFileTest`, `TestZipFixtures`) with 13 torrentzip fixture archives and 5 sample zips. Tests cover CLI invocation, check-only mode, rebuild cleanup, torrentzip rule validation, zip64 boundary (65535/65536 entries), and archive reopen validity. JaCoCo produces XML and HTML coverage reports.
- **Improvement roadmap** and Gradle split plan documentation.

### Changed
- **Refactored rezip flow** — entry-copy helpers extracted from `reZipFiles`; `ZipFile` usage in rebuild and tests converted to try-with-resources for automatic resource cleanup.
- **Extracted zip open flow** into helper methods. Replaced `$NON-NLS` comments with `NOSONAR`. Converted `UnsignedTypes` to a class.
- **Retargeted Maven publishing** URLs to `optyfr-org` GitHub organization after repository move.
- **Switched JDK 25 distribution** from Temurin to GraalVM in CI.
- **Modularized Gradle scripts** — split the monolithic `build.gradle` into focused scripts for native, publishing, quality, eclipse, and distribution. Centralized library and plugin versions in `libs.versions.toml` and pinned plugin versions.

### Fixed
- Corrected ASCII sort order for zip entries (uses torrentzip ASCII lower-case comparator instead of unicode ignore-case).
- Fixed duplicate entry detection — identical duplicates are kept; conflicting ones are marked corrupt.
- Fixed zip64 entry count calculations — now emits zip64 when there are more than 65535 entries.
- Program now exits with codes 0/1/2 and keeps walking after a failed file.
- Native-image agent output moves under `build/`.

### Removed
- Eclipse IDE project files (`.project`, `.classpath`, `.settings/`). Now ignored along with `.vscode` so IDE metadata stays local.

---

## [1.3.1] — 2023-02-03

> **Requires:** Java 17 · Gradle 7.2

### Fixed
- Fixed Messages package name for proper resource loading.
- Fixed build classpath configuration.

### Changed
- Migrated from Java 8 → Java 11 (with JPMS modularity support) → **Java 17** with `module-info.java`.
- Upgraded Gradle through 7.0 compatibility to **Gradle 7.2**.
- Changed package naming to lowercase convention (`JTrrntzip` → `jtrrntzip`).
- Improved code quality and fixed SonarQube findings.
- Added Maven publishing configuration to GitHub Packages.
- Updated group name to `com.github.optyfr` and version metadata.

---

## [1.2b7] — 2019-05-20

> **Requires:** Java 8 · Gradle 5.3.1

### Fixed
- Fixed UTF-8 encoding for filenames — now encodes to UTF-8 as soon as content is not US-ASCII (instead of falling back to CP437).
- Fixed zip64 reading issues ([JRomManager#14](https://github.com/optyfr/JRomManager/issues/14)).
- Fixed SpotBugs warnings.
- Fixed various bugs in zip processing.

### Changed
- Upgraded Gradle from 4.3 → 4.8.1 → 5.3.1 (with 7.0 compatibility fixes along the way).
- Relaxed dependency version constraints (no longer pinned to exact versions, using `1.+`/`2.+`/`3.+` ranges).
- Cleaned up unused imports.

---

## [1.1b5] — 2018-06-03

> **Requires:** Java 8 · Gradle 4.3

### Changed
- **Replaced delete/move with copy/delete** for final rezipped file — safer file handling.
- **Optimized inflater/deflater reuse** — single inflater instance shared across all entries in a zip file instead of allocating per entry.
- **Migrated build system to Gradle 4.3** from Eclipse-only project setup.
- Translated internal messages and documentation.

### Fixed
- Fixed inflater deallocation issue.
- Fixed bug with files in STORE mode.
- Fixed resource leak when checking archives.
- Fixed missing resource folders in JAR packaging.
- Fixed compiler options.

---

## [1.1b2] — 2018-05-04

> **Requires:** Java 8 · Eclipse (no build tool)

### Added
- Initial public release of JTrntZip — a Java TorrentZip implementation.
- Build TorrentZip from standard zip files.
- Enhanced status/return code reporting.
- Public log and status interfaces for library consumers.
- Hex string formatting for checksums.
- Unsigned type handling for zip headers.

---

[Unreleased]: https://github.com/optyfr/Jtrrntzip/compare/1.4.0...HEAD
[1.4.0]: https://github.com/optyfr/Jtrrntzip/compare/1.3.1...1.4.0
[1.3.1]: https://github.com/optyfr/Jtrrntzip/compare/1.2b7...1.3.1
[1.2b7]: https://github.com/optyfr/Jtrrntzip/compare/1.1b5...1.2b7
[1.1b5]: https://github.com/optyfr/Jtrrntzip/compare/1.1b2...1.1b5
[1.1b2]: https://github.com/optyfr/Jtrrntzip/releases/tag/1.1b2
