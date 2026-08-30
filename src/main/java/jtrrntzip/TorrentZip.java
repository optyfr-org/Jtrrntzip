package jtrrntzip;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
		buffer = new byte[8 * 1024];
	}

	public final Set<TrrntZipStatus> process(final File f) throws IOException
	{
		if(statusLogCallBack.isVerboseLogging())
			statusLogCallBack.statusLogCallBack(""); //$NON-NLS-1$

		statusLogCallBack.statusLogCallBack(f.getName() + " - "); //$NON-NLS-1$

		// First open the zip file, and fail out if it is corrupt.

		final AtomicReference<ICompress> zipFile = new AtomicReference<>();
		final EnumSet<TrrntZipStatus> tzs = openZip(f, zipFile);
		// this will return ValidTrrntZip or CorruptZip.

		if(tzs.contains(TrrntZipStatus.CORRUPTZIP))
		{
			statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZip.ZipFileCorrupt")); //$NON-NLS-1$
			return tzs;
		}

		// the zip file may have found a valid trrntzip header, but we now check that all the file info
		// is actually valid, and may invalidate it being a valid trrntzip if any problem is found.

		final List<ZippedFile> zippedFiles = readZipContent(zipFile.get());
		tzs.addAll(TorrentZipCheck.checkZipFiles(zippedFiles,statusLogCallBack));

		// if tza is now just 'ValidTrrntzip' the it is fully valid, and nothing needs to be done to it.

		if(tzs.contains(TrrntZipStatus.VALIDTRRNTZIP) && !options.isForceRezip())
		{
			statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZip.SkippingFile")); //$NON-NLS-1$
			zipFile.get().zipFileClose();
			return tzs;
		}
		if(options.isCheckOnly())
		{
			statusLogCallBack.statusLogCallBack(tzs.toString());
			zipFile.get().zipFileClose();
			return tzs;
		}
		// differing duplicate entries mark the zip corrupt, a rebuild cannot fix them
		if(tzs.contains(TrrntZipStatus.CORRUPTZIP))
		{
			statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZip.ZipFileCorrupt")); //$NON-NLS-1$
			zipFile.get().zipFileClose();
			return tzs;
		}
		statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZip.TorrentZipping")); //$NON-NLS-1$
		final Set<TrrntZipStatus> rebuilt = TorrentZipRebuild.reZipFiles(zippedFiles, zipFile.get(), buffer, statusLogCallBack);
		if(rebuilt.contains(TrrntZipStatus.CORRUPTZIP))
			statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZip.ZipFileCorrupt")); //$NON-NLS-1$
		return rebuilt;
	}

	private final EnumSet<TrrntZipStatus> openZip(final File f, final AtomicReference<ICompress> zipFile) throws IOException
	{
		zipFile.set(new ZipFile());

		final ZipReturn zr = zipFile.get().zipFileOpen(f, f.lastModified(), true);
		if(zr != ZipReturn.ZIPGOOD)
		{
			return EnumSet.of(TrrntZipStatus.CORRUPTZIP);
		}

		final EnumSet<TrrntZipStatus> tzStatus = EnumSet.noneOf(TrrntZipStatus.class);

		// first check if the file is a trrntip files
		if(zipFile.get().zipStatus().contains(ZipStatus.TRRNTZIP))
			tzStatus.add(TrrntZipStatus.VALIDTRRNTZIP);

		return tzStatus;
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
