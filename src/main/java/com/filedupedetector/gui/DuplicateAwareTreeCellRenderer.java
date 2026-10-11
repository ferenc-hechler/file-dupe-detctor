package com.filedupedetector.gui;

import com.filedupedetector.model.DuplicateGroup.DuplicateGroupKey;
import com.filedupedetector.service.DuplicateQueryService;

import javax.swing.JTree;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.util.HashSet;
import java.util.Set;

/**
 * Tree cell renderer that visually marks file nodes belonging to a duplicate group (FR-010).
 */
public final class DuplicateAwareTreeCellRenderer extends DefaultTreeCellRenderer {

    private static final Color DUPLICATE_BACKGROUND = new Color(255, 230, 150);

    private final DuplicateQueryService duplicateQueryService;
    private Set<DuplicateGroupKey> duplicateKeysCache;

    public DuplicateAwareTreeCellRenderer(DuplicateQueryService duplicateQueryService) {
        this.duplicateQueryService = duplicateQueryService;
    }

    /** Call after a (re)scan so newly computed duplicate groups are reflected in the tree. */
    public void refreshDuplicateKeys() {
        duplicateKeysCache = null;
    }

    private Set<DuplicateGroupKey> duplicateKeys() {
        if (duplicateKeysCache == null) {
            duplicateKeysCache = new HashSet<>();
            duplicateQueryService.getAllDuplicateGroups().forEach(g -> duplicateKeysCache.add(g.key()));
        }
        return duplicateKeysCache;
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                    boolean leaf, int row, boolean hasFocus) {
        Component component = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        if (value instanceof LazyFileTreeModel.FileNode fileNode) {
            var record = fileNode.getRecord();
            boolean isDuplicate = record.getContentHash() != null
                    && duplicateKeys().contains(new DuplicateGroupKey(record.getSizeBytes(), record.getContentHash()));
            if (isDuplicate) {
                setBackgroundNonSelectionColor(DUPLICATE_BACKGROUND);
                setToolTipText("Duplicate file - part of a duplicate group");
            } else {
                setBackgroundNonSelectionColor(null);
                setToolTipText(null);
            }
        }
        return component;
    }
}
