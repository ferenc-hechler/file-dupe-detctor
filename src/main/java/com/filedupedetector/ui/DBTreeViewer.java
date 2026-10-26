package com.filedupedetector.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.persistence.DBFileWalker;
import com.filedupedetector.persistence.DBFileWalker.CountResults;
import com.filedupedetector.persistence.DBFileWalker.DBFileVisitor;
import com.filedupedetector.persistence.DBManager;

/**
 * Swing viewer showing scan locations, folders and files in a lazily loaded
 * tree. Children are read from the database only when a node is expanded.
 */
public class DBTreeViewer extends JFrame {

	private static final long serialVersionUID = 1L;

	/** Marker child so that unloaded folder nodes show an expand handle. */
	private static final String PLACEHOLDER = "...";

	private static final Logger LOG = LoggerFactory.getLogger(DBTreeViewer.class);

	private final transient Set<DefaultMutableTreeNode> checkedNodes = new HashSet<>();
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
		final JCheckBox renderBox = new JCheckBox();
		renderBox.setOpaque(false);
		tree.setCellRenderer(new DefaultTreeCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean expanded,
					boolean leaf, int row, boolean hasFocus) {
				super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, hasFocus);
				if (!sel && value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof NodeData d) {
					Color c = d.color();
					if (c != null) {
						setForeground(c);
					}
				}
				if (value instanceof DefaultMutableTreeNode n && isScanLocationNode(n)) {
					renderBox.setSelected(checkedNodes.contains(n));
					JPanel panel = new JPanel(new BorderLayout());
					panel.setOpaque(false);
					panel.add(renderBox, BorderLayout.WEST);
					panel.add(this, BorderLayout.CENTER);
					return panel;
				}
				return this;
			}
		});

		// toggle the checkbox of a scan location when clicking on it
		tree.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				TreePath path = tree.getPathForLocation(e.getX(), e.getY());
				if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode n)
						|| !isScanLocationNode(n)) {
					return;
				}
				Rectangle bounds = tree.getPathBounds(path);
				if (bounds != null && e.getX() <= bounds.x + renderBox.getPreferredSize().width) {
					if (!checkedNodes.remove(n)) {
						checkedNodes.add(n);
					}
					tree.repaint(bounds);
				}
			}
		});

		JButton showButton = new JButton("Show Selected ScanLocations");
		showButton.addActionListener(e -> {
			onShowSelectedScanLocations();
		});

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
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
		top.add(showButton);
		add(top, BorderLayout.NORTH);
		add(new JScrollPane(tree), BorderLayout.CENTER);
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setSize(900, 600);
		setLocationRelativeTo(null);
	}

	

	private void onShowSelectedScanLocations() {
		List<ScanLocation> selected = getCheckedScanLocations();
		LOG.info("Selected ScanLocations ({}):", selected.size());
		selected.forEach(loc -> LOG.info("  {}", loc.disp()));
		searchForDuplicateHashes(selected);
		CountResults counts = walkLocations(selected);
		LOG.info("Counts for selected ScanLocations: " + counts);
	}

	private void searchForDuplicateHashes(List<ScanLocation> selected) {
		HashFilewalker hashWalker = new HashFilewalker();
		DBFileWalker<Object> walker = new DBFileWalker<>(dbManager);
		walker.walk(selected, hashWalker);
		duplicateSHAs.clear();
		duplicateSHAs.addAll(hashWalker.duplicateHashIDsMap.keySet());
		if (!hashWalker.duplicateHashIDsMap.isEmpty()) {
			LOG.info("Duplicate hashes found: "+hashWalker.duplicateHashIDsMap.size());
		} else {
			LOG.info("No duplicate hashes found.");
		}
	}

	public static class HashFilewalker implements DBFileVisitor<Object> {
		
		record DupsCount(int duplicates, int unique) {
			static DupsCount sum(List<DupsCount> dCounts) {
				int duplicates = 0;
				int unique = 0;
				for (DupsCount dc : dCounts) {
					duplicates += dc.duplicates;
					unique += dc.unique;
				}
				return new DupsCount(duplicates, unique);
			}
			DupsCount add(DupsCount other) {
				return new DupsCount(this.duplicates + other.duplicates, this.unique + other.unique);
			}
		}
		
		public Map<String, Long> hash2fileIdMap = new HashMap<>();
		public Map<String, Set<Long>> duplicateHashIDsMap = new HashMap<>();
		
		@Override
		public void preVisitFolder(ScanLocation location, FolderRecord folderRecord, Path path) {
		}

		@Override
		public Object visitFolder(ScanLocation location, FolderRecord folderRecord, Path path,
				List<Object> childVisits) {
			return null;
		}

		@Override
		public Object visitFile(ScanLocation location, FolderRecord folderRecord, FileRecord fileRecord, Path path) {
			if (fileRecord.getSha1hash() != null) {
				Long dup = hash2fileIdMap.put(fileRecord.getSha1hash(), fileRecord.getId());
				if (dup != null) {
					duplicateHashIDsMap.computeIfAbsent(fileRecord.getSha1hash(), k -> new HashSet<>()).add(dup);
					duplicateHashIDsMap.get(fileRecord.getSha1hash()).add(fileRecord.getId());
				}
			}
			return null;
		}
	}
	
	private CountResults walkLocations(List<ScanLocation> selected) {
		DBFileWalker<CountResults> walker = new DBFileWalker<>(dbManager);
		CountResults result = walker.walk(selected, new DBFileVisitor<>() {
			@Override
			public void preVisitFolder(ScanLocation location, FolderRecord folderRecord, Path path) {
			}

			@Override
			public CountResults visitFolder(ScanLocation location, FolderRecord folderRecord, Path path,
					List<CountResults> childVisits) {
				CountResults result = CountResults.count(childVisits);
				if (location != null) {
					result = result.add(CountResults.count(folderRecord));
				}
				return result;
			}

			@Override
			public CountResults visitFile(ScanLocation location, FolderRecord folderRecord, FileRecord fileRecord,
					Path path) {
				CountResults result = CountResults.count(fileRecord);
				return result;
			}
		});
		return result;
	}

	private boolean isScanLocationNode(DefaultMutableTreeNode n) {
		return n.getUserObject() instanceof NodeData d && d.object() instanceof ScanLocation;
	}

	/** Returns the ScanLocations whose checkbox is checked. */
	public List<ScanLocation> getCheckedScanLocations() {
		List<ScanLocation> result = new ArrayList<>();
		DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
		for (int i = 0; i < root.getChildCount(); i++) {
			DefaultMutableTreeNode n = (DefaultMutableTreeNode) root.getChildAt(i);
			if (checkedNodes.contains(n) && n.getUserObject() instanceof NodeData d
					&& d.object() instanceof ScanLocation loc) {
				result.add(loc);
			}
		}
		return result;
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

	static Set<String> duplicateSHAs = new HashSet<>();
	
	/** User object holding the model object and its display text. */
	private record NodeData(Object object, String label) {
		@Override
		public String toString() {
			return label;
		}

		/** Color of the model object, or null for the default color. */
		Color color() {
			if (object instanceof ScanLocation l) {
				return l.getColor();
			} else if (object instanceof FolderRecord f) {
				return f.getColor();
			} else if (object instanceof FileRecord f) {
				if (duplicateSHAs.contains(f.getSha1hash())) {
					return Color.RED;
				}
				return Color.BLACK;
			}
			return null;
		}
	}

	public static void main(String[] args) {
		DBManager dbManager = new DBManager(false);
		SwingUtilities.invokeLater(() -> new DBTreeViewer(dbManager).setVisible(true));
	}
}
