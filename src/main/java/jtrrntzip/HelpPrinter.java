package jtrrntzip;

import java.io.PrintStream;

public final class HelpPrinter
{
	private final String version;

	public HelpPrinter(final String version)
	{
		this.version = version;
	}

	public final void printTo(final PrintStream out)
	{
		out.format("Jtrrntzip v%s%n", version);
		out.println(Messages.getString("AbstractTorrentZipOptions.Copyright"));
		out.println(Messages.getString("AbstractTorrentZipOptions.BasedOnTrrntzipDN"));
		out.println(Messages.getString("AbstractTorrentZipOptions.Usage"));
		out.println(Messages.getString("AbstractTorrentZipOptions.Options"));
		out.println(Messages.getString("AbstractTorrentZipOptions.ShowThisHelp"));
		out.println(Messages.getString("AbstractTorrentZipOptions.PreventSubDirRecursion"));
		out.println(Messages.getString("AbstractTorrentZipOptions.ForceReZip"));
		out.println(Messages.getString("AbstractTorrentZipOptions.CheckOnly"));
		out.println(Messages.getString("AbstractTorrentZipOptions.VerboseLogging"));
		out.println(Messages.getString("AbstractTorrentZipOptions.ShowVersion"));
		out.println(Messages.getString("AbstractTorrentZipOptions.PauseWhenFinished"));
	}
}
