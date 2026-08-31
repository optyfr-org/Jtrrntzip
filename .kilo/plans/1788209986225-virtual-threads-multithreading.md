# Plan: Virtual Threads for Multi-File Processing

## Goal

When the CLI receives multiple files (via directory walk or wildcard/glob), process them concurrently using Java virtual threads. Single-file arguments remain sequential. No new CLI flag — concurrency is automatic when >1 file is discovered.

## Thread-Safety Analysis

| Component | Thread-safe? | Action |
|---|---|---|
| `TorrentZip.buffer` (shared `byte[64K]`) | No | Each thread gets its own `TorrentZip` instance |
| `TorrentZip.process()` → `ZipFile` | Yes (new instance per call via try-with-resources) | None |
| `TorrentZipCheck.checkZipFiles()` | Yes (static, stateless) | None |
| `TorrentZipRebuild.reZipFiles()` | Yes (static, local buffers) | None |
| `Program` as `LogCallback` (stdout) | Partially (`println` is atomic but interleaves) | Buffer per-thread, flush atomically |
| `Program.failures` counter | No (currently `int`) | Use `AtomicInteger` |

## Design

### 1. Separate file discovery from processing

Extract the file-walking logic from `Program.run()` into a new `collectFiles()` method that returns `List<File>`. This includes:
- Directory recursion (`processDir` logic → returns list instead of processing)
- Glob/literal file handling (`processLiteralFileOrGlob` logic → returns list)

### 2. Process collected files — sequential or concurrent

In `run()`, after collecting files:
- If `files.size() <= 1`: call existing `processSingle()` (sequential, unchanged)
- If `files.size() > 1`: call new `processConcurrent(files)` using virtual threads

### 3. `processConcurrent(List<File>)` implementation

```java
private int processConcurrent(List<File> files) {
    var failures = new AtomicInteger(0);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var futures = new ArrayList<Future<?>>();
        for (File file : files) {
            futures.add(executor.submit(() -> {
                var tz = new TorrentZip(
                    new BufferedLogCallback(this),
                    new SimpleTorrentZipOptions(options.forceReZip(), options.checkOnly())
                );
                try {
                    Set<TrrntZipStatus> status = tz.process(file);
                    if (status.contains(TrrntZipStatus.CORRUPTZIP))
                        failures.incrementAndGet();
                } catch (IOException e) {
                    System.err.println(describe(e));
                    failures.incrementAndGet();
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(); }
            catch (ExecutionException | InterruptedException e) { /* log */ }
        }
    }
    return failures.get();
}
```

### 4. New `BufferedLogCallback` class

A `LogCallback` implementation that buffers all output in memory. When the file is done processing, `flushTo(target)` prints everything atomically under a shared lock.

```java
final class BufferedLogCallback implements LogCallback {
    private final LogCallback delegate;
    private final List<String> lines = new ArrayList<>();
    private final StringBuilder currentLine = new StringBuilder();

    // statusLogCallBack → buffer the line
    // statusCallBack → append percentage to current line buffer
    // isVerboseLogging → delegate
    // flushTo → synchronized on a shared lock, print all lines
}
```

The `flushTo` method uses a shared `Object` lock (or `synchronized` on `System.out`) to ensure the entire per-file output block prints without interleaving.

### 5. Refactor `processDir` and `processLiteralFileOrGlob` to collect-only

Change these methods from `int processDir(File)` → `void collectFromDir(File, List<File>)` and `void collectFromGlob(File, List<File>)`. They add discovered `.zip` files to the list instead of calling `processSingle` inline.

## Files to Change

| File | Change |
|---|---|
| `src/main/java/jtrrntzip/Program.java` | Refactor `run()`, extract `collectFiles()`, add `processConcurrent()`, refactor `processDir`/`processLiteralFileOrGlob` to collect-only |
| `src/main/java/jtrrntzip/BufferedLogCallback.java` | **New file** — buffering `LogCallback` with atomic flush |
| `src/main/java/jtrrntzip/messages.properties` | No changes needed (no new CLI flags) |
| `src/main/java/jtrrntzip/messages_fr.properties` | No changes needed |
| `src/main/java/module-info.java` | No changes needed (`java.util.concurrent` is in `java.base`) |
| `build.gradle.kts` | No changes needed (Java 25 toolchain already supports virtual threads) |

## Edge Cases

1. **Single file arg**: stays sequential — no executor overhead
2. **Directory with 1 zip**: `collectFiles` returns size 1 → sequential
3. **Corrupt file among many**: each thread handles its own failure; `AtomicInteger` accumulates
4. **Glob matches nothing**: empty list → no processing, returns 0
5. **`-c` check-only mode**: works unchanged — each thread creates its own `TorrentZip` with `checkOnly=true`
6. **`-f` force mode**: works unchanged
7. **`-l` verbose mode**: `BufferedLogCallback` delegates `isVerboseLogging()` to the original options
8. **Console output ordering**: files complete in arbitrary order; each file's output block is printed atomically but blocks may appear in any order. This is acceptable for a CLI tool.

## Validation

1. Run existing tests: `./gradlew test` — all must pass
2. Manual test: process a directory with multiple zips, verify output is not garbled
3. Manual test: process a single file, verify sequential behavior unchanged
4. Manual test: `-c` and `-f` flags work with multi-file
5. Verify no thread leaks: virtual threads are daemon threads, executor is closed with try-with-resources
