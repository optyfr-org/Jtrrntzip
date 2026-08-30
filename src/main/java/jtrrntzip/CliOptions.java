package jtrrntzip;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public record CliOptions(boolean noRecursion, boolean forceReZip, boolean checkOnly, boolean verboseLogging, boolean guiLaunch, Info info, List<File> argfiles) {

	public enum Info {
		NONE, HELP, VERSION
	}

	public static CliOptions parse(final String[] args) {
		var noRecursion = false;
		var forceReZip = false;
		var checkOnly = false;
		var verboseLogging = false;
		var guiLaunch = false;
		final var files = new ArrayList<File>();

		for (final String arg : args) {
			switch (arg) {
				case "-?":
					return new CliOptions(noRecursion, forceReZip, checkOnly, verboseLogging, guiLaunch, Info.HELP, List.of());
				case "-v":
					return new CliOptions(noRecursion, forceReZip, checkOnly, verboseLogging, guiLaunch, Info.VERSION, List.of());
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
				case "-g":
					guiLaunch = true;
					break;
				default:
					files.add(new File(arg));
					break;
			}
		}
		return new CliOptions(noRecursion, forceReZip, checkOnly, verboseLogging, guiLaunch, Info.NONE, List.copyOf(files));
	}
}
