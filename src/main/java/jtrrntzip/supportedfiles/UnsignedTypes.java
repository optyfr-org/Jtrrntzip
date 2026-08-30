package jtrrntzip.supportedfiles;

public class UnsignedTypes {
    private UnsignedTypes() {
    }

    public static int fromUInt(final long l) {
        return (int) l;
    }

    public static short fromUShort(final int i) {
        return (short) i;
    }

    public static long toUInt(final int value) {
        return Integer.toUnsignedLong(value);
    }

    public static int toUShort(final short value) {
        return Short.toUnsignedInt(value);
    }
}
