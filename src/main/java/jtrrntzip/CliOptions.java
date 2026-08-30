package jtrrntzip;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The parsed command line of the program.
 *
 * @param noRecursion
 *            {@code true} when sub-directories are not searched recursively
 *            ({@code -s})
 * @param forceReZip
 *            {@code true} when valid torrentzips are rebuilt anyway
 *            ({@code -f})
 * @param checkOnly
 *            {@code true} when archives are only checked, never repaired
 *            ({@code -c})
 * @param verboseLogging
 *            {@code true} when detailed logging about individual rule
 *            violations is requested ({@code -l})
 * @param guiLaunch
 *            {@code true} when the program waits for a key press after it
 *            finished ({@code -g})
 * @param info
 *            the informational request implied by the arguments, see
 *            {@link Info}
 * @param argfiles
 *            the files, directories and glob patterns to process
 */
public record CliOptions(boolean noRecursion, boolean forceReZip, boolean checkOnly, boolean verboseLogging, boolean guiLaunch, Info info, List<File> argfiles) {

    /**
     * The informational request to print instead of processing archives.
     */
    public enum Info {
        /**
         * Process the selected archives normally.
         */
        NONE,
        /**
         * Print the usage text and exit successfully.
         */
        HELP,
        /**
         * Print the version banner and exit successfully.
         */
        VERSION
    }

    /**
     * Parses the raw command line arguments.
     *
     * <p>Recognized options are {@code -?} (help), {@code -v} (version),
     * {@code -s}, {@code -f}, {@code -c}, {@code -l} and {@code -g}; an
     * information request option terminates the parse and wins over the
     * remaining arguments. Every other argument is taken as a file, directory
     * or glob pattern to process.</p>
     *
     * @param args
     *            the raw command line arguments
     * @return the parsed options, never {@code null}
     */
    public static CliOptions parse(final String[] args) {
        var noRecursion = false;
        var forceReZip = false;
        var checkOnly = false;
        var verboseLogging = false;
        var guiLaunch = false;
        final var files = new ArrayList<File>();

        for (final String arg : args) {
            switch (arg) {
                case "-?" -> {
                    return new CliOptions(noRecursion, forceReZip, checkOnly, verboseLogging, guiLaunch, Info.HELP, List.of());
                }
                case "-v" -> {
                    return new CliOptions(noRecursion, forceReZip, checkOnly, verboseLogging, guiLaunch, Info.VERSION, List.of());
                }
                case "-s" -> noRecursion = true;
                case "-f" -> forceReZip = true;
                case "-c" -> checkOnly = true;
                case "-l" -> verboseLogging = true;
                case "-g" -> guiLaunch = true;
                default -> files.add(new File(arg));
            }
        }
        return new CliOptions(noRecursion, forceReZip, checkOnly, verboseLogging, guiLaunch, Info.NONE, List.copyOf(files));
    }
}
