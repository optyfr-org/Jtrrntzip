package jtrrntzip;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

import org.apache.commons.io.FilenameUtils;

public final class Program extends AbstractTorrentZipOptions implements LogCallback
{
	public static void main(final String[] args)
	{
		if(args.length == 0)
		{
			System.out.println(""); //NOSONAR
			System.out.println(Messages.getString("Program.MissingPath")); //NOSONAR
			System.out.println(Messages.getString("Program.Usage")); //NOSONAR
			return;
		}

		new Program(args);

	}

	private TorrentZip tz;

	public Program(final String[] args)
	{
		super(args);

		if(argfiles != null && !argfiles.isEmpty())
		{
			tz = new TorrentZip(this, this);
			for(final File argfile : argfiles)
			{
				// first check if arg is a directory
				if(argfile.isDirectory())
				{
					try
					{
						processDir(argfile);
					}
					catch(final IOException e)
					{
						System.err.println(e.getMessage());
					}
					continue;
				}

				// now check if arg is a directory/filename with possible wild cards.
				String dir = argfile.getParent();
				if(dir == null)
					dir = Paths.get(".").toAbsolutePath().normalize().toString(); 

				final String filename = argfile.getName();

				try(DirectoryStream<Path> dirStream = Files.newDirectoryStream(Paths.get(dir), filename))
				{
					dirStream.forEach(path -> {
						final String ext = FilenameUtils.getExtension(path.getFileName().toString());
						if(ext != null && (ext.equalsIgnoreCase("zip"))) 
						{
							try
							{
								processFile(path.toFile());
							}
							catch(final IOException e)
							{
								System.err.println(e.getMessage());
							}
						}
					});
				}
				catch(final IOException e)
				{
					System.err.println(e.getMessage());
				}
			}
		}
		if(guiLaunch)
		{
			System.out.format(Messages.getString("Program.Complete"));  //NOSONAR
			try(final var scanner = new Scanner(System.in))
			{
				scanner.nextLine();
			}
		}
	}

	private void processDir(final File dir) throws IOException
	{
		if(isVerboseLogging())
			System.out.println(Messages.getString("Program.CheckingDir") + dir); //NOSONAR

		File[] files = dir.listFiles();
		if(files==null)
            return;
        for(final File f : files)
		{
			if(f.isDirectory())
			{
				if(!noRecursion)
					processDir(f);
			}
			else
			{
				final String ext = FilenameUtils.getExtension(f.getName());
				if(ext != null && (ext.equalsIgnoreCase("zip"))) 
				{
					tz.process(f);
				}
			}
		}
	}

	private void processFile(final File file) throws IOException
	{
		tz.process(file);
	}

	@Override
	public final void statusLogCallBack(final String log)
	{
		System.out.format("%s%n", log); //NOSONAR
	}

	@Override
	public final void statusCallBack(final int percent)
	{
		System.out.format("%03d%% ", percent); //NOSONAR
	}

	@Override
	public final boolean isVerboseLogging()
	{
		return verboseLogging;
	}

}
