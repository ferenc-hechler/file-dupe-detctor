# Internal Module Contracts: Duplicate File Detector

This desktop application has no external network API. These contracts describe the internal Java interfaces between the `gui`, `service`, `scan`, and `persistence` packages (see [plan.md](../plan.md) Project Structure), so each layer can be developed and tested independently.

## `scan` package

```java
package com.filedupedetector.scan;

public interface FileWalker {
    /**
     * Walks the given root path, invoking the visitor for each regular file found.
     * Errors reading individual files/directories are reported via the visitor
     * and MUST NOT abort the walk of remaining files (FR-012, FR-014).
     */
    void walk(Path rootPath, FileVisitCallback callback);
}

public interface FileVisitCallback {
    void onFileFound(Path absolutePath, long sizeBytes, FileTime lastModified);
    void onError(Path absolutePath, IOException error);
    void onInaccessible(Path absolutePath, InaccessibleReason reason); // e.g. CLOUD_PLACEHOLDER, SYMLINK_SKIPPED
}

public interface HashService {
    /** Computes a SHA-1 hex digest by streaming file content; never loads whole file into memory. */
    String computeHash(Path absolutePath) throws IOException;
}
```

## `persistence` package

```java
package com.filedupedetector.persistence;

public interface FileRecordRepository {
    Optional<FileRecord> findByLocationAndPath(long scanLocationId, String relativePath);
    FileRecord upsert(FileRecord record); // insert or update, keyed by (scanLocationId, relativePath)
    void markMissing(long scanLocationId, Set<String> relativePathsNotSeen, long scanSessionId); // returns count in implementation
    List<DuplicateGroup> findDuplicateGroups(); // PRESENT + non-null hash, grouped by (size, hash), count > 1
    List<FileRecord> findErroredBySession(long scanSessionId);
}

public interface ScanLocationRepository {
    ScanLocation save(ScanLocation location);
    List<ScanLocation> findAll();
    void markAvailability(long scanLocationId, boolean available);
}

public interface ScanSessionRepository {
    ScanSession start(List<Long> scanLocationIds);
    void complete(long sessionId, ScanOutcome outcome, int processed, int errored, int missing);
}
```

## `service` package (orchestration layer consumed by `gui`)

```java
package com.filedupedetector.service;

public interface ScanService {
    /**
     * Runs (or re-runs) a scan over the given scan locations.
     * MUST run off the Swing EDT; progress/completion delivered via the listener.
     * Applies size-first pre-filter + SHA-1 hashing, reusing persisted hashes
     * for files whose size/lastModified are unchanged since the last scan (FR-006/FR-007).
     */
    void scanAsync(List<ScanLocation> locations, ScanProgressListener listener);
}

public interface ScanProgressListener {
    void onProgress(int filesProcessed, int totalEstimate);
    void onSessionComplete(ScanSession result);
    void onLocationUnavailable(ScanLocation location); // Edge case: disconnected mid-scan
}

public interface DuplicateQueryService {
    List<DuplicateGroup> getAllDuplicateGroups();
    List<FileRecord> getGroupMembers(DuplicateGroupKey key);
}
```

## `gui` package (consumer-only; exposes no contract to other layers)

The `gui` package depends on `ScanService` and `DuplicateQueryService` only. It is responsible for:
- Building/updating a lazy `javax.swing.tree.TreeModel` from `ScanLocation` + `FileRecord` data (children loaded on node expansion, not eagerly for the whole tree — supports SC-003/responsiveness requirement).
- A `TreeCellRenderer` that visually marks nodes belonging to a `DuplicateGroup` (FR-010).
- A selection listener that, given a selected duplicate node, calls `DuplicateQueryService.getGroupMembers(...)` and renders full paths + source locations (FR-011).

No public contract is defined here since `gui` is the outermost layer with no other package depending on it.
