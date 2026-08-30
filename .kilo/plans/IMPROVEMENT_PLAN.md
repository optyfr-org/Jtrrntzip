# Jtrrntzip - Review Findings and Improvement Plan (final v3, 2026-08-29)

Status: implementation-ready. All open decisions D1-D5 are resolved. First run scope is W0 plus W1,
released as 1.4.0, followed by the W2 refactor as 2.0.0. No implementation has been performed yet.
This file is the authoritative plan.

## Resolved decisions

D1 Breaking API changes: YES. Ship W1 as 1.4.x, stage deprecations in 1.4 where mechanical
   (centeralDirectoryWrite rename), land W2 as 2.0.0 including dead-API removal.

D2 Duplicate entries: keep one entry and rebuild when name, CRC and size are identical,
   differing duplicates mark the zip corrupt and skip rebuild. Diverges from the report-only
   reference behavior, documented in a code comment.

D3 Exit codes: 0 all files processed OK, 1 one or more files corrupt or failed, 2 usage
   error. Help and version flags exit 0, the -g GUI pause flow is otherwise unchanged, and
   per-file errors no longer abort the traversal (BUG-9).

D4 Test dumps: keep the dump facility but gate it behind -Djtrrntzip.test.dump=true and
   write into build/dump, zero output by default.

D5 First run: W0 plus W1 only, then reassess, W2 as a 2.0.0 follow-up, trivial W3 items
   ride along in W1 pull requests where natural.

## Out of scope (explicit)

7z support (the port is zip-only by design), RVZSTD, parallel rebuild, and the MD5/SHA1

deep-scan features present in the C# original.

## Findings

Correctness bugs verified this session with file and line references. The C# reference
arogl/trrntzipDN was cross-checked: flaws marked ported exist there too, so fixing them
intentionally diverges from the reference.

1. BUG-1 (HIGH) Short-read corruption in the rebuild copy loop, TorrentZipRebuild.java lines
129-131. Fix with a filled-read loop, treat EOF as corruption. Ported flaw.

2. BUG-2 (HIGH) Resource leaks on the CRC-mismatch early return, TorrentZipRebuild.java lines
142-143, the tmp file is left on disk and the original channel stays open. Fix with
try-finally plus zipFileCloseFailed plus tmp deletion on all exits. Ported flaw.
3. BUG-3 (HIGH) ZIP64 not forced above 65,535 entries, ZipFile.java lines 181-182 and
364-390, EOCD counts are capped with no zip64 EOCD. Trigger zip64 in zipFileClose when
localFiles.size() strictly exceeds 0xffff. Ported flaw contradicting the ZIP APPNOTE.
4. BUG-4 (MEDIUM) zipFileOpenReadStreamQuick clears localFiles, ZipFile.java lines 651-652,
verified dead exported API across main and test sources, remove per decision D1.
5. BUG-5 (MEDIUM) NPE for parentless output paths, createDirForFile in ZipFile.java lines
35-38, guard the null parent.
6. BUG-6 (MEDIUM) Glob metacharacters break literal file args, Program.java line 61. Fix
with an exact-path lookup first, escaping the pattern on PatternSyntaxException.
7. BUG-7 (MEDIUM) Duplicate entries are rebuilt as-is today so the output fails ordering
validation on reopen, TorrentZipCheck.java lines 125-137, policy per decision D2.
8. BUG-8 (HIGH) Comparator divergence: the port uses full-Unicode compareToIgnoreCase where
the reference uses an ASCII-only fold, in TorrentZipCheck.java line 57 and ZipFile.java
lines 530-554. Non-ASCII names get ordered differently than by trrntzipDN, RomVault and
other trrntzip tools. Port the reference comparator verbatim and use it in the rule 2
sort, the order validation and the directory checks.
9. BUG-9 (MEDIUM) One IOException aborts an entire directory traversal, Program.processDir,
fix with a per-file try-catch that logs, continues and counts failures for D3.
10. BUG-10 (HIGH) Exactly 65,535-entry zips fail to reopen because count 0xffff
unconditionally demands a zip64 locator, ZipFile.java lines 556-571. Peek the locator
signature before entering the zip64 path, otherwise treat 0xffff as the literal count.

**Robustness findings**: the catch-all in TorrentZipRebuild.java lines 47-61 returns corrupt
without logging, LOGGER.log(Level.FINE, e::getMessage) drops stack traces in LocalFile.java
lines 174, 306 and 352, Program prints possibly null messages at lines 49, 73 and 81,
localFileCheck swallows exceptions at LocalFile.java line 219, and the comment-CRC
compare should be strict ordinal like the reference, ZipFile.java lines 575-588, low
priority.

**API and design, for W2 per decision D1**: replace BigInteger sizes with long, replace the
AtomicReference and boolean-array out-params with records, use ByteArrayOutputStream for
extra fields, rename centeralDirectoryWrite with a staged deprecation, remove the dead
API (BUG-4 plus deepScan, localHeader, zipFileRollBack, zipFileAddDirectory), replace the
quadratic bubble sort with a detection pass plus List.sort using the shared comparator
with an equivalence test, extract Program.run, split AbstractTorrentZipOptions into a pure
parser plus a help printer with unit tests, and unify the duplicated directory-entry
logic.

**Tests**: keep the golden corpus of 16 real zips and the roundtrip design, simplify
extractZipToDir after BUG-4 removal, gate the dumps per D4, and add negative-path tests
for truncated zips, bad CRC, identical and conflicting duplicates, 65,535 and 65,536
entries, rollback and zipFileCloseFailed behavior, CLI parsing, and a one-byte-read
stream decorator for BUG-1.

**Build and CI, for W0**: pin the floating dependency and plugin versions by resolving with
gradlew dependencies and gradlew buildEnvironment then hardcoding them, remove the
unused commons-lang3, add gradle wrapper validation, remove the bogus submodule flags,
fix the cross-job cache strategy, spell -x test explicitly, reroute GraalVM agent metadata
out of src/main/resources, verify jacoco output for Java 25 class files, move the version
out of dist/ver.properties into build.gradle, and expand the README.

## Workstreams and PR plan

1. W0 Build and CI hardening, no product behavior change. Acceptance: two consecutive cold
CI runs green with identical dependency resolution and no src/main/resources diffs.
2. W1 Correctness, failing test per bug first, released as 1.4.0:
   - PR1 BUG-1 and BUG-2, one-byte-read decorator test plus a forced-failure test asserting
     tmp deletion, an unlocked source, and CORRUPTZIP status.
   - PR2 BUG-3 and BUG-10, roundtrips with exactly 65,535 and 65,536 entries.
   - PR3 BUG-8, shared comparator, CP437 high-byte and UTF-8 fixtures, and a golden-corpus
     regression guard that ASCII orderings stay unchanged.
   - PR4 BUG-5, BUG-6, BUG-9 plus decision D3 exit codes, minimal Program.run extraction.
   - PR5 BUG-7 per decision D2, duplicate fixture with identical and conflicting cases.
   - PR6 Logging and exception fixes including the strict comment-CRC compare.
3. W2 API refactor as 2.0.0 after 1.4 ships, per the items listed above under API and design.
4. W3 Docs and quality riding along: README expansion, format unification (older files use
tabs, LocalFile.java uses 4 spaces), optional 64 KiB buffers.


## Verification

Per-bug failing test first. gradlew build jacocoTestReport green, gradlew sonar with no
regressions, manual smoke on the specs corpus, bracket-named files, a corrupt zip, deep
directories with and without -s, Windows-primary smoke for locking and path edge cases,
no leftover tmp files, and no locked files after a forced failure.

## Spec sources

TorrentZip spec text at the wiki.romvault.com torrentzip page and romvault.com
trrntzip-explained.pdf by GordonJ, identical to specs/torrentzip.pdf which this model
cannot read directly, plus the C# original arogl/trrntzipDN used as the port-parity
reference.

## Revision notes

Effort estimates removed per planning policy. BUG-8 and BUG-10 were added after
reference cross-checks. The BUG-3 trigger was corrected to strictly-greater because
exactly 65,535 entries is legal in a classic EOCD. The earlier claim that -s is ignored
in the wildcard branch was withdrawn, that branch is inherently non-recursive. The
comment-CRC case-sensitivity difference was confirmed against the reference and kept as
a low-priority alignment item.