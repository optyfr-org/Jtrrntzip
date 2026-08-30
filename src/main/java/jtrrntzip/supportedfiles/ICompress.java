package jtrrntzip.supportedfiles;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import jtrrntzip.ZipOpenType;
import jtrrntzip.ZipReturn;
import jtrrntzip.ZipStatus;

import java.util.EnumSet;

public interface ICompress extends Closeable {

	record OpenedReadStream(ZipReturn status, InputStream stream, long size, int compressionMethod) {
		public static OpenedReadStream failed(final ZipReturn status) {
			return new OpenedReadStream(status, null, 0, 0);
		}
	}

	record OpenedWriteStream(ZipReturn status, OutputStream stream) {
		public static OpenedWriteStream failed(final ZipReturn status) {
			return new OpenedWriteStream(status, null);
		}
	}

	int localFilesCount();

	String filename(int i);

	long uncompressedSize(int i);

	byte[] crc32(int i);

	ZipReturn fileStatus(int i);

	ZipOpenType zipOpen();

	ZipReturn zipFileOpen(File newFilename, long timestamp, boolean readHeaders) throws IOException;

	void zipFileClose() throws IOException;

	OpenedReadStream zipFileOpenReadStream(int index, boolean raw) throws IOException;

	OpenedWriteStream zipFileOpenWriteStream(boolean raw, boolean trrntzip, String filename, long uncompressedSize, short compressionMethod) throws IOException;

	ZipReturn zipFileCloseReadStream() throws IOException;

	EnumSet<ZipStatus> zipStatus();

	String zipFilename();

	long timeStamp();

	ZipReturn zipFileCreate(File newFilename) throws IOException;

	ZipReturn zipFileCloseWriteStream(byte[] crc32) throws IOException;

	void zipFileCloseFailed() throws IOException;
}
