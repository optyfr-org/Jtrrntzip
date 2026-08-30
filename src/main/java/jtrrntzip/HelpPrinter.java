package jtrrntzip;

import java.io.PrintStream;

/**
 * Prints the usage text of the program.
 *
 * <p>The text is assembled from the localized messages of the
 * {@code jtrrntzip.messages} bundle, so it follows the locale of the running
 * process.</p>
 */
public final class HelpPrinter {
    private final String version;

    /**
     * Creates a help printer.
     *
     * @param version
     *            the version shown in the banner line
     */
    public HelpPrinter(final String version) {
        this.version = version;
    }

    /**
     * Prints the banner line and the list of supported options.
     *
     * @param out
     *            the stream to print the help to
     */
    public final void printTo(final PrintStream out) {
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
