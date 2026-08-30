package jtrrntzip;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.apache.commons.codec.binary.Hex;

public class ZippedFile
{
	private int index;
	private String name;
	private long size;
	private int crc;

	public final byte[] getLECRC()
	{
		final var bb = ByteBuffer.allocate(4);
		bb.order(ByteOrder.LITTLE_ENDIAN);
		bb.putInt(getCrc());
		return bb.array();
	}

	public final void setCRC(final byte[] value)
	{
		crc = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt();
	}

	@Override
	public final String toString()
	{
		return Hex.encodeHexString(getLECRC());
	}

	/**
	 * @param index the index to set
	 */
	public void setIndex(int index)
	{
		this.index = index;
	}

	/**
	 * @param name the name to set
	 */
	public void setName(String name)
	{
		this.name = name;
	}

	/**
	 * @param size the size to set
	 */
	public void setSize(long size)
	{
		this.size = size;
	}

	/**
	 * @return the name
	 */
	public String getName()
	{
		return name;
	}

	/**
	 * @return the crc
	 */
	public int getCrc()
	{
		return crc;
	}

	/**
	 * @return the size
	 */
	public long getSize()
	{
		return size;
	}

	/**
	 * @return the index
	 */
	public int getIndex()
	{
		return index;
	}
}
