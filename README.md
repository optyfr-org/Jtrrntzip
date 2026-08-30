[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=optyfr-org_Jtrrntzip&metric=reliability_rating)](https://sonarcloud.io/dashboard?id=optyfr-org_Jtrrntzip)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=optyfr-org_Jtrrntzip&metric=security_rating)](https://sonarcloud.io/dashboard?id=optyfr-org_Jtrrntzip)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=optyfr-org_Jtrrntzip&metric=sqale_rating)](https://sonarcloud.io/dashboard?id=optyfr-org_Jtrrntzip)

![GitHub top language](https://img.shields.io/github/languages/top/optyfr/Jtrrntzip)
![GitHub](https://img.shields.io/github/license/optyfr/Jtrrntzip)

# Jtrrntzip

Java version of [trrntzip](https://en.wikipedia.org/wiki/Zip_\(file_format\)#Torrent_compression), a tool that
converts zip files to the standardized *torrentzip* format.

Based on C# code from [TrrntzipDN](https://github.com/arogl/trrntzipDN) by GordonJ.

Only zip files are supported, there is no 7z read/write support.

## The torrentzip format

Torrentzip is a convention on top of the normal zip format so that identical
inputs always produce byte-identical zip files, which makes them compatible
with bittorrent-based archiving tools such as RomVault:

* entry names use `/` separators and are sorted with an ASCII lower-case compare,
* file data is deflate compressed at maximum level,
* directory marker entries are only kept for empty directories,
* the zip file comment `TORRENTZIPPED-XXXXXXXX` carries a CRC of the central directory.

The full specification lives in the
[torrentzip documentation](https://wiki.romvault.com/doku.php?id=torrentzip) and in the `specs/` folder
of this repository.

## Usage

```
Usage: trrntzip [OPTIONS] [PATH/ZIP FILE]
Options:
 -? : show this help
 -s : prevent sub-directory recursion
 -f : force re-zip
 -c : check files only do not repair
 -l : verbose logging
 -v : show version
 -g : pause when finished
```

Paths can be directories, single zip files, or
[glob patterns](https://docs.oracle.com/en/java/javase/25/docs/api/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String))
such as `roms/*.zip`. Zips found inside directories are processed
recursively unless `-s` is given.

### Exit codes

| Code | Meaning                                                            |
|------|--------------------------------------------------------------------|
| 0    | all processed files are valid or were repaired successfully       |
| 1    | one or more files were corrupt or failed to process               |
| 2    | usage error, for example when no path argument is given            |

## Download

Pre-built binaries are attached to the
[GitHub releases](https://github.com/optyfr/Jtrrntzip/releases). The release
archive contains a runnable `Jtrrntzip.jar` plus `Jtrrntzip.bat` / `Jtrrntzip.sh`
launcher scripts, and requires a Java 25 runtime.

## Build

The project builds with the bundled Gradle wrapper and a Java 25 toolchain:

```
./gradlew build              # compile, run tests and assemble the jar
./gradlew jacocoTestReport   # write test coverage reports into build/reports/jacoco
./gradlew distZip2           # build the release zip into build/distributions
./gradlew nativeCompile      # optional GraalVM native image, requires GraalVM
```

## License

[MIT](LICENSE), Copyright (C) 2018-2026 opty
