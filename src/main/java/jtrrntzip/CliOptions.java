package jtrrntzip;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;

/**
 * The parsed command line of the program, parsed with JCommander.
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
     * JCommander-annotated mutable holder used only during parsing.
     */
    @Parameters(resourceBundle = "jtrrntzip.messages")
    private static final class JCommanderArgs {
        @Parameter(names = "-?", descriptionKey = "AbstractTorrentZipOptions.ShowThisHelp")
        private boolean help;

        @Parameter(names = "-v", descriptionKey = "AbstractTorrentZipOptions.ShowVersion")
        private boolean version;

        @Parameter(names = "-s", descriptionKey = "AbstractTorrentZipOptions.PreventSubDirRecursion")
        private boolean noRecursion;

        @Parameter(names = "-f", descriptionKey = "AbstractTorrentZipOptions.ForceReZip")
        private boolean forceReZip;

        @Parameter(names = "-c", descriptionKey = "AbstractTorrentZipOptions.CheckOnly")
        private boolean checkOnly;

        @Parameter(names = "-l", descriptionKey = "AbstractTorrentZipOptions.VerboseLogging")
        private boolean verboseLogging;

        @Parameter(names = "-g", descriptionKey = "AbstractTorrentZipOptions.PauseWhenFinished")
        private boolean guiLaunch;

        @Parameter(description = "<files...>")
        private List<String> argfiles = new ArrayList<>();
    }

    /**
     * Parses the raw command line arguments using JCommander.
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
     * @throws ParameterException
     *             when the arguments contain an unrecognized option
     */
    public static CliOptions parse(final String[] args) {
        final var holder = new JCommanderArgs();
        JCommander.newBuilder()
                .addObject(holder)
                .build()
                .parse(args);

        final Info info = determineInfo(args);

        final List<File> files = info != Info.NONE ? List.of()
                : holder.argfiles.stream().map(File::new).toList();

        return new CliOptions(holder.noRecursion, holder.forceReZip, holder.checkOnly,
                holder.verboseLogging, holder.guiLaunch, info, files);
    }

    /**
     * Scans the raw arguments left-to-right for the first info flag
     * ({@code -?} or {@code -v}) and returns the corresponding
     * {@link Info} value.
     */
    private static Info determineInfo(final String[] args) {
        for (final String arg : args) {
            if ("-?".equals(arg))
                return Info.HELP;
            if ("-v".equals(arg))
                return Info.VERSION;
        }
        return Info.NONE;
    }
}
