package jtrrntzip.supportedfiles;

/**
 * Conversions between the unsigned values used by the on-disk archive
 * structures and the signed Java primitives.
 *
 * <p>Binary formats store unsigned numbers, while Java primitives are signed;
 * the values here reinterpret the bits so the full unsigned range survives a
 * round trip through the wider signed type.</p>
 */
public final class UnsignedTypes {
    private UnsignedTypes() {
    }

    /**
     * Narrows an unsigned 32-bit value, held in a long, back into an int.
     *
     * @param l
     *            the unsigned 32-bit value
     * @return the same bits reinterpreted as a signed int
     */
    public static int fromUInt(final long l) {
        return (int) l;
    }

    /**
     * Narrows an unsigned 16-bit value, held in an int, back into a short.
     *
     * @param i
     *            the unsigned 16-bit value
     * @return the same bits reinterpreted as a signed short
     */
    public static short fromUShort(final int i) {
        return (short) i;
    }

    /**
     * Widens an int that holds an unsigned 32-bit value into its unsigned
     * numeric value.
     *
     * @param value
     *            the int holding the unsigned 32-bit value
     * @return the unsigned value in the range 0 to 2^32-1
     */
    public static long toUInt(final int value) {
        return Integer.toUnsignedLong(value);
    }

    /**
     * Widens a short that holds an unsigned 16-bit value into its unsigned
     * numeric value.
     *
     * @param value
     *            the short holding the unsigned 16-bit value
     * @return the unsigned value in the range 0 to 65535
     */
    public static int toUShort(final short value) {
        return Short.toUnsignedInt(value);
    }
}
