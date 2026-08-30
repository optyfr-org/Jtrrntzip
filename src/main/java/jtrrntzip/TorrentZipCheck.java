package jtrrntzip;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class TorrentZipCheck
{
	private TorrentZipCheck()
	{
		throw new IllegalStateException("Utility class");
	}

	private static final Comparator<ZippedFile> TRRNT_ZIP_NAME_ORDER = Comparator.comparing(ZippedFile::getName, TorrentZipCheck::trrntZipStringCompare);

	public static Set<TrrntZipStatus> checkZipFiles(final List<ZippedFile> zippedFiles, final LogCallback StatusLogCallBack)
	{
		final EnumSet<TrrntZipStatus> tzStatus = EnumSet.noneOf(TrrntZipStatus.class);

		// ***************************** RULE 1 *************************************
		// Directory separator should be a '/' a '\' is invalid and should be replaced with '/'
		//
		// check if any '\' = 92 need converted to '/' = 47
		// this needs done before the sort, so that the sort is correct.
		// return BadDirectorySeparator if errors found.
		var error1 = false;
		for(final ZippedFile t : zippedFiles)
		{
			final char[] bytes = t.getName().toCharArray();
			var fixDir = false;
			for(var j = 0; j < bytes.length; j++)
			{
				if(bytes[j] != '\\')
					continue;
				fixDir = true;
				bytes[j] = '/';
				tzStatus.add(TrrntZipStatus.BADDIRECTORYSEPARATOR);
				if(!error1 && StatusLogCallBack.isVerboseLogging())
				{
					error1 = true;
					StatusLogCallBack.statusLogCallBack(Messages.getString("TorrentZipCheck.IncorrectDirectorySeparatoreFound")); //$NON-NLS-1$
				}
			}
			if(fixDir)
				t.setName(new String(bytes));
		}

		// ***************************** RULE 2 *************************************
		// All Files in a torrentzip should be sorted with a lower case file compare.
		//
		// a single detection pass decides if the list is unsorted, the actual
		// ordering is then done in one O(n log n) sort instead of bubble passes.
		// return Unsorted if errors found.
		var unsorted = false;
		for(var i = 0; i < zippedFiles.size() - 1; i++)
		{
			if(trrntZipStringCompare(zippedFiles.get(i).getName(), zippedFiles.get(i + 1).getName()) > 0)
			{
				unsorted = true;
				break;
			}
		}
		if(unsorted)
		{
			zippedFiles.sort(TRRNT_ZIP_NAME_ORDER);
			tzStatus.add(TrrntZipStatus.UNSORTED);
			if(StatusLogCallBack.isVerboseLogging())
			{
				StatusLogCallBack.statusLogCallBack(Messages.getString("TorrentZipCheck.IncorrectFileOrderFound")); //$NON-NLS-1$
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
		var error3 = false;
		for(var i = 0; i < zippedFiles.size() - 1; i++)
		{
			if(!isUnnecessaryDirectoryEntry(zippedFiles.get(i).getName(), zippedFiles.get(i + 1).getName()))
				continue;

			// we found an incorrect directory so remove it.
			zippedFiles.remove(i);
			tzStatus.add(TrrntZipStatus.EXTRADIRECTORYENTRIES);
			if(!error3 && StatusLogCallBack.isVerboseLogging())
			{
				error3 = true;
				StatusLogCallBack.statusLogCallBack(Messages.getString("TorrentZipCheck.UnneededDirectoryRecordsFound")); //$NON-NLS-1$
			}

			i--;
		}

		// ***************************** RULE 4 *************************************
		// Diverges from the reference trrntzipDN, which only reports duplicates here:
		// keep one entry and let the caller rebuild when name, CRC and size are identical,
		// differing duplicates mark the zip corrupt so the caller skips the rebuild.
		var error4 = false;
		for(var i = 0; i < zippedFiles.size() - 1; i++)
		{
			final var a = zippedFiles.get(i);
			final var b = zippedFiles.get(i + 1);
			if(!a.getName().equals(b.getName()))
				continue;

			tzStatus.add(TrrntZipStatus.REPEATFILESFOUND);
			if(!error4 && StatusLogCallBack.isVerboseLogging())
			{
				error4 = true;
				StatusLogCallBack.statusLogCallBack(Messages.getString("TorrentZipCheck.DuplicateFileEntriesFound")); //$NON-NLS-1$
			}

			if(a.getCrc() == b.getCrc() && a.getSize() == b.getSize())
			{
				zippedFiles.remove(i + 1);
				i--;
			}
			else
			{
				tzStatus.add(TrrntZipStatus.CORRUPTZIP);
			}
		}

		return tzStatus;
	}

	public static boolean isUnnecessaryDirectoryEntry(final String directoryEntry, final String nextEntry)
	{
		return directoryEntry.charAt(directoryEntry.length() - 1) == '/' && nextEntry.length() > directoryEntry.length() && trrntZipStringCompare(directoryEntry, nextEntry.substring(0, directoryEntry.length())) == 0;
	}

	// perform an ascii based lower case string file compare
	public static int trrntZipStringCompare(final String string1, final String string2)
	{
		final char[] bytes1 = string1.toCharArray();
		final char[] bytes2 = string2.toCharArray();

		var pos1 = 0;
		var pos2 = 0;

		for (;;)
		{
			if (pos1 == bytes1.length)
				return ((pos2 == bytes2.length) ? 0 : -1);
			if (pos2 == bytes2.length)
				return 1;

			var byte1 = bytes1[pos1++];
			var byte2 = bytes2[pos2++];

			if (byte1 >= 65 && byte1 <= 90) byte1 += 0x20;
			if (byte2 >= 65 && byte2 <= 90) byte2 += 0x20;

			if (byte1 < byte2)
				return -1;
			if (byte1 > byte2)
				return 1;
		}
	}

}
