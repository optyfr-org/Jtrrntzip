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
				case "-?": 
					System.out.format("Jtrrntzip v%s%n", Program.class.getPackage().getSpecificationVersion()); //NOSONAR
					System.out.println(Messages.getString("AbstractTorrentZipOptions.Copyright")); //NOSONAR
					System.out.println(Messages.getString("AbstractTorrentZipOptions.BasedOnTrrntzipDN")); //NOSONAR
					System.out.println(Messages.getString("AbstractTorrentZipOptions.Usage")); //NOSONAR
					System.out.println(Messages.getString("AbstractTorrentZipOptions.Options")); //NOSONAR
					System.out.println(Messages.getString("AbstractTorrentZipOptions.ShowThisHelp")); //NOSONAR
					System.out.println(Messages.getString("AbstractTorrentZipOptions.PreventSubDirRecursion")); //NOSONAR
					System.out.println(Messages.getString("AbstractTorrentZipOptions.ForceReZip")); //NOSONAR
					System.out.println(Messages.getString("AbstractTorrentZipOptions.CheckOnly")); //NOSONAR
					System.out.println(Messages.getString("AbstractTorrentZipOptions.VerboseLogging")); //NOSONAR
					System.out.println(Messages.getString("AbstractTorrentZipOptions.ShowVersion")); //NOSONAR
					System.out.println(Messages.getString("AbstractTorrentZipOptions.PauseWhenFinished")); //NOSONAR
					return;
				case "-s": 
					noRecursion = true;
					break;
				case "-f": 
					forceReZip = true;
					break;
				case "-c": 
					checkOnly = true;
					break;
				case "-l": 
					verboseLogging = true;
					break;
				case "-v": 
					System.out.format("TorrentZip v%s", Program.class.getPackage().getSpecificationVersion()); //NOSONAR
					return;
				case "-g": 
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
