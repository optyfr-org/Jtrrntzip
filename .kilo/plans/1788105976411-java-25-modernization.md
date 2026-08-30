# Java 25 language modernization

## Goal

Apply applicable Java 17–25 language/API features. Gradle already targets 25. Torrentzip **bytes stay identical**. Public API **may break**.

## Decisions

- `ZippedFile` → immutable record; `TorrentZipCheck` still mutates the **same** `List` instance (tests assert on it). Replace elements with `withName`; keep `sort` / `remove`.
- `ZippedFile.index` is the original zip-entry index for stream open. Sort/remove must not rewrite it.
- `sealed ICompress permits ZipFile`; `sealed TorrentZipOptions permits SimpleTorrentZipOptions`.
- `Program` no longer implements `TorrentZipOptions`; it passes `new SimpleTorrentZipOptions(options.forceReZip(), options.checkOnly())`.
- `LogCallback` / `StatusCallback` stay open.
- Drop `commons-codec`. Keep `commons-io`.
- No preview features, module imports, markdown javadoc, ZipFile/LocalFile retab, `UnsignedTypes` deletion, `copyFully`→`transferTo`, or `listFiles`→`Files.list`.

## `ZippedFile`

```java
public record ZippedFile(int index, String name, long size, int crc) {
    public ZippedFile withName(final String name) {
        return this.name.equals(name) ? this : new ZippedFile(index, name, size, crc);
    }
    public byte[] leCrc() {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc).array();
    }
    public static int crcFromLe(final byte[] value) {
        return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
    @Override
    public String toString() {
        return HexFormat.of().formatHex(leCrc());
    }
}
```

- No compact-constructor null checks (old bean allowed unset fields).
- **Must** override `toString()` (verbose logs use it). Commons `Hex.encodeHexString` ≡ `HexFormat.of().formatHex` (lowercase, no delimiters).
- `TorrentZip.readZipContent`: `new ZippedFile(i, zipFile.filename(i), zipFile.uncompressedSize(i), ZippedFile.crcFromLe(zipFile.crc32(i)))`.
- `TorrentZipCheck.fixBackslashSeparators`: if `name.indexOf('\\') >= 0`, `set(i, t.withName(t.name().replace('\\', '/')))`. Drop the `char[]` loop. Keep the verbose-log-once flag.
- `TorrentZipRebuild`: `t.name()`, `t.size()`, `t.crc()`, `t.leCrc()`, `t.index()`.
- Tests: `getName()` → `name()`. Helper: `new ZippedFile(0, name, size, crc)` (do not round-trip CRC through bytes).

## Seal + Program

- `ICompress` (package `jtrrntzip.supportedfiles`) may `permits ZipFile` in `jtrrntzip.supportedfiles.zipfile` (same module).
- `Program` drops `implements TorrentZipOptions`, `isForceRezip()`, `isCheckOnly()`. Still implements `LogCallback`.
- Keep `zipErrorMessageText` on `ZipFile`; convert the body to a switch expression (do not move it onto `ZipReturn`).

## Language / JDK (no output change)

| Location | Change |
|---|---|
| `ZipFile.zipErrorMessageText` | `return switch (zS) { ... }` |
| `ZipFile.zipFileClose` / `zipFileCloseFailed` | `switch (zipOpen)` with `CLOSED` / `OPENREAD` / `OPENWRITE` — same control flow as today’s ifs |
| `ZipFile.zipFileCloseWriteStream` | `localFiles.getLast()` |
| `LocalFile.localFileOpenReadStream` | switch expression; keep `case 8` vs `default` (stored / unknown → bounded stream), including `default`+`case 0` behavior |
| `Program.run` | switch expression on `options.info()` for HELP/VERSION; NONE falls through to processing |
| `Program` | `Path.of(...)` instead of `Paths.get` |
| `ZipFile` comment + `isTorrentZipped` hex | `HexFormat.of().withUpperCase().toHexDigits((int) crc)` — always 8 uppercase digits, same as `"%08X"` for CRC32’s 32-bit value in a `long` |

Surgical edits only in `ZipFile.java` / `LocalFile.java` (keep tabs).

## Drop commons-codec

Remove from:

- `build.gradle` (`implementation libs.commons.codec`)
- `gradle/libs.versions.toml` (`[versions]` + `[libraries]` entries)
- `module-info.java` (`requires org.apache.commons.codec`)

Do not edit `.kilo/plans/BUILD_GRADLE_SPLIT.md`.

## Files

Main: `ZippedFile.java`, `TorrentZip.java`, `TorrentZipCheck.java`, `TorrentZipRebuild.java`, `ICompress.java`, `TorrentZipOptions.java`, `Program.java`, `ZipFile.java`, `LocalFile.java`, `module-info.java`, `build.gradle`, `gradle/libs.versions.toml`.

Tests: `TorrentZipCheckTest.java` (required). Grep `getName` / `getLECRC` / `setName` / `setCRC` / `setIndex` / `setSize` / `getCrc` and update leftovers.

## Consumer breakage (intended)

| Old | New |
|---|---|
| `getName` / `getCrc` / `getSize` / `getIndex` | `name()` / `crc()` / `size()` / `index()` |
| `setName` / `setCRC` / `setIndex` / `setSize` | constructor / `withName` / `crcFromLe` |
| `getLECRC()` | `leCrc()` |
| extra `ICompress` implementations | impossible (`sealed`) |
| extra `TorrentZipOptions` implementations | use `SimpleTorrentZipOptions` |
| `Program instanceof TorrentZipOptions` | false |

## Validation

```
./gradlew test
```

Rebuild/check tests must stay green (byte-identical zips). Incomplete codec removal fails `compileJava` / module compile.

Do not run native-image.