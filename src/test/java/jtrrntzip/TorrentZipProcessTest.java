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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jtrrntzip.supportedfiles.zipfile.ZipFile;

class TorrentZipProcessTest {

    @TempDir
    Path tempDir;

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

    private static void writeMultiProblemZip(final File zip) throws IOException {
        // a zip breaking rule 1 (backslash separator), rule 2 (unsorted), rule 3
        // (unneeded directory marker after sorting) and rule 4 (identical duplicate)
        final List<String> names = List.of("z.txt", "b\\file.txt", "a.txt", "dir/", "dir/x.txt", "q.txt", "q.txt");
        TestZipFixtures.writeZipWithOwnWriter(zip, names);
    }
}
