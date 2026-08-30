package jtrrntzip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jtrrntzip.supportedfiles.zipfile.ZipFile;

/**
 * Tests for the low level reading and writing of
 * {@link jtrrntzip.supportedfiles.zipfile.ZipFile}.
 */
@DisplayName("Tests for the low level zip reading and writing")
class ZipFileTest {

    @TempDir
    Path tempDir;

    /** The literal 65,535 entry count needs no zip64 structures and converts cleanly. */
    @DisplayName("65,535 entries round trip without zip64")
    @Test
    void exactly65535EntriesRoundTripWithoutZip64() throws Exception {
        final var zip = tempDir.resolve("count-65535.zip");
        TestZipFixtures.writeZipWithOwnWriter(zip.toFile(), sortedNames(65535));

        assertOpensWithCount(zip, 65535);

        final var tz = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, true));
        final var status = tz.process(zip.toFile());
        assertTrue(status.contains(TrrntZipStatus.VALIDTRRNTZIP), "expected valid torrent zip but got " + status);
    }

    /** One entry past the classic count writes and reads the zip64 structures. */
    @DisplayName("65,536 entries round trip with zip64")
    @Test
    void exactly65536EntriesRoundTripWithZip64() throws Exception {
        final var zip = tempDir.resolve("count-65536.zip");
        TestZipFixtures.writeZipWithOwnWriter(zip.toFile(), sortedNames(65536));

        assertOpensWithCount(zip, 65536);

        final var tz = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, true));
        final var status = tz.process(zip.toFile());
        assertTrue(status.contains(TrrntZipStatus.VALIDTRRNTZIP), "expected valid torrent zip but got " + status);
    }

    /** Generates count ascending zero padded entry names. */
    private static List<String> sortedNames(final int count) {
        final List<String> names = new ArrayList<>(count);
        for (var i = 0; i < count; i++)
            names.add(String.format("f%06d.txt", i));
        return names;
    }

    /** Reopens the archive and asserts the entry count survives the round trip. */
    private static void assertOpensWithCount(final Path zip, final int expectedCount) throws IOException {
        try (var openZip = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, openZip.zipFileOpen(zip.toFile(), zip.toFile().lastModified(), true),
                    "zip with " + expectedCount + " entries must reopen cleanly");
            assertEquals(expectedCount, openZip.localFilesCount());
            openZip.zipFileClose();
        }
    }

    /** A truncated archive does not open. */
    @DisplayName("a truncated zip is rejected")
    @Test
    void truncatedZipIsRejected() throws Exception {
        final var zip = tempDir.resolve("truncated.zip");
        writeSingleEntryStoredZip(zip, "stored.bin");

        final var data = Files.readAllBytes(zip);
        Files.write(zip, Arrays.copyOf(data, data.length - 10));

        try (var openZip = new ZipFile()) {
            assertNotEquals(ZipReturn.ZIPGOOD, openZip.zipFileOpen(zip.toFile(), zip.toFile().lastModified(), true),
                    "a truncated zip must not open");
            openZip.zipFileClose();
        }
    }

    /** Abandoning a written archive deletes its partial output file. */
    @DisplayName("closeFailed deletes the partial output")
    @Test
    void zipFileCloseFailedDeletesThePartialOutput() throws Exception {
        final var zip = tempDir.resolve("failed.zip");

        try (var writer = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, writer.zipFileCreate(zip.toFile()));
            TestZipFixtures.writeOwnEntry(writer, "a.txt", new byte[0]);
            writer.zipFileCloseFailed();
        }

        assertFalse(Files.exists(zip), "closeFailed must delete the partial output file");
    }

    /** Creating an archive whose path has no parent directory succeeds. */
    @DisplayName("create accepts a path without a parent directory")
    @Test
    void zipFileCreateAcceptsPathWithoutParentDirectory() throws Exception {
        final var relative = new File("jtrrntzip-relative-create-test.zip");
        try {
            try (var writer = new ZipFile()) {
                assertEquals(ZipReturn.ZIPGOOD, writer.zipFileCreate(relative),
                        "create must not fail for a path without a parent directory");
                writer.zipFileClose();
            }
            assertTrue(relative.exists());
        } finally {
            Files.deleteIfExists(relative.toPath());
        }
    }

    /** A lower cased TORRENTZIPPED comment fails the torrentzip detection. */
    @DisplayName("the torrentzip comment CRC match is case sensitive")
    @Test
    void torrentZipCommentCrcMatchIsCaseSensitive() throws Exception {
        final var zip = tempDir.resolve("lowercase-comment.zip");
        writeSingleEntryStoredZip(zip, "stored.bin");

        final var converter = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, false));
        assertTrue(converter.process(zip.toFile()).contains(TrrntZipStatus.VALIDTRRNTZIP),
                "conversion to torrent zip must succeed");

        TestZipFixtures.lowerCaseTorrentZipComment(zip);

        try (var openZip = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, openZip.zipFileOpen(zip.toFile(), zip.toFile().lastModified(), true));
            assertFalse(openZip.zipStatus().contains(ZipStatus.TRRNTZIP),
                    "a lower case comment crc must not validate as torrent zip");
            openZip.zipFileClose();
        }
    }

    /** Non-ASCII names fold per the raw reference comparator after the conversion. */
    @DisplayName("non-ASCII names follow the reference fold order")
    @Test
    void nonAsciiNamesAreOrderedPerReferenceFoldAfterConversion() throws Exception {
        // 'KELVIN SIGN' (U+212A) sorts before 'Ä' (U+00C4) under a full unicode fold,
        // the reference torrentzip order places 'Ä' first
        final var zip = tempDir.resolve("non-ascii.zip");
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("\u212A.txt", "\u00C4 first".getBytes(StandardCharsets.UTF_8));
        entries.put("\u00C4.txt", "kelvin second".getBytes(StandardCharsets.UTF_8));
        TestZipFixtures.writeStoredZip(zip.toFile(), entries);

        final var converter = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, false));
        final var status = converter.process(zip.toFile());
        assertTrue(status.contains(TrrntZipStatus.VALIDTRRNTZIP), "conversion must succeed, got " + status);

        try (var openZip = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, openZip.zipFileOpen(zip.toFile(), zip.toFile().lastModified(), true));
            assertEquals(2, openZip.localFilesCount());
            assertEquals("\u00C4.txt", openZip.filename(0), "reference fold must place Ä before the kelvin sign");
            assertEquals("\u212A.txt", openZip.filename(1));
            assertTrue(openZip.zipStatus().contains(ZipStatus.TRRNTZIP));
            openZip.zipFileClose();
        }
    }

    /** UTF-8 entry names survive the conversion and reopen in raw code unit order. */
    @DisplayName("UTF-8 names survive the conversion")
    @Test
    void utf8NamesSurviveTorrentZipConversion() throws Exception {
        final var zip = tempDir.resolve("utf8.zip");
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("\u65E5.txt", "day".getBytes(StandardCharsets.UTF_8));
        entries.put("\u4E2D.txt", "middle".getBytes(StandardCharsets.UTF_8));
        TestZipFixtures.writeStoredZip(zip.toFile(), entries);

        final var converter = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, false));
        assertTrue(converter.process(zip.toFile()).contains(TrrntZipStatus.VALIDTRRNTZIP));

        try (var openZip = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, openZip.zipFileOpen(zip.toFile(), zip.toFile().lastModified(), true));
            assertEquals(2, openZip.localFilesCount());
            assertEquals("\u4E2D.txt", openZip.filename(0), "raw code unit order puts 中 before 日");
            assertEquals("\u65E5.txt", openZip.filename(1));
            openZip.zipFileClose();
        }
    }

    /** Writes a single entry stored zip with a small text payload. */
    private static void writeSingleEntryStoredZip(final Path zip, final String name) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(name, "content".getBytes(StandardCharsets.UTF_8));
        TestZipFixtures.writeStoredZip(zip.toFile(), entries);
    }
}
