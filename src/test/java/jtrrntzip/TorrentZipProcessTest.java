package jtrrntzip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jtrrntzip.supportedfiles.zipfile.ZipFile;

/**
 * Tests for the processing of archives breaking several torrentzip rules at
 * once, from the pure check report up to the full rebuild.
 */
@DisplayName("Tests for the torrentzip processing")
class TorrentZipProcessTest {

    @TempDir
    Path tempDir;

    /** Check only mode reports every violated rule without modifying the archive. */
    @DisplayName("check only reports every rule violation without repairing")
    @Test
    void checkOnlyReportsEveryRuleViolationWithoutRepairing() throws Exception {
        final var zip = tempDir.resolve("multi-problem.zip");
        writeMultiProblemZip(zip.toFile());

        final var before = Files.readAllBytes(zip);

        final var tz = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, true));
        final var status = tz.process(zip.toFile());

        assertTrue(status.contains(TrrntZipStatus.BADDIRECTORYSEPARATOR), "expected BADDIRECTORYSEPARATOR, got " + status);
        assertTrue(status.contains(TrrntZipStatus.UNSORTED), "expected UNSORTED, got " + status);
        assertTrue(status.contains(TrrntZipStatus.EXTRADIRECTORYENTRIES), "expected EXTRADIRECTORYENTRIES, got " + status);
        assertTrue(status.contains(TrrntZipStatus.REPEATFILESFOUND), "expected REPEATFILESFOUND, got " + status);
        assertFalse(status.contains(TrrntZipStatus.VALIDTRRNTZIP));

        assertArrayEquals(before, Files.readAllBytes(zip), "check only must not modify the file");
    }

    /** A verbose conversion logs one message per violated rule plus the rebuild step. */
    @DisplayName("verbose conversion logs every rule")
    @Test
    void verboseConversionLogsEveryRule() throws Exception {
        final var zip = tempDir.resolve("multi-problem.zip");
        writeMultiProblemZip(zip.toFile());

        final var logCallback = new TestZipFixtures.RecordingLogCallback();
        final var tz = new TorrentZip(logCallback, new SimpleTorrentZipOptions(true, false));
        final var status = tz.process(zip.toFile());

        assertTrue(status.contains(TrrntZipStatus.VALIDTRRNTZIP), "conversion must succeed, got " + status);
        assertTrue(logCallback.logs().contains(Messages.getString("TorrentZipCheck.IncorrectDirectorySeparatoreFound")));
        assertTrue(logCallback.logs().contains(Messages.getString("TorrentZipCheck.IncorrectFileOrderFound")));
        assertTrue(logCallback.logs().contains(Messages.getString("TorrentZipCheck.UnneededDirectoryRecordsFound")));
        assertTrue(logCallback.logs().contains(Messages.getString("TorrentZipCheck.DuplicateFileEntriesFound")));
        assertTrue(logCallback.logs().contains(Messages.getString("TorrentZip.TorrentZipping")));
    }

    /** The rebuilt multi problem archive reopens as a valid torrentzip without the removed entries. */
    @DisplayName("the converted multi problem zip reopens as valid torrentzip")
    @Test
    void convertedMultiProblemZipReopensAsValidTzip() throws Exception {
        final var zip = tempDir.resolve("multi-problem.zip");
        writeMultiProblemZip(zip.toFile());

        final var tz = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, false));
        assertTrue(tz.process(zip.toFile()).contains(TrrntZipStatus.VALIDTRRNTZIP));

        try (var zf = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, zf.zipFileOpen(zip.toFile(), zip.toFile().lastModified(), true));
            assertEquals(5, zf.localFilesCount(), "the duplicate and the unneeded marker must be gone");
            assertTrue(zf.zipStatus().contains(ZipStatus.TRRNTZIP));
            assertEquals("a.txt", zf.filename(0));
            assertEquals("b/file.txt", zf.filename(1));
            assertEquals("dir/x.txt", zf.filename(2));
            assertEquals("q.txt", zf.filename(3));
            assertEquals("z.txt", zf.filename(4));
            zf.zipFileClose();
        }
    }

    /** Conflicting duplicates mark the archive corrupt, skip the rebuild and leave no tmp file. */
    @DisplayName("conflicting duplicates skip the rebuild")
    @Test
    void conflictingDuplicatesMarkCorruptAndSkipRebuild() throws Exception {
        final var zip = tempDir.resolve("conflict.zip");
        try (final var zf = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, zf.zipFileCreate(zip.toFile()));
            TestZipFixtures.writeOwnEntry(zf, "q.txt", "aa".getBytes(StandardCharsets.UTF_8));
            TestZipFixtures.writeOwnEntry(zf, "q.txt", "bb".getBytes(StandardCharsets.UTF_8));
            zf.zipFileClose();
        }

        final var before = Files.readAllBytes(zip);

        final var tz = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, false));
        final var status = tz.process(zip.toFile());

        assertTrue(status.contains(TrrntZipStatus.CORRUPTZIP), "expected corrupt status, got " + status);
        assertArrayEquals(before, Files.readAllBytes(zip), "a zip with conflicting duplicates must not be rebuilt");

        try (DirectoryStream<Path> tmps = Files.newDirectoryStream(tempDir, "*.tmp")) {
            assertFalse(tmps.iterator().hasNext(), "no tmp file may be left behind");
        }
    }

    /** Identical duplicates collapse into one entry of the rebuilt archive. */
    @DisplayName("identical duplicates collapse into one entry")
    @Test
    void identifyingDuplicatesAreCollapsedIntoOneByTheRebuild() throws Exception {
        final var zip = tempDir.resolve("identical-dup.zip");
        try (final var zf = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, zf.zipFileCreate(zip.toFile()));
            TestZipFixtures.writeOwnEntry(zf, "q.txt", "same".getBytes(StandardCharsets.UTF_8));
            TestZipFixtures.writeOwnEntry(zf, "q.txt", "same".getBytes(StandardCharsets.UTF_8));
            zf.zipFileClose();
        }

        final var tz = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, false));
        final var status = tz.process(zip.toFile());

        assertTrue(status.contains(TrrntZipStatus.VALIDTRRNTZIP), "identical duplicates rebuild fine, got " + status);

        try (var zf = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, zf.zipFileOpen(zip.toFile(), zip.toFile().lastModified(), true));
            assertEquals(1, zf.localFilesCount());
            assertTrue(zf.zipStatus().contains(ZipStatus.TRRNTZIP));
            zf.zipFileClose();
        }
    }

    /** An entry with the 4 GiB sentinel size makes the whole archive read and write the zip64 structures. */
    @DisplayName("a huge declared size writes the zip64 structures for the whole archive")
    @Test
    void anEntryDeclaringAHugeSizeWritesZip64StructuresForTheWholeZipFile() throws Exception {
        final var zip = tempDir.resolve("declared-huge.zip");
        try (final var zf = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, zf.zipFileCreate(zip.toFile()));
            TestZipFixtures.writeOwnEntryWithDeclaredSize(zf, "big.bin", 0xFFFFFFFFL, new byte[0]);
            zf.zipFileClose();
        }

        final var data = Files.readAllBytes(zip);
        assertTrue(containsSignature(data, new byte[] { 0x50, 0x4b, 0x06, 0x07 }), "zip64 end of central directory locator missing");
        assertTrue(containsSignature(data, new byte[] { 0x50, 0x4b, 0x06, 0x06 }), "zip64 end of central directory record missing");

        try (var zf = new ZipFile()) {
            assertEquals(ZipReturn.ZIPGOOD, zf.zipFileOpen(zip.toFile(), zip.toFile().lastModified(), true));
            assertEquals(1, zf.localFilesCount());
            assertEquals(0xFFFFFFFFL, zf.uncompressedSize(0));
            assertTrue(zf.zipStatus().contains(ZipStatus.TRRNTZIP));
            zf.zipFileClose();
        }
    }

    /** Tells if the data contains the given four byte signature. */
    private static boolean containsSignature(final byte[] data, final byte[] signature) {
        for (var i = 0; i <= data.length - signature.length; i++) {
            var match = true;
            for (var j = 0; j < signature.length; j++) {
                if (data[i + j] != signature[j]) {
                    match = false;
                    break;
                }
            }
            if (match)
                return true;
        }
        return false;
    }

    /** Writes a zip breaking rule 1 to 4: backslash separator, unsorted names, an unneeded directory marker and an identical duplicate. */
    private static void writeMultiProblemZip(final File zip) throws IOException {
        // a zip breaking rule 1 (backslash separator), rule 2 (unsorted), rule 3
        // (unneeded directory marker after sorting) and rule 4 (identical duplicate)
        final List<String> names = List.of("z.txt", "b\\file.txt", "a.txt", "dir/", "dir/x.txt", "q.txt", "q.txt");
        TestZipFixtures.writeZipWithOwnWriter(zip, names);
    }
}
