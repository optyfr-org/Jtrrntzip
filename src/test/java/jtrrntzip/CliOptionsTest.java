package jtrrntzip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CliOptions#parse(String[])}.
 */
@DisplayName("Tests for the command line options parser")
class CliOptionsTest {

    /** Parses a single file argument with every flag left off. */
    @DisplayName("parses a single file argument with the default flags off")
    @Test
    void parsesDefaultOptions() {
        final var options = CliOptions.parse(new String[] { "roms.zip" });

        assertEquals(CliOptions.Info.NONE, options.info());
        assertFalse(options.noRecursion());
        assertFalse(options.forceReZip());
        assertFalse(options.checkOnly());
        assertFalse(options.verboseLogging());
        assertFalse(options.guiLaunch());
        assertEquals(1, options.argfiles().size());
        assertEquals(new File("roms.zip"), options.argfiles().get(0));
    }

    /** Applies every boolean flag at once and keeps the file argument. */
    @DisplayName("parses all boolean flags at once")
    @Test
    void parsesAllFlags() {
        final var options = CliOptions.parse(new String[] { "-s", "-f", "-c", "-l", "-g", "roms.zip" });

        assertEquals(CliOptions.Info.NONE, options.info());
        assertTrue(options.noRecursion());
        assertTrue(options.forceReZip());
        assertTrue(options.checkOnly());
        assertTrue(options.verboseLogging());
        assertTrue(options.guiLaunch());
        assertEquals(List.of(new File("roms.zip")), options.argfiles());
    }

    /** Keeps file, directory and flag arguments in their command line order. */
    @DisplayName("collects multiple file arguments in order")
    @Test
    void collectMultipleFileArguments() {
        final var options = CliOptions.parse(new String[] { "a.zip", "dir", "-l", "b.zip" });

        assertEquals(CliOptions.Info.NONE, options.info());
        assertTrue(options.verboseLogging());
        assertEquals(List.of(new File("a.zip"), new File("dir"), new File("b.zip")), options.argfiles());
    }

    /** A flags-only command line parses to an empty file list in normal mode. */
    @DisplayName("flags-only arguments yield no files and no info mode")
    @Test
    void flagsOnlyYieldsNoFilesWithoutInfoMode() {
        final var options = CliOptions.parse(new String[] { "-c" });

        assertEquals(CliOptions.Info.NONE, options.info());
        assertTrue(options.checkOnly());
        assertTrue(options.argfiles().isEmpty());
    }

    /** The help flag ends the parse, arguments after it are not collected. */
    @DisplayName("the help flag short-circuits parsing")
    @Test
    void helpFlagShortCircuitsParsing() {
        final var options = CliOptions.parse(new String[] { "-l", "-?", "roms.zip" });

        assertEquals(CliOptions.Info.HELP, options.info());
        assertTrue(options.argfiles().isEmpty(), "arguments after the help flag must not be collected");
    }

    /** The version flag ends the parse but keeps the flags seen before it. */
    @DisplayName("the version flag short-circuits parsing")
    @Test
    void versionFlagShortCircuitsParsing() {
        final var options = CliOptions.parse(new String[] { "-g", "-v", "roms.zip" });

        assertEquals(CliOptions.Info.VERSION, options.info());
        assertTrue(options.guiLaunch(), "flags seen before the version flag keep their value");
        assertTrue(options.argfiles().isEmpty());
    }

    /** The first information flag in the argument order decides the mode. */
    @DisplayName("the first information flag wins")
    @Test
    void firstInfoFlagWins() {
        assertEquals(CliOptions.Info.VERSION, CliOptions.parse(new String[] { "-v", "-?" }).info());
        assertEquals(CliOptions.Info.HELP, CliOptions.parse(new String[] { "-?", "-v" }).info());
    }
}
