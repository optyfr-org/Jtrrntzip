package jtrrntzip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

class CliOptionsTest {

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

    @Test
    void collectMultipleFileArguments() {
        final var options = CliOptions.parse(new String[] { "a.zip", "dir", "-l", "b.zip" });

        assertEquals(CliOptions.Info.NONE, options.info());
        assertTrue(options.verboseLogging());
        assertEquals(List.of(new File("a.zip"), new File("dir"), new File("b.zip")), options.argfiles());
    }

    @Test
    void flagsOnlyYieldsNoFilesWithoutInfoMode() {
        final var options = CliOptions.parse(new String[] { "-c" });

        assertEquals(CliOptions.Info.NONE, options.info());
        assertTrue(options.checkOnly());
        assertTrue(options.argfiles().isEmpty());
    }

    @Test
    void helpFlagShortCircuitsParsing() {
        final var options = CliOptions.parse(new String[] { "-l", "-?", "roms.zip" });

        assertEquals(CliOptions.Info.HELP, options.info());
        assertTrue(options.argfiles().isEmpty(), "arguments after the help flag must not be collected");
    }

    @Test
    void versionFlagShortCircuitsParsing() {
        final var options = CliOptions.parse(new String[] { "-g", "-v", "roms.zip" });

        assertEquals(CliOptions.Info.VERSION, options.info());
        assertTrue(options.guiLaunch(), "flags seen before the version flag keep their value");
        assertTrue(options.argfiles().isEmpty());
    }

    @Test
    void firstInfoFlagWins() {
        assertEquals(CliOptions.Info.VERSION, CliOptions.parse(new String[] { "-v", "-?" }).info());
        assertEquals(CliOptions.Info.HELP, CliOptions.parse(new String[] { "-?", "-v" }).info());
    }
}
