---

description: "Task list template for feature implementation"
---

<!-- markdownlint-disable MD053 -->

# Tasks: Duplicate File Detector

**Input**: Design documents from `/specs/001-duplicate-file-detector/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Not explicitly requested as TDD in the feature specification. Unit/integration test tasks are still included (not as TDD-first gates) because `plan.md`'s Technical Context and Project Structure commit to JUnit 5 with a dedicated `src/test/java` tree as part of the architecture; they are ordered after the corresponding implementation task in each phase.

**Organization**: Tasks are grouped by user story (from `spec.md`) to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- File paths are relative to the repository root `C:\Users\micro\git\file-dupe-detctor\`

## Path Conventions

Single Maven project (per `plan.md` Structure Decision):
- `pom.xml` at repo root
- `src/main/java/com/filedupedetector/{model,scan,persistence,service,gui}/`
- `src/main/resources/schema.sql`
- `src/test/java/com/filedupedetector/{scan,persistence,service,gui}/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure

- [X] T001 Create Maven project skeleton: `pom.xml` at repo root with `groupId`/`artifactId`, Java 17 `maven.compiler.release`, and package structure directories under `src/main/java/com/filedupedetector/` (`model`, `scan`, `persistence`, `service`, `gui`) and `src/test/java/com/filedupedetector/` (`scan`, `persistence`, `service`, `gui`)
- [X] T002 Add dependencies to `pom.xml`: `com.h2database:h2` (embedded DB), `org.slf4j:slf4j-api` + `org.slf4j:slf4j-simple` (logging), `org.junit.jupiter:junit-jupiter` (test scope), `org.assertj:assertj-core` (test scope)
- [X] T003 [P] Configure `maven-shade-plugin` (or `maven-assembly-plugin`) in `pom.xml` to build a runnable fat JAR with `Main-Class: com.filedupedetector.Main`
- [X] T004 [P] Configure `maven-surefire-plugin` in `pom.xml` for unit test execution (`src/test/java` pattern `*Test.java`)
- [X] T005 [P] Create `.gitignore` entries for `target/` and local DB files (e.g. `*.mv.db`, `*.trace.db`) — append to existing `C:\Users\micro\git\file-dupe-detctor\.gitignore`
- [X] T006 Create `com.filedupedetector.Main` stub in `src/main/java/com/filedupedetector/Main.java` with a `main(String[] args)` that prints a startup banner (placeholder until Phase 2/5 wire real startup)

**Checkpoint**: `mvn clean package` succeeds and produces a runnable (placeholder) JAR.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T007 [P] Create `ScanLocation` model in `src/main/java/com/filedupedetector/model/ScanLocation.java` with fields `id` (long), `displayName` (String), `rootPath` (String, must be non-blank and unique), `type` (enum `LocationType { LOCAL, REMOVABLE, CLOUD }`), `lastScannedAt` (nullable Instant), `available` (boolean)
- [X] T008 [P] Create `FileRecord` model in `src/main/java/com/filedupedetector/model/FileRecord.java` with fields `id` (long), `scanLocationId` (long), `relativePath` (String, unique together with `scanLocationId`), `sizeBytes` (long), `contentHash` (nullable String, hex SHA-1), `lastModifiedAt` (Instant), `status` (enum `FileStatus { PRESENT, MISSING, ERRORED }`), `lastScanSessionId` (nullable long), `lastSeenAt` (Instant)
- [X] T009 [P] Create `ScanSession` model in `src/main/java/com/filedupedetector/model/ScanSession.java` with fields `id` (long), `startedAt`/`endedAt` (Instant, nullable end), `scanLocationIds` (List<Long>), `filesProcessed`/`filesSkippedErrored`/`filesMissing` (int), `outcome` (enum `ScanOutcome { COMPLETED, INTERRUPTED }`)
- [X] T010 [P] Create `DuplicateGroup` derived model in `src/main/java/com/filedupedetector/model/DuplicateGroup.java` with fields `sizeBytes` (long), `contentHash` (String), `members` (List<FileRecord>); only materialized with 2+ members per data-model.md
- [X] T011 Create embedded DB schema in `src/main/resources/schema.sql`: tables `scan_location` (unique index on `root_path`), `file_record` (unique index on `(scan_location_id, relative_path)`, index on `(size_bytes, content_hash)`), `scan_session`, join table `scan_session_location`
- [X] T012 Create `JdbcConnectionManager` in `src/main/java/com/filedupedetector/persistence/JdbcConnectionManager.java` that opens/initializes the embedded H2 file DB at `${user.home}/.file-dupe-detector/store` (MVStore mode), running `schema.sql` on first startup (idempotent `CREATE TABLE IF NOT EXISTS`)
- [X] T013 [P] Create `ScanLocationRepository` interface + JDBC implementation in `src/main/java/com/filedupedetector/persistence/ScanLocationRepository.java` / `JdbcScanLocationRepository.java` per contracts: `save`, `findAll`, `markAvailability`
- [X] T014 [P] Create `FileRecordRepository` interface + JDBC implementation in `src/main/java/com/filedupedetector/persistence/FileRecordRepository.java` / `JdbcFileRecordRepository.java` per contracts: `findByLocationAndPath`, `upsert` (keyed by unique `(scanLocationId, relativePath)`), `markMissing`, `findDuplicateGroups` (query: `status='PRESENT' AND content_hash IS NOT NULL GROUP BY size_bytes, content_hash HAVING COUNT(*) > 1`), `findErroredBySession`
- [X] T015 [P] Create `ScanSessionRepository` interface + JDBC implementation in `src/main/java/com/filedupedetector/persistence/ScanSessionRepository.java` / `JdbcScanSessionRepository.java` per contracts: `start`, `complete`
- [X] T016 [P] Create `FileWalker` + `FileVisitCallback` interfaces in `src/main/java/com/filedupedetector/scan/FileWalker.java` / `FileVisitCallback.java` per contracts, with an `InaccessibleReason` enum (`CLOUD_PLACEHOLDER`, `SYMLINK_SKIPPED`, `OTHER`)
- [X] T017 [P] Create `HashService` interface in `src/main/java/com/filedupedetector/scan/HashService.java` per contracts (`computeHash(Path): String`, streaming, no whole-file load)
- [X] T018 Configure shared logging setup (SLF4J binding config / `simplelogger.properties`) in `src/main/resources/simplelogger.properties` for consistent log formatting across all packages

**Checkpoint**: Foundation ready — models, schema, repositories, and scan interfaces compile; `mvn test` runs (even with no tests yet) without errors; user story implementation can now begin.

---

## Phase 3: User Story 1 - Scan locations and find duplicates (Priority: P1) 🎯 MVP

**Goal**: Scan one or more user-designated locations, compute size + SHA-1 hash per file, and group files into duplicate groups by exact (size, hash) match.

**Independent Test**: Point the tool at two or more folders (one pair of files with identical content/different names, plus unique files) and verify the resulting duplicate groups are correct per `quickstart.md` Validation Scenario 1.

### Implementation for User Story 1

- [X] T019 [P] [US1] Implement `NioFileWalker` (implements `FileWalker`) in `src/main/java/com/filedupedetector/scan/NioFileWalker.java` using `Files.walkFileTree`: do not follow symlinks, catch `IOException` per file/directory and report via `FileVisitCallback.onError` instead of aborting the walk (FR-012, FR-014), detect non-regular/placeholder files and report via `onInaccessible`
- [X] T020 [P] [US1] Implement `Sha1HashService` (implements `HashService`) in `src/main/java/com/filedupedetector/scan/Sha1HashService.java` using `java.security.MessageDigest` with a buffered `InputStream` (fixed-size chunk reads, never loading the whole file into memory)
- [X] T021 [US1] Implement `DuplicateMatcher` in `src/main/java/com/filedupedetector/service/DuplicateMatcher.java`: given a list of `(path, sizeBytes)` pairs, group by `sizeBytes` first and return only the subset of paths that share a size with at least one other path (size pre-filter per research.md decision 4), to be passed on for hashing
- [X] T022 [US1] Implement `ScanService` (per contracts) in `src/main/java/com/filedupedetector/service/ScanServiceImpl.java`: `scanAsync(locations, listener)` orchestrates `NioFileWalker` → `DuplicateMatcher` size pre-filter → `Sha1HashService` for candidates → `FileRecordRepository.upsert` for each file (status=PRESENT, contentHash set; status=ERRORED with null/previous hash for failures per FR-012); runs on a background thread (not the caller's thread) and invokes `ScanProgressListener` callbacks
- [X] T023 [US1] Implement `DuplicateQueryService` (per contracts) in `src/main/java/com/filedupedetector/service/DuplicateQueryServiceImpl.java`: `getAllDuplicateGroups()` delegates to `FileRecordRepository.findDuplicateGroups()`; `getGroupMembers(key)` returns full `FileRecord` list (with full path = `ScanLocation.rootPath` + `FileRecord.relativePath`) for a given `(sizeBytes, contentHash)` key
- [X] T024 [US1] Wire a minimal console entry point in `src/main/java/com/filedupedetector/Main.java`: accept one or more folder paths as CLI args, register them as `ScanLocation`s, run `ScanService.scanAsync` synchronously (blocking wait for this manual-test path), print resulting duplicate groups with full paths to stdout — serves as a smoke-test harness ahead of the GUI (Phase 5)
- [X] T025 [P] [US1] Unit test `Sha1HashServiceTest` in `src/test/java/com/filedupedetector/scan/Sha1HashServiceTest.java`: verify identical-content files (different names) produce identical hashes; verify files differing by a single byte produce different hashes
- [X] T026 [P] [US1] Unit test `DuplicateMatcherTest` in `src/test/java/com/filedupedetector/service/DuplicateMatcherTest.java`: verify files with unique sizes are excluded from hashing candidates; verify files sharing a size are all included
- [X] T027 [US1] Integration test `ScanServiceDuplicateDetectionTest` in `src/test/java/com/filedupedetector/service/ScanServiceDuplicateDetectionTest.java` using JUnit `@TempDir`: create two folders with one identical-content pair (different names) + one unique file per folder each; run `ScanService.scanAsync` against an in-memory/test H2 instance; assert exactly one duplicate group with 2 members and the unique files are absent from any group (covers acceptance scenarios 1–3 of User Story 1)

**Checkpoint**: At this point, User Story 1 is fully functional and independently testable (via the console harness and automated tests) without the GUI or re-scan optimizations.

---

## Phase 4: User Story 2 - Reuse persisted file information to avoid recomputation (Priority: P2)

**Goal**: Re-scanning a previously scanned location reuses stored size/hash data for unchanged files, recomputes only for changed files, marks missing files, and survives an application restart.

**Independent Test**: Run a scan, restart the application, re-scan the same unmodified folder and confirm hashes are reused (not recomputed) and results are unchanged; modify/delete a file and confirm correct re-detection, per `quickstart.md` Validation Scenario 2.

### Implementation for User Story 2

- [X] T028 [US2] Extend `ScanServiceImpl` (`src/main/java/com/filedupedetector/service/ScanServiceImpl.java`) with change-detection logic: before hashing a file, call `FileRecordRepository.findByLocationAndPath`; if an existing `PRESENT` record's `sizeBytes` and `lastModifiedAt` both match the current file's values, reuse the stored `contentHash` and skip re-hashing (FR-006); otherwise recompute the hash (FR-007)
- [X] T029 [US2] Extend `ScanServiceImpl` to call `FileRecordRepository.markMissing(scanLocationId, relativePathsNotSeen, scanSessionId)` after walking a location, setting `status=MISSING` for any previously `PRESENT` record whose `relativePath` was not observed in the current walk (FR-008); on a later re-scan where a `MISSING` path reappears, treat it as new (always recompute hash per data-model.md state transition, since the prior hash cannot be trusted across an absence)
- [X] T030 [US2] Wire `ScanSessionRepository.start`/`complete` into `ScanServiceImpl`: start a session before walking, track `filesProcessed`/`filesSkippedErrored`/`filesMissing` counts, and call `complete` with `outcome=COMPLETED` on success or `outcome=INTERRUPTED` (while still persisting all `FileRecord` updates gathered so far, per FR-014) when a location becomes unavailable mid-scan — surfaced via `ScanProgressListener.onLocationUnavailable`
- [X] T031 [US2] Implement `ScanLocation` availability tracking: at the start of a scan, if the configured `rootPath` is not accessible, call `ScanLocationRepository.markAvailability(id, false)` instead of failing the whole run (Edge Case: disconnected volume); mark `true` again once a scan of that location succeeds
- [X] T032 [US2] Update `Main.java` (or add an `AppBootstrap` class in `src/main/java/com/filedupedetector/service/AppBootstrap.java`) to load all persisted `ScanLocation`s and existing duplicate groups via `ScanLocationRepository.findAll()` / `DuplicateQueryService.getAllDuplicateGroups()` on startup, so results are available without a new scan (FR-005, SC-004)
- [X] T033 [P] [US2] Integration test `RescanReusesHashTest` in `src/test/java/com/filedupedetector/service/RescanReusesHashTest.java`: scan a folder, record the stored `contentHash` and a `HashService` call counter/spy, re-scan without modifying files, assert the hash service was NOT invoked again for unchanged files and results are identical (SC-002)
- [X] T034 [P] [US2] Integration test `RescanDetectsChangesTest` in `src/test/java/com/filedupedetector/service/RescanDetectsChangesTest.java`: scan a folder, modify one file's content (change size/mtime), re-scan, assert only the modified file's hash was recomputed and duplicate grouping reflects the change
- [X] T035 [P] [US2] Integration test `RescanDetectsMissingFilesTest` in `src/test/java/com/filedupedetector/service/RescanDetectsMissingFilesTest.java`: scan a folder, delete one file, re-scan, assert the deleted file's `FileRecord.status` becomes `MISSING` and it is excluded from current duplicate groups

**Checkpoint**: At this point, User Stories 1 AND 2 both work independently — re-scans are fast and accurate, and results persist across restarts.

---

## Phase 5: User Story 3 - Visualize files and duplicates in a tree view (Priority: P3)

**Goal**: A Swing GUI presents scanned locations and their folder/file hierarchy in a `JTree`, visually marks duplicate files, and lets the user inspect full duplicate group membership.

**Independent Test**: After a scan with known duplicates at different folder depths, open the GUI and verify the tree matches the real folder hierarchy, duplicates are visually distinguishable, and selecting a duplicate shows all group members with full paths/locations, per `quickstart.md` Validation Scenario 3.

### Implementation for User Story 3

- [X] T036 [P] [US3] Implement `LazyFileTreeNode` + `LazyFileTreeModel` in `src/main/java/com/filedupedetector/gui/LazyFileTreeModel.java`: root nodes are `ScanLocation`s; child nodes (folders/files) are loaded on demand from `FileRecordRepository` only when a node is expanded (not the whole tree eagerly), so expansion remains responsive for 10k+ files (FR-009, Edge Case responsiveness requirement)
- [X] T037 [P] [US3] Implement `DuplicateAwareTreeCellRenderer` in `src/main/java/com/filedupedetector/gui/DuplicateAwareTreeCellRenderer.java`: for each file node, query whether it belongs to a `DuplicateGroup` (via `DuplicateQueryService`) and apply a distinct icon/color/badge if so (FR-010)
- [X] T038 [US3] Implement `DuplicateGroupDetailPanel` in `src/main/java/com/filedupedetector/gui/DuplicateGroupDetailPanel.java`: given a selected duplicate file/group, call `DuplicateQueryService.getGroupMembers(key)` and render each member's full path and source `ScanLocation` display name (FR-011)
- [X] T039 [US3] Implement `MainWindow` (`JFrame`) in `src/main/java/com/filedupedetector/gui/MainWindow.java`: assembles a `JTree` (using `LazyFileTreeModel` + `DuplicateAwareTreeCellRenderer`) alongside `DuplicateGroupDetailPanel`, an "Add Scan Location" dialog/button (invokes `ScanLocationRepository.save`), and a "Scan" button that calls `ScanService.scanAsync` via a `SwingWorker` so hashing/walking never blocks the Event Dispatch Thread
- [X] T040 [US3] Add a progress indicator (e.g., `JProgressBar` + status label) to `MainWindow` wired to `ScanProgressListener.onProgress`/`onSessionComplete`/`onLocationUnavailable` callbacks, including a visible skipped/errored files count (FR-012)
- [X] T041 [US3] Update `Main.java` to launch `MainWindow` on the Swing Event Dispatch Thread via `SwingUtilities.invokeLater`, replacing/supplementing the Phase 3 console harness as the primary entry point, loading persisted state via `AppBootstrap` (T032) on startup
- [X] T042 [P] [US3] Unit test `LazyFileTreeModelTest` in `src/test/java/com/filedupedetector/gui/LazyFileTreeModelTest.java`: verify child nodes are not loaded until a parent node is expanded/queried, and that the folder hierarchy produced matches a known `FileRecord` path structure (headless, no `Toolkit`/display required)
- [X] T043 [P] [US3] Unit test `DuplicateGroupDetailPanelLogicTest` in `src/test/java/com/filedupedetector/gui/DuplicateGroupDetailPanelLogicTest.java`: extract the selection-to-members mapping logic into a plain testable method and verify it returns all expected members with correct full paths, independent of actual Swing rendering

**Checkpoint**: All user stories are now independently functional; the full GUI experience from `quickstart.md` Scenarios 1–3 is achievable end-to-end.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [X] T044 [P] Add a `README.md` at repo root (or update existing) documenting build (`mvn clean package`), run (`java -jar ...`), and basic usage instructions, cross-referencing `specs/001-duplicate-file-detector/quickstart.md`
- [X] T045 Review and harden error handling across `scan`/`persistence`/`service` packages: ensure every checked `IOException`/`SQLException` path results in a reported skipped/errored state (FR-012) rather than an uncaught exception reaching the EDT
- [X] T046 [P] Add unit tests for edge cases in `src/test/java/com/filedupedetector/scan/NioFileWalkerEdgeCasesTest.java`: zero-byte files compared by hash, unreadable/locked file reported as errored without stopping the walk, symbolic links not followed
- [X] T047 Run full `quickstart.md` validation manually (Scenarios 1–4) against the built JAR and record results/any deviations in `specs/001-duplicate-file-detector/quickstart.md` notes or a short validation log
- [X] T048 Performance pass: verify re-scan of ~10k unchanged files meets the ≥80% time-reduction target (SC-002) using a disposable test dataset; tune buffered I/O chunk size in `Sha1HashService` if needed

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational completion only
- **User Story 2 (Phase 4)**: Depends on Foundational completion; extends `ScanServiceImpl` from Phase 3 (T022) in place — should be started after Phase 3 completes to avoid merge conflicts on the same file, though the underlying persistence/change-detection logic is conceptually independent
- **User Story 3 (Phase 5)**: Depends on Foundational completion; consumes `ScanService`/`DuplicateQueryService` from Phase 3 (and benefits from Phase 4's persisted-state startup loading, T032) but does not modify their files — can be developed in parallel with Phase 4 by a different contributor
- **Polish (Phase 6)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: No dependencies on other stories — standalone MVP
- **User Story 2 (P2)**: Builds on top of US1's `ScanServiceImpl`/repositories (same files extended, not replaced) — functionally independent (its acceptance scenarios can be verified without the GUI) but implemented as a direct extension of US1 code
- **User Story 3 (P3)**: Consumes US1/US2 services as read-only dependencies (`ScanService`, `DuplicateQueryService`, `ScanLocationRepository`) through the documented interfaces in `contracts/` — does not modify US1/US2 files, so it is independently testable and can be built in parallel with US2

### Within Each User Story

- Implementation tasks before their corresponding tests (tests validate the just-built behavior)
- Models/interfaces (Foundational) before services (US1/US2) before GUI (US3)
- Story complete and checkpoint validated before moving to the next priority (if working sequentially)

### Parallel Opportunities

- All Setup tasks marked `[P]` (T003–T005) can run in parallel after T001–T002
- All Foundational model tasks marked `[P]` (T007–T010) can run in parallel; repository/interface tasks marked `[P]` (T013–T017) can run in parallel once models (T007–T010) and schema (T011–T012) exist
- Within US1: T019 and T020 (walker, hasher) can run in parallel; T025/T026 (unit tests) can run in parallel once their respective implementations exist
- Within US2: T033–T035 (integration tests) can run in parallel once T028–T031 are complete
- Within US3: T036 and T037 (tree model, renderer) can run in parallel; T042/T043 can run in parallel
- Once Foundational (Phase 2) completes, US3's GUI-facing scaffolding (T036, T037) can begin in parallel with US2's service extensions (T028–T031), since they touch different files

---

## Parallel Example: User Story 1

```bash
# Launch core US1 implementation pieces together (different files):
Task: "Implement NioFileWalker in src/main/java/com/filedupedetector/scan/NioFileWalker.java"
Task: "Implement Sha1HashService in src/main/java/com/filedupedetector/scan/Sha1HashService.java"

# Launch US1 unit tests together once implementations exist:
Task: "Unit test Sha1HashServiceTest in src/test/java/com/filedupedetector/scan/Sha1HashServiceTest.java"
Task: "Unit test DuplicateMatcherTest in src/test/java/com/filedupedetector/service/DuplicateMatcherTest.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Run `quickstart.md` Validation Scenario 1 via the console harness (T024) and automated tests (T025–T027)
5. Demo duplicate detection via console output if ready

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Demo (MVP!)
3. Add User Story 2 → Test independently (persistence/re-scan speedup) → Demo
4. Add User Story 3 → Test independently (full GUI) → Demo
5. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1, then User Story 2 (same files, sequential)
   - Developer B: User Story 3 GUI scaffolding (T036, T037) in parallel, integrating with US1/US2 service interfaces as they land
3. Stories complete and integrate via the stable `contracts/` interfaces

---

## Notes

- `[P]` tasks = different files, no dependencies
- `[Story]` label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same-file conflicts, cross-story dependencies that break independence
