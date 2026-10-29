package com.filedupedetector.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
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
import com.filedupedetector.persistence.SimpleDBFileWalker;
import com.filedupedetector.persistence.SimpleDBFileWalker.SimpleDBFileVisitor;
import com.filedupedetector.utils.AppUtils;

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
	private final transient DBManager dbMgr;
	private final DefaultTreeModel treeModel;
	private final JTree tree;

	public DBTreeViewer(DBManager dbMgr) {
		super("File Dupe Detector - DB Viewer");
		this.dbMgr = dbMgr;

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

		JButton markButton = new JButton("Mark Remote");
		markButton.addActionListener(e -> {
			onMarkRemote();
		});

		JButton joinButton = new JButton("Join");
		joinButton.addActionListener(e -> {
//			onQuickJoin();
			onJoin();
		});

		JButton renameButton = new JButton("Rename");
		renameButton.addActionListener(e -> {
			onRename();
		});

		JButton filterButton = new JButton("Filter");
		filterButton.addActionListener(e -> {
			onFilter();
		});

		JButton deleteButton = new JButton("Delete");
		deleteButton.addActionListener(e -> {
			onDelete();
		});


		dbMgr.getScanLocationRepository().findAll().forEach(loc -> root.add(createLazyNode(loc, loc.disp(), loc.hasRootFolderId())));
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
		top.add(markButton);
		top.add(joinButton);
		top.add(renameButton);
		top.add(filterButton);
		top.add(deleteButton);
		add(top, BorderLayout.NORTH);
		add(new JScrollPane(tree), BorderLayout.CENTER);
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setSize(900, 600);
		setLocationRelativeTo(null);
	}

	class FolderCreator {
		private final ScanLocation location;
		private final Path rootPath;
		private final Map<Path, FolderRecord> folderCache = new HashMap<>();

		public FolderCreator(ScanLocation location) {
			this.location = location;
			this.rootPath = AppUtils.normPath(location.getRootPath());
			folderCache.put(rootPath, dbMgr.getFolderRecordRepository().findById(location.getRootFolderId()));
		}

		public Path getRootPath() {
			return rootPath;
		}
		
		public FolderRecord getOrCreateFolder(Path path) {
			if (path == null) {
				return null;
			}
			FolderRecord folder = folderCache.get(path);
			if (folder != null) {
				return folder;
			}
			if (!path.startsWith(rootPath)) {
				throw new IllegalArgumentException("Path " + path + " is not under root path " + location.getRootPath());
			}
			Path parentPath = path.getParent();
			String folderName = path.getFileName().toString();
			FolderRecord parentFolder = getOrCreateFolder(parentPath);
			if (!parentFolder.isNew()) {
				FolderRecord existing = dbMgr.getFolderRecordRepository().findByParentFolderAndName(parentFolder.getId(), folderName);
				if (existing != null) {
					existing.setFullPath(path);
					folderCache.put(path, existing);
					return existing;
				}
			}
			FolderRecord newFolder = new FolderRecord();
			newFolder.setParentFolderId(parentFolder.getId());
			newFolder.setName(folderName);
			dbMgr.getFolderRecordRepository().upsert(newFolder);
			newFolder.setNew(true);
			newFolder.setFullPath(path);
			folderCache.put(path, newFolder);
			return newFolder;
		}
	}
	
	public class FileCopyVisitor extends SimpleDBFileVisitor {

		private FolderCreator folderCreator;

		public FileCopyVisitor(FolderCreator folderCreator) {
			this.folderCreator = folderCreator;
		}

		@Override
		public void preVisitFolder(ScanLocation location, FolderRecord folderRecord, Path path) {}

		@Override
		public void folderVisit(ScanLocation location, FolderRecord folderRecord, Path path) {
			folderCreator.getOrCreateFolder(path);
		}

		@Override
		public void fileVisit(ScanLocation location, FolderRecord folderRecord, FileRecord fileRecord, Path path) {
			if (fileRecord != null) {
				FolderRecord parentFolder = folderCreator.getOrCreateFolder(path.getParent());
				if (!parentFolder.isNew()) {
					FileRecord existing = dbMgr.getFileRecordRepository().findByParentFolderAndName(parentFolder.getId(), fileRecord.getName());
					if (existing != null) {
						existing.setSizeBytes(fileRecord.getSizeBytes());
						existing.setLastModifiedAt(fileRecord.getLastModifiedAt());
						if (fileRecord.getSha1hash() != null) {
							existing.setSha1hash(fileRecord.getSha1hash());
						}
						dbMgr.getFileRecordRepository().upsert(existing);
						return;
					}
				}
				FileRecord newFile = new FileRecord();
				newFile.setParentFolderId(parentFolder.getId());
				newFile.setName(fileRecord.getName());
				newFile.setSizeBytes(fileRecord.getSizeBytes());
				newFile.setLastModifiedAt(fileRecord.getLastModifiedAt());
				newFile.setSha1hash(fileRecord.getSha1hash());
				dbMgr.getFileRecordRepository().upsert(newFile);
			}
		}
		
	}

	private void onJoin() {
		try {
			List<ScanLocation> selected = getCheckedScanLocations();
			LOG.info("Selected ScanLocations ({}):", selected.size());
			selected.forEach(loc -> LOG.info("  {}", loc.disp()));
			if (selected.size() < 2) {
				LOG.warn("At least two ScanLocations must be selected for joining.");
				return;
			}
			ScanLocation first = selected.get(0);
			ScanLocation rootLocation = new ScanLocation();
			rootLocation.setId(0L);
			rootLocation.setDisplayName(first.getDisplayName());
			rootLocation.setRootPath(first.getRootPath());
			rootLocation.setVolName(first.getVolName());
			rootLocation.setVolType(first.getVolType());
			rootLocation.setVolSize(first.getVolSize());
			rootLocation.setVolVSN(first.getVolVSN());
			rootLocation.setType(first.getType());
			rootLocation.setRootFolderId(null);
			for (int i = 1; i < selected.size(); i++) {
				ScanLocation other = selected.get(i);
				verifyJoinable(rootLocation, other);
			}
			FolderRecord rootFolder;
			final String rootPath = rootLocation.getRootPath();
			Optional<ScanLocation> origRootLoc = selected.stream().filter(loc -> rootPath.equals(loc.getRootPath())).findAny();
			if (origRootLoc.isPresent()) {
				rootLocation = origRootLoc.get();
				rootFolder = dbMgr.getFolderRecordRepository().findById(rootLocation.getRootFolderId());
                rootFolder.setFullPathstr(rootLocation.getRootPath());
			}
			else {
                rootFolder = new FolderRecord();
                rootFolder.setName(rootLocation.getRootPath());
                rootFolder.setParentFolderId(null);
                rootFolder = dbMgr.getFolderRecordRepository().upsert(rootFolder);
                rootFolder.setNew(true);
                rootFolder.setFullPathstr(rootLocation.getRootPath());
                rootLocation.setRootFolderId(rootFolder.getId());
                rootLocation = dbMgr.getScanLocationRepository().save(rootLocation);
    			DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
    			treeModel.insertNodeInto(createLazyNode(rootLocation, rootLocation.disp(), true), root,
    					root.getChildCount());
            }
			LOG.info("Joining ScanLocations into: " + rootLocation.disp());
			FolderCreator folderCreator = new FolderCreator(rootLocation);
			SimpleDBFileWalker walker = new SimpleDBFileWalker(dbMgr);
			walker.walk(selected, new FileCopyVisitor(folderCreator));
		}
		catch (IllegalStateException ex) {
			LOG.error("Error joining ScanLocations: " + ex.getMessage());
		}
		tree.repaint();
	}

	public class QuickFileCopyVisitor extends SimpleDBFileVisitor {

		private FolderCreator folderCreator;

		public QuickFileCopyVisitor(FolderCreator folderCreator) {
			this.folderCreator = folderCreator;
		}

		@Override
		public void preVisitFolder(ScanLocation location, FolderRecord folderRecord, Path path) {
			if (folderRecord == null) {
				return;
			}
			if (path.equals(folderCreator.getRootPath())) {
				return;
			}
			FolderRecord parentFolder = folderCreator.getOrCreateFolder(path.getParent());
			String folderName = folderRecord.getName();
			if (folderName.indexOf('/') >= 0) {
				folderName = folderName.substring(folderName.lastIndexOf('/') + 1);
			}
			FolderRecord existingFolderRecord = dbMgr.getFolderRecordRepository().findByParentFolderAndName(parentFolder.getId(), folderName);
			if (existingFolderRecord == null) {
				if (folderRecord.getName().indexOf('/') >= 0) {
					location.setRootFolderId(null);
					dbMgr.getScanLocationRepository().save(location);
					folderRecord.setName(folderName);
					dbMgr.getFolderRecordRepository().upsert(folderRecord);
				}
				dbMgr.getFolderRecordRepository().changeParentFolder(folderRecord.getId(), parentFolder.getId());
			}
		}

		@Override
		public void folderVisit(ScanLocation location, FolderRecord folderRecord, Path path) {}

		@Override
		public void fileVisit(ScanLocation location, FolderRecord folderRecord, FileRecord fileRecord, Path path) {
			if (fileRecord != null) {
				FolderRecord parentFolder = folderCreator.getOrCreateFolder(path.getParent());
				if (!parentFolder.isNew()) {
					FileRecord existing = dbMgr.getFileRecordRepository().findByParentFolderAndName(parentFolder.getId(), fileRecord.getName());
					if (existing != null) {
						// update existing file record only if the new one is newer (to avoid overwriting with older data)
						if (fileRecord.getLastModifiedAt().isAfter(existing.getLastModifiedAt())) {
							existing.setSizeBytes(fileRecord.getSizeBytes());
							existing.setLastModifiedAt(fileRecord.getLastModifiedAt());
							existing.setSha1hash(fileRecord.getSha1hash());
							dbMgr.getFileRecordRepository().upsert(existing);
						}
						return;
					}
				}
				// destructive move filerecord from old location to new location (parent folder) - this will remove the file from the old location and add it to the new one
				dbMgr.getFileRecordRepository().changeParentFolder(fileRecord.getId(), parentFolder.getId());
			}
		}
		
	}


	private void onQuickJoin() {
		try {
			List<ScanLocation> selected = getCheckedScanLocations();
			LOG.info("Selected ScanLocations ({}):", selected.size());
			selected.forEach(loc -> LOG.info("  {}", loc.disp()));
			if (selected.size() < 2) {
				LOG.warn("At least two ScanLocations must be selected for joining.");
				return;
			}
			ScanLocation first = selected.get(0);
			ScanLocation rootLocation = new ScanLocation();
			rootLocation.setId(0L);
			rootLocation.setDisplayName(first.getDisplayName());
			rootLocation.setRootPath(first.getRootPath());
			rootLocation.setVolName(first.getVolName());
			rootLocation.setVolType(first.getVolType());
			rootLocation.setVolSize(first.getVolSize());
			rootLocation.setVolVSN(first.getVolVSN());
			rootLocation.setType(first.getType());
			rootLocation.setRootFolderId(null);
			for (int i = 1; i < selected.size(); i++) {
				ScanLocation other = selected.get(i);
				verifyJoinable(rootLocation, other);
			}
			FolderRecord rootFolder;
			final String rootPath = rootLocation.getRootPath();
			Optional<ScanLocation> origRootLoc = selected.stream().filter(loc -> rootPath.equals(loc.getRootPath())).findAny();
			if (origRootLoc.isPresent()) {
				rootLocation = origRootLoc.get();
				rootFolder = dbMgr.getFolderRecordRepository().findById(rootLocation.getRootFolderId());
                rootFolder.setFullPathstr(rootLocation.getRootPath());
			}
			else {
                rootFolder = new FolderRecord();
                rootFolder.setName(rootLocation.getRootPath());
                rootFolder.setParentFolderId(null);
                rootFolder = dbMgr.getFolderRecordRepository().upsert(rootFolder);
                rootFolder.setNew(true);
                rootFolder.setFullPathstr(rootLocation.getRootPath());
                rootLocation.setRootFolderId(rootFolder.getId());
                rootLocation = dbMgr.getScanLocationRepository().save(rootLocation);
    			DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
    			treeModel.insertNodeInto(createLazyNode(rootLocation, rootLocation.disp(), true), root,
    					root.getChildCount());
            }
			LOG.info("Joining ScanLocations into: " + rootLocation.disp());
			FolderCreator folderCreator = new FolderCreator(rootLocation);
			SimpleDBFileWalker walker = new SimpleDBFileWalker(dbMgr);
			walker.walk(selected, new QuickFileCopyVisitor(folderCreator));
		}
		catch (IllegalStateException ex) {
			LOG.error("Error joining ScanLocations: " + ex.getMessage());
		}
		tree.repaint();
	}


	private void onDelete() {
		try {
			List<ScanLocation> selected = getCheckedScanLocations();
			LOG.info("Selected ScanLocations ({}):", selected.size());
			selected.forEach(loc -> LOG.info("  {}", loc.disp()));
			if (selected.size() == 0) {
				LOG.warn("At least one ScanLocations must be selected for deletion.");
				return;
			}
			DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
			for (ScanLocation loc : selected) {
				dbMgr.getScanLocationRepository().delete(loc.getId());
				dbMgr.recursiveDeleteFolder(loc.getRootFolderId());
				LOG.info("Deleted ScanLocation: " + loc.disp());
				for (int i = root.getChildCount() - 1; i >= 0; i--) {
					DefaultMutableTreeNode n = (DefaultMutableTreeNode) root.getChildAt(i);
					if (n.getUserObject() instanceof NodeData d && d.object() instanceof ScanLocation l
							&& l.getId() == loc.getId()) {
						checkedNodes.remove(n);
						treeModel.removeNodeFromParent(n);
					}
				}
			}
			LOG.info("Deleted ScanLocations finished.");
		}
		catch (IllegalStateException ex) {
			LOG.error("Error deleting ScanLocations: " + ex.getMessage());
		}
		tree.repaint();
	}

	private String promptForInput(String message, String initialValue) {
		return JOptionPane.showInputDialog(this, message, initialValue);
	}
	
	private void onRename() {
		try {
			List<ScanLocation> selected = getCheckedScanLocations();
			LOG.info("Selected ScanLocations ({}):", selected.size());
			selected.forEach(loc -> LOG.info("  {}", loc.disp()));
			if (selected.size() != 1) {
				LOG.warn("exactly one ScanLocations must be selected for renaming.");
				return;
			}
			DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
			ScanLocation location = selected.get(0);
			String newName = promptForInput("Enter new name for ScanLocation:", location.getDisplayName());
			if (newName == null || newName.trim().isEmpty()) {
				LOG.warn("Rename cancelled or empty name provided.");
				return;
			}
			location.setDisplayName(newName.trim());
			dbMgr.getScanLocationRepository().rename(location.getId(), newName.trim());
			for (int i = 0; i < root.getChildCount(); i++) {
				DefaultMutableTreeNode n = (DefaultMutableTreeNode) root.getChildAt(i);
				if (n.getUserObject() instanceof NodeData d && d.object() instanceof ScanLocation l
						&& l.getId() == location.getId()) {
					n.setUserObject(new NodeData(location, location.disp()));
					treeModel.nodeChanged(n);
				}
			}
			LOG.info("Rename ScanLocation finished.");
		}
		catch (IllegalStateException ex) {
			LOG.error("Error deleting ScanLocations: " + ex.getMessage());
		}
		tree.repaint();
	}



	private void onFilter() {
		try {
			List<ScanLocation> selected = getCheckedScanLocations();
			LOG.info("Selected ScanLocations ({}):", selected.size());
			selected.forEach(loc -> LOG.info("  {}", loc.disp()));
			if (selected.size() != 1) {
				LOG.warn("exactly one ScanLocations must be selected for renaming.");
				return;
			}
			DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
			ScanLocation location = selected.get(0);
			String newName = promptForInput("Enter new name for ScanLocation:", location.getDisplayName());
			if (newName == null || newName.trim().isEmpty()) {
				LOG.warn("Rename cancelled or empty name provided.");
				return;
			}
			location.setDisplayName(newName.trim());
			dbMgr.getScanLocationRepository().rename(location.getId(), newName.trim());
			for (int i = 0; i < root.getChildCount(); i++) {
				DefaultMutableTreeNode n = (DefaultMutableTreeNode) root.getChildAt(i);
				if (n.getUserObject() instanceof NodeData d && d.object() instanceof ScanLocation l
						&& l.getId() == location.getId()) {
					n.setUserObject(new NodeData(location, location.disp()));
					treeModel.nodeChanged(n);
				}
			}
			LOG.info("Rename ScanLocation finished.");
		}
		catch (IllegalStateException ex) {
			LOG.error("Error deleting ScanLocations: " + ex.getMessage());
		}
		tree.repaint();
	}




	private void verifyJoinable(ScanLocation rootScanLocation, ScanLocation otherLocation) {
		if (!otherLocation.getType().equals(rootScanLocation.getType())) {
			throw new IllegalStateException("Cannot join ScanLocations with different types: "
					+ otherLocation.disp() + " vs " + rootScanLocation.disp());
		}
		if (!otherLocation.getVolVSN().equals(rootScanLocation.getVolVSN())) {
			throw new IllegalStateException("Cannot join ScanLocations with different volume VSNs: "
					+ otherLocation.disp() + " vs " + rootScanLocation.disp());
		}
		if (!otherLocation.getVolName().equals(rootScanLocation.getVolName())) {
			throw new IllegalStateException("Cannot join ScanLocations with different volume names: "
					+ otherLocation.disp() + " vs " + rootScanLocation.disp());
		}
		if (!otherLocation.getVolType().equals(rootScanLocation.getVolType())) {
			throw new IllegalStateException("Cannot join ScanLocations with different volume types: "
					+ otherLocation.disp() + " vs " + rootScanLocation.disp());
		}
		if (otherLocation.getVolSize() != rootScanLocation.getVolSize()) {
			throw new IllegalStateException("Cannot join ScanLocations with different volume sizes: "
					+ otherLocation.disp() + " vs " + rootScanLocation.disp());
		}
		Path rootPath = Paths.get(rootScanLocation.getRootPath());
		Path otherPath = Paths.get(otherLocation.getRootPath());
		if (rootPath.startsWith(otherPath)) {
			rootScanLocation.setRootPath(otherLocation.getRootPath());
			rootScanLocation.setDisplayName(otherLocation.getDisplayName());
		}
		else if (!otherPath.startsWith(rootPath)) {
			Path commonPath = rootPath.getParent();
			while (commonPath != null && !otherPath.startsWith(commonPath)) {
				commonPath = commonPath.getParent();
			}
			rootScanLocation.setRootPath(commonPath != null ? AppUtils.normFilename(commonPath) : "/");
			if (!rootScanLocation.getDisplayName().startsWith("PARENT: ")) {
				rootScanLocation.setDisplayName("PARENT: " + otherLocation.getDisplayName());
			}
		}
	}



	private void onMarkRemote() {
		List<ScanLocation> selected = getCheckedScanLocations();
		LOG.info("Selected ScanLocations ({}):", selected.size());
		selected.forEach(loc -> LOG.info("  {}", loc.disp()));
		searchForDuplicateHashes(selected);
		CountResults counts = walkLocations(selected);
		LOG.info("Counts for selected ScanLocations: " + counts);
	}

	private void searchForDuplicateHashes(List<ScanLocation> selected) {
		HashFilewalker hashWalker = new HashFilewalker();
		DBFileWalker<Object> walker = new DBFileWalker<>(dbMgr);
		walker.walk(selected, hashWalker);
		markedSHAs.clear();
		markedSHAs.putAll(hashWalker.hash2fileIdMap);
		if (!hashWalker.duplicateHashIDsMap.isEmpty()) {
			LOG.info("Duplicate in marked files found: "+hashWalker.duplicateHashIDsMap.size());
		} else {
			LOG.info("No duplicates inside marked files found.");
		}
		DupsCount dups = markDuplicateFolders();
		LOG.info("TOTAL Duplicate files: " + dups.duplicates + " / " + (dups.duplicates+dups.unique) );
		tree.repaint();
	}

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

	
	static Map<String, Long> markedSHAs = new HashMap<>();
	static Map<Long, Color> folderColors = new HashMap<>();
	
	public DupsCount markDuplicateFolders() {
		folderColors.clear();
		DBFileWalker<DupsCount> walker = new DBFileWalker<>(dbMgr);
		DupsCount result = walker.walk(new DBFileVisitor<>() {
			
			@Override
			public void preVisitFolder(ScanLocation location, FolderRecord folderRecord, Path path) {
			}

			@Override
			public DupsCount visitFolder(ScanLocation location, FolderRecord folderRecord, Path path,
					List<DupsCount> childVisits) {
				DupsCount result = DupsCount.sum(childVisits);
				if (location != null) {
					if (result.duplicates > 0) {
						if (result.unique > 0) {
							folderColors.put(folderRecord.getId(), Color.ORANGE);
						} else {
							folderColors.put(folderRecord.getId(), Color.RED);
						}
					}
				}
				return result;
			}

			@Override
			public DupsCount visitFile(ScanLocation location, FolderRecord folderRecord, FileRecord fileRecord,
					Path path) {
				Long dup = markedSHAs.get(fileRecord.getSha1hash());
				if (dup != null && dup != fileRecord.getId()) {
					return new DupsCount(1, 0);
				}
				return new DupsCount(0, 1);
			}
		});
		return result;
	}

	public static class HashFilewalker implements DBFileVisitor<Object> {
		
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
					hash2fileIdMap.put(fileRecord.getSha1hash(), 0L);
					Set<Long> dupSet = duplicateHashIDsMap.computeIfAbsent(fileRecord.getSha1hash(), k -> new HashSet<>());
					if (dup != 0) {
						dupSet.add(dup);
					}
					dupSet.add(fileRecord.getId());
				}
			}
			return null;
		}
	}
	
	private CountResults walkLocations(List<ScanLocation> selected) {
		DBFileWalker<CountResults> walker = new DBFileWalker<>(dbMgr);
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
			dbMgr.getFolderRecordRepository().findByParentFolder(id)
					.forEach(f -> node.add(createLazyNode(f, f.disp(""), true)));
			for (FileRecord file : dbMgr.getFileRecordRepository().findByParentFolder(id)) {
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

		/** Color of the model object, or null for the default color. */
		Color color() {
			if (object instanceof ScanLocation l) {
				if (folderColors.containsKey(l.getRootFolderId())) {
					return folderColors.get(l.getRootFolderId());
				}
				return Color.BLACK;
			} else if (object instanceof FolderRecord f) {
				if (folderColors.containsKey(f.getId())) {
					return folderColors.get(f.getId());
				}
				return Color.BLACK;
			} else if (object instanceof FileRecord f) {
				Long dup = markedSHAs.get(f.getSha1hash());
				if (dup != null && dup != f.getId()) {
					return Color.RED;
				}
				return Color.BLACK;
			}
			return null;
		}
	}

	public static void main(String[] args) {
		DBManager dbMgr = new DBManager(false);
		SwingUtilities.invokeLater(() -> new DBTreeViewer(dbMgr).setVisible(true));
	}
}
