package jtrrntzip;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jtrrntzip.supportedfiles.zipfile.ZipFile;

final class TestZipFixtures {

    private TestZipFixtures() {
    }

    static byte[] leCrc(final long crc) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) crc).array();
    }

    static byte[] crcOf(final byte[] content) {
        final var crc = new CRC32();
        crc.update(content);
        return leCrc(crc.getValue());
    }

    static void writeStoredZip(final File out, final Map<String, byte[]> entries) throws IOException {
        try (var zos = new ZipOutputStream(new FileOutputStream(out))) {
            zos.setMethod(ZipOutputStream.STORED);
            for (final var entry : entries.entrySet()) {
                final var content = entry.getValue();
                final var zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setSize(content.length);
                zipEntry.setCrc(crcByte(content));
                zos.putNextEntry(zipEntry);
                zos.write(content);
                zos.closeEntry();
            }
        }
    }

    static long crcByte(final byte[] content) {
        final var crc = new CRC32();
        crc.update(content);
        return crc.getValue();
    }

    static void writeZipWithOwnWriter(final File out, final List<String> sortedNames) throws IOException {
        try (final var zf = new ZipFile()) {
            if (zf.zipFileCreate(out) != ZipReturn.ZIPGOOD)
                throw new IOException("failed to create " + out);
            for (final var name : sortedNames) {
                writeOwnEntry(zf, name, new byte[0]);
            }
            zf.zipFileClose();
        }
    }

    static void writeOwnEntry(final ZipFile zf, final String name, final byte[] content) throws IOException {
        final var stream = new AtomicReference<OutputStream>();
        if (zf.zipFileOpenWriteStream(false, true, name, BigInteger.valueOf(content.length), (short) 8, stream) != ZipReturn.ZIPGOOD)
            throw new IOException("failed to open write stream for " + name);
        if (content.length > 0)
            stream.get().write(content);
        if (stream.get() instanceof DeflaterOutputStream ds)
            ds.finish();
        if (zf.zipFileCloseWriteStream(crcOf(content)) != ZipReturn.ZIPGOOD)
            throw new IOException("failed to close write stream for " + name);
    }

    static int findEocd(final byte[] data) {
        final var start = Math.max(0, data.length - 22 - 65535);
        for (var i = data.length - 22; i >= start; i--) {
            if (data[i] == 0x50 && data[i + 1] == 0x4b && data[i + 2] == 0x05 && data[i + 3] == 0x06)
                return i;
        }
        return -1;
    }

    static int readUShortLE(final byte[] b, final int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static long readUIntLE(final byte[] b, final int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    static void corruptFirstEntryCrcs(final Path file) throws IOException {
        final var data = java.nio.file.Files.readAllBytes(file);
        final var eocd = findEocd(data);
        if (eocd < 0)
            throw new IOException("no EOCD found in " + file);
        final var cdStart = (int) readUIntLE(data, eocd + 16);

        // flip the CRC of the first central directory record
        data[cdStart + 16] ^= 0x01;

        // flip the matching CRC in the local file header as well, so the zip stays internally consistent
        final var localStart = (int) readUIntLE(data, cdStart + 42);
        data[localStart + 14] ^= 0x01;

        java.nio.file.Files.write(file, data);
    }

    static void lowerCaseTorrentZipComment(final Path file) throws IOException {
        final var data = java.nio.file.Files.readAllBytes(file);
        final var eocd = findEocd(data);
        if (eocd < 0)
            throw new IOException("no EOCD found in " + file);
        final var commentLength = readUShortLE(data, eocd + 20);
        final var comment = new String(data, eocd + 22, commentLength, StandardCharsets.US_ASCII);
        if (!comment.startsWith("TORRENTZIPPED-"))
            throw new IOException("expected TORRENTZIPPED comment in " + file + " but found '" + comment + "'");
        final var bytes = comment.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, data, eocd + 22, commentLength);
        java.nio.file.Files.write(file, data);
    }

    static byte[] extractStoredEntry(final Path file) throws IOException {
        // extract the first entry assuming a stored single entry zip built by writeStoredZip
        final var data = java.nio.file.Files.readAllBytes(file);
        final var eocd = findEocd(data);
        final var cdStart = (int) readUIntLE(data, eocd + 16);
        final var size = (int) readUIntLE(data, cdStart + 24);
        @SuppressWarnings("unused")
        final var nameLength = readUShortLE(data, cdStart + 28);
        @SuppressWarnings("unused")
        final var extraLength = readUShortLE(data, cdStart + 30);
        final var localStart = (int) readUIntLE(data, cdStart + 42);
        var pos = localStart + 30;
        pos += readUShortLE(data, localStart + 26); // file name length
        pos += readUShortLE(data, localStart + 28); // extra field length
        return Arrays.copyOfRange(data, pos, pos + size);
    }

    static void storeZip(final Path target, final Map<String, String> entries) throws IOException {
        try (var zos = new ZipOutputStream(new FileOutputStream(target.toFile()))) {
            zos.setMethod(ZipOutputStream.DEFLATED);
            for (final var entry : entries.entrySet()) {
                final var zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                zos.putNextEntry(zipEntry);
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }
}
