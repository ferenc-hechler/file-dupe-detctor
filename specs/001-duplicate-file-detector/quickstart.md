# Quickstart: Duplicate File Detector

**Purpose**: Validate the feature end-to-end once implemented. This is a run/validation guide, not an implementation guide — see [data-model.md](./data-model.md) and [contracts/](./contracts/) for design details, and `tasks.md` (from `/speckit-tasks`) for the implementation breakdown.

## Prerequisites

- Java 17+ JDK installed and on `PATH` (`java -version`, `javac -version`)
- Maven 3.8+ installed (`mvn -version`)
- No external services required — persistence is a local embedded H2 database file; no network access needed

## Build

```powershell
mvn -f pom.xml clean package
```

Expected outcome: `BUILD SUCCESS`, producing a runnable JAR (e.g., `target/file-dupe-detector-<version>.jar`) that bundles dependencies (H2 driver) via the shade/assembly plugin.

## Run

```powershell
java -jar target/file-dupe-detector-<version>.jar
```

Expected outcome: A Swing window opens. The persisted database file is created on first run under a user-local directory (e.g., `%USERPROFILE%\.file-dupe-detector\store.mv.db` on Windows).

## Validation Scenario 1 — First scan finds duplicates (User Story 1, P1)

1. Prepare two test folders, e.g. `C:\temp\dupe-test\A` and `C:\temp\dupe-test\B`.
2. Place an identical file (same bytes) in both folders under different names (e.g., `A\photo1.jpg` and `B\photo-copy.jpg`), plus one unique file in each folder.
3. In the GUI, add both folders as Scan Locations and start a scan.
4. **Expected**: The tree view shows both locations; `photo1.jpg` and `photo-copy.jpg` are visually marked as duplicates and selecting either shows both as members of the same duplicate group with full paths. The unique files are not marked as duplicates.

## Validation Scenario 2 — Re-scan reuses persisted data (User Story 2, P2)

1. Immediately after Scenario 1 completes, close the application.
2. Reopen the application; confirm the previously scanned locations and duplicate results are visible without re-running a scan (within ~5 seconds of startup — SC-004).
3. Trigger a re-scan of the same two folders without modifying any files.
4. **Expected**: Re-scan completes noticeably faster than the first scan (no re-hashing of unchanged files — SC-002), and results are identical to Scenario 1.
5. Modify one of the duplicate files' content (break the duplicate pair), then re-scan.
6. **Expected**: The tool detects the change (new hash computed for the modified file only) and the file is no longer part of the duplicate group (unless it now matches something else).
7. Delete one of the test files, then re-scan its folder.
8. **Expected**: The deleted file no longer appears in current duplicate results; it is marked missing/excluded rather than causing an error.

## Validation Scenario 3 — Tree view navigation (User Story 3, P3)

1. With scan results from Scenario 1/2 available, expand/collapse branches in the tree for both scan locations.
2. **Expected**: Expansion is responsive even if a folder contains many files (no freeze); folder hierarchy matches the real filesystem structure.
3. Select a file marked as a duplicate.
4. **Expected**: Within at most two interactions, the GUI shows all other members of that duplicate group, including each member's full path and which scan location (volume/cloud folder) it belongs to (SC-003).

## Validation Scenario 4 — Resilience (Edge Cases)

1. Add a removable volume (e.g., a USB drive or a folder standing in for one) as a scan location, then simulate it becoming unavailable mid-scan (e.g., unmount/disconnect while scanning a large folder, or simply remove a folder's read access).
2. **Expected**: The scan reports the interruption for that location but previously gathered results from other locations (and files already processed in this session) remain intact and persisted (FR-014).
3. Create a file with no read permission (or lock it with another process) inside a scanned folder, then scan.
4. **Expected**: The file is reported as skipped/errored (FR-012); the scan still completes for all other files.

## Success Criteria Traceability

| Scenario step | Success Criteria |
|---|---|
| Scenario 1 | SC-001, SC-006 |
| Scenario 2 | SC-002, SC-004 |
| Scenario 3 | SC-003 |
| Scenario 4 | SC-005 |
