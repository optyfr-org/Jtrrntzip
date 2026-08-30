package jtrrntzip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the rebuild of archives into the torrentzip format and the
 * cleanup performed when a rebuild fails or runs out of data.
 */
@DisplayName("Tests for the torrentzip rebuild")
class TorrentZipRebuildTest {

    @TempDir
    Path tempDir;

    /** An input stream that hands out a single byte per read call, however large the buffer is. */
    private static final class OneByteAtATimeInputStream extends InputStream {
        private final byte[] data;
        private int pos;

        OneByteAtATimeInputStream(final byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return pos < data.length ? (data[pos++] & 0xff) : -1;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) {
            if (pos >= data.length)
                return -1;
            b[off] = data[pos++];
            return 1;
        }
    }

    /** copyFully keeps transferring the exact size when the input returns one byte per read. */
    @DisplayName("copyFully handles one-byte-at-a-time streams")
    @Test
    void copyFullyHandlesStreamsReturningOneByteAtATime() throws Exception {
        final var data = new byte[17 * 1024 + 3];
        for (var i = 0; i < data.length; i++)
            data[i] = (byte) (i * 31 + 7);
        final var buffer = new byte[8 * 1024];
        Arrays.fill(buffer, (byte) 0x5A);

        final var out = new ByteArrayOutputStream();
        assertTrue(TorrentZipRebuild.copyFully(new OneByteAtATimeInputStream(data), out, data.length, buffer),
                "copyFully must keep reading until the full size is transferred");
        assertArrayEquals(data, out.toByteArray());
    }

    /** An input that ends before the requested size is reported as corruption. */
    @DisplayName("copyFully treats an early EOF as corruption")
    @Test
    void copyFullyTreatsEofBeforeFullSizeAsCorruption() throws Exception {
        final var data = new byte[100];
        final var out = new ByteArrayOutputStream();
        assertFalse(TorrentZipRebuild.copyFully(new OneByteAtATimeInputStream(data), out, data.length + 1, new byte[8]),
                "a stream ending early must be reported as corruption");
    }

    /** A rebuild failing on corrupt CRCs leaves no tmp file behind. */
    @DisplayName("a failed rebuild cleans up the tmp file and unlocks the source")
    @Test
    void rebuildFailureCleansUpTmpFileAndUnlocksSource() throws Exception {
        final var source = tempDir.resolve("corrupt-crc.zip").toFile();
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("stored.bin", "test content for rebuild".getBytes(StandardCharsets.UTF_8));
        TestZipFixtures.writeStoredZip(source, entries);
        TestZipFixtures.corruptFirstEntryCrcs(source.toPath());

        final var tz = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, false));
        final var status = tz.process(source);

        assertTrue(status.contains(TrrntZipStatus.CORRUPTZIP), "expected corrupt status but got " + status);
        try (DirectoryStream<Path> tmps = Files.newDirectoryStream(tempDir, "*.tmp")) {
            assertFalse(tmps.iterator().hasNext(), "tmp file left behind after failed rebuild");
        }
        Files.delete(source.toPath());
    }

    /** A truncated archive is reported corrupt, left in place and unlocked. */
    @DisplayName("a truncated zip is reported corrupt and unlocked")
    @Test
    void truncatedZipIsReportedCorruptAndUnlocked() throws Exception {
        final var source = tempDir.resolve("truncated-entry.zip");
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("stored.bin", "0123456789".getBytes(StandardCharsets.US_ASCII));
        TestZipFixtures.writeStoredZip(source.toFile(), entries);

        final var data = Files.readAllBytes(source);
        Files.write(source, Arrays.copyOf(data, data.length - 10));

        final var tz = new TorrentZip(new DummyLogCallback(), new SimpleTorrentZipOptions(false, false));
        final var status = tz.process(source.toFile());

        assertTrue(status.contains(TrrntZipStatus.CORRUPTZIP), "expected corrupt status but got " + status);
        try (DirectoryStream<Path> tmps = Files.newDirectoryStream(tempDir, "*.tmp")) {
            assertFalse(tmps.iterator().hasNext(), "tmp file left behind after failed rebuild");
        }
        Files.delete(source);
    }
}
