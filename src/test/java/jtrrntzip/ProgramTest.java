package jtrrntzip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jtrrntzip.supportedfiles.zipfile.ZipFile;

/**
 * End to end tests for the command line flow of {@link Program}, driving
 * {@code Program.run()} directly so the test JVM is never exited.
 */
@DisplayName("Tests for the program command line flow")
class ProgramTest {

    @TempDir
    Path tempDir;

    /** The help flag prints the usage text and exits with EXIT_OK. */
    @DisplayName("the help flag exits with OK")
    @Test
    void helpFlagExitsOk() {
        assertEquals(Program.EXIT_OK, new Program(new String[] { "-?" }).run());
    }

    /** The version flag prints the banner and exits with EXIT_OK. */
    @DisplayName("the version flag exits with OK")
    @Test
    void versionFlagExitsOk() {
        assertEquals(Program.EXIT_OK, new Program(new String[] { "-v" }).run());
    }

    /** A selected plain zip converts to the torrentzip format and exits with EXIT_OK. */
    @DisplayName("a valid zip exits OK and is converted")
    @Test
    void validZipExitsOkAndConverts() throws Exception {
        final var zip = writeSingleEntryStoredZip(tempDir.resolve("plain.zip"));

        assertEquals(Program.EXIT_OK, new Program(new String[] { zip.toString() }).run());
        assertIsValidTorrentZip(zip);
    }

    /** A corrupt selected zip makes the run exit with EXIT_FAILED. */
    @DisplayName("a corrupt zip exits FAILED")
    @Test
    void corruptZipExitsFailed() throws Exception {
        final var zip = writeSingleEntryStoredZip(tempDir.resolve("corrupt.zip"));
        TestZipFixtures.corruptFirstEntryCrcs(zip);

        assertEquals(Program.EXIT_FAILED, new Program(new String[] { zip.toString() }).run());
    }

    /** A path under a directory that cannot be listed exits with EXIT_FAILED. */
    @DisplayName("a nonexistent directory argument exits FAILED")
    @Test
    void nonexistentDirectoryArgumentExitsFailed() {
        assertEquals(Program.EXIT_FAILED,
                new Program(new String[] { tempDir.resolve("missing-dir").resolve("inner.zip").toString() }).run());
    }

    /** An existing file whose name contains glob metacharacters is processed literally. */
    @DisplayName("glob metacharacters in an existing file name are processed as-is")
    @Test
    void globMetacharacterFileArgumentIsProcessedAsLiteralPath() throws Exception {
        final var zip = writeSingleEntryStoredZip(tempDir.resolve("brackets[1].zip"));

        assertEquals(Program.EXIT_OK, new Program(new String[] { zip.toString() }).run());
        assertIsValidTorrentZip(zip);
    }

    /** A corrupt archive does not stop the directory traversal and is never rewritten. */
    @DisplayName("directory traversal continues after a corrupt file")
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

    /** With -c a plain zip is reported but its bytes stay untouched. */
    @DisplayName("-c leaves plain zips unchanged")
    @Test
    void checkOnlyFlagLeavesPlainZipsUnchanged() throws Exception {
        final var zip = writeSingleEntryStoredZip(tempDir.resolve("check-only.zip"));
        final var before = Files.readAllBytes(zip);

        assertEquals(Program.EXIT_OK, new Program(new String[] { "-c", zip.toString() }).run());

        assertArrayEquals(before, Files.readAllBytes(zip), "-c must not repair anything");
    }

    /** With -s only the top level is processed, a later plain run converts the deeper files too. */
    @DisplayName("-s stops recursion at top-level directories")
    @Test
    void recursionStopsAtTopLevelDirectoriesWithDashS() throws Exception {
        final var dir = tempDir.resolve("nested");
        Files.createDirectories(dir.resolve("inner"));
        final var outer = writeSingleEntryStoredZip(dir.resolve("outer.zip"));
        final var inner = writeSingleEntryStoredZip(dir.resolve("inner").resolve("inner.zip"));

        assertEquals(Program.EXIT_OK, new Program(new String[] { "-s", dir.toString() }).run());
        assertIsValidTorrentZip(outer);
        assertFalseFileIsTorrentZip(inner);

        assertEquals(Program.EXIT_OK, new Program(new String[] { dir.toString() }).run());
        assertIsValidTorrentZip(inner);
    }

    /** Asserts the given archive was not converted, so it lacks the torrentzip state. */
    private static void assertFalseFileIsTorrentZip(final Path zip) throws IOException {
        try (var openZip = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, openZip.zipFileOpen(zip.toFile(), zip.toFile().lastModified(), true));
            assertFalse(openZip.zipStatus().contains(ZipStatus.TRRNTZIP),
                    zip + " must not have been touched by the -s run");
            openZip.zipFileClose();
        }
    }

    /** A glob that matches nothing exits OK without failures. */
    @DisplayName("an unmatched glob finds nothing and exits OK")
    @Test
    void unmatchedBracketFileArgumentFindsNothingAndExitsOk() {
        assertEquals(Program.EXIT_OK,
                new Program(new String[] { tempDir.resolve("no[match].zip").toString() }).run());
    }

    /** Verbose logging on a directory of plain zips runs without failing. */
    @DisplayName("verbose logging on a directory succeeds")
    @Test
    void verboseFlagRunsOnADirectoryWithoutFailing() throws Exception {
        final var dir = tempDir.resolve("verbose-dir");
        Files.createDirectories(dir);
        writeSingleEntryStoredZip(dir.resolve("plain.zip"));

        assertEquals(Program.EXIT_OK, new Program(new String[] { "-l", dir.toString() }).run());
    }

    /** Writes a single entry stored zip with a small text file payload. */
    private static Path writeSingleEntryStoredZip(final Path zip) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("stored.bin", "program walk content".getBytes(StandardCharsets.UTF_8));
        TestZipFixtures.writeStoredZip(zip.toFile(), entries);
        return zip;
    }

    /** Reopens the archive and asserts it fully validates as a torrentzip. */
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
