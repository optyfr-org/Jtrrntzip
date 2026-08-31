package jtrrntzip;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.io.FilenameUtils;

import com.beust.jcommander.ParameterException;

/**
 * The command line entry point of Jtrrntzip.
 *
 * <p>The program walks the files, directories and glob patterns selected on
 * the command line, see {@link CliOptions}, and processes every zip archive
 * it finds. When multiple files are discovered they are processed
 * concurrently using Java virtual threads; a single file is processed
 * sequentially. Progress and processing messages are written to standard
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
     * Shared lock used by {@link BufferedLogCallback} to flush output
     * blocks atomically when processing files concurrently.
     */
    private final Object outputLock = new Object();

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
        try {
            System.exit(new Program(args).run());
        } catch (final ParameterException e) {
            System.err.println(e.getMessage()); // NOSONAR
            System.err.println(Messages.getString("Program.Usage")); // NOSONAR
            System.exit(EXIT_USAGE);
        }
    }

    private final CliOptions options;

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

        final List<File> files = new ArrayList<>();
        var collectionErrors = false;
        for (final File argfile : options.argfiles()) {
            if (argfile.isDirectory()) {
                collectionErrors |= collectFromDir(argfile, files);
            } else {
                collectionErrors |= collectFromGlob(argfile, files);
            }
        }

        if (files.isEmpty())
            return collectionErrors ? EXIT_FAILED : EXIT_OK;

        final int failures = files.size() == 1
                ? processSingle(files.getFirst())
                : processConcurrent(files);

        if (options.guiLaunch()) {
            System.out.format(Messages.getString("Program.Complete")); // NOSONAR
            try (final var scanner = new Scanner(System.in)) {
                scanner.nextLine();
            }
        }

        final int totalFailures = failures + (collectionErrors ? 1 : 0);
        return totalFailures > 0 ? EXIT_FAILED : EXIT_OK;
    }

    /**
     * Collects every zip archive in the directory, descending into
     * sub-directories unless recursion was disabled with {@code -s}.
     *
     * @param dir
     *            the directory to scan
     * @param files
     *            the accumulator for discovered zip files
     * @return {@code true} when an error occurred (e.g.&nbsp;the directory
     *         could not be listed)
     */
    private boolean collectFromDir(final File dir, final List<File> files) {
        if (isVerboseLogging())
            System.out.println(Messages.getString("Program.CheckingDir") + dir); // NOSONAR

        final File[] children = dir.listFiles();
        if (children == null) {
            System.err.println(dir); // NOSONAR
            return true;
        }

        var error = false;
        for (final File f : children) {
            if (f.isDirectory()) {
                if (!options.noRecursion())
                    error |= collectFromDir(f, files);
            } else {
                final String ext = FilenameUtils.getExtension(f.getName());
                if (ext != null && ext.equalsIgnoreCase("zip"))
                    files.add(f);
            }
        }
        return error;
    }

    /**
     * Collects zip files matching a literal file name or glob pattern.
     *
     * <p>An argument naming an existing file is processed literally, so names
     * containing glob metacharacters keep working. Otherwise the argument is
     * treated as a glob pattern relative to its parent directory and every
     * matching zip is collected.</p>
     *
     * @param argfile
     *            the file or glob pattern
     * @param files
     *            the accumulator for discovered zip files
     * @return {@code true} when an error occurred (e.g.&nbsp;the parent
     *         directory could not be opened)
     */
    private boolean collectFromGlob(final File argfile, final List<File> files) {
        if (argfile.isFile()) {
            files.add(argfile);
            return false;
        }

        String dir = argfile.getParent();
        if (dir == null)
            dir = Path.of(".").toAbsolutePath().normalize().toString();

        final String filename = argfile.getName();

        try (DirectoryStream<Path> dirStream = openDirectoryStream(Path.of(dir), filename)) {
            for (final Path path : dirStream) {
                final String ext = FilenameUtils.getExtension(path.getFileName().toString());
                if (ext == null || !ext.equalsIgnoreCase("zip"))
                    continue;
                files.add(path.toFile());
            }
            return false;
        } catch (final IOException e) {
            System.err.println(describe(e)); // NOSONAR
            return true;
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
        } catch (final PatternSyntaxException _) {
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
                case '*', '?', '\\', '[', ']', '{', '}' -> {
                    sb.append('\\');
                    sb.append(c);
                }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Processes multiple zip archives concurrently using virtual threads.
     *
     * <p>Each file gets its own {@link TorrentZip} instance and its own
     * {@link BufferedLogCallback} so that no mutable state is shared between
     * threads. Output is flushed atomically per file under a shared lock.</p>
     *
     * @param files
     *            the archives to process
     * @return the number of archives that failed processing
     */
    private int processConcurrent(final List<File> files) {
        final var failures = new AtomicInteger(0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var futures = new ArrayList<Future<?>>();
            for (final File file : files) {
                futures.add(executor.submit(() -> processFileConcurrently(file, failures)));
            }
            awaitFutures(futures, failures);
        }
        return failures.get();
    }

    private void processFileConcurrently(final File file, final AtomicInteger failures) {
        final var log = new BufferedLogCallback(this, outputLock);
        final var engine = new TorrentZip(
                log,
                new SimpleTorrentZipOptions(options.forceReZip(), options.checkOnly())
        );
        try {
            final Set<TrrntZipStatus> status = engine.process(file);
            if (status.contains(TrrntZipStatus.CORRUPTZIP))
                failures.incrementAndGet();
        } catch (final IOException e) {
            System.err.println(describe(e)); // NOSONAR
            failures.incrementAndGet();
        }
        log.flushTo(System.out); // NOSONAR
    }

    private void awaitFutures(final List<Future<?>> futures, final AtomicInteger failures) {
        for (final Future<?> f : futures) {
            try {
                f.get();
            } catch (final ExecutionException e) {
                System.err.println(e.getMessage() == null ? e.toString() : e.getMessage()); // NOSONAR
                failures.incrementAndGet();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println(e.getMessage() == null ? e.toString() : e.getMessage()); // NOSONAR
                failures.incrementAndGet();
            }
        }
    }

    /**
     * Processes a single zip archive sequentially.
     *
     * @param file
     *            the archive to check and repair
     * @return 1 when the archive is corrupt or failed processing, otherwise 0
     */
    private int processSingle(final File file) {
        final var tz = new TorrentZip(this, new SimpleTorrentZipOptions(options.forceReZip(), options.checkOnly()));
        try {
            final Set<TrrntZipStatus> status = tz.process(file);
            return status.contains(TrrntZipStatus.CORRUPTZIP) ? 1 : 0;
        } catch (final IOException e) {
            System.err.println(describe(e)); // NOSONAR
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
     * Returns the specification version of this package.
     *
     * @return the version taken from the jar manifest
     */
    private static String specificationVersion() {
        return Program.class.getPackage().getSpecificationVersion();
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
