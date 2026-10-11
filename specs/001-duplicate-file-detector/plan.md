# Implementation Plan: Duplicate File Detector

**Branch**: `001-duplicate-file-detector` | **Date**: 2026-10-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-duplicate-file-detector/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Build a Java desktop application that scans user-designated locations (local drives, removable volumes, cloud-sync folders mounted on the local file system), identifies duplicate files by comparing file size first and then a content hash, persists all collected file metadata (path, size, hash, last-modified, status) in a local embedded database so repeat scans skip unchanged files, and presents scanned locations plus duplicate groups through a Swing tree view. The project is built and packaged with Maven.

## Technical Context

**Language/Version**: Java 17 (LTS)

**Primary Dependencies**: Swing (JDK-bundled, no extra dependency) for the GUI; H2 Database Engine (embedded, file-based, pure-Java, single JAR) via JDBC for persistence; SLF4J + a minimal binding for logging

**Storage**: Embedded H2 database stored as a local file (e.g., `~/.file-dupe-detector/store.mv.db`) holding Scan Location, File Record, Duplicate Group (derived view), and Scan Session data

**Testing**: JUnit 5 (unit + integration tests), AssertJ for fluent assertions; Maven Surefire/Failsafe for test execution; AssertJ-Swing or manual harness for smoke-testing GUI wiring where practical

**Target Platform**: Cross-platform desktop (Windows, Linux, macOS) wherever a Java 17+ JRE/JDK is available

**Project Type**: Single desktop application (Maven single-module project)

**Performance Goals**: Re-scan of 10k unchanged files completes with at least 80% time reduction vs. initial hash computation (SC-002); tree view remains responsive (no UI freeze) for result sets of 10k+ files via lazy/incremental node loading

**Constraints**: Must run fully offline/local (no network calls); hashing must not block the Swing Event Dispatch Thread (background worker threads required); persistence file must survive application restarts (SC-004: results browsable within 5s of startup)

**Scale/Scope**: Designed for personal/prosumer use — tens of thousands of files across a handful of scan locations per session; not intended for multi-user concurrent access to the same data store

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

The project constitution (`.specify/memory/constitution.md`) is still the unpopulated template placeholder — no concrete principles or gates have been ratified for this repository yet. There are therefore no project-specific constitutional constraints to check against.

As a general-purpose baseline (not a formal gate, since no constitution is ratified), this plan follows standard good-practice defaults: single-module simplicity over premature multi-module split, test coverage for core logic (hashing, duplicate matching, persistence reuse), and no unnecessary third-party dependencies beyond what is needed for the embedded database and GUI. No violations to track.

**Status**: PASS (no ratified constitution to violate).

## Project Structure

### Documentation (this feature)

```text
specs/001-duplicate-file-detector/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
pom.xml

src/
├── main/
│   ├── java/
│   │   └── com/filedupedetector/
│   │       ├── Main.java                  # Application entry point (launches Swing EDT)
│   │       ├── model/                     # Scan Location, File Record, Duplicate Group, Scan Session
│   │       ├── scan/                      # Filesystem walker, size pre-filter, hashing service
│   │       ├── persistence/               # H2/JDBC repository layer, schema migration
│   │       ├── service/                   # ScanService orchestration, DuplicateIndex, re-scan diffing
│   │       └── gui/                       # Swing main window, tree model/renderer, duplicate detail panel
│   └── resources/
│       └── schema.sql                     # Embedded DB schema (tables + indexes)
└── test/
    └── java/
        └── com/filedupedetector/
            ├── scan/                      # Hashing + size-pre-filter unit tests
            ├── persistence/               # Repository/reuse-on-rescan integration tests
            ├── service/                   # Duplicate grouping + re-scan diffing unit/integration tests
            └── gui/                       # Tree model / selection logic tests (headless where possible)
```

**Structure Decision**: Single-module Maven project (`pom.xml` at repo root, standard `src/main/java` + `src/test/java` layout). Packages are organized by responsibility (`model`, `scan`, `persistence`, `service`, `gui`) rather than by layer-per-module, since this is a single desktop application, not a multi-service system. `gui` depends on `service`, `service` depends on `scan` + `persistence` + `model`; `scan` and `persistence` have no dependency on `gui`, keeping core logic (hashing, duplicate matching, persistence reuse) independently testable without launching Swing.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — the Constitution Check above found no ratified project constitution to violate, and the chosen structure (single Maven module, minimal dependencies: Swing + H2 + JUnit) is the simplest design that satisfies all functional requirements and success criteria. This section is intentionally empty.
