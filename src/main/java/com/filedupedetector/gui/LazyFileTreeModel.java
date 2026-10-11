package com.filedupedetector.gui;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.persistence.FileRecordRepository;

import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link TreeModel} whose root nodes are {@link ScanLocation}s and whose folder/file children
 * are loaded from {@link FileRecordRepository} only when a location node is first expanded
 * (FR-009), rather than eagerly loading the whole result set up front - keeping the tree
 * responsive for large scans (Edge Case: responsiveness for 10k+ files).
 */
public final class LazyFileTreeModel implements TreeModel {

    /** Invisible root required by {@link TreeModel}; holds the list of {@link ScanLocation}s. */
    public static final class RootNode {
        final List<ScanLocation> locations;

        RootNode(List<ScanLocation> locations) {
            this.locations = locations;
        }
    }

    /** A scan-location root node in the visible tree. Children loaded lazily on first access. */
    public final class LocationNode {
        final ScanLocation location;
        private List<Object> children; // lazily populated: FolderNode / FileNode

        LocationNode(ScanLocation location) {
            this.location = location;
        }

        public ScanLocation getLocation() {
            return location;
        }

        List<Object> children() {
            if (children == null) {
                children = buildChildren(location);
            }
            return children;
        }

        @Override
        public String toString() {
            return location.getDisplayName();
        }
    }

    /** A folder node, grouping child folders/files that share a relative-path prefix. */
    public static final class FolderNode {
        final String name;
        final List<Object> children;

        FolderNode(String name, List<Object> children) {
            this.name = name;
            this.children = children;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A leaf node representing a single scanned file. */
    public static final class FileNode {
        final FileRecord record;
        final String displayName;

        FileNode(FileRecord record, String displayName) {
            this.record = record;
            this.displayName = displayName;
        }

        public FileRecord getRecord() {
            return record;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private final FileRecordRepository fileRecordRepository;
    private final RootNode root;
    private final List<LocationNode> locationNodes;

    public LazyFileTreeModel(List<ScanLocation> locations, FileRecordRepository fileRecordRepository) {
        this.fileRecordRepository = fileRecordRepository;
        this.root = new RootNode(locations);
        this.locationNodes = new ArrayList<>();
        for (ScanLocation location : locations) {
            locationNodes.add(new LocationNode(location));
        }
    }

    private List<Object> buildChildren(ScanLocation location) {
        List<FileRecord> records = fileRecordRepository.findByLocation(location.getId());
        // Build a nested folder structure from flat relative paths, e.g. "a/b/file.txt".
        Map<String, Object> tree = new LinkedHashMap<>(); // folder name -> nested Map, or FileRecord leaf
        for (FileRecord record : records) {
            String[] parts = record.getRelativePath().split("[/\\\\]");
            insert(tree, parts, 0, record);
        }
        return toNodeList(tree);
    }

    @SuppressWarnings("unchecked")
    private void insert(Map<String, Object> currentLevel, String[] parts, int index, FileRecord record) {
        String part = parts[index];
        if (index == parts.length - 1) {
            currentLevel.put(part, record);
            return;
        }
        Object existing = currentLevel.get(part);
        Map<String, Object> nested;
        if (existing instanceof Map) {
            nested = (Map<String, Object>) existing;
        } else {
            nested = new LinkedHashMap<>();
            currentLevel.put(part, nested);
        }
        insert(nested, parts, index + 1, record);
    }

    @SuppressWarnings("unchecked")
    private List<Object> toNodeList(Map<String, Object> level) {
        List<Object> nodes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : level.entrySet()) {
            if (entry.getValue() instanceof Map) {
                nodes.add(new FolderNode(entry.getKey(), toNodeList((Map<String, Object>) entry.getValue())));
            } else {
                FileRecord record = (FileRecord) entry.getValue();
                nodes.add(new FileNode(record, entry.getKey()));
            }
        }
        return nodes;
    }

    @Override
    public Object getRoot() {
        return root;
    }

    @Override
    public Object getChild(Object parent, int index) {
        if (parent == root) {
            return locationNodes.get(index);
        }
        if (parent instanceof LocationNode locationNode) {
            return locationNode.children().get(index);
        }
        if (parent instanceof FolderNode folderNode) {
            return folderNode.children.get(index);
        }
        return null;
    }

    @Override
    public int getChildCount(Object parent) {
        if (parent == root) {
            return locationNodes.size();
        }
        if (parent instanceof LocationNode locationNode) {
            return locationNode.children().size();
        }
        if (parent instanceof FolderNode folderNode) {
            return folderNode.children.size();
        }
        return 0;
    }

    @Override
    public boolean isLeaf(Object node) {
        return node instanceof FileNode;
    }

    @Override
    public void valueForPathChanged(TreePath path, Object newValue) {
        // Not editable.
    }

    @Override
    public int getIndexOfChild(Object parent, Object child) {
        if (parent == root) {
            return locationNodes.indexOf(child);
        }
        if (parent instanceof LocationNode locationNode) {
            return locationNode.children().indexOf(child);
        }
        if (parent instanceof FolderNode folderNode) {
            return folderNode.children.indexOf(child);
        }
        return -1;
    }

    @Override
    public void addTreeModelListener(TreeModelListener l) {
        // No mutation after construction in the current GUI flow; listener not required yet.
    }

    @Override
    public void removeTreeModelListener(TreeModelListener l) {
        // See addTreeModelListener.
    }
}
