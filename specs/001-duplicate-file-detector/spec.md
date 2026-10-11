# Feature Specification: Duplicate File Detector

**Feature Branch**: `001-duplicate-file-detector`

**Created**: 2026-10-11

**Status**: Draft

**Input**: User description: "Build a tool for detecting file duplicates on different volumes and cloud storage. Use filesize and a hash value to identify duplicates. A gui will visualize the file and duplicate info in a tree like view. file information has to be persisted so that hash calculation has not to be done every time"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Scan locations and find duplicates (Priority: P1)

As a user with files scattered across multiple local drives, external volumes, and cloud-synced folders, I want to scan one or more locations and have the tool tell me which files are exact duplicates of each other, so that I can decide which copies to keep and which to remove to reclaim storage space.

**Why this priority**: This is the core value proposition of the tool. Without reliable duplicate detection, nothing else (persistence, GUI visualization) has a purpose. It must work standalone as the MVP.

**Independent Test**: Point the tool at two or more folders (including at least one containing files with identical content but different names/locations) and verify that the tool correctly groups files with identical content together while not flagging files with different content as duplicates, even if they share the same size or name.

**Acceptance Scenarios**:

1. **Given** two folders each containing a copy of the same file (identical content, different file names), **When** the user runs a scan across both folders, **Then** the tool reports both files as members of the same duplicate group.
2. **Given** two files of the same size but different content, **When** the user runs a scan, **Then** the tool does NOT report them as duplicates.
3. **Given** a folder with three files where two are identical and one is unique, **When** the user runs a scan, **Then** the tool reports exactly one duplicate group containing the two identical files, and the unique file is not part of any group.
4. **Given** a previously scanned location with no changes, **When** the user runs the scan again, **Then** the tool produces the same duplicate results without recomputing hashes for unchanged files (see User Story 2).

---

### User Story 2 - Reuse persisted file information to avoid recomputation (Priority: P2)

As a user who scans large volumes of data regularly, I want the tool to remember file information (location, size, hash, last-modified timestamp) from previous scans, so that re-scanning the same locations is fast and does not require recalculating hashes for files that have not changed.

**Why this priority**: Hash calculation over large volumes and cloud storage is slow and resource-intensive. Persisting results is what makes repeated use of the tool practical, but the tool still delivers value on a single scan without it (P1 covers first-run correctness).

**Independent Test**: Run a scan on a folder, close the tool, reopen it, and run the scan again on the same folder without modifying any files; verify (e.g., via timing or an indicator in the GUI) that hashes are retrieved from storage rather than recomputed, and that results are identical to the first scan.

**Acceptance Scenarios**:

1. **Given** a folder that was already scanned and whose files have not changed since, **When** the user re-scans the same folder, **Then** the tool reuses the previously stored size/hash information instead of re-reading and re-hashing unchanged file contents.
2. **Given** a file that was scanned previously but has since been modified (different size, content, or last-modified timestamp), **When** the user re-scans the folder containing it, **Then** the tool detects the change and recalculates the hash for that file.
3. **Given** a file that was scanned previously and has since been deleted, **When** the user re-scans the folder, **Then** the tool removes or marks the stored information for that file as no longer present and excludes it from current duplicate results.
4. **Given** the tool is closed and reopened, **When** the user views previously scanned locations, **Then** previously discovered file and duplicate information is still available without requiring a new scan.

---

### User Story 3 - Visualize files and duplicates in a tree view (Priority: P3)

As a user reviewing scan results, I want to see the scanned locations, their folder structure, and duplicate relationships in a hierarchical tree view within a graphical interface, so that I can understand where duplicates live and navigate to them easily before deciding what action to take.

**Why this priority**: Visualization makes the results usable and actionable for a human, but the underlying detection (P1) and persistence (P2) must exist first; a basic text/console output could serve as a fallback until the GUI is built.

**Independent Test**: After running a scan with known duplicate files at different folder depths, open the GUI and verify that the tree reflects the actual folder hierarchy of scanned locations, that duplicate files are visually distinguishable from unique files, and that selecting a duplicate group shows all member files with their full paths and storage location.

**Acceptance Scenarios**:

1. **Given** completed scan results containing duplicate and unique files, **When** the user opens the GUI, **Then** a tree view displays the scanned locations as root nodes with their folder/file hierarchy underneath.
2. **Given** a duplicate group exists among the scanned files, **When** the user views the tree, **Then** files belonging to a duplicate group are visually marked (e.g., icon, color, or badge) and distinguishable from unique files.
3. **Given** the user selects a duplicate group or a duplicate file in the tree, **When** the selection is made, **Then** the GUI displays all other files in the same duplicate group, including their full path and which volume/cloud storage location they reside on.
4. **Given** a very large number of scanned files, **When** the tree view is displayed, **Then** the user can expand/collapse branches and the view remains responsive (does not freeze or require loading the entire result set to view a single branch).

---

### Edge Cases

- What happens when a scanned location (volume or cloud storage folder) becomes unavailable or disconnected mid-scan (e.g., USB drive unplugged, cloud sync offline)? The tool should report the interruption and preserve results gathered so far.
- How does the system handle zero-byte files? Files with identical size of 0 are treated as potential duplicates and still compared by hash (or a defined equivalent rule for empty files) rather than being automatically grouped by size alone.
- How does the system handle files it cannot read due to permission errors or being locked by another process? Such files should be reported as skipped/errored rather than silently ignored or causing the whole scan to fail.
- How does the system handle symbolic links / shortcuts / cloud "placeholder" files (e.g., cloud-only files not yet downloaded locally)? The tool should distinguish and, where content is not locally accessible, exclude or clearly flag those files instead of mis-identifying them as duplicates or non-duplicates.
- What happens if the same physical location is scanned under two different paths (e.g., a mounted network share and a local mount point pointing to the same files)? The tool treats them as the separately scanned paths it is told about; true duplicates found this way are still reported as duplicates.
- What happens when two different files happen to produce the same hash value (hash collision)? The tool treats this as an extremely low-probability event; final duplicate confirmation requires matching size and hash together.
- How does the system behave when persisted file information references a location/volume that is not currently connected? Previously discovered information remains visible/browsable but is marked as "not currently available" until the location is reconnected and re-scanned.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow a user to designate one or more scan locations, including local drives, external/removable volumes, and cloud storage folders (as mounted or synced to the local file system).
- **FR-002**: System MUST, for each file within scanned locations, record its file size and compute a content hash sufficient to confirm whether two files have identical content.
- **FR-003**: System MUST identify files as duplicates of each other only when their file size AND content hash both match.
- **FR-004**: System MUST group duplicate files together and allow the user to view, for each duplicate group, every file (and its location) that belongs to that group.
- **FR-005**: System MUST persist collected file information (path, size, hash, last-modified timestamp, source location) between application sessions so results do not need to be fully recalculated after closing and reopening the tool.
- **FR-006**: System MUST, on a repeat scan of a previously scanned location, reuse persisted information for files that have not changed instead of recomputing their hash.
- **FR-007**: System MUST detect when a previously scanned file has changed (based on criteria such as size or last-modified timestamp) and recompute its hash in that case.
- **FR-008**: System MUST detect when a previously scanned file no longer exists at its recorded location and update stored information accordingly so it is excluded from current duplicate results.
- **FR-009**: System MUST provide a graphical user interface that presents scanned locations and their contents in a hierarchical (tree-like) view.
- **FR-010**: GUI MUST visually distinguish files that are part of a duplicate group from files that are unique.
- **FR-011**: GUI MUST allow the user to select a file or duplicate group and view all other members of that duplicate group, including each member's full path and source location.
- **FR-012**: System MUST report files that could not be scanned (e.g., permission errors, locked files, inaccessible cloud placeholders) as skipped/errored items, separate from successfully scanned files.
- **FR-013**: System MUST allow the user to initiate a new scan of previously configured locations without having to reconfigure them each time.
- **FR-014**: System MUST continue operating and preserve already-collected results if a scanned location becomes unavailable during a scan, rather than losing all progress.

### Key Entities

- **Scan Location**: A root path (local drive, external volume, or cloud storage folder) designated by the user for scanning. Attributes include a display name, root path, type (local/removable/cloud), and last-scanned timestamp.
- **File Record**: Persisted information about a single scanned file. Attributes include full path, containing scan location, file size, content hash, last-modified timestamp, last-seen status (present/missing), and last scan date.
- **Duplicate Group**: A set of two or more File Records that share identical file size and content hash. Attributes include the shared size/hash identity and the list of member File Records.
- **Scan Session**: A record of a single scan run. Attributes include start/end time, locations scanned, counts of files processed/skipped/errored, and outcome status.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can identify all duplicate files across at least two different storage locations (e.g., one local drive and one cloud-synced folder) in a single scan with zero false-positive duplicate groups (files incorrectly grouped despite different content) in representative test sets.
- **SC-002**: On a repeat scan where no files have changed, overall scan time for previously-scanned, unchanged files is reduced by at least 80% compared to the initial scan of the same files, due to reuse of persisted information.
- **SC-003**: Users can navigate from the tree view to a full list of a duplicate group's file locations in 2 interactions or fewer (e.g., one click to select, view is immediately shown).
- **SC-004**: After an application restart, previously scanned file and duplicate information is available for review within 5 seconds, without requiring a new scan.
- **SC-005**: In a test scan of at least 10,000 files including deliberately inserted permission-restricted or locked files, 100% of inaccessible files are reported as skipped/errored rather than causing the scan to abort or silently disappear from results.
- **SC-006**: 90% of first-time users can, without external instructions, locate and identify at least one duplicate file group using only the tree view GUI.

## Assumptions

- "Cloud storage" refers to cloud storage that is accessible through the local file system (e.g., a synced/mounted folder), not a tool-specific direct API integration with individual cloud providers; any such folder path can be added as a scan location like a local or removable volume.
- Duplicate detection is based on exact content equality (same size and same hash); similar-but-not-identical files (e.g., resized images, re-encoded media, documents with minor edits) are explicitly out of scope for duplicate detection in this version.
- The persisted file information store resides on the local machine running the tool; it is not synchronized or shared across multiple machines in this version.
- Users are responsible for deciding and performing any action on identified duplicates (e.g., deletion, moving); this version focuses on detection and visualization, not automatic deletion or file management actions.
- A cryptographically strong, collision-resistant hash algorithm is used so that hash collisions between different file contents are considered negligible in practice, consistent with the edge case handling above.
- The tool runs on a single user's machine/session at a time; concurrent multi-user access to the same persisted data store is out of scope.
- Scanned "cloud storage" content must be locally accessible (fully downloaded/materialized) for hashing; cloud-only placeholder files that are not locally materialized are flagged as inaccessible rather than hashed.
