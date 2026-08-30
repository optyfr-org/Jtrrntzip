package jtrrntzip;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;

public record ZippedFile(int index, String name, long size, int crc) {
    public ZippedFile withName(final String name) {
        return this.name.equals(name) ? this : new ZippedFile(index, name, size, crc);
    }

    public byte[] leCrc() {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc).array();
    }

    public static int crcFromLe(final byte[] value) {
        return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    @Override
    public String toString() {
        return HexFormat.of().formatHex(leCrc());
    }
}
