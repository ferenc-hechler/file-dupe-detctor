package com.filedupedetector.ui;

import java.awt.BorderLayout;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.persistence.DBManager;

/**
 * Swing viewer showing scan locations, folders and files in a lazily loaded
 * tree. Children are read from the database only when a node is expanded.
 */
public class DBTreeViewer extends JFrame {

	private static final long serialVersionUID = 1L;

	/** Marker child so that unloaded folder nodes show an expand handle. */
	private static final String PLACEHOLDER = "...";

	private final transient DBManager dbManager;
	private final DefaultTreeModel treeModel;
	private final JTree tree;

	public DBTreeViewer(DBManager dbManager) {
		super("File Dupe Detector - DB Viewer");
		this.dbManager = dbManager;

		DefaultMutableTreeNode root = new DefaultMutableTreeNode("Scan Locations");
		this.treeModel = new DefaultTreeModel(root);
		this.tree = new JTree(treeModel);
		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);

		dbManager.getScanLocationRepository().findAll().forEach(loc -> root.add(createLazyNode(loc, loc.disp(), loc.hasRootFolderId())));
		// children were added after the tree was created -> notify the model
		treeModel.reload();

		tree.addTreeWillExpandListener(new TreeWillExpandListener() {
			@Override
			public void treeWillExpand(TreeExpansionEvent event) {
				loadChildren((DefaultMutableTreeNode) event.getPath().getLastPathComponent());
			}

			@Override
			public void treeWillCollapse(TreeExpansionEvent event) {
				// nothing to do
			}
		});

		setLayout(new BorderLayout());
		add(new JScrollPane(tree), BorderLayout.CENTER);
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setSize(900, 600);
		setLocationRelativeTo(null);
	}

	/** Creates a node; if it can have children, a placeholder child is added. */
	private DefaultMutableTreeNode createLazyNode(Object userObject, String label, boolean expandable) {
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(new NodeData(userObject, label), expandable);
		if (expandable) {
			node.add(new DefaultMutableTreeNode(PLACEHOLDER, false));
		}
		return node;
	}

	private void loadChildren(DefaultMutableTreeNode node) {
		if (!(node.getUserObject() instanceof NodeData data) || node.getChildCount() != 1
				|| !PLACEHOLDER.equals(((DefaultMutableTreeNode) node.getFirstChild()).getUserObject())) {
			return; // already loaded or not expandable
		}
		node.removeAllChildren();

		Long folderId = null;
		if (data.object instanceof ScanLocation loc) {
			folderId = loc.getRootFolderId();
		} else if (data.object instanceof FolderRecord folder) {
			folderId = folder.getId();
		}
		if (folderId != null) {
			final long id = folderId;
			dbManager.getFolderRecordRepository().findByParentFolder(id)
					.forEach(f -> node.add(createLazyNode(f, f.disp(""), true)));
			for (FileRecord file : dbManager.getFileRecordRepository().findByParentFolder(id)) {
				node.add(new DefaultMutableTreeNode(new NodeData(file, file.disp("")), false));
			}
		}
		treeModel.nodeStructureChanged(node);
	}

	/** User object holding the model object and its display text. */
	private record NodeData(Object object, String label) {
		@Override
		public String toString() {
			return label;
		}
	}

	public static void main(String[] args) {
		DBManager dbManager = new DBManager(false);
		SwingUtilities.invokeLater(() -> new DBTreeViewer(dbManager).setVisible(true));
	}
}
