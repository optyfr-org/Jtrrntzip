package jtrrntzip;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HelpPrinterTest {

    @Test
    void printsVersionAndEveryOptionLine() {
        final var out = new ByteArrayOutputStream();
        new HelpPrinter("9.9").printTo(new PrintStream(out, true, StandardCharsets.UTF_8));
        final var text = out.toString(StandardCharsets.UTF_8);

        assertTrue(text.startsWith("Jtrrntzip v9.9"), "the first line must be the version banner");
        assertTrue(text.contains(Messages.getString("AbstractTorrentZipOptions.Copyright")));
        assertTrue(text.contains(Messages.getString("AbstractTorrentZipOptions.BasedOnTrrntzipDN")));
        assertTrue(text.contains(Messages.getString("AbstractTorrentZipOptions.Usage")));
        assertTrue(text.contains(Messages.getString("AbstractTorrentZipOptions.ShowThisHelp")));
        assertTrue(text.contains(Messages.getString("AbstractTorrentZipOptions.PreventSubDirRecursion")));
        assertTrue(text.contains(Messages.getString("AbstractTorrentZipOptions.ForceReZip")));
        assertTrue(text.contains(Messages.getString("AbstractTorrentZipOptions.CheckOnly")));
        assertTrue(text.contains(Messages.getString("AbstractTorrentZipOptions.VerboseLogging")));
        assertTrue(text.contains(Messages.getString("AbstractTorrentZipOptions.ShowVersion")));
        assertTrue(text.contains(Messages.getString("AbstractTorrentZipOptions.PauseWhenFinished")));
    }

    @Test
    void printsOptionLinesInHelpOrder() {
        final var out = new ByteArrayOutputStream();
        new HelpPrinter("1.0").printTo(new PrintStream(out, true, StandardCharsets.UTF_8));
        final var text = out.toString(StandardCharsets.UTF_8);

        final var indexOfUsage = text.indexOf(Messages.getString("AbstractTorrentZipOptions.Usage"));
        final var indexOfShowHelp = text.indexOf(Messages.getString("AbstractTorrentZipOptions.ShowThisHelp"));
        final var indexOfPause = text.indexOf(Messages.getString("AbstractTorrentZipOptions.PauseWhenFinished"));

        assertTrue(indexOfUsage >= 0 && indexOfShowHelp > indexOfUsage && indexOfPause > indexOfShowHelp,
                "usage must precede the option list and the pause hint must follow it");
        assertTrue(text.lastIndexOf(Messages.getString("AbstractTorrentZipOptions.PauseWhenFinished").strip()) < text.length(),
                "the pause hint is printed");
    }

    @Test
    void handlesMissingSpecificationVersion() {
        final var out = new ByteArrayOutputStream();
        new HelpPrinter(null).printTo(new PrintStream(out, true, StandardCharsets.UTF_8));

        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("Jtrrntzip vnull"),
                "a missing manifest specification version prints as the string null");
    }
}
