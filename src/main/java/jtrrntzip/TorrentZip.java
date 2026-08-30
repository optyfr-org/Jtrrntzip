package jtrrntzip;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import jtrrntzip.supportedfiles.ICompress;
import jtrrntzip.supportedfiles.zipfile.ZipFile;

public final class TorrentZip
{
	private final LogCallback statusLogCallBack;
	private final TorrentZipOptions options;

	private final byte[] buffer;

	public TorrentZip(final LogCallback statusLogCallBack, final TorrentZipOptions options)
	{
		this.statusLogCallBack = statusLogCallBack;
		this.options = options;
		buffer = new byte[64 * 1024];
	}

	private record OpenedZip(EnumSet<TrrntZipStatus> status, ICompress zip)
	{
	}

	public final Set<TrrntZipStatus> process(final File f) throws IOException
	{
		if(statusLogCallBack.isVerboseLogging())
			statusLogCallBack.statusLogCallBack(""); //$NON-NLS-1$

		statusLogCallBack.statusLogCallBack(f.getName() + " - "); //$NON-NLS-1$

		// First open the zip file, and fail out if it is corrupt.

		final OpenedZip opened = openZip(f);
		final var tzs = opened.status();
		// this will return ValidTrrntZip or CorruptZip.

		if(tzs.contains(TrrntZipStatus.CORRUPTZIP))
		{
			statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZip.ZipFileCorrupt")); //$NON-NLS-1$
			return tzs;
		}

		// the zip file may have found a valid trrntzip header, but we now check that all the file info
		// is actually valid, and may invalidate it being a valid trrntzip if any problem is found.

		final List<ZippedFile> zippedFiles = readZipContent(opened.zip());
		tzs.addAll(TorrentZipCheck.checkZipFiles(zippedFiles,statusLogCallBack));

		// if tza is now just 'ValidTrrntzip' the it is fully valid, and nothing needs to be done to it.

		if(tzs.contains(TrrntZipStatus.VALIDTRRNTZIP) && !options.isForceRezip())
		{
			statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZip.SkippingFile")); //$NON-NLS-1$
			opened.zip().zipFileClose();
			return tzs;
		}
		if(options.isCheckOnly())
		{
			statusLogCallBack.statusLogCallBack(tzs.toString());
			opened.zip().zipFileClose();
			return tzs;
		}
		// differing duplicate entries mark the zip corrupt, a rebuild cannot fix them
		if(tzs.contains(TrrntZipStatus.CORRUPTZIP))
		{
			statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZip.ZipFileCorrupt")); //$NON-NLS-1$
			opened.zip().zipFileClose();
			return tzs;
		}
		statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZip.TorrentZipping")); //$NON-NLS-1$
		final Set<TrrntZipStatus> rebuilt = TorrentZipRebuild.reZipFiles(zippedFiles, opened.zip(), buffer, statusLogCallBack);
		if(rebuilt.contains(TrrntZipStatus.CORRUPTZIP))
			statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZip.ZipFileCorrupt")); //$NON-NLS-1$
		return rebuilt;
	}

	private final OpenedZip openZip(final File f) throws IOException
	{
		final ICompress zipFile = new ZipFile();

		final ZipReturn zr = zipFile.zipFileOpen(f, f.lastModified(), true);
		if(zr != ZipReturn.ZIPGOOD)
		{
			return new OpenedZip(EnumSet.of(TrrntZipStatus.CORRUPTZIP), zipFile);
		}

		final EnumSet<TrrntZipStatus> tzStatus = EnumSet.noneOf(TrrntZipStatus.class);

		// first check if the file is a trrntip files
		if(zipFile.zipStatus().contains(ZipStatus.TRRNTZIP))
			tzStatus.add(TrrntZipStatus.VALIDTRRNTZIP);

		return new OpenedZip(tzStatus, zipFile);
	}

	private final List<ZippedFile> readZipContent(final ICompress zipFile)
	{
		final List<ZippedFile> zippedFiles = new ArrayList<>();
		for(var i = 0; i < zipFile.localFilesCount(); i++)
		{
			final int ii = i;
			final var zf = new ZippedFile();
			zf.setIndex(ii);
			zf.setName(zipFile.filename(ii));
			zf.setCRC(zipFile.crc32(ii));
			zf.setSize(zipFile.uncompressedSize(ii));
			zippedFiles.add(zf);
		}
		return zippedFiles;
	}

}
