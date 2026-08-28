package jtrrntzip;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AbstractTorrentZipOptions implements TorrentZipOptions
{
	protected boolean noRecursion = false;
	protected boolean forceReZip = false;
	protected boolean checkOnly = false;
	protected boolean verboseLogging = false;
	protected boolean guiLaunch = false;

	protected List<File> argfiles = null;

	public AbstractTorrentZipOptions(final String[] args)
	{
		final List<File> argfls = new ArrayList<>();
		for(final String arg : args)
		{
			switch(arg)
			{
				case "-?": //$NON-NLS-1$
					System.out.format("Jtrrntzip v%s%n", Program.class.getPackage().getSpecificationVersion()); //$NON-NLS-1$
					System.out.println(Messages.getString("AbstractTorrentZipOptions.Copyright")); //$NON-NLS-1$
					System.out.println(Messages.getString("AbstractTorrentZipOptions.BasedOnTrrntzipDN")); //$NON-NLS-1$
					System.out.println(Messages.getString("AbstractTorrentZipOptions.Usage")); //$NON-NLS-1$
					System.out.println(Messages.getString("AbstractTorrentZipOptions.Options")); //$NON-NLS-1$
					System.out.println(Messages.getString("AbstractTorrentZipOptions.ShowThisHelp")); //$NON-NLS-1$
					System.out.println(Messages.getString("AbstractTorrentZipOptions.PreventSubDirRecursion")); //$NON-NLS-1$
					System.out.println(Messages.getString("AbstractTorrentZipOptions.ForceReZip")); //$NON-NLS-1$
					System.out.println(Messages.getString("AbstractTorrentZipOptions.CheckOnly")); //$NON-NLS-1$
					System.out.println(Messages.getString("AbstractTorrentZipOptions.VerboseLogging")); //$NON-NLS-1$
					System.out.println(Messages.getString("AbstractTorrentZipOptions.ShowVersion")); //$NON-NLS-1$
					System.out.println(Messages.getString("AbstractTorrentZipOptions.PauseWhenFinished")); //$NON-NLS-1$
					return;
				case "-s": //$NON-NLS-1$
					noRecursion = true;
					break;
				case "-f": //$NON-NLS-1$
					forceReZip = true;
					break;
				case "-c": //$NON-NLS-1$
					checkOnly = true;
					break;
				case "-l": //$NON-NLS-1$
					verboseLogging = true;
					break;
				case "-v": //$NON-NLS-1$
					System.out.format("TorrentZip v%s", Program.class.getPackage().getSpecificationVersion()); //$NON-NLS-1$
					return;
				case "-g": //$NON-NLS-1$
					guiLaunch = true;
					break;
				default:
					argfls.add(new File(arg));
					break;
			}
		}
		this.argfiles = argfls;
	}

	@Override
	public boolean isForceRezip()
	{
		return forceReZip;
	}

	@Override
	public boolean isCheckOnly()
	{
		return checkOnly;
	}

}
