package com.filedupedetector.gui;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.persistence.FileRecordRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless test (T042): verifies child nodes are not loaded until a parent (location) node is
 * expanded/queried, and that the folder hierarchy produced matches a known FileRecord path
 * structure.
 */
class LazyFileTreeModelTest {

    @Test
    void childrenAreNotLoadedUntilLocationNodeIsExpanded() {
        AtomicInteger loadCallCount = new AtomicInteger();
        FileRecordRepository stubRepository = new CountingFileRecordRepository(loadCallCount, List.of());

        ScanLocation location = newLocation(1L, "A", "/tmp/a");
        LazyFileTreeModel model = new LazyFileTreeModel(List.of(location), stubRepository);

        assertThat(loadCallCount.get()).isZero();

        // Accessing the root's children (location nodes) must not trigger a repository load.
        Object root = model.getRoot();
        Object locationNode = model.getChild(root, 0);
        assertThat(loadCallCount.get()).isZero();

        // Only expanding/querying the location node's own children triggers the load.
        model.getChildCount(locationNode);
        assertThat(loadCallCount.get()).isEqualTo(1);

        // Repeated access must not reload (cached after first expansion).
        model.getChildCount(locationNode);
        assertThat(loadCallCount.get()).isEqualTo(1);
    }

    @Test
    void folderHierarchyMatchesKnownFileRecordPaths() {
        ScanLocation location = newLocation(1L, "A", "/tmp/a");
        List<FileRecord> records = List.of(
                newRecord(location.getId(), "top.txt", 10),
                newRecord(location.getId(), "sub/nested.txt", 20),
                newRecord(location.getId(), "sub/deeper/leaf.txt", 30));
        FileRecordRepository stubRepository = new CountingFileRecordRepository(new AtomicInteger(), records);

        LazyFileTreeModel model = new LazyFileTreeModel(List.of(location), stubRepository);
        Object root = model.getRoot();
        Object locationNode = model.getChild(root, 0);

        assertThat(model.getChildCount(locationNode)).isEqualTo(2); // "top.txt" and "sub" folder

        Object topFile = findChildByName(model, locationNode, "top.txt");
        assertThat(topFile).isInstanceOf(LazyFileTreeModel.FileNode.class);
        assertThat(model.isLeaf(topFile)).isTrue();

        Object subFolder = findChildByName(model, locationNode, "sub");
        assertThat(subFolder).isInstanceOf(LazyFileTreeModel.FolderNode.class);
        assertThat(model.isLeaf(subFolder)).isFalse();
        assertThat(model.getChildCount(subFolder)).isEqualTo(2); // "nested.txt" and "deeper" folder

        Object nestedFile = findChildByName(model, subFolder, "nested.txt");
        assertThat(nestedFile).isInstanceOf(LazyFileTreeModel.FileNode.class);

        Object deeperFolder = findChildByName(model, subFolder, "deeper");
        assertThat(model.getChildCount(deeperFolder)).isEqualTo(1);
        Object leafFile = model.getChild(deeperFolder, 0);
        assertThat(((LazyFileTreeModel.FileNode) leafFile).getRecord().getRelativePath())
                .isEqualTo("sub/deeper/leaf.txt");
    }

    private Object findChildByName(LazyFileTreeModel model, Object parent, String name) {
        int count = model.getChildCount(parent);
        for (int i = 0; i < count; i++) {
            Object child = model.getChild(parent, i);
            if (child.toString().equals(name)) {
                return child;
            }
        }
        throw new AssertionError("No child named '" + name + "' found under " + parent);
    }

    private ScanLocation newLocation(long id, String name, String path) {
        ScanLocation location = new ScanLocation();
        location.setId(id);
        location.setDisplayName(name);
        location.setRootPathWithVolInfo(path);
        location.setType(LocationType.LOCAL);
        return location;
    }

    private FileRecord newRecord(long locationId, String relativePath, long size) {
        FileRecord record = new FileRecord();
        record.setScanLocationId(locationId);
        record.setRelativePath(relativePath);
        record.setSizeBytes(size);
        record.setContentHash(null);
        record.setLastModifiedAt(Instant.now());
        record.setLastSeenAt(Instant.now());
        return record;
    }

    /** Minimal stub counting {@code findByLocation} calls, returning a fixed record list. */
    private record CountingFileRecordRepository(AtomicInteger loadCallCount,
                                                  List<FileRecord> records) implements FileRecordRepository {
        @Override
        public java.util.Optional<FileRecord> findByLocationAndPath(long scanLocationId, String relativePath) {
            return java.util.Optional.empty();
        }

        @Override
        public FileRecord upsert(FileRecord record) {
            return record;
        }

        @Override
        public int deleteMissing(long scanLocationId, java.util.Set<String> relativePathsSeen) {
            return 0;
        }

        @Override
        public List<com.filedupedetector.model.DuplicateGroup> findDuplicateGroups() {
            return List.of();
        }

        @Override
        public List<FileRecord> findByLocation(long scanLocationId) {
            loadCallCount.incrementAndGet();
            return new ArrayList<>(records);
        }
    }
}