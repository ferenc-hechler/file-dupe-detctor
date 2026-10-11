# Phase 0 Research: Duplicate File Detector

**Input**: Technical Context from [plan.md](./plan.md), requirements from [spec.md](./spec.md)

This document resolves all technology choices needed before Phase 1 design. No `NEEDS CLARIFICATION` markers remained in the Technical Context, so research below focuses on validating the chosen stack and best practices for each area.

## 1. Language & Build Tooling

**Decision**: Java 17 (LTS) with Maven (standard `pom.xml`, `maven-compiler-plugin`, `maven-shade-plugin` or `maven-assembly-plugin` for a runnable fat JAR).

**Rationale**: User explicitly requested "a java application using maven and a swing GUI." Java 17 is the most recent widely-available LTS release, has a stable `java.nio.file` API (needed for robust filesystem walking, symbolic link detection, and file attributes such as last-modified time) and built-in Swing — no additional GUI dependency required. Maven is the requested build tool and has mature plugins for packaging a runnable desktop JAR.

**Alternatives considered**:
- *Gradle*: Equally capable but not what the user asked for; rejected to match explicit instruction.
- *JavaFX* instead of Swing: More modern look-and-feel but requires extra module dependencies (not bundled with the JDK since Java 11) and the user explicitly asked for Swing.

## 2. GUI Framework — Swing Tree View

**Decision**: `javax.swing.JTree` with a custom `TreeModel` (backed by an in-memory tree built from Scan Location → folder → file nodes) and a custom `TreeCellRenderer` to visually flag duplicate files (icon/color/badge). Long-running scan/hash work is executed on a `javax.swing.SwingWorker` (or a background `ExecutorService` with results marshalled back via `SwingUtilities.invokeLater`) to keep the Event Dispatch Thread (EDT) responsive.

**Rationale**: `JTree` is the standard Swing component for hierarchical data and satisfies FR-009/FR-010/FR-011 directly. A custom `TreeModel` (rather than `DefaultTreeModel` populated eagerly) allows lazy/incremental expansion so large result sets (10k+ files, per SC and Edge Cases) don't block the UI or require loading the entire tree eagerly. `SwingWorker` is the idiomatic way to keep scanning/hashing off the EDT while allowing progress updates and cancellation.

**Alternatives considered**:
- *JTable* flat list with grouping column: Simpler, but does not naturally represent folder hierarchy as requested ("tree like view").
- Loading entire result set into a `DefaultMutableTreeNode` tree eagerly: Simpler to implement but risks UI unresponsiveness for very large scans (violates the "remains responsive" edge case/FR-009 requirement); mitigated by lazy child-loading per node instead.

## 3. Persistence

**Decision**: H2 Database Engine (embedded, file-based, `MVStore` mode) accessed via plain JDBC (`java.sql.*`), with a simple hand-written repository layer (no ORM) and schema defined in a bundled `schema.sql` executed on first startup / schema version check.

**Rationale**: Requirement is "file information has to be persisted so that hash calculation has not to be done every time" (FR-005/FR-006/FR-007/FR-008, SC-002, SC-004). An embedded relational database:
- Requires no external server/install (pure-Java, single dependency, single local file) — fits a desktop tool distributed as one JAR.
- Supports indexed lookup by `(path)` for change-detection (size/mtime comparison) and by `(size, hash)` for duplicate grouping, which is exactly the Key Entities' access pattern (File Record, Duplicate Group).
- Is ACID and crash-safe, so partial/interrupted scans (Edge Case: location disconnects mid-scan) don't corrupt previously stored results.
- Scales comfortably to the stated scope (tens of thousands of file rows per session).

**Alternatives considered**:
- *Flat file / JSON / Java serialization*: Simple but would require implementing indexed lookup and incremental update logic by hand, and is more fragile under partial writes (crash-safety) — rejected given persistence correctness is a core requirement (User Story 2).
- *SQLite via JDBC driver (org.xerial:sqlite-jdbc)*: Also viable and very popular, but ships a native binary per platform; H2 is pure Java (simpler cross-platform packaging, matches "minimal dependencies" preference) — H2 preferred for this project.
- *Full ORM (e.g., Hibernate/JPA)*: Adds significant dependency weight and complexity disproportionate to the four simple entities in this feature; a thin hand-written repository layer is simpler and sufficient (YAGNI).

## 4. Hashing Strategy

**Decision**: Two-stage comparison — (1) group candidate files by exact file size first (cheap, from file metadata, no I/O beyond a directory listing), then (2) compute a cryptographic hash (SHA-256 via `java.security.MessageDigest`) only for files that share a size with at least one other file, streaming the file content in fixed-size buffered chunks rather than loading whole files into memory.

**Rationale**: Matches FR-002/FR-003 exactly ("size AND content hash both match") and avoids wastefully hashing every file when most files have a unique size (classic duplicate-finder optimization, directly supports SC-002's re-scan performance target when combined with persistence reuse). SHA-256 is a cryptographically strong, collision-resistant hash available in the JDK standard library with no extra dependency, satisfying the Assumptions section's "collisions considered negligible in practice."

**Alternatives considered**:
- *MD5/CRC32*: Faster but weaker/collision-prone (CRC32 especially); rejected given the spec's explicit assumption of a "cryptographically strong, collision-resistant hash algorithm."
- *Hash-first (no size pre-filter)*: Simpler code path but strictly more I/O/CPU than necessary; rejected since size is essentially free to obtain and eliminates the majority of non-candidate files before any hashing.

## 5. Change Detection for Re-Scans (User Story 2)

**Decision**: For each file encountered during a scan, compare its current `(size, lastModifiedTime)` against the persisted File Record for the same path. If both match the stored values, reuse the stored hash and skip re-reading file content. If either differs, recompute the hash and update the record. If a previously recorded path is no longer found during the scan of its location, mark that File Record as `last-seen status = missing` rather than deleting it outright (so history/UI can still show "not currently available" per the Edge Cases section).

**Rationale**: Directly implements FR-006/FR-007/FR-008 and the acceptance scenarios in User Story 2. Using `(size, lastModifiedTime)` as the cheap "has this file possibly changed" signal is the standard, low-cost heuristic used by most duplicate-finder and backup tools before falling back to expensive re-hashing.

**Alternatives considered**:
- *Always re-hash on every scan*: Simplest but defeats the entire purpose of persistence (fails SC-002 by definition); rejected.
- *Content-based change detection (re-hash everything to detect changes)*: Circular — would require doing exactly the work persistence is meant to avoid; rejected.

## 6. Handling Inaccessible / Special Files

**Decision**: Use `java.nio.file.Files` APIs (`Files.walkFileTree` with a `FileVisitor`, `Files.readAttributes`) which surface `IOException` per file/directory rather than aborting the whole walk. Symbolic links are not followed by default (`FileVisitOption` not set), and files whose basic attributes indicate a non-regular, non-locally-materialized state (e.g., cloud placeholder markers, where detectable via `DosFileAttributes`/provider-specific attributes) are recorded as skipped rather than hashed. All I/O errors (permission denied, locked file) during attribute reads or hashing are caught per-file and recorded in the Scan Session's skipped/errored count (FR-012) without stopping the overall scan (FR-014, SC-005).

**Rationale**: `java.nio.file` is designed exactly for this kind of robust, resumable tree walking with per-file error callbacks, satisfying the edge cases around locked files, permission errors, and partial location availability.

**Alternatives considered**:
- *`java.io.File` + manual recursion*: Older API, weaker error reporting and no clean per-file visitor/error-handling contract; rejected in favor of `java.nio.file`.

## 7. Testing Strategy

**Decision**: JUnit 5 for all unit and integration tests. Core logic (hashing, size pre-filtering, duplicate grouping, change detection, repository CRUD) is tested headlessly without launching any GUI. GUI-adjacent logic (tree model construction, node-to-duplicate-group mapping, selection-to-detail-view logic) is factored out of `JTree`/Swing classes into plain Java classes so it can be unit tested directly; only thin Swing wiring remains untested by automated tests (validated manually via `quickstart.md`).

**Rationale**: Keeps the bulk of business logic (which is where correctness for FR-001–FR-014 matters most) fast and reliably testable in CI/local runs without requiring a display/headless Swing environment, while still allowing confidence in the GUI layer's logic.

**Alternatives considered**:
- *AssertJ-Swing full UI automation*: Possible but adds a heavy dependency and flaky, environment-dependent tests (requires a display); deferred — logic is extracted and tested directly instead, with AssertJ-Swing left as an optional future enhancement rather than a hard requirement.

## Summary of Resolved Decisions

| Area | Decision |
|------|----------|
| Language/Build | Java 17 + Maven |
| GUI | Swing `JTree` + custom `TreeModel`/`TreeCellRenderer`, `SwingWorker` for background work |
| Persistence | Embedded H2 (file-based, JDBC, hand-written repository layer) |
| Hashing | SHA-256, size-first two-stage comparison, streamed/buffered I/O |
| Change detection | `(size, lastModifiedTime)` comparison against persisted File Record |
| File walking | `java.nio.file.Files.walkFileTree`, per-file error isolation |
| Testing | JUnit 5, logic extracted from Swing classes for headless testability |

All `NEEDS CLARIFICATION` items: none remained after `/speckit-specify` and `/speckit-plan` Technical Context authoring.
