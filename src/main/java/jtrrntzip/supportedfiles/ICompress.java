package jtrrntzip.supportedfiles;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

import jtrrntzip.ZipOpenType;
import jtrrntzip.ZipReturn;
import jtrrntzip.ZipStatus;

public interface ICompress extends Closeable {
	int localFilesCount();

	String filename(int i);

	BigInteger localHeader(int i);

	BigInteger uncompressedSize(int i);

	byte[] crc32(int i);

	ZipReturn fileStatus(int i);

	ZipOpenType zipOpen();

	ZipReturn zipFileOpen(File newFilename, long timestamp, boolean readHeaders) throws IOException;

	void zipFileClose() throws IOException;

	ZipReturn zipFileOpenWriteStream(boolean raw, boolean trrntzip, String filename, BigInteger uncompressedSize, short compressionMethod, AtomicReference<OutputStream> stream)
			throws IOException;

	ZipReturn zipFileCloseReadStream() throws IOException;

	void deepScan();

	EnumSet<ZipStatus> zipStatus();

	String zipFilename();

	long timeStamp();

	void zipFileAddDirectory() throws IOException;

	ZipReturn zipFileCreate(File newFilename) throws IOException;

	ZipReturn zipFileCloseWriteStream(byte[] crc32) throws IOException;

	ZipReturn zipFileRollBack() throws IOException;

	void zipFileCloseFailed() throws IOException;
}
