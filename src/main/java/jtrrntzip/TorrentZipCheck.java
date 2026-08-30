package jtrrntzip;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Validation of the entries of an archive against the torrentzip rules, plus
 * repair of the fixable violations in memory.
 *
 * <p>The four torrentzip rules checked here are:</p>
 * <ol>
 * <li>entry names use {@code /} as the directory separator,</li>
 * <li>entry names are sorted with a lower case ASCII compare,</li>
 * <li>directory marker entries only exist for empty directories,</li>
 * <li>entries are unique.</li>
 * </ol>
 * <p>Most violations are only a matter of representation, so the corrections
 * are applied directly to the passed entry list and a matching
 * {@link TrrntZipStatus} is returned to request a rebuild. Identical duplicate
 * entries are dropped here as well. Conflicting duplicates, entries with the
 * same name but differing content, cannot be repaired: they add
 * {@link TrrntZipStatus#CORRUPTZIP} so the caller skips the rebuild.</p>
 */
public final class TorrentZipCheck {
    private TorrentZipCheck() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * The sort order of the torrentzip format, see
     * {@link #trrntZipStringCompare(String, String)}.
     */
    private static final Comparator<ZippedFile> TRRNT_ZIP_NAME_ORDER = Comparator.comparing(ZippedFile::name, TorrentZipCheck::trrntZipStringCompare);

    /**
     * Checks the entries of an archive against the torrentzip rules and
     * repairs the fixable violations directly in the list.
     *
     * @param zippedFiles
     *            the archive entries, may be reordered and pruned by the
     *            checks
     * @param statusLogCallBack
     *            receives verbose messages about the violations found
     * @return the rule violations found, empty when every rule is satisfied
     */
    public static Set<TrrntZipStatus> checkZipFiles(final List<ZippedFile> zippedFiles, final LogCallback statusLogCallBack) {
        final EnumSet<TrrntZipStatus> tzStatus = EnumSet.noneOf(TrrntZipStatus.class);
        fixBackslashSeparators(zippedFiles, tzStatus, statusLogCallBack);
        sortIfNeeded(zippedFiles, tzStatus, statusLogCallBack);
        removeUnneededDirectoryMarkers(zippedFiles, tzStatus, statusLogCallBack);
        processDuplicates(zippedFiles, tzStatus, statusLogCallBack);
        return tzStatus;
    }

    // ***************************** RULE 1 *************************************
    // Directory separator should be a '/' a '\' is invalid and should be replaced with '/'
    //
    // check if any '\' = 92 need converted to '/' = 47
    // this needs done before the sort, so that the sort is correct.
    // return BadDirectorySeparator if errors found.
    private static void fixBackslashSeparators(final List<ZippedFile> zippedFiles, final EnumSet<TrrntZipStatus> tzStatus, final LogCallback statusLogCallBack) {
        var errorFound = false;
        for (var i = 0; i < zippedFiles.size(); i++) {
            final ZippedFile t = zippedFiles.get(i);
            if (t.name().indexOf('\\') < 0)
                continue;
            zippedFiles.set(i, t.withName(t.name().replace('\\', '/')));
            tzStatus.add(TrrntZipStatus.BADDIRECTORYSEPARATOR);
            if (!errorFound && statusLogCallBack.isVerboseLogging()) {
                errorFound = true;
                statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZipCheck.IncorrectDirectorySeparatoreFound")); //$NON-NLS-1$
            }
        }
    }

    // ***************************** RULE 2 *************************************
    // All Files in a torrentzip should be sorted with a lower case file compare.
    //
    // a single detection pass decides if the list is unsorted, the actual
    // ordering is then done in one O(n log n) sort instead of bubble passes.
    // return Unsorted if errors found.
    private static void sortIfNeeded(final List<ZippedFile> zippedFiles, final EnumSet<TrrntZipStatus> tzStatus, final LogCallback statusLogCallBack) {
        for (var i = 0; i < zippedFiles.size() - 1; i++) {
            if (trrntZipStringCompare(zippedFiles.get(i).name(), zippedFiles.get(i + 1).name()) > 0) {
                zippedFiles.sort(TRRNT_ZIP_NAME_ORDER);
                tzStatus.add(TrrntZipStatus.UNSORTED);
                if (statusLogCallBack.isVerboseLogging())
                    statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZipCheck.IncorrectFileOrderFound")); //$NON-NLS-1$
                return;
            }
        }
    }

    // ***************************** RULE 3 *************************************
    // Directory marker files are only needed if they are empty directories.
    //
    // now that the files are sorted correctly, we can see if there are unneeded
    // directory files, by first finding directory files (these end in a '\' character ascii 92)
    // and then checking if the next file is a file in that found directory.
    // If we find this 2 entry pattern (directory followed by file in that directory)
    // then the directory entry should not be present and the torrentzip is incorrect.
    // return ExtraDirectoryEnteries if error is found.
    private static void removeUnneededDirectoryMarkers(final List<ZippedFile> zippedFiles, final EnumSet<TrrntZipStatus> tzStatus, final LogCallback statusLogCallBack) {
        var errorFound = false;
        for (var i = zippedFiles.size() - 2; i >= 0; i--) {
            if (!isUnnecessaryDirectoryEntry(zippedFiles.get(i).name(), zippedFiles.get(i + 1).name()))
                continue;

            // we found an incorrect directory so remove it.
            zippedFiles.remove(i);
            tzStatus.add(TrrntZipStatus.EXTRADIRECTORYENTRIES);
            if (!errorFound && statusLogCallBack.isVerboseLogging()) {
                errorFound = true;
                statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZipCheck.UnneededDirectoryRecordsFound")); //$NON-NLS-1$
            }
        }
    }

    // ***************************** RULE 4 *************************************
    // Diverges from the reference trrntzipDN, which only reports duplicates here:
    // keep one entry and let the caller rebuild when name, CRC and size are identical,
    // differing duplicates mark the zip corrupt so the caller skips the rebuild.
    private static void processDuplicates(final List<ZippedFile> zippedFiles, final EnumSet<TrrntZipStatus> tzStatus, final LogCallback statusLogCallBack) {
        var errorFound = false;
        for (var i = zippedFiles.size() - 2; i >= 0; i--) {
            final var a = zippedFiles.get(i);
            final var b = zippedFiles.get(i + 1);
            if (!a.name().equals(b.name()))
                continue;

            tzStatus.add(TrrntZipStatus.REPEATFILESFOUND);
            if (!errorFound && statusLogCallBack.isVerboseLogging()) {
                errorFound = true;
                statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZipCheck.DuplicateFileEntriesFound")); //$NON-NLS-1$
            }

            if (a.crc() == b.crc() && a.size() == b.size()) {
                zippedFiles.remove(i + 1);
            } else {
                tzStatus.add(TrrntZipStatus.CORRUPTZIP);
            }
        }
    }

    /**
     * Tells if the given directory marker entry is unneeded because the
     * archive also contains an entry in that directory.
     *
     * <p>The check relies on the list being in torrentzip sort order: a
     * marker entry is unnecessary exactly when the entry following it lives
     * inside the marked directory.</p>
     *
     * @param directoryEntry
     *            the name of the directory marker entry, expected to end in a
     *            slash
     * @param nextEntry
     *            the name of the entry following the marker in sort order
     * @return {@code true} when the marker entry is not needed
     */
    public static boolean isUnnecessaryDirectoryEntry(final String directoryEntry, final String nextEntry) {
        return directoryEntry.charAt(directoryEntry.length() - 1) == '/' && nextEntry.length() > directoryEntry.length()
                && trrntZipStringCompare(directoryEntry, nextEntry.substring(0, directoryEntry.length())) == 0;
    }

    /**
     * Compares two entry names the way the torrentzip format requires.
     *
     * <p>The comparison walks both names character by character and folds
     * ASCII upper case letters to lower case before comparing; characters
     * outside of ASCII compare by their raw character value, so this is an
     * ASCII fold and intentionally not a full Unicode case folding. When one
     * name is a prefix of the other, the shorter name sorts first.</p>
     *
     * @param string1
     *            the first name
     * @param string2
     *            the second name
     * @return a negative number, zero or a positive number as the first name
     *         sorts before, equal to or after the second name
     */
    public static int trrntZipStringCompare(final String string1, final String string2) {
        final char[] bytes1 = string1.toCharArray();
        final char[] bytes2 = string2.toCharArray();

        var pos1 = 0;
        var pos2 = 0;
        var result = 0;

        while (result == 0 && pos1 < bytes1.length && pos2 < bytes2.length)
        {
            final var byte1 = asciiLowerFold(bytes1[pos1++]);
            final var byte2 = asciiLowerFold(bytes2[pos2++]);
            result = byte1 - byte2;
        }

        if (result == 0) {
            if (pos1 < bytes1.length)
                result = 1;
            if (pos2 < bytes2.length)
                result -= 1;
        }
        return result;
    }

    private static char asciiLowerFold(final char value) {
        return value >= 65 && value <= 90 ? (char) (value + 0x20) : value;
    }

}
