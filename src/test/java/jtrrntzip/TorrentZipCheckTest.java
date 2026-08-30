package jtrrntzip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
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
        assertEquals("a.txt", zippedFiles.get(0).name());
        assertEquals("b.txt", zippedFiles.get(1).name());
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
        assertEquals("a.txt", zippedFiles.get(0).name());
        assertEquals("z.txt", zippedFiles.get(1).name());
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

    @Test
    void unsortedInputIsSortedExactlyLikeTheSharedComparator() {
        final var names = List.of("m.txt", "B.txt", "a.txt", "zz.dat", "b.dat", "Q", "q2", "ac", "ab");
        final List<ZippedFile> zippedFiles = new ArrayList<>();
        for (final var name : names)
            zippedFiles.add(zippedFile(name, 1, 1));

        final var expected = new ArrayList<>(zippedFiles);
        expected.sort(Comparator.comparing(ZippedFile::name, TorrentZipCheck::trrntZipStringCompare));

        final var status = TorrentZipCheck.checkZipFiles(zippedFiles, new DummyLogCallback());

        assertTrue(status.contains(TrrntZipStatus.UNSORTED), "unsorted input must be flagged");
        assertEquals(expected.size(), zippedFiles.size());
        for (var i = 0; i < expected.size(); i++)
            assertEquals(expected.get(i).name(), zippedFiles.get(i).name(),
                    "order must match the shared comparator at index " + i);
    }

    @Test
    void alreadySortedInputKeepsItsOrderWithoutUnsortedFlag() {
        final var names = List.of("a.txt", "B.txt", "b.txt", "z.txt");
        final List<ZippedFile> zippedFiles = new ArrayList<>();
        for (final var name : names)
            zippedFiles.add(zippedFile(name, 1, 1));

        final var status = TorrentZipCheck.checkZipFiles(zippedFiles, new DummyLogCallback());

        assertFalse(status.contains(TrrntZipStatus.UNSORTED), "sorted input must not be flagged");
        assertEquals(names.size(), zippedFiles.size());
        for (var i = 0; i < names.size(); i++)
            assertEquals(names.get(i), zippedFiles.get(i).name());
    }

    @Test
    void foldEqualNamesKeepTheirRelativeOrder() {
        final List<ZippedFile> zippedFiles = new ArrayList<>();
        zippedFiles.add(zippedFile("AB", 1, 1));
        zippedFiles.add(zippedFile("ab", 2, 1));
        zippedFiles.add(zippedFile("c", 3, 1));

        final var status = TorrentZipCheck.checkZipFiles(zippedFiles, new DummyLogCallback());

        assertEquals("AB", zippedFiles.get(0).name(), "fold-equal names must keep their original relative order");
        assertEquals("ab", zippedFiles.get(1).name());
    }

    @Test
    void unnecessaryDirectoryEntryPredicateMatchesTheValidationRule() {
        assertTrue(TorrentZipCheck.isUnnecessaryDirectoryEntry("dir/", "dir/file.txt"));
        assertTrue(TorrentZipCheck.isUnnecessaryDirectoryEntry("DIR/", "dir/file.txt"),
                "the marker matches its directory using the folded compare like the reopen check");
        assertFalse(TorrentZipCheck.isUnnecessaryDirectoryEntry("dir/", "other/file.txt"));
        assertFalse(TorrentZipCheck.isUnnecessaryDirectoryEntry("dir/", "dir"));
        assertFalse(TorrentZipCheck.isUnnecessaryDirectoryEntry("file.txt", "file.txt/child"));
    }

    @Test
    void checkZipFilesRemovesDirectoryMarkersExactlyLikeThePredicate() {
        final List<ZippedFile> zippedFiles = new ArrayList<>();
        zippedFiles.add(zippedFile("dir/", 0, 0));
        zippedFiles.add(zippedFile("dir/file.txt", 1, 5));
        zippedFiles.add(zippedFile("keep/", 0, 0));

        final var status = TorrentZipCheck.checkZipFiles(zippedFiles, new DummyLogCallback());

        assertTrue(status.contains(TrrntZipStatus.EXTRADIRECTORYENTRIES));
        assertEquals(2, zippedFiles.size());
        assertEquals("dir/file.txt", zippedFiles.get(0).name());
        assertEquals("keep/", zippedFiles.get(1).name());
    }

    private static ZippedFile zippedFile(final String name, final int crc, final long size) {
        return new ZippedFile(0, name, size, crc);
    }
}
