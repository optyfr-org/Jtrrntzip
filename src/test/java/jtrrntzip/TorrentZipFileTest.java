package jtrrntzip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import jtrrntzip.supportedfiles.zipfile.ZipFile;

class TorrentZipFileTest {

    private static final boolean DUMP_ENABLED = Boolean.getBoolean("jtrrntzip.test.dump");

    private static final Path DUMP_ROOT = Path.of("build", "dump");

    @TempDir
    Path tempDir;

    private static final List<String> TORRENT_ZIP_RESOURCES = List.of(
        "03A3D133F0BB34F8A7A1E18C30EE847F47A291F1.zip",
        "3ACF0BB6DE56430ADB6F78B6D4475DDD32827CE5.zip",
        "19F33A06D4B909FB3766B3846E44B0EB489D0097.zip",
        "0674D5755E93BBDC81D17B23AB06CEDA3DEE9AB7.zip",
        "bbcm.zip",
        "BFB5E4C92A25A560F5C8765A4D5DE6F2E3FB75B2.zip",
        "C6863CEC9A2488BCA3EA45BC9ACC274E8A225B48.zip",
        "cacb1f582c13d2d28b81470f3efc144b9732a7f0.zip",
        "cps1demo.zip",
        "E22A0E0EF7AC6E2B80048990FEEB8C8BD46D3333.zip",
        "F81F2CB938F55051164F6FA04BD094CC29885CDA.zip",
        "sample-1.zip",
        "sample-2.zip",
        "sample-3.zip",
        "sample-4.zip",
        "sample-5.zip"
    );

    static List<String> torrentZipResources() {
        return TORRENT_ZIP_RESOURCES;
    }

    @ParameterizedTest
    @MethodSource("torrentZipResources")
    void testZipFileOpensAsZipGood(String resourceName) throws IOException, URISyntaxException {
        URL resourceUrl = getClass().getResource("/" + resourceName);
        assertNotNull(resourceUrl, "Resource not found: " + resourceName);
        File zipFile = new File(resourceUrl.toURI());
        assertTrue(zipFile.exists(), "Zip file does not exist: " + zipFile);

        try (ZipFile zf = new ZipFile()) {  // note: ZipFile implements ICompress but no AutoCloseable? check
            ZipReturn zr = zf.zipFileOpen(zipFile, zipFile.lastModified(), true);
            assertEquals(ZipReturn.ZIPGOOD, zr, "Expected ZIPGOOD for " + resourceName + " but got " + zr + " (" + ZipFile.zipErrorMessageText(zr) + ")");
        }
    }

    @ParameterizedTest
    @MethodSource("torrentZipResources")
    void testOpensAsValidTorrentZip(String resourceName) throws IOException, URISyntaxException {
        URL resourceUrl = getClass().getResource("/" + resourceName);
        assertNotNull(resourceUrl, "Resource not found: " + resourceName);
        File zipFile = new File(resourceUrl.toURI());

        SimpleTorrentZipOptions options = new SimpleTorrentZipOptions(false, true);
        DummyLogCallback log = new DummyLogCallback();
        TorrentZip tz = new TorrentZip(log, options);
        var status = tz.process(zipFile);
        assertTrue(status.contains(TrrntZipStatus.VALIDTRRNTZIP), "Expected VALIDTRRNTZIP for " + resourceName + " but got " + status);
    }

    @ParameterizedTest
    @MethodSource("torrentZipResources")
    void testGoldenCorpusOrderIsStableUnderReferenceComparator(String resourceName) throws IOException, URISyntaxException {
        URL resourceUrl = getClass().getResource("/" + resourceName);
        assertNotNull(resourceUrl, "Resource not found: " + resourceName);
        File zipFile = new File(resourceUrl.toURI());

        jtrrntzip.supportedfiles.zipfile.ZipFile zf = new jtrrntzip.supportedfiles.zipfile.ZipFile();
        try {
            assertEquals(ZipReturn.ZIPGOOD, zf.zipFileOpen(zipFile, zipFile.lastModified(), true),
                    "Expected ZIPGOOD for " + resourceName);
            for (int i = 1; i < zf.localFilesCount(); i++) {
                String prev = zf.filename(i - 1);
                String next = zf.filename(i);
                assertTrue(TorrentZipCheck.trrntZipStringCompare(prev, next) < 0,
                        "Corpus order for " + resourceName + " must be ascending per the reference comparator: "
                                + prev + " !< " + next);
            }
        } finally {
            try { zf.zipFileClose(); zf.close(); } catch (Exception _) { /* ignore */ }
        }
    }

    @ParameterizedTest
    @MethodSource("torrentZipResources")
    void testUncompressRecompressToPlainThenToTzipAndCompare(String resourceName) throws Exception {
        URL resourceUrl = getClass().getResource("/" + resourceName);
        assertNotNull(resourceUrl, "Resource not found: " + resourceName);
        File originalTzip = new File(resourceUrl.toURI());
        assertTrue(originalTzip.exists());

        // unique work dir under @TempDir 
        String base = resourceName.replace(".zip", "").replaceAll("[^a-zA-Z0-9]", "_");
        Path workDir = tempDir.resolve(base);
        Files.createDirectories(workDir);

        // 1. uncompress (extract) the original tzip contents
        Path extractDir = workDir.resolve("extracted");
        extractZipToDir(originalTzip, extractDir);

        // 2. recompress the extracted files into a plain (non-torrent) zip
        File plainZip = workDir.resolve(base + "-plain.zip").toFile();
        createPlainZip(extractDir, plainZip);

        // 3. copy plain to work zip (will be overwritten by tzip conversion)
        File workZip = workDir.resolve(base + ".zip").toFile();
        Files.copy(plainZip.toPath(), workZip.toPath(), StandardCopyOption.REPLACE_EXISTING);

        // 4. convert the plain zip to tzip (in place via TorrentZip.process)
        SimpleTorrentZipOptions options = new SimpleTorrentZipOptions(false, false); // not checkOnly
        DummyLogCallback log = new DummyLogCallback();
        TorrentZip tz = new TorrentZip(log, options);
        var status = tz.process(workZip);
        assertTrue(status.contains(TrrntZipStatus.VALIDTRRNTZIP),
            "Conversion to tzip should succeed for " + resourceName + ", got " + status);

        File producedTzip = workZip;
        assertTrue(producedTzip.exists());

        // Dump central directories for investigation (persistent in build/)
        dumpCentralDirectoryComparison(originalTzip, producedTzip, base);

        // 5. compare original tzip vs produced tzip: size, structure, comment, etc.
        assertTzipFilesEquivalent(originalTzip, producedTzip);
    }

    private void extractZipToDir(File zipFile, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        // First, get the count using a probe (full open succeeds for these files)
        int count;
        jtrrntzip.supportedfiles.zipfile.ZipFile probe = new jtrrntzip.supportedfiles.zipfile.ZipFile();
        try {
            ZipReturn openRet = probe.zipFileOpen(zipFile, zipFile.lastModified(), true);
            if (openRet != ZipReturn.ZIPGOOD) {
                throw new IOException("Failed to open zip for extract: " + openRet + " for " + zipFile);
            }
            count = probe.localFilesCount();
        } finally {
            try { probe.zipFileClose(); probe.close(); } catch (Exception _) { /* ignore */}
        }

        // Extract each entry using a *fresh* ZipFile instance per entry.
        // This avoids side effects from ZipFileOpenReadStreamQuick (which clears localFiles).
        // We use the normal openReadStream after each fresh open.
        for (int i = 0; i < count; i++) {
            jtrrntzip.supportedfiles.zipfile.ZipFile zf = new jtrrntzip.supportedfiles.zipfile.ZipFile();
            try {
                ZipReturn openRet = zf.zipFileOpen(zipFile, zipFile.lastModified(), true);
                if (openRet != ZipReturn.ZIPGOOD) {
                    throw new IOException("Failed to open zip for extract entry " + i + ": " + openRet);
                }

                String name = zf.filename(i).replace('\\', '/');
                Path outPath = targetDir.resolve(name);

                if (name.endsWith("/")) {
                    Files.createDirectories(outPath);
                    continue;
                }

                Files.createDirectories(outPath.getParent());

                AtomicReference<InputStream> readStream = new AtomicReference<>();
                AtomicReference<BigInteger> streamSize = new AtomicReference<>();
                AtomicInteger compMethod = new AtomicInteger();

                ZipReturn zr = zf.zipFileOpenReadStream(i, false, readStream, streamSize, compMethod);
                if (zr != ZipReturn.ZIPGOOD || readStream.get() == null) {
                    throw new IOException("Failed to open read stream for " + name + " (i=" + i + "): " + zr);
                }

                BigInteger usize = streamSize.get();
                try (InputStream in = readStream.get();
                     OutputStream out = Files.newOutputStream(outPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    long togo = usize.longValue();
                    byte[] buf = new byte[8192];
                    while (togo > 0) {
                        int len = (int) Math.min(buf.length, togo);
                        int n = in.read(buf, 0, len);
                        if (n < 0) break;
                        out.write(buf, 0, n);
                        togo -= n;
                    }
                } finally {
                    try { zf.zipFileCloseReadStream(); } catch (Exception _) { /* ignore */ }
                }
            } finally {
                try { zf.zipFileClose(); zf.close(); } catch (Exception _) { /* ignore */ }
            }
        }
    }

    private void createPlainZip(Path srcDir, File outZip) throws IOException {
        Files.createDirectories(outZip.getParentFile().toPath());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outZip.toPath()))) {
            zos.setMethod(ZipOutputStream.DEFLATED);
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);

            // include explicit directory entries (for empty dirs that may be present as markers in orig tzip)
            List<Path> dirs = Files.walk(srcDir)
                    .filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path d : dirs) {
                if (d.equals(srcDir)) continue;
                String name = srcDir.relativize(d).toString().replace('\\', '/') + "/";
                ZipEntry ze = new ZipEntry(name);
                ze.setTime(0L);
                zos.putNextEntry(ze);
                zos.closeEntry();
            }

            List<Path> files = Files.walk(srcDir)
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.reverseOrder()) // deliberately not alpha order to simulate unsorted plain zip
                    .toList();

            for (Path p : files) {
                String name = srcDir.relativize(p).toString().replace('\\', '/');
                ZipEntry ze = new ZipEntry(name);
                ze.setTime(0L); // normalize time
                zos.putNextEntry(ze);
                Files.copy(p, zos);
                zos.closeEntry();
            }
        }
    }

    private void assertTzipFilesEquivalent(File expected, File actual) throws IOException {
        // compare structure (logical files), comment etc. Note: container size/bytes may legitimately
        // differ if the checked-in tzip was produced by a different trrntzip impl (different deflate output
        // for same content).

        // explicit structure via project's parser (works for all tzips)
        jtrrntzip.supportedfiles.zipfile.ZipFile zexp = new jtrrntzip.supportedfiles.zipfile.ZipFile();
        jtrrntzip.supportedfiles.zipfile.ZipFile zact = new jtrrntzip.supportedfiles.zipfile.ZipFile();
        try {
            assertEquals(ZipReturn.ZIPGOOD, zexp.zipFileOpen(expected, expected.lastModified(), true), "open expected");
            assertEquals(ZipReturn.ZIPGOOD, zact.zipFileOpen(actual, actual.lastModified(), true), "open actual");

            assertTrue(zexp.zipStatus().contains(ZipStatus.TRRNTZIP), "expected not detected as tzip");
            assertTrue(zact.zipStatus().contains(ZipStatus.TRRNTZIP), "actual not detected as tzip");

            int n = zexp.localFilesCount();
            assertEquals(n, zact.localFilesCount(), "file count mismatch");

            for (int i = 0; i < n; i++) {
                assertEquals(zexp.filename(i), zact.filename(i), "filename mismatch at " + i);
                assertArrayEquals(zexp.crc32(i), zact.crc32(i), "crc mismatch at " + i + " " + zexp.filename(i));
                assertEquals(zexp.uncompressedSize(i), zact.uncompressedSize(i), "usize mismatch at " + i);
            }

            // comment via manual eocd parse (since not exposed)
            String cexp = readZipFileComment(expected);
            String cact = readZipFileComment(actual);
            assertEquals(cexp, cact, "torrentzip comment mismatch");
            assertTrue(cexp.startsWith("TORRENTZIPPED-"), "bad comment on expected");
        } finally {
            try { zexp.zipFileClose(); zexp.close(); } catch (Exception _) { /* ignore */ }
            try { zact.zipFileClose(); zact.close(); } catch (Exception _) { /* ignore */ }
        }
    }

    private String readZipFileComment(File f) throws IOException {
        byte[] data = Files.readAllBytes(f.toPath());
        // EOCD signature search from near end (handles small comment)
        int start = Math.max(0, data.length - 65535 - 22);
        for (int i = data.length - 22; i >= start; i--) {
            if (data[i] == 0x50 && data[i + 1] == 0x4b && data[i + 2] == 0x05 && data[i + 3] == 0x06) {
                int commentLen = (data[i + 20] & 0xFF) | ((data[i + 21] & 0xFF) << 8);
                if (i + 22 + commentLen == data.length) {
                    return new String(data, i + 22, commentLen, StandardCharsets.US_ASCII);
                }
            }
        }
        return "";
    }

    // === Central Directory Dump for debugging, disabled unless -Djtrrntzip.test.dump=true ===
    private void dumpCentralDirectoryComparison(File orig, File produced, String base) throws IOException {
        if (!DUMP_ENABLED) return;

        Path dumpRoot = DUMP_ROOT.resolve(base);
        Files.createDirectories(dumpRoot);

        byte[] cdOrig = extractCentralDirBytes(orig);
        byte[] cdProd = extractCentralDirBytes(produced);

        Files.write(dumpRoot.resolve("orig-cd.bin"), cdOrig);
        Files.write(dumpRoot.resolve("produced-cd.bin"), cdProd);

        // CRC of CD (as used for TORRENTZIPPED comment)
        long crcOrig = crc32(cdOrig);
        long crcProd = crc32(cdProd);
        Files.writeString(dumpRoot.resolve("cd-crcs.txt"),
            "Orig CD CRC (hex): " + String.format("%08X", crcOrig) + "\n" +
            "Prod CD CRC (hex): " + String.format("%08X", crcProd) + "\n" +
            "Comment would be: TORRENTZIPPED-" + String.format("%08X", crcOrig) + " vs TORRENTZIPPED-" + String.format("%08X", crcProd) + "\n");

        // Parsed text dumps
        dumpParsedCentralDir(cdOrig, dumpRoot.resolve("orig-cd.txt"), "ORIG");
        dumpParsedCentralDir(cdProd, dumpRoot.resolve("produced-cd.txt"), "PROD");

        // JSON dumps of central directory (structured)
        dumpCentralDirAsJson(cdOrig, dumpRoot.resolve("orig-cd.json"));
        dumpCentralDirAsJson(cdProd, dumpRoot.resolve("produced-cd.json"));

        // Byte diff summary (first diffs)
        StringBuilder diffs = new StringBuilder();
        int minLen = Math.min(cdOrig.length, cdProd.length);
        int diffCount = 0;
        for (int i = 0; i < minLen && diffCount < 50; i++) {
            if (cdOrig[i] != cdProd[i]) {
                diffs.append(String.format("0x%04X: %02X -> %02X%n", i, cdOrig[i] & 0xFF, cdProd[i] & 0xFF));
                diffCount++;
            }
        }
        if (cdOrig.length != cdProd.length) {
            diffs.append("Length differs: " + cdOrig.length + " vs " + cdProd.length + "\n");
        }
        if (diffs.isEmpty()) {
            diffs.append("CD bytes are IDENTICAL\n");
        }
        Files.writeString(dumpRoot.resolve("cd-byte-diffs.txt"), diffs.toString());

        // Also log to console for immediate visibility
        System.out.println("=== CD DUMP for " + base + " ===");
        System.out.println("Orig CD len=" + cdOrig.length + " CRC=" + String.format("%08X", crcOrig));
        System.out.println("Prod CD len=" + cdProd.length + " CRC=" + String.format("%08X", crcProd));
        System.out.println("Dumps written to: " + dumpRoot);
    }

    private byte[] extractCentralDirBytes(File f) throws IOException {
        byte[] data = Files.readAllBytes(f.toPath());
        int start = Math.max(0, data.length - 65535 - 22);
        for (int i = data.length - 22; i >= start; i--) {
            if (data[i] == 0x50 && data[i + 1] == 0x4b && data[i + 2] == 0x05 && data[i + 3] == 0x06) {
                long cdSize = ((data[i + 12] & 0xFFL) | ((data[i + 13] & 0xFFL) << 8) |
                               ((data[i + 14] & 0xFFL) << 16) | ((data[i + 15] & 0xFFL) << 24));
                long cdOffset = ((data[i + 16] & 0xFFL) | ((data[i + 17] & 0xFFL) << 8) |
                                 ((data[i + 18] & 0xFFL) << 16) | ((data[i + 19] & 0xFFL) << 24));
                if (cdOffset + cdSize <= data.length) {
                    byte[] cd = new byte[(int) cdSize];
                    System.arraycopy(data, (int) cdOffset, cd, 0, (int) cdSize);
                    return cd;
                }
            }
        }
        return new byte[0];
    }

    private void dumpParsedCentralDir(byte[] cd, Path out, String label) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("=== " + label + " Central Directory ===\n");
        sb.append("Total CD bytes: " + cd.length + "\n\n");

        int pos = 0;
        int idx = 0;
        while (pos + 46 <= cd.length) {
            int sig = readIntLE(cd, pos);
            if (sig != 0x02014b50) break;

            sb.append(String.format("Entry #%d%n", idx++));
            pos += 4;
            sb.append("  verMade=" + readUShortLE(cd, pos) + "  verNeeded=" + readUShortLE(cd, pos+2) + "\n"); pos += 4;
            int flags = readUShortLE(cd, pos); pos += 2;
            int method = readUShortLE(cd, pos); pos += 2;
            int mtime = readUShortLE(cd, pos); pos += 2;
            int mdate = readUShortLE(cd, pos); pos += 2;
            byte[] crc = new byte[4]; System.arraycopy(cd, pos, crc, 0, 4); pos += 4;
            long csize = readUIntLE(cd, pos); pos += 4;
            long usize = readUIntLE(cd, pos); pos += 4;
            int nlen = readUShortLE(cd, pos); pos += 2;
            int elen = readUShortLE(cd, pos); pos += 2;
            int clen = readUShortLE(cd, pos); pos += 2;
            pos += 2 + 2 + 4; // disk + intAttr + extAttr
            long relOff = readUIntLE(cd, pos); pos += 4;

            Charset nameCharset = ((flags & (1 << 11)) != 0) ? StandardCharsets.UTF_8 : Charset.forName("Cp437");
            String name = new String(cd, pos, nlen, nameCharset);
            pos += nlen;

            byte[] extra = (elen > 0) ? Arrays.copyOfRange(cd, pos, pos + elen) : new byte[0];
            pos += elen;

            String comment = (clen > 0) ? new String(cd, pos, clen, StandardCharsets.US_ASCII) : "";
            pos += clen;

            sb.append(String.format("  flags=0x%04X method=%d time=%04X date=%04X%n", flags, method, mtime, mdate));
            sb.append("  crc=" + bytesToHex(crc) + " csize=" + csize + " usize=" + usize + "\n");
            sb.append("  nameLen=" + nlen + " extraLen=" + elen + " commentLen=" + clen + "\n");
            sb.append("  relOffset=" + relOff + "\n");
            sb.append("  name=" + name + "\n");
            if (extra.length > 0) {
                sb.append("  extra=" + bytesToHex(extra) + "\n");
            }
            if (!comment.isEmpty()) {
                sb.append("  comment=" + comment + "\n");
            }
            sb.append("\n");
        }
        Files.writeString(out, sb.toString());
    }

    private int readUShortLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private long readUIntLE(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) |
               ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private int readIntLE(byte[] b, int off) {
        return (int) readUIntLE(b, off);
    }

    private String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02X", v & 0xFF));
        return sb.toString();
    }

    private long crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private void dumpCentralDirAsJson(byte[] cd, Path out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        int pos = 0;
        int idx = 0;
        boolean first = true;
        while (pos + 46 <= cd.length) {
            int sig = readIntLE(cd, pos);
            if (sig != 0x02014b50) break;

            if (!first) sb.append(",\n");
            first = false;

            pos += 4;
            int verMade = readUShortLE(cd, pos);
            int verNeeded = readUShortLE(cd, pos + 2); pos += 4;
            int flags = readUShortLE(cd, pos); pos += 2;
            int method = readUShortLE(cd, pos); pos += 2;
            int mtime = readUShortLE(cd, pos); pos += 2;
            int mdate = readUShortLE(cd, pos); pos += 2;
            byte[] crc = Arrays.copyOfRange(cd, pos, pos + 4); pos += 4;
            long csize = readUIntLE(cd, pos); pos += 4;
            long usize = readUIntLE(cd, pos); pos += 4;
            int nlen = readUShortLE(cd, pos); pos += 2;
            int elen = readUShortLE(cd, pos); pos += 2;
            int clen = readUShortLE(cd, pos); pos += 2;
            pos += 2 + 2 + 4; // disk, intAttr, extAttr
            long relOff = readUIntLE(cd, pos); pos += 4;

            boolean utf8 = (flags & (1 << 11)) != 0;
            Charset nameCharset = utf8 ? StandardCharsets.UTF_8 : Charset.forName("Cp437");
            String name = new String(cd, pos, nlen, nameCharset);
            pos += nlen;

            byte[] extra = (elen > 0) ? Arrays.copyOfRange(cd, pos, pos + elen) : new byte[0];
            pos += elen;

            String comment = (clen > 0) ? new String(cd, pos, clen, StandardCharsets.US_ASCII) : "";
            pos += clen;

            sb.append("  {\n");
            sb.append("    \"index\": " + idx + ",\n");
            sb.append("    \"verMade\": " + verMade + ", \"verNeeded\": " + verNeeded + ",\n");
            sb.append("    \"flags\": " + flags + ", \"flagsHex\": \"0x" + String.format("%04X", flags) + "\",\n");
            sb.append("    \"method\": " + method + ",\n");
            sb.append("    \"mtime\": " + mtime + ", \"mdate\": " + mdate + ",\n");
            sb.append("    \"crc32\": \"" + bytesToHex(crc) + "\",\n");
            sb.append("    \"compressedSize\": " + csize + ", \"uncompressedSize\": " + usize + ",\n");
            sb.append("    \"nameLength\": " + nlen + ", \"extraLength\": " + elen + ", \"commentLength\": " + clen + ",\n");
            sb.append("    \"relOffset\": " + relOff + ",\n");
            sb.append("    \"utf8\": " + utf8 + ",\n");
            sb.append("    \"name\": \"" + escapeJson(name) + "\",\n");
            sb.append("    \"extraHex\": \"" + bytesToHex(extra) + "\",\n");
            sb.append("    \"comment\": \"" + escapeJson(comment) + "\"\n");
            sb.append("  }");
            idx++;
        }
        sb.append("\n]\n");
        Files.writeString(out, sb.toString());
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
