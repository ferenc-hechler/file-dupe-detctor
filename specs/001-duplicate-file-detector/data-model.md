# Phase 1 Data Model: Duplicate File Detector

**Input**: Key Entities from [spec.md](./spec.md), decisions from [research.md](./research.md)

## Entity: ScanLocation

Represents a root path designated by the user for scanning (local drive, removable volume, or cloud-sync folder mounted on the local file system).

| Field | Type | Notes |
|---|---|---|
| `id` | long (PK, auto-increment) | Internal identifier |
| `displayName` | String | User-friendly label shown in the GUI tree root |
| `rootPath` | String | Absolute filesystem path; unique per location |
| `type` | enum: `LOCAL`, `REMOVABLE`, `CLOUD` | Informational only; does not change scan behavior, used for GUI iconography |
| `lastScannedAt` | timestamp, nullable | Set when a scan of this location completes (successfully or partially) |
| `available` | boolean | `false` when the path is not currently accessible (Edge Case: disconnected volume); previously stored data remains visible but flagged |

**Validation rules**:
- `rootPath` must be non-blank and unique across all ScanLocations.
- `rootPath` does not need to exist at creation time, but a scan against a non-existent path marks `available = false` and records zero files processed for that session.

**Relationships**: One `ScanLocation` has many `FileRecord`s (via `FileRecord.scanLocationId`).

---

## Entity: FileRecord

Persisted information about a single scanned file (FR-005).

| Field | Type | Notes |
|---|---|---|
| `id` | long (PK, auto-increment) | Internal identifier |
| `scanLocationId` | long (FK → ScanLocation.id) | Owning scan location |
| `relativePath` | String | Path relative to the scan location's root (full path = rootPath + relativePath) |
| `sizeBytes` | long | File size in bytes, captured at last successful read |
| `contentHash` | String, nullable | Hex-encoded SHA-1 digest; `null` if not yet computed or file skipped/errored |
| `lastModifiedAt` | timestamp | Filesystem last-modified time at last successful read, used for change detection |
| `status` | enum: `PRESENT`, `MISSING`, `ERRORED` | `PRESENT` = seen and readable in the most recent scan; `MISSING` = previously seen, not found in latest scan of its location; `ERRORED` = found but could not be read/hashed (permission/lock/placeholder) |
| `lastScanSessionId` | long (FK → ScanSession.id), nullable | Which scan session last updated this record |
| `lastSeenAt` | timestamp | Last time this record was confirmed present |

**Validation rules**:
- `(scanLocationId, relativePath)` is unique — one record per file path within a location.
- `contentHash` MUST be present for any FileRecord considered when forming Duplicate Groups; records with `status != PRESENT` or `contentHash IS NULL` are excluded from duplicate matching (FR-003, FR-008, FR-012).
- Change detection (FR-007): a re-scan recomputes `contentHash` only when `sizeBytes` or `lastModifiedAt` differs from the stored values for that path; otherwise the stored `contentHash` is reused as-is.

**State transitions**:
- `(new path found)` → `PRESENT` (hash computed)
- `PRESENT` + unchanged size/mtime on re-scan → remains `PRESENT` (hash reused, no recompute)
- `PRESENT` + changed size/mtime on re-scan → remains `PRESENT` (hash recomputed)
- `PRESENT` + path not found on re-scan of its location → `MISSING`
- `MISSING` + path found again on a later re-scan → `PRESENT` (hash computed, since prior hash cannot be trusted as unchanged across an absence)
- `(read/hash error)` → `ERRORED` (contentHash left as previous value or null; excluded from duplicate matching per FR-012)

**Relationships**: Belongs to one `ScanLocation`. Many `FileRecord`s with equal `(sizeBytes, contentHash)` and `status = PRESENT` form a `DuplicateGroup` (derived, not separately stored).

---

## Entity: DuplicateGroup (derived view, not a persisted table)

A set of two or more `FileRecord`s that share identical `sizeBytes` and `contentHash` (FR-003, FR-004).

| Field | Type | Notes |
|---|---|---|
| `sizeBytes` | long | Shared size identity |
| `contentHash` | String | Shared hash identity |
| `members` | List<FileRecord> | All `PRESENT` FileRecords sharing this (size, hash) pair, across all ScanLocations |

**Validation rules**:
- A group is only materialized/shown when it has 2+ members.
- Computed on demand (e.g., via a SQL query grouping by `(size_bytes, content_hash) HAVING COUNT(*) > 1` filtered to `status = PRESENT`), not stored as its own table, since it is fully derivable from `FileRecord` and must always reflect the latest data.

**Relationships**: Composed of `FileRecord`s, potentially spanning multiple `ScanLocation`s (Edge Case: same file duplicated across two different scan locations).

---

## Entity: ScanSession

A record of a single scan run (for history/diagnostics and skipped/errored reporting — FR-012).

| Field | Type | Notes |
|---|---|---|
| `id` | long (PK, auto-increment) | Internal identifier |
| `startedAt` | timestamp | Scan start time |
| `endedAt` | timestamp, nullable | Scan end time; null while in progress |
| `scanLocationIds` | List<long> (join table `scan_session_location`) | Which locations were included in this run |
| `filesProcessed` | int | Count of files successfully read/hashed (or reused) |
| `filesSkippedErrored` | int | Count of files recorded with `status = ERRORED` during this session |
| `filesMissing` | int | Count of previously known files newly marked `MISSING` this session |
| `outcome` | enum: `COMPLETED`, `INTERRUPTED` | `INTERRUPTED` when a location became unavailable mid-scan (Edge Case); partial results are still preserved and persisted |

**Validation rules**:
- `endedAt`, when present, must be ≥ `startedAt`.
- An `INTERRUPTED` session still commits all `FileRecord` updates gathered up to the point of interruption (FR-014) rather than rolling them back.

**Relationships**: Many-to-many with `ScanLocation` (a session may cover multiple locations; a location has many sessions over time). `FileRecord.lastScanSessionId` references the session that last touched it.

---

## Derived/Query Access Patterns

| Use case | Query shape |
|---|---|
| Change detection on re-scan (FR-006/FR-007) | `SELECT * FROM file_record WHERE scan_location_id = ? AND relative_path = ?` (unique index) |
| Mark missing files (FR-008) | For a given `scanLocationId`, any `PRESENT` FileRecord whose `relativePath` was not observed in the current walk → set `status = MISSING` |
| Duplicate grouping (FR-003/FR-004) | `SELECT size_bytes, content_hash, COUNT(*) FROM file_record WHERE status = 'PRESENT' AND content_hash IS NOT NULL GROUP BY size_bytes, content_hash HAVING COUNT(*) > 1` |
| Tree view population (FR-009) | Load `ScanLocation`s as roots; lazily query `FileRecord`s per location (optionally filtered/sorted by relative path prefix) to build folder/file child nodes on demand |
| Skipped/errored report (FR-012) | `SELECT * FROM file_record WHERE last_scan_session_id = ? AND status = 'ERRORED'` |

## Indexes

- Unique index on `file_record (scan_location_id, relative_path)` — required for fast change-detection lookups and upsert-on-rescan.
- Index on `file_record (size_bytes, content_hash)` — required for efficient duplicate-group derivation.
- Index on `scan_location (root_path)` — unique, prevents duplicate location registration.
