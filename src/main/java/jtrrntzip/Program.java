package jtrrntzip;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.io.FilenameUtils;

/**
 * The command line entry point of Jtrrntzip.
 *
 * <p>The program walks the files, directories and glob patterns selected on
 * the command line, see {@link CliOptions}, and processes every zip archive
 * it finds. Progress and processing messages are written to standard
 * output; standard error receives the descriptions of I/O failures. The
 * process exits with one of:</p>
 * <ul>
 * <li>{@value #EXIT_OK} - no processed archive was corrupt or failed to
 * process, rule violations that are only reported in check only mode do not
 * change the exit code</li>
 * <li>{@value #EXIT_FAILED} - at least one archive was corrupt or failed to
 * process</li>
 * <li>{@value #EXIT_USAGE} - the command line itself was wrong, for example
 * no path was given</li>
 * </ul>
 */
public final class Program implements LogCallback {
    /** Exit code when no processed archive was corrupt or failed to process. */
    static final int EXIT_OK = 0;
    /** Exit code when at least one archive was corrupt or failed to process. */
    static final int EXIT_FAILED = 1;
    /** Exit code for usage errors, for example when no path is given. */
    static final int EXIT_USAGE = 2;

    /**
     * Program entry point.
     *
     * @param args
     *            the command line arguments, see {@link CliOptions}
     */
    public static void main(final String[] args) {
        if (args.length == 0) {
            System.out.println(""); // NOSONAR
            System.out.println(Messages.getString("Program.MissingPath")); // NOSONAR
            System.out.println(Messages.getString("Program.Usage")); // NOSONAR
            System.exit(EXIT_USAGE);
            return;
        }

        System.exit(new Program(args).run());
    }

    private final CliOptions options;

    private TorrentZip tz;

    /**
     * Creates the program for the given command line.
     *
     * @param args
     *            the command line arguments, parsed with
     *            {@link CliOptions#parse(String[])}
     */
    public Program(final String[] args) {
        options = CliOptions.parse(args);
    }

    /**
     * Runs the program.
     *
     * <p>Prints the requested information when a help or version option is
     * present, otherwise processes every selected archive.</p>
     *
     * @return the exit code, see {@link #EXIT_OK}, {@link #EXIT_FAILED} and
     *         {@link #EXIT_USAGE}
     */
    int run() {
        final var infoHandled = switch (options.info()) {
            case HELP -> {
                new HelpPrinter(specificationVersion()).printTo(System.out); // NOSONAR
                yield true;
            }
            case VERSION -> {
                System.out.format("TorrentZip v%s", specificationVersion()); // NOSONAR
                yield true;
            }
            case NONE -> false;
        };
        if (infoHandled)
            return EXIT_OK;

        var failures = 0;
        if (!options.argfiles().isEmpty()) {
            tz = new TorrentZip(this, new SimpleTorrentZipOptions(options.forceReZip(), options.checkOnly()));
            for (final File argfile : options.argfiles()) {
                // first check if arg is a directory
                if (argfile.isDirectory()) {
                    failures += processDir(argfile);
                    continue;
                }

                // now check if arg is a directory/filename with possible wild cards.
                failures += processLiteralFileOrGlob(argfile);
            }
        }

        if (options.guiLaunch()) {
            System.out.format(Messages.getString("Program.Complete")); // NOSONAR
            try (final var scanner = new Scanner(System.in)) {
                scanner.nextLine();
            }
        }

        return failures > 0 ? EXIT_FAILED : EXIT_OK;
    }

    /**
     * Returns the specification version of this package.
     *
     * @return the version taken from the jar manifest
     */
    private static String specificationVersion() {
        return Program.class.getPackage().getSpecificationVersion();
    }

    /**
     * Processes every zip archive in the directory, descending into
     * sub-directories unless recursion was disabled with {@code -s}.
     *
     * @param dir
     *            the directory to scan
     * @return the number of archives that failed processing
     */
    private int processDir(final File dir) {
        if (isVerboseLogging())
            System.out.println(Messages.getString("Program.CheckingDir") + dir); // NOSONAR

        final File[] files = dir.listFiles();
        if (files == null) {
            System.err.println(dir);
            return 1;
        }

        var failures = 0;
        for (final File f : files) {
            if (f.isDirectory()) {
                if (!options.noRecursion())
                    failures += processDir(f);
            } else {
                final String ext = FilenameUtils.getExtension(f.getName());
                if (ext != null && ext.equalsIgnoreCase("zip")) {
                    failures += processSingle(f);
                }
            }
        }
        return failures;
    }

    /**
     * Processes a single command line file argument.
     *
     * <p>An argument naming an existing file is processed literally, so names
     * containing glob metacharacters keep working. Otherwise the argument is
     * treated as a glob pattern relative to its parent directory and every
     * matching zip is processed.</p>
     *
     * @param argfile
     *            the file or glob pattern to process
     * @return the number of archives that failed processing
     */
    private int processLiteralFileOrGlob(final File argfile) {
        // an argument matching an existing file is processed as-is, this keeps
        // literal names that contain glob metacharacters working
        if (argfile.isFile())
            return processSingle(argfile);

        String dir = argfile.getParent();
        if (dir == null)
            dir = Path.of(".").toAbsolutePath().normalize().toString();

        final String filename = argfile.getName();

        try (DirectoryStream<Path> dirStream = openDirectoryStream(Path.of(dir), filename)) {
            var failures = 0;
            for (final Path path : dirStream) {
                final String ext = FilenameUtils.getExtension(path.getFileName().toString());
                if (ext == null || !ext.equalsIgnoreCase("zip"))
                    continue;
                failures += processSingle(path.toFile());
            }
            return failures;
        } catch (final IOException e) {
            System.err.println(describe(e));
            return 1;
        }
    }

    /**
     * Opens a directory stream for the glob, escaping all metacharacters for
     * a retry when the pattern does not form a valid glob.
     *
     * @param dir
     *            the directory to stream
     * @param glob
     *            the requested glob pattern
     * @return the opened directory stream
     * @throws IOException
     *             when the directory cannot be opened
     */
    private static DirectoryStream<Path> openDirectoryStream(final Path dir, final String glob) throws IOException {
        try {
            return Files.newDirectoryStream(dir, glob);
        } catch (final PatternSyntaxException e) {
            // the pattern contains glob metacharacters that do not form a valid
            // pattern, retry with all metacharacters escaped so the literal name works
            return Files.newDirectoryStream(dir, escapeGlob(glob));
        }
    }

    /**
     * Prefixes every glob metacharacter of the given name with a backslash so
     * the name matches literally.
     *
     * @param glob
     *            the file name to escape
     * @return the escaped pattern
     */
    private static String escapeGlob(final String glob) {
        final var sb = new StringBuilder();
        for (var i = 0; i < glob.length(); i++) {
            final char c = glob.charAt(i);
            switch (c) {
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

    /**
     * Processes a single zip archive.
     *
     * @param file
     *            the archive to check and repair
     * @return 1 when the archive is corrupt or failed processing, otherwise 0
     */
    private int processSingle(final File file) {
        try {
            final Set<TrrntZipStatus> status = tz.process(file);
            return status.contains(TrrntZipStatus.CORRUPTZIP) ? 1 : 0;
        } catch (final IOException e) {
            System.err.println(describe(e));
            return 1;
        }
    }

    /**
     * Returns the message of the exception, or its string representation when
     * the message is {@code null}.
     *
     * @param e
     *            the exception to describe
     * @return a printable description
     */
    private static String describe(final IOException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /**
     * Prints a log line to standard output.
     *
     * @param log
     *            the log message to print
     */
    @Override
    public final void statusLogCallBack(final String log) {
        System.out.format("%s%n", log); // NOSONAR
    }

    /**
     * Prints a progress percentage to standard output.
     *
     * @param percent
     *            the progress percentage to print
     */
    @Override
    public final void statusCallBack(final int percent) {
        System.out.format("%03d%% ", percent); // NOSONAR
    }

    /**
     * Returns the verbose flag of the command line.
     *
     * @return {@code true} when the {@code -l} option was given
     */
    @Override
    public final boolean isVerboseLogging() {
        return options.verboseLogging();
    }

}
