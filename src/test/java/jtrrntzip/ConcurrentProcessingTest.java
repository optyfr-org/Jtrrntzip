package jtrrntzip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jtrrntzip.supportedfiles.zipfile.ZipFile;

/**
 * Tests for the concurrent multi-file processing path introduced by the
 * virtual-threads feature. These tests exercise the {@code Program.run()}
 * end-to-end flow with multiple zip archives, verify that each archive is
 * converted independently, and assert that no temporary files or resources
 * leak when processing finishes.
 */
@DisplayName("Tests for concurrent multi-file processing")
class ConcurrentProcessingTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Writes a single-entry stored zip with a unique payload. */
    private static Path writeStoredZip(final Path dir, final String name, final String payload) throws IOException {
        final var zip = dir.resolve(name);
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("entry.bin", payload.getBytes(StandardCharsets.UTF_8));
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

    /** Asserts no stale .tmp files remain in the directory tree. */
    private static void assertNoTmpFiles(final Path dir) throws IOException {
        try (DirectoryStream<Path> tmps = Files.newDirectoryStream(dir, "*.tmp")) {
            assertFalse(tmps.iterator().hasNext(), "no tmp file may be left behind in " + dir);
        }
    }

    // -----------------------------------------------------------------------
    // End-to-end concurrent conversion via Program.run()
    // -----------------------------------------------------------------------

    /** A directory of multiple plain zips is converted concurrently and exits OK. */
    @DisplayName("multiple zips in a directory are all converted concurrently")
    @Test
    void multipleZipsInDirectoryAreAllConvertedConcurrently() throws Exception {
        final var dir = tempDir.resolve("multi");
        Files.createDirectories(dir);

        for (var i = 0; i < 8; i++)
            writeStoredZip(dir, "file" + i + ".zip", "content-" + i);

        assertEquals(Program.EXIT_OK, new Program(new String[] { dir.toString() }).run());

        try (DirectoryStream<Path> zips = Files.newDirectoryStream(dir, "*.zip")) {
            for (final Path zip : zips)
                assertIsValidTorrentZip(zip);
        }
        assertNoTmpFiles(dir);
    }

    /** A mix of valid and corrupt zips in a directory: valid ones convert, corrupt ones are left untouched. */
    @DisplayName("concurrent processing converts valid zips and skips corrupt ones")
    @Test
    void concurrentProcessingConvertsValidAndSkipsCorrupt() throws Exception {
        final var dir = tempDir.resolve("mixed");
        Files.createDirectories(dir);

        final var good1 = writeStoredZip(dir, "good1.zip", "good-content-1");
        final var good2 = writeStoredZip(dir, "good2.zip", "good-content-2");
        final var bad = writeStoredZip(dir, "bad.zip", "corrupt-content");
        TestZipFixtures.corruptFirstEntryCrcs(bad);
        final var badBytesBefore = Files.readAllBytes(bad);

        assertEquals(Program.EXIT_FAILED, new Program(new String[] { dir.toString() }).run());

        assertIsValidTorrentZip(good1);
        assertIsValidTorrentZip(good2);
        assertArrayEquals(badBytesBefore, Files.readAllBytes(bad), "the corrupt file must not be rebuilt");
        assertNoTmpFiles(dir);
    }

    /** Two separate directory arguments are collected and processed together. */
    @DisplayName("files from multiple directory arguments are processed concurrently")
    @Test
    void filesFromMultipleDirectoryArgumentsAreProcessedConcurrently() throws Exception {
        final var dirA = tempDir.resolve("dirA");
        final var dirB = tempDir.resolve("dirB");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);

        writeStoredZip(dirA, "a.zip", "alpha");
        writeStoredZip(dirB, "b.zip", "beta");

        assertEquals(Program.EXIT_OK, new Program(new String[] { dirA.toString(), dirB.toString() }).run());

        assertIsValidTorrentZip(dirA.resolve("a.zip"));
        assertIsValidTorrentZip(dirB.resolve("b.zip"));
    }

    /** A directory with a single zip still uses the sequential path and exits OK. */
    @DisplayName("a directory with one zip uses the sequential path")
    @Test
    void singleZipInDirectoryUsesSequentialPath() throws Exception {
        final var dir = tempDir.resolve("single");
        Files.createDirectories(dir);
        writeStoredZip(dir, "only.zip", "solo");

        assertEquals(Program.EXIT_OK, new Program(new String[] { dir.toString() }).run());
        assertIsValidTorrentZip(dir.resolve("only.zip"));
    }

    // -----------------------------------------------------------------------
    // Check-only and force-rezip flags with concurrent processing
    // -----------------------------------------------------------------------

    /** Check-only mode with a directory of zips: none are modified. */
    @DisplayName("-c with multiple zips leaves all files unchanged")
    @Test
    void checkOnlyWithMultipleZipsLeavesAllUnchanged() throws Exception {
        final var dir = tempDir.resolve("check-multi");
        Files.createDirectories(dir);

        final var zip1 = writeStoredZip(dir, "c1.zip", "check-1");
        final var zip2 = writeStoredZip(dir, "c2.zip", "check-2");
        final var bytes1Before = Files.readAllBytes(zip1);
        final var bytes2Before = Files.readAllBytes(zip2);

        assertEquals(Program.EXIT_OK, new Program(new String[] { "-c", dir.toString() }).run());

        assertArrayEquals(bytes1Before, Files.readAllBytes(zip1), "-c must not modify c1.zip");
        assertArrayEquals(bytes2Before, Files.readAllBytes(zip2), "-c must not modify c2.zip");
    }

    /** Force-rezip on already-converted zips rebuilds them and exits OK. */
    @DisplayName("-f rebuilds already-valid torrentzips concurrently")
    @Test
    void forceRezipRebuildsAlreadyValidTorrentzipsConcurrently() throws Exception {
        final var dir = tempDir.resolve("force-multi");
        Files.createDirectories(dir);

        final var zip1 = writeStoredZip(dir, "f1.zip", "force-1");
        final var zip2 = writeStoredZip(dir, "f2.zip", "force-2");

        // first convert normally
        assertEquals(Program.EXIT_OK, new Program(new String[] { dir.toString() }).run());
        assertIsValidTorrentZip(zip1);
        assertIsValidTorrentZip(zip2);

        final var bytes1Converted = Files.readAllBytes(zip1);
        final var bytes2Converted = Files.readAllBytes(zip2);

        // force-rezip: should rebuild even though they are already valid
        assertEquals(Program.EXIT_OK, new Program(new String[] { "-f", dir.toString() }).run());

        assertIsValidTorrentZip(zip1);
        assertIsValidTorrentZip(zip2);
        assertArrayEquals(bytes1Converted, Files.readAllBytes(zip1), "force-rezip of a valid tzip must be deterministic");
        assertArrayEquals(bytes2Converted, Files.readAllBytes(zip2), "force-rezip of a valid tzip must be deterministic");
    }

    // -----------------------------------------------------------------------
    // Verbose logging with concurrent processing
    // -----------------------------------------------------------------------

    /** Verbose logging on a directory of multiple zips runs without failing. */
    @DisplayName("-l with multiple zips succeeds")
    @Test
    void verboseLoggingWithMultipleZipsSucceeds() throws Exception {
        final var dir = tempDir.resolve("verbose-multi");
        Files.createDirectories(dir);

        writeStoredZip(dir, "v1.zip", "verbose-1");
        writeStoredZip(dir, "v2.zip", "verbose-2");
        writeStoredZip(dir, "v3.zip", "verbose-3");

        assertEquals(Program.EXIT_OK, new Program(new String[] { "-l", dir.toString() }).run());

        try (DirectoryStream<Path> zips = Files.newDirectoryStream(dir, "*.zip")) {
            for (final Path zip : zips)
                assertIsValidTorrentZip(zip);
        }
    }

    // -----------------------------------------------------------------------
    // BufferedLogCallback thread-safety
    // -----------------------------------------------------------------------

    /** Multiple BufferedLogCallbacks flushing to the same lock produce non-interleaved blocks. */
    @DisplayName("BufferedLogCallback flushes atomically under a shared lock")
    @Test
    void bufferedLogCallbackFlushesAtomically() throws Exception {
        final var lock = new Object();
        final var baos = new ByteArrayOutputStream();
        final var out = new PrintStream(baos, true, "UTF-8");
        final var delegate = new DummyLogCallback();
        final var latch = new CountDownLatch(1);
        final var readyLatch = new CountDownLatch(2);
        final var errors = new CopyOnWriteArrayList<Throwable>();

        final var cb1 = new BufferedLogCallback(delegate, lock);
        final var cb2 = new BufferedLogCallback(delegate, lock);

        // buffer some lines
        cb1.statusLogCallBack("A-start");
        cb1.statusCallBack(50);
        cb1.statusLogCallBack("A-end");

        cb2.statusLogCallBack("B-start");
        cb2.statusCallBack(75);
        cb2.statusLogCallBack("B-end");

        // flush concurrently; the shared lock guarantees each block is atomic
        final var t1 = Thread.ofVirtual().start(() -> {
            readyLatch.countDown();
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add(e);
                return;
            }
            cb1.flushTo(out);
        });
        final var t2 = Thread.ofVirtual().start(() -> {
            readyLatch.countDown();
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add(e);
                return;
            }
            cb2.flushTo(out);
        });

        readyLatch.await();
        latch.countDown();
        t1.join();
        t2.join();

        assertTrue(errors.isEmpty(), "no thread errors expected: " + errors);

        final var output = baos.toString("UTF-8");
        final var lines = output.lines().toList();

        // each callback produced exactly 2 lines (statusLogCallBack lines; statusCallBack appends to the last buffered line)
        assertEquals(4, lines.size(), "expected 4 lines total, got: " + lines);

        // the two lines from cb1 must be contiguous, and likewise for cb2
        final var aStart = lines.indexOf("A-start050% ");
        final var aEnd = lines.indexOf("A-end");
        final var bStart = lines.indexOf("B-start075% ");
        final var bEnd = lines.indexOf("B-end");

        assertTrue(aStart >= 0 && aEnd >= 0, "A lines must be present: " + lines);
        assertTrue(bStart >= 0 && bEnd >= 0, "B lines must be present: " + lines);
        assertEquals(aStart + 1, aEnd, "A block must be contiguous");
        assertEquals(bStart + 1, bEnd, "B block must be contiguous");
    }

    /** statusCallBack with no preceding statusLogCallBack is a no-op. */
    @DisplayName("BufferedLogCallback statusCallBack with empty buffer is safe")
    @Test
    void bufferedLogCallbackStatusCallBackOnEmptyBufferIsSafe() {
        final var cb = new BufferedLogCallback(new DummyLogCallback(), new Object());
        // must not throw
        cb.statusCallBack(42);
    }

    /** isVerboseLogging delegates to the wrapped callback. */
    @DisplayName("BufferedLogCallback delegates isVerboseLogging")
    @Test
    void bufferedLogCallbackDelegatesIsVerboseLogging() {
        final var verbose = new TestZipFixtures.RecordingLogCallback();
        final var cb = new BufferedLogCallback(verbose, new Object());
        assertTrue(cb.isVerboseLogging());

        final var quiet = new DummyLogCallback();
        final var cb2 = new BufferedLogCallback(quiet, new Object());
        assertFalse(cb2.isVerboseLogging());
    }

    // -----------------------------------------------------------------------
    // TorrentZip engine thread-safety: independent instances on same file
    // -----------------------------------------------------------------------

    /** Two TorrentZip engines processing different files concurrently produce correct results. */
    @DisplayName("independent TorrentZip engines process different files concurrently")
    @Test
    void independentEnginesProcessDifferentFilesConcurrently() throws Exception {
        final var zip1 = writeStoredZip(tempDir, "engine1.zip", "payload-1");
        final var zip2 = writeStoredZip(tempDir, "engine2.zip", "payload-2");

        final var latch = new CountDownLatch(1);
        final var readyLatch = new CountDownLatch(2);
        final var results = new CopyOnWriteArrayList<Set<TrrntZipStatus>>();
        final var errors = new CopyOnWriteArrayList<Throwable>();

        final var t1 = Thread.ofVirtual().start(() -> {
            readyLatch.countDown();
            try {
                latch.await();
                var tz = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, false));
                results.add(tz.process(zip1.toFile()));
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        final var t2 = Thread.ofVirtual().start(() -> {
            readyLatch.countDown();
            try {
                latch.await();
                var tz = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, false));
                results.add(tz.process(zip2.toFile()));
            } catch (Throwable e) {
                errors.add(e);
            }
        });

        readyLatch.await();
        latch.countDown();
        t1.join();
        t2.join();

        assertTrue(errors.isEmpty(), "no errors expected: " + errors);
        assertEquals(2, results.size());
        for (final var status : results)
            assertTrue(status.contains(TrrntZipStatus.VALIDTRRNTZIP), "expected valid, got " + status);

        assertIsValidTorrentZip(zip1);
        assertIsValidTorrentZip(zip2);
        assertNoTmpFiles(tempDir);
    }

    // -----------------------------------------------------------------------
    // Glob-based concurrent processing
    // -----------------------------------------------------------------------

    /** A glob matching multiple zips triggers concurrent processing. */
    @DisplayName("glob matching multiple zips processes them concurrently")
    @Test
    void globMatchingMultipleZipsProcessesConcurrently() throws Exception {
        writeStoredZip(tempDir, "glob-a.zip", "glob-a");
        writeStoredZip(tempDir, "glob-b.zip", "glob-b");
        writeStoredZip(tempDir, "glob-c.zip", "glob-c");

        final String globPattern = tempDir.toString() + File.separator + "glob-*.zip";
        assertEquals(Program.EXIT_OK, new Program(new String[] { globPattern }).run());

        assertIsValidTorrentZip(tempDir.resolve("glob-a.zip"));
        assertIsValidTorrentZip(tempDir.resolve("glob-b.zip"));
        assertIsValidTorrentZip(tempDir.resolve("glob-c.zip"));
    }

    // -----------------------------------------------------------------------
    // Nested directory concurrent processing
    // -----------------------------------------------------------------------

    /** Zips in nested subdirectories are all discovered and converted concurrently. */
    @DisplayName("nested subdirectory zips are discovered and converted concurrently")
    @Test
    void nestedSubdirectoryZipsAreDiscoveredAndConvertedConcurrently() throws Exception {
        final var sub1 = tempDir.resolve("sub1");
        final var sub2 = tempDir.resolve("sub2/deep");
        Files.createDirectories(sub1);
        Files.createDirectories(sub2);

        writeStoredZip(sub1, "n1.zip", "nested-1");
        writeStoredZip(sub2, "n2.zip", "nested-2");

        assertEquals(Program.EXIT_OK, new Program(new String[] { tempDir.toString() }).run());

        assertIsValidTorrentZip(sub1.resolve("n1.zip"));
        assertIsValidTorrentZip(sub2.resolve("n2.zip"));
    }

    // -----------------------------------------------------------------------
    // Resource leak: no tmp files left after concurrent processing
    // -----------------------------------------------------------------------

    /** After concurrent processing of many zips, no .tmp files remain anywhere. */
    @DisplayName("no tmp files remain after concurrent processing of many zips")
    @Test
    void noTmpFilesRemainAfterConcurrentProcessingOfManyZips() throws Exception {
        final var dir = tempDir.resolve("many");
        Files.createDirectories(dir);

        for (var i = 0; i < 16; i++)
            writeStoredZip(dir, "many" + i + ".zip", "data-" + i);

        assertEquals(Program.EXIT_OK, new Program(new String[] { dir.toString() }).run());

        assertNoTmpFiles(dir);
        assertNoTmpFiles(tempDir);
    }

    // -----------------------------------------------------------------------
    // Deterministic output: concurrent conversion produces identical bytes
    // -----------------------------------------------------------------------

    /** Two separate runs on the same input produce byte-identical results. */
    @DisplayName("concurrent conversion is deterministic across runs")
    @Test
    void concurrentConversionIsDeterministicAcrossRuns() throws Exception {
        final var dir1 = tempDir.resolve("run1");
        final var dir2 = tempDir.resolve("run2");
        Files.createDirectories(dir1);
        Files.createDirectories(dir2);

        for (var i = 0; i < 4; i++) {
            final var payload = "deterministic-" + i;
            writeStoredZip(dir1, "det" + i + ".zip", payload);
            writeStoredZip(dir2, "det" + i + ".zip", payload);
        }

        assertEquals(Program.EXIT_OK, new Program(new String[] { dir1.toString() }).run());
        assertEquals(Program.EXIT_OK, new Program(new String[] { dir2.toString() }).run());

        for (var i = 0; i < 4; i++) {
            assertArrayEquals(
                    Files.readAllBytes(dir1.resolve("det" + i + ".zip")),
                    Files.readAllBytes(dir2.resolve("det" + i + ".zip")),
                    "run " + i + " must produce identical bytes"
            );
        }
    }

    // -----------------------------------------------------------------------
    // Edge case: empty directory
    // -----------------------------------------------------------------------

    /** An empty directory with no zips exits OK without failures. */
    @DisplayName("empty directory exits OK")
    @Test
    void emptyDirectoryExitsOk() throws Exception {
        final var dir = tempDir.resolve("empty");
        Files.createDirectories(dir);

        assertEquals(Program.EXIT_OK, new Program(new String[] { dir.toString() }).run());
    }

    // -----------------------------------------------------------------------
    // Edge case: non-zip files are ignored
    // -----------------------------------------------------------------------

    /** Non-zip files in a directory are ignored; only .zip files are processed. */
    @DisplayName("non-zip files are ignored during concurrent processing")
    @Test
    void nonZipFilesAreIgnoredDuringConcurrentProcessing() throws Exception {
        final var dir = tempDir.resolve("mixed-ext");
        Files.createDirectories(dir);

        writeStoredZip(dir, "valid.zip", "valid-content");
        Files.writeString(dir.resolve("readme.txt"), "not a zip");
        Files.writeString(dir.resolve("data.tar"), "also not a zip");

        assertEquals(Program.EXIT_OK, new Program(new String[] { dir.toString() }).run());
        assertIsValidTorrentZip(dir.resolve("valid.zip"));
    }
}
