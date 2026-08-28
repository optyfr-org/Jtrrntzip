package jtrrntzip;

public class SimpleTorrentZipOptions implements TorrentZipOptions
{
	private boolean forceRezip = false;
	private boolean checkOnly = false;

	public SimpleTorrentZipOptions()
	{
	}

	public SimpleTorrentZipOptions(final boolean forceRezip, final boolean checkOnly)
	{
		this.forceRezip = forceRezip;
		this.checkOnly = checkOnly;
	}

	@Override
	public boolean isForceRezip()
	{
		return forceRezip;
	}

	@Override
	public boolean isCheckOnly()
	{
		return checkOnly;
	}

}
