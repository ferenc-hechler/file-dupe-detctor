package com.filedupedetector.gui;

import com.filedupedetector.model.DuplicateGroup.DuplicateGroupKey;
import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.service.DuplicateQueryService;

import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Shows all members of a selected duplicate group, including each member's full path and
 * source scan location (FR-011).
 */
public final class DuplicateGroupDetailPanel extends JPanel {

    private final DuplicateQueryService duplicateQueryService;
    private final Map<Long, ScanLocation> locationsById;
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JLabel headerLabel = new JLabel("Select a duplicate file to see its group members");

    public DuplicateGroupDetailPanel(DuplicateQueryService duplicateQueryService, Map<Long, ScanLocation> locationsById) {
        super(new BorderLayout());
        this.duplicateQueryService = duplicateQueryService;
        this.locationsById = locationsById;

        add(headerLabel, BorderLayout.NORTH);
        JList<String> list = new JList<>(listModel);
        add(new JScrollPane(list), BorderLayout.CENTER);
    }

    /** Invoked when the user selects a tree node; updates the panel if it is a duplicate file. */
    public void onNodeSelected(Object selectedNode) {
        listModel.clear();
        if (!(selectedNode instanceof LazyFileTreeModel.FileNode fileNode)) {
            headerLabel.setText("Select a duplicate file to see its group members");
            return;
        }
        List<String> memberDescriptions = resolveMemberDescriptions(fileNode.getRecord(), duplicateQueryService, locationsById);
        if (memberDescriptions.isEmpty()) {
            headerLabel.setText(fileNode.getRecord().getRelativePath() + " is not part of a duplicate group");
            return;
        }
        headerLabel.setText("Duplicate group members (" + memberDescriptions.size() + "):");
        memberDescriptions.forEach(listModel::addElement);
    }

    /**
     * Pure logic (no Swing dependency) extracted for headless testing (T043): given a selected
     * file's {@link FileRecord}, returns a human-readable "full path (location)" description for
     * every member of its duplicate group, or an empty list if it is not a duplicate.
     */
    static List<String> resolveMemberDescriptions(FileRecord selected, DuplicateQueryService duplicateQueryService,
                                                    Map<Long, ScanLocation> locationsById) {
        if (selected.getContentHash() == null) {
            return List.of();
        }
        DuplicateGroupKey key = new DuplicateGroupKey(selected.getSizeBytes(), selected.getContentHash());
        List<FileRecord> members = duplicateQueryService.getGroupMembers(key);
        if (members.size() < 2) {
            return List.of();
        }
        return members.stream()
                .map(member -> describe(member, locationsById))
                .toList();
    }

    private static String describe(FileRecord member, Map<Long, ScanLocation> locationsById) {
        ScanLocation location = locationsById.get(member.getScanLocationId());
        String locationName = location == null ? ("location#" + member.getScanLocationId()) : location.getDisplayName();
        String fullPath = location == null
                ? member.getRelativePath()
                : Path.of(location.getRootPath(), member.getRelativePath()).toString();
        return fullPath + "  [" + locationName + "]";
    }
}
