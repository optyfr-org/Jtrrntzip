package jtrrntzip.supportedfiles.zipfile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Encodes and decodes zip entry names according to the zip specification.
 *
 * <p>Names are stored as CP437 unless they cannot be represented, in which case
 * UTF-8 is used and the unicode path flag (bit 11 of the general purpose bit
 * flags) is set. The unicode path extra field (0x7075) may additionally supply
 * a UTF-8 name.</p>
 */
final class ZipNameCodec {

	static final Charset CP437 = Charset.forName("Cp437"); //$NON-NLS-1$

	static final int UNICODE_FLAG = 1 << 11;

	private ZipNameCodec() {
	}

	/**
	 * Encoded result: the bytes to store in the name field and whether the
	 * unicode flag bit must be set in the general purpose bit flags.
	 *
	 * @param bytes
	 *            the name bytes (CP437 or UTF-8)
	 * @param unicodeFlag
	 *            true if the unicode flag should be set
	 */
	record EncodedName(byte[] bytes, boolean unicodeFlag) {
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof EncodedName(var otherBytes, var otherUnicodeFlag))) return false;
			return unicodeFlag == otherUnicodeFlag && Arrays.equals(bytes, otherBytes);
		}

		@Override
		public int hashCode() {
			return 31 * Arrays.hashCode(bytes) + Boolean.hashCode(unicodeFlag);
		}

		@Override
		public String toString() {
			return "EncodedName[bytes=" + Arrays.toString(bytes) + ", unicodeFlag=" + unicodeFlag + "]";
		}
	}

	/**
	 * Encodes the entry name.
	 *
	 * @param fileName
	 *            the logical entry name
	 * @return encoded bytes and the flag indication
	 */
	static EncodedName encode(String fileName) {
		if (!CP437.newEncoder().canEncode(fileName)) {
			return new EncodedName(fileName.getBytes(StandardCharsets.UTF_8), true);
		}
		return new EncodedName(fileName.getBytes(CP437), false);
	}

	/**
	 * Decodes an entry name.
	 *
	 * @param nameBytes
	 *            the raw bytes from header or central directory
	 * @param generalPurposeBitFlag
	 *            the flags from the header (bit 11 selects UTF-8)
	 * @return the decoded name
	 */
	static String decode(byte[] nameBytes, int generalPurposeBitFlag) {
		return new String(nameBytes, (generalPurposeBitFlag & UNICODE_FLAG) == 0 ? CP437 : StandardCharsets.UTF_8);
	}
}
