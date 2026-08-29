package jtrrntzip.supportedfiles;

import java.math.BigInteger;

public class UnsignedTypes {
	private UnsignedTypes() {
	}

	public static long fromULong(final BigInteger bi) {
		return bi.longValue();
	}

	public static int fromUInt(final long l) {
		return (int) l;
	}

	public static short fromUShort(final int i) {
		return (short) i;
	}

	public static BigInteger toULong(final long value) {
		if (value >= 0L)
			return BigInteger.valueOf(value);
		else {
			final int upper = (int) (value >>> 32);
			final int lower = (int) value;
			// return (upper << 32) + lower
			return (BigInteger.valueOf(Integer.toUnsignedLong(upper))).shiftLeft(32).add(BigInteger.valueOf(Integer.toUnsignedLong(lower)));
		}
	}

	public static long toUInt(final int value) {
		return Integer.toUnsignedLong(value);
	}

	public static int toUShort(final short value) {
		return Short.toUnsignedInt(value);
	}
}
