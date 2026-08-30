package jtrrntzip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TorrentZipCheckTest {

    @Test
    void comparatorFoldsOnlyAsciiUpperCases() {
        assertEquals(0, TorrentZipCheck.trrntZipStringCompare("abc", "ABC"));
        assertTrue(TorrentZipCheck.trrntZipStringCompare("a.txt", "B.txt") < 0);
        assertTrue(TorrentZipCheck.trrntZipStringCompare("B.txt", "a.txt") > 0);
        assertTrue(TorrentZipCheck.trrntZipStringCompare("b.txt", "A.txt") > 0);
        assertTrue(TorrentZipCheck.trrntZipStringCompare("abc", "abcd") < 0);
        assertTrue(TorrentZipCheck.trrntZipStringCompare("abcd", "abc") > 0);
    }

    @Test
    void comparatorDoesNotFoldNonAsciiCharacters() {
        // 'KELVIN SIGN' (U+212A) folds to 'k' in a full unicode case fold but stays raw here,
        // so 'Ä' (U+00C4) must be ordered first by the reference comparator
        final var kelvin = "\u212A.txt";
        final var aDiaeresis = "\u00C4.txt";
        assertTrue(TorrentZipCheck.trrntZipStringCompare(aDiaeresis, kelvin) < 0);
        assertTrue(TorrentZipCheck.trrntZipStringCompare(kelvin, aDiaeresis) > 0);
        // the full-unicode fold used before BUG-8 ordered these the other way around
        assertTrue(aDiaeresis.compareToIgnoreCase(kelvin) > 0);
    }

    @Test
    void identicalDuplicatesKeepOneEntry() {
        final List<ZippedFile> zippedFiles = new ArrayList<>();
        zippedFiles.add(zippedFile("a.txt", 0x11223344, 10));
        zippedFiles.add(zippedFile("a.txt", 0x11223344, 10));
        zippedFiles.add(zippedFile("b.txt", 0x00000001, 5));

        final var status = TorrentZipCheck.checkZipFiles(zippedFiles, new DummyLogCallback());

        assertTrue(status.contains(TrrntZipStatus.REPEATFILESFOUND));
        assertFalse(status.contains(TrrntZipStatus.CORRUPTZIP));
        assertEquals(2, zippedFiles.size());
        assertEquals("a.txt", zippedFiles.get(0).getName());
        assertEquals("b.txt", zippedFiles.get(1).getName());
    }

    @Test
    void conflictingDuplicatesMarkTheZipCorrupt() {
        final List<ZippedFile> zippedFiles = new ArrayList<>();
        zippedFiles.add(zippedFile("a.txt", 0x00000001, 10));
        zippedFiles.add(zippedFile("a.txt", 0x00000002, 10));

        final var status = TorrentZipCheck.checkZipFiles(zippedFiles, new DummyLogCallback());

        assertTrue(status.contains(TrrntZipStatus.REPEATFILESFOUND));
        assertTrue(status.contains(TrrntZipStatus.CORRUPTZIP), "differing duplicates must corrupt the zip");
        assertEquals(2, zippedFiles.size(), "conflicting duplicates must not be merged");
    }

    @Test
    void tripleIdenticalDuplicatesCollapseToASingleEntry() {
        final List<ZippedFile> zippedFiles = new ArrayList<>();
        zippedFiles.add(zippedFile("a.txt", 7, 3));
        zippedFiles.add(zippedFile("a.txt", 7, 3));
        zippedFiles.add(zippedFile("a.txt", 7, 3));
        zippedFiles.add(zippedFile("z.txt", 1, 1));

        final var status = TorrentZipCheck.checkZipFiles(zippedFiles, new DummyLogCallback());

        assertTrue(status.contains(TrrntZipStatus.REPEATFILESFOUND));
        assertFalse(status.contains(TrrntZipStatus.CORRUPTZIP));
        assertEquals(2, zippedFiles.size());
        assertEquals("a.txt", zippedFiles.get(0).getName());
        assertEquals("z.txt", zippedFiles.get(1).getName());
    }

    @Test
    void sameSizeDifferentCrcDuplicatesConflict() {
        final List<ZippedFile> zippedFiles = new ArrayList<>();
        zippedFiles.add(zippedFile("a.txt", 7, 3));
        zippedFiles.add(zippedFile("a.txt", 8, 3));

        final var status = TorrentZipCheck.checkZipFiles(zippedFiles, new DummyLogCallback());

        assertTrue(status.contains(TrrntZipStatus.REPEATFILESFOUND));
        assertTrue(status.contains(TrrntZipStatus.CORRUPTZIP));
    }

    private static ZippedFile zippedFile(final String name, final int crc, final long size) {
        final var zippedFile = new ZippedFile();
        zippedFile.setName(name);
        zippedFile.setCRC(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc).array());
        zippedFile.setSize(BigInteger.valueOf(size));
        return zippedFile;
    }
}
