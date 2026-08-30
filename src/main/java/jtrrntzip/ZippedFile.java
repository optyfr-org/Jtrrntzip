package jtrrntzip;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;

/**
 * One entry of an opened zip archive, reduced to the fields the torrentzip
 * rules operate on.
 *
 * @param index
 *            the position of the entry inside its archive, used when
 *            streaming the entry data out during a rebuild
 * @param name
 *            the entry name as decoded by the archive reader
 * @param size
 *            the uncompressed size of the entry data in bytes
 * @param crc
 *            the CRC-32 of the uncompressed entry data
 */
public record ZippedFile(int index, String name, long size, int crc) {
    /**
     * Returns a copy of this entry with a different name.
     *
     * @param name
     *            the new entry name
     * @return this entry when the name is unchanged, otherwise the new entry
     */
    public ZippedFile withName(final String name) {
        return this.name.equals(name) ? this : new ZippedFile(index, name, size, crc);
    }

    /**
     * Returns the CRC-32 encoded as the four little endian bytes used by the
     * on-disk zip structures.
     *
     * @return the CRC-32 as four little endian bytes
     */
    public byte[] leCrc() {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc).array();
    }

    /**
     * Decodes a CRC-32 from the four little endian bytes used by the on-disk
     * zip structures.
     *
     * @param value
     *            the CRC-32 as four little endian bytes
     * @return the decoded CRC-32 value
     */
    public static int crcFromLe(final byte[] value) {
        return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /**
     * Returns the CRC-32 formatted as lower case hexadecimal, as used by the
     * per entry verbose logging.
     *
     * @return the CRC-32 as eight lower case hex digits
     */
    @Override
    public String toString() {
        return HexFormat.of().formatHex(leCrc());
    }
}
