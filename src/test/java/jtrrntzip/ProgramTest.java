package jtrrntzip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jtrrntzip.supportedfiles.zipfile.ZipFile;

class ProgramTest {

    @TempDir
    Path tempDir;

    @Test
    void helpFlagExitsOk() {
        assertEquals(Program.EXIT_OK, new Program(new String[] { "-?" }).run());
    }

    @Test
    void versionFlagExitsOk() {
        assertEquals(Program.EXIT_OK, new Program(new String[] { "-v" }).run());
    }

    @Test
    void validZipExitsOkAndConverts() throws Exception {
        final var zip = writeSingleEntryStoredZip(tempDir.resolve("plain.zip"));

        assertEquals(Program.EXIT_OK, new Program(new String[] { zip.toString() }).run());
        assertIsValidTorrentZip(zip);
    }

    @Test
    void corruptZipExitsFailed() throws Exception {
        final var zip = writeSingleEntryStoredZip(tempDir.resolve("corrupt.zip"));
        TestZipFixtures.corruptFirstEntryCrcs(zip);

        assertEquals(Program.EXIT_FAILED, new Program(new String[] { zip.toString() }).run());
    }

    @Test
    void nonexistentDirectoryArgumentExitsFailed() {
        assertEquals(Program.EXIT_FAILED,
                new Program(new String[] { tempDir.resolve("missing-dir").resolve("inner.zip").toString() }).run());
    }

    @Test
    void globMetacharacterFileArgumentIsProcessedAsLiteralPath() throws Exception {
        final var zip = writeSingleEntryStoredZip(tempDir.resolve("brackets[1].zip"));

        assertEquals(Program.EXIT_OK, new Program(new String[] { zip.toString() }).run());
        assertIsValidTorrentZip(zip);
    }

    @Test
    void traversalContinuesAfterACorruptFile() throws Exception {
        final var dir = tempDir.resolve("mixed-dir");
        Files.createDirectories(dir);

        final var bad = writeSingleEntryStoredZip(dir.resolve("bad.zip"));
        TestZipFixtures.corruptFirstEntryCrcs(bad);
        final var badBytesBefore = Files.readAllBytes(bad);

        final var good = writeSingleEntryStoredZip(dir.resolve("good.zip"));

        assertEquals(Program.EXIT_FAILED, new Program(new String[] { dir.toString() }).run());

        assertIsValidTorrentZip(good);
        assertArrayEquals(badBytesBefore, Files.readAllBytes(bad), "the corrupt file must not be rebuilt");
    }

    private static Path writeSingleEntryStoredZip(final Path zip) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("stored.bin", "program walk content".getBytes(StandardCharsets.UTF_8));
        TestZipFixtures.writeStoredZip(zip.toFile(), entries);
        return zip;
    }

    private static void assertIsValidTorrentZip(final Path zip) throws IOException {
        try (var openZip = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, openZip.zipFileOpen(zip.toFile(), zip.toFile().lastModified(), true),
                    "reopening " + zip + " failed");
            assertTrue(openZip.zipStatus().contains(ZipStatus.TRRNTZIP),
                    zip + " is not a valid torrent zip after processing");
            openZip.zipFileClose();
        }
    }
}
