package jtrrntzip;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.io.FilenameUtils;

public final class Program implements LogCallback, TorrentZipOptions
{
	static final int EXIT_OK = 0;
	static final int EXIT_FAILED = 1;
	static final int EXIT_USAGE = 2;

	public static void main(final String[] args)
	{
		if(args.length == 0)
		{
			System.out.println(""); //NOSONAR
			System.out.println(Messages.getString("Program.MissingPath")); //NOSONAR
			System.out.println(Messages.getString("Program.Usage")); //NOSONAR
			System.exit(EXIT_USAGE);
			return;
		}

		System.exit(new Program(args).run());
	}

	private final CliOptions options;

	private TorrentZip tz;

	public Program(final String[] args)
	{
		options = CliOptions.parse(args);
	}

	int run()
	{
		switch (options.info())
		{
			case HELP:
				new HelpPrinter(specificationVersion()).printTo(System.out); //NOSONAR
				return EXIT_OK;
			case VERSION:
				System.out.format("TorrentZip v%s", specificationVersion()); //NOSONAR
				return EXIT_OK;
			case NONE:
				break;
		}

		var failures = 0;
		if(!options.argfiles().isEmpty())
		{
			tz = new TorrentZip(this, this);
			for(final File argfile : options.argfiles())
			{
				// first check if arg is a directory
				if(argfile.isDirectory())
				{
					failures += processDir(argfile);
					continue;
				}

				// now check if arg is a directory/filename with possible wild cards.
				failures += processLiteralFileOrGlob(argfile);
			}
		}

		if(options.guiLaunch())
		{
			System.out.format(Messages.getString("Program.Complete"));  //NOSONAR
			try(final var scanner = new Scanner(System.in))
			{
				scanner.nextLine();
			}
		}

		return failures > 0 ? EXIT_FAILED : EXIT_OK;
	}

	private static String specificationVersion()
	{
		return Program.class.getPackage().getSpecificationVersion();
	}

	private int processDir(final File dir)
	{
		if(isVerboseLogging())
			System.out.println(Messages.getString("Program.CheckingDir") + dir); //NOSONAR

		final File[] files = dir.listFiles();
		if(files == null)
		{
			System.err.println(dir);
			return 1;
		}

		var failures = 0;
		for(final File f : files)
		{
			if(f.isDirectory())
			{
				if(!options.noRecursion())
					failures += processDir(f);
			}
			else
			{
				final String ext = FilenameUtils.getExtension(f.getName());
				if(ext != null && ext.equalsIgnoreCase("zip"))
				{
					failures += processSingle(f);
				}
			}
		}
		return failures;
	}

	private int processLiteralFileOrGlob(final File argfile)
	{
		// an argument matching an existing file is processed as-is, this keeps
		// literal names that contain glob metacharacters working
		if(argfile.isFile())
			return processSingle(argfile);

		String dir = argfile.getParent();
		if(dir == null)
			dir = Paths.get(".").toAbsolutePath().normalize().toString();

		final String filename = argfile.getName();

		try(DirectoryStream<Path> dirStream = openDirectoryStream(Paths.get(dir), filename))
		{
			var failures = 0;
			for(final Path path : dirStream)
			{
				final String ext = FilenameUtils.getExtension(path.getFileName().toString());
				if(ext == null || !ext.equalsIgnoreCase("zip"))
					continue;
				failures += processSingle(path.toFile());
			}
			return failures;
		}
		catch(final IOException e)
		{
			System.err.println(describe(e));
			return 1;
		}
	}

	private static DirectoryStream<Path> openDirectoryStream(final Path dir, final String glob) throws IOException
	{
		try
		{
			return Files.newDirectoryStream(dir, glob);
		}
		catch(final PatternSyntaxException e)
		{
			// the pattern contains glob metacharacters that do not form a valid
			// pattern, retry with all metacharacters escaped so the literal name works
			return Files.newDirectoryStream(dir, escapeGlob(glob));
		}
	}

	private static String escapeGlob(final String glob)
	{
		final var sb = new StringBuilder();
		for(var i = 0; i < glob.length(); i++)
		{
			final char c = glob.charAt(i);
			switch(c)
			{
				case '*':
				case '?':
				case '\\':
				case '[':
				case ']':
				case '{':
				case '}':
					sb.append('\\');
					sb.append(c);
					break;
				default:
					sb.append(c);
			}
		}
		return sb.toString();
	}

	private int processSingle(final File file)
	{
		try
		{
			final Set<TrrntZipStatus> status = tz.process(file);
			return status.contains(TrrntZipStatus.CORRUPTZIP) ? 1 : 0;
		}
		catch(final IOException e)
		{
			System.err.println(describe(e));
			return 1;
		}
	}

	private static String describe(final IOException e)
	{
		return e.getMessage() == null ? e.toString() : e.getMessage();
	}

	@Override
	public final boolean isForceRezip()
	{
		return options.forceReZip();
	}

	@Override
	public final boolean isCheckOnly()
	{
		return options.checkOnly();
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
		return options.verboseLogging();
	}

}
