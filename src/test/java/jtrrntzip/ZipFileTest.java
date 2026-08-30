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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jtrrntzip.supportedfiles.zipfile.ZipFile;

class ZipFileTest {

    @TempDir
    Path tempDir;

    @Test
    void exactly65535EntriesRoundTripWithoutZip64() throws Exception {
        final var zip = tempDir.resolve("count-65535.zip");
        TestZipFixtures.writeZipWithOwnWriter(zip.toFile(), sortedNames(65535));

        assertOpensWithCount(zip, 65535);

        final var tz = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, true));
        final var status = tz.process(zip.toFile());
        assertTrue(status.contains(TrrntZipStatus.VALIDTRRNTZIP), "expected valid torrent zip but got " + status);
    }

    @Test
    void exactly65536EntriesRoundTripWithZip64() throws Exception {
        final var zip = tempDir.resolve("count-65536.zip");
        TestZipFixtures.writeZipWithOwnWriter(zip.toFile(), sortedNames(65536));

        assertOpensWithCount(zip, 65536);

        final var tz = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, true));
        final var status = tz.process(zip.toFile());
        assertTrue(status.contains(TrrntZipStatus.VALIDTRRNTZIP), "expected valid torrent zip but got " + status);
    }

    private static List<String> sortedNames(final int count) {
        final List<String> names = new ArrayList<>(count);
        for (var i = 0; i < count; i++)
            names.add(String.format("f%06d.txt", i));
        return names;
    }

    private static void assertOpensWithCount(final Path zip, final int expectedCount) throws IOException {
        try (var openZip = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, openZip.zipFileOpen(zip.toFile(), zip.toFile().lastModified(), true),
                    "zip with " + expectedCount + " entries must reopen cleanly");
            assertEquals(expectedCount, openZip.localFilesCount());
            openZip.zipFileClose();
        }
    }

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

    private static void writeSingleEntryStoredZip(final Path zip, final String name) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(name, "content".getBytes(StandardCharsets.UTF_8));
        TestZipFixtures.writeStoredZip(zip.toFile(), entries);
    }
}
