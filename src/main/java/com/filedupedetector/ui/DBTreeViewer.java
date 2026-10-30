package com.filedupedetector.ui;

import static com.filedupedetector.utils.AppUtils.readable;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingConstants;
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
import com.filedupedetector.utils.ProgressLogger;

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
				JPanel panel = new JPanel(new BorderLayout());
				panel.setOpaque(false);
				int level = 0;
				if (value instanceof DefaultMutableTreeNode n) {
					level = n.getLevel();
					if (isScanLocationNode(n)) {
						renderBox.setSelected(checkedNodes.contains(n));
						panel.add(renderBox, BorderLayout.WEST);
					}
				}
				panel.add(this, BorderLayout.CENTER);
				String size = "";
				String modified = "";
				String sha1 = "";
				if (value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof NodeData d) {
					size = d.sizeText();
					modified = d.modifiedText();
					sha1 = d.sha1Text();
				}
				Color bg = sel ? getBackgroundSelectionColor() : null;
				Color fg = sel ? getTextSelectionColor() : getForeground();
				JPanel cols = createColumns(size, modified, sha1, fg, bg);
				panel.add(cols, BorderLayout.EAST);
				int indent = level * 20;
				int avail = t.getParent() != null ? t.getParent().getWidth() - indent : 0;
				Dimension pref = panel.getPreferredSize();
				panel.setPreferredSize(new Dimension(Math.max(avail, pref.width), pref.height));
				return panel;
			}
		});
		tree.addComponentListener(new java.awt.event.ComponentAdapter() {
			@Override
			public void componentResized(java.awt.event.ComponentEvent e) {
				tree.treeDidChange();
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

		JButton resetTreeButton = new JButton("Reset Tree");
		resetTreeButton.addActionListener(e -> {
			onResetTree();
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
		top.add(resetTreeButton);
		top.add(deleteButton);
		add(top, BorderLayout.NORTH);
		add(new JScrollPane(tree), BorderLayout.CENTER);
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setSize(900, 600);
		setLocationRelativeTo(null);
	}

	private static final int[] COLUMN_WIDTHS = { 90, 150, 300 };

	/** Creates the right-aligned columns (size, last modified, SHA1). */
	private static JPanel createColumns(String size, String modified, String sha1, Color fg, Color bg) {
		JPanel cols = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		cols.setOpaque(bg != null);
		if (bg != null) {
			cols.setBackground(bg);
		}
		String[] texts = { size, modified, sha1 };
		for (int i = 0; i < texts.length; i++) {
			JLabel l = new JLabel(texts[i], i == 0 ? SwingConstants.RIGHT : SwingConstants.LEFT);
			l.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
			l.setPreferredSize(new Dimension(COLUMN_WIDTHS[i], l.getPreferredSize().height));
			if (fg != null) {
				l.setForeground(fg);
			}
			cols.add(l);
		}
		return cols;
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

	
	
	private Pattern compileFilterPattern(String filter) {
		if (filter == null || filter.trim().isEmpty() || "*.*".equals(filter.trim())) {
			return null;
		}
		String regex = filter.trim().replace(".", "\\.").replace("*", ".*");
		return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
	}
	
	private String filterString = "*.*";
	private Pattern filterPattern = null;
	
	private boolean checkFilter(String lowercaseFileName) {
		if (filterPattern == null) {
			return true;
		}
		return filterPattern.matcher(lowercaseFileName).matches();
	}

	private boolean checkFilter(FileRecord fileRecord) {
		if (filterPattern == null) {
			return true;
		}
		return filterPattern.matcher(fileRecord.getName().toLowerCase()).matches();
	}

	private boolean checkFilter(FolderRecord folderRecord) {
		if (filterPattern == null) {
			return true;
		}
		MemFolder mf = memFolderMap.get(folderRecord.getId());
		return (mf == null) || mf.isVisible();
	}

	
    public static Path getMemFolderFile() {
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, ".file-dupe-detector-dev", "memfolder.serialized");
    }
    
	/**
	 * Persists a MemFolder tree (only folderId, files and childFolders) in a
	 * binary format.
	 */
	public void serializeObjectToFile(MemFolder memRootFolder, Path memFolderFile) throws IOException {
		try (DataOutputStream out = new DataOutputStream(
				new BufferedOutputStream(Files.newOutputStream(memFolderFile)))) {
			writeMemFolder(memRootFolder, out);
		}
	}

	private void writeMemFolder(MemFolder folder, DataOutputStream out) throws IOException {
		out.writeLong(folder.folderId);
		out.writeInt(folder.files.size());
		for (MemFile file : folder.files) {
			writeMemFile(file, out);
		}
		out.writeInt(folder.childFolders.size());
		for (MemFolder child : folder.childFolders) {
			writeMemFolder(child, out);
		}
	}

	private void writeMemFile(MemFile file, DataOutputStream out) throws IOException {
//		long fileId, String fileName, long sizeBytes, long lastModifiedAt, String sha1hash
		out.writeLong(file.fileId);
		out.writeUTF(file.fileName);
		out.writeLong(file.sizeBytes);
		out.writeLong(file.lastModifiedAt);
		out.writeUTF(file.sha1hash != null ? file.sha1hash : "");
	}
	

	/**
	 * Reads a MemFolder tree written by {@link #serializeObjectToFile}. All read
	 * folders are registered in the given map by their folderId (the root with id
	 * -1 is additionally registered under the key null). The visible flag is not
	 * persisted and is initially false.
	 */
	public MemFolder deserializeObjectFromFile(Path memFolderFile, Map<Long, MemFolder> folderMap) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(memFolderFile)))) {
			MemFolder root = readMemFolder(in, folderMap);
			if (root.folderId == -1) {
				folderMap.put(null, root);
			}
			return root;
		}
	}

	private MemFolder readMemFolder(DataInputStream in, Map<Long, MemFolder> folderMap) throws IOException {
		MemFolder folder = new MemFolder(in.readLong());
		folderMap.put(folder.folderId, folder);
		int fileCount = in.readInt();
		for (int i = 0; i < fileCount; i++) {
			folder.files.add(readMemFile(in));
		}
		int childCount = in.readInt();
		for (int i = 0; i < childCount; i++) {
			folder.childFolders.add(readMemFolder(in, folderMap));
		}
		return folder;
	}

	private MemFile readMemFile(DataInputStream in) throws IOException {
		long fileId = in.readLong();
		String name = in.readUTF();
		long sizeBytes = in.readLong();
		long lastModifiedAt = in.readLong();
		String sha1hash = in.readUTF();
		if (sha1hash.isEmpty()) {
			sha1hash = null;
		}
		MemFile file = new MemFile(fileId, name, sizeBytes, lastModifiedAt, sha1hash);
		return file;
	}

	/** Loads the persisted MemFolder tree into memRootFolder/memFolderMap. */
	public boolean loadMemFolderFromFile() {
		Path memFolderFile = getMemFolderFile();
		if (!Files.isRegularFile(memFolderFile)) {
			return false;
		}
		try {
			Map<Long, MemFolder> folderMap = new HashMap<>();
			MemFolder root = deserializeObjectFromFile(memFolderFile, folderMap);
			memFolderMap = folderMap;
			memRootFolder = root;
			return true;
		} catch (Exception e) {
			LOG.error("Failed to load MemFolder from file: " + memFolderFile, e);
			return false;
		}
	}

    public void saveMemFolderToFile(MemFolder memRootFolder) {
		Path memFolderFile = getMemFolderFile();
		try {
			Files.createDirectories(memFolderFile.getParent());
			serializeObjectToFile(memRootFolder, memFolderFile);
		} catch (Exception e) {
			LOG.error("Failed to save MemFolder to file: " + memFolderFile, e);
		}
	}
	
	public static class MemFolderOLD {
		private static ProgressLogger PL;
		private static Predicate<String> filter;
		
		private long folderId;
		private List<MemFolder> childFolders;
		private List<String> files;
		
		private transient long countChildFiles;
//		private transient long countChildBytes;
		
		public MemFolderOLD(long folderId) {
			this.folderId = folderId;
			this.childFolders = new ArrayList<>();
			this.files = new ArrayList<>();
			this.countChildFiles = 0;
//			this.countChildBytes = 0;
		}
		public void addFile(String fileName) {
			files.add(fileName.toLowerCase());
		}
		public void addChildFolder(MemFolder child) {
			childFolders.add(child);
		}
		public boolean isVisible() {
			return countChildFiles > 0L;
		}
		public long countFiles() {
			countChildFiles = 0;
			for (MemFolder child : childFolders) {
				countChildFiles += child.countFiles();
			}
			for (String file : files) {
				PL.progress(()->"Checking file: " + file);
				if (filter.test(file)) {
					countChildFiles++;
				}
			}
			return countChildFiles;
		}

		public long startCountFiles(String filterText, Predicate<String> currentFilter) {
			filter = currentFilter;
			PL = new ProgressLogger();
			countFiles();
			PL.finished(()->"countFiles("+filterText+") finished");
			return countChildFiles;
		}
		public void setCountChildFiles(int fileCount) {
			this.countChildFiles = fileCount;
		}

	}

	public static class MemFolder {
		private static ProgressLogger PL;
		private static Predicate<String> filter;
		
		private long folderId;
		private List<MemFolder> childFolders;
		private List<MemFile> files;
		
		private transient long countChildFiles;
//		private transient long countChildBytes;
		
		public MemFolder(long folderId) {
			this.folderId = folderId;
			this.childFolders = new ArrayList<>();
			this.files = new ArrayList<>();
			this.countChildFiles = 0;
//			this.countChildBytes = 0;
		}
		public void addFile(FileRecord fileRecord) {
			files.add(MemFile.createFromFileRecord(fileRecord));
		}
		public void addChildFolder(MemFolder child) {
			childFolders.add(child);
		}
		public boolean isVisible() {
			return countChildFiles > 0L;
		}
		public long countFiles() {
			countChildFiles = 0;
			for (MemFolder child : childFolders) {
				countChildFiles += child.countFiles();
			}
			for (MemFile mFil : files) {
				PL.progress(()->"Checking file: " + mFil.fileName());
				if (filter.test(mFil.fileName().toLowerCase())) {
					countChildFiles++;
				}
			}
			return countChildFiles;
		}

		public long startCountFiles(String filterText, Predicate<String> currentFilter) {
			filter = currentFilter;
			PL = new ProgressLogger();
			countFiles();
			PL.finished(()->"countFiles("+filterText+") finished");
			return countChildFiles;
		}
		public void setCountChildFiles(int fileCount) {
			this.countChildFiles = fileCount;
		}

	}

	record MemFile(long fileId, String fileName, long sizeBytes, long lastModifiedAt, String sha1hash) {
		public static MemFile createFromFileRecord(FileRecord fileRecord) {
			return new MemFile(fileRecord.getId(), fileRecord.getName(), fileRecord.getSizeBytes(), fileRecord.getLastModifiedAt().toEpochMilli(), fileRecord.getSha1hash());
		}
	}

	Map<Long, MemFolder> memFolderMap = new HashMap<>();
	MemFolder memRootFolder = null;


	private void updateMemFolderVisibility() {
		if (memRootFolder == null) {
			if (loadMemFolderFromFile()) {
				memRootFolder.startCountFiles(filterString, s -> checkFilter(s));
			}
			else {
				readMemFoldersFromDB();
				saveMemFolderToFile(memRootFolder);
			}
		}
		else {
			memRootFolder.startCountFiles(filterString, s -> checkFilter(s));
		}
	}
	
	private void readMemFoldersFromDB() {
		memFolderMap = new HashMap<>();
		memRootFolder = new MemFolder(-1);
		memFolderMap.put(null, memRootFolder);
		memFolderMap.put(-1L, memRootFolder);
		if (filterPattern == null) {
			return;
		}
		ProgressLogger progressLogger = new ProgressLogger();
		DBFileWalker<CountResults> walker = new DBFileWalker<>(dbMgr);
		CountResults result = walker.walk(new DBFileVisitor<>() {
			@Override
			public void preVisitFolder(ScanLocation location, FolderRecord folderRecord, Path path) {
				MemFolder mf = new MemFolder(folderRecord.getId());
				memFolderMap.put(mf.folderId, mf);
				memFolderMap.get(folderRecord.getParentFolderId()).addChildFolder(mf);
			}

			@Override
			public CountResults visitFolder(ScanLocation location, FolderRecord folderRecord, Path path,
					List<CountResults> childVisits) {
				CountResults result = CountResults.count(childVisits);
				if (folderRecord != null) {
					result = result.add(CountResults.count(folderRecord));
					memFolderMap.get(folderRecord.getId()).setCountChildFiles(result.fileCount());
				}
				return result;
			}

			@Override
			public CountResults visitFile(ScanLocation location, FolderRecord folderRecord, FileRecord fileRecord,
					Path path) {
				progressLogger.progress(()->AppUtils.normFilename(path));
				memFolderMap.get(folderRecord.getId()).addFile(fileRecord);
				CountResults result;
				if (checkFilter(fileRecord)) {
					result = CountResults.count(fileRecord);
				}
				else {
					result = new CountResults(0, 0, 0);
				}
				return result;
			}
		});
		progressLogger.finished(()->"Filter: " + filterString + ", counts: " + result);
	}


//	private void collectVisibleFolders() {
//		visibleFolders.clear();
//		emptyFolders.clear();
//		if (filterPattern == null) {
//			return;
//		}
//		ProgressLogger<FileRecord> progressLogger = new ProgressLogger<>();
//		DBFileWalker<CountResults> walker = new DBFileWalker<>(dbMgr);
//		CountResults result = walker.walk(new DBFileVisitor<>() {
//			@Override
//			public void preVisitFolder(ScanLocation location, FolderRecord folderRecord, Path path) {}
//
//			@Override
//			public CountResults visitFolder(ScanLocation location, FolderRecord folderRecord, Path path,
//					List<CountResults> childVisits) {
//				CountResults result = CountResults.count(childVisits);
//				if (location != null) {
//					result = result.add(CountResults.count(folderRecord));
//					if (result.fileCount() > 0) {
//						visibleFolders.add(folderRecord.getId());
//					}
//					else {
//						emptyFolders.add(folderRecord.getId());
//					}
//				}
//				return result;
//			}
//
//			@Override
//			public CountResults visitFile(ScanLocation location, FolderRecord folderRecord, FileRecord fileRecord,
//					Path path) {
//				progressLogger.progress(()->AppUtils.normFilename(path));
//				CountResults result;
//				if (checkFilter(fileRecord)) {
//					result = CountResults.count(fileRecord);
//				}
//				else {
//					result = new CountResults(0, 0, 0);
//				}
//				return result;
//			}
//		});
//		progressLogger.finished(()->"Filter: " + filterString + ", visible folders: " + visibleFolders.size() + ", empty folders: "
//				+ emptyFolders.size() + ", total counts: " + result);
//	}



	private void onFilter() {
		String newFilterString = promptForInput("Enter Filter:", filterString);
		filterPattern = compileFilterPattern(newFilterString);
		filterString = newFilterString;
		updateMemFolderVisibility();
		
		treeModel.setRoot(new DefaultMutableTreeNode("Scan Locations"));
		dbMgr.getScanLocationRepository().findAll().forEach(loc -> ((DefaultMutableTreeNode) treeModel.getRoot())
				.add(createLazyNode(loc, loc.disp(), loc.hasRootFolderId())));
		treeModel.reload();
		tree.repaint();
	}


	public void onResetTree() {
		treeModel.setRoot(new DefaultMutableTreeNode("Scan Locations"));
		dbMgr.getScanLocationRepository().findAll().forEach(loc -> ((DefaultMutableTreeNode) treeModel.getRoot())
				.add(createLazyNode(loc, loc.disp(), loc.hasRootFolderId())));
		treeModel.reload();
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
			for (FolderRecord folder : dbMgr.getFolderRecordRepository().findByParentFolder(id)) {
				if (checkFilter(folder)) {
					String label = folder.disp("");
					MemFolder mf = memFolderMap.get(folder.getId());
					if (mf != null) {
						label = label + "   (" + mf.countChildFiles + ")";
					}
					node.add(createLazyNode(folder, label, true));
				}
			}
			for (FileRecord file : dbMgr.getFileRecordRepository().findByParentFolder(id)) {
				if (checkFilter(file)) {
//					String name = file.getName();
//					String sizeBytes = readable(file.getSizeBytes());
//					String lastModifiedAt = readable(file.getLastModifiedAt());
//					String sha1hash = readable(file.getSha1hash());
//					String label = "%s %s  %s  %s".formatted(AppUtils.padr(name,60), AppUtils.padl(sizeBytes,10), lastModifiedAt, sha1hash);
					node.add(new DefaultMutableTreeNode(new NodeData(file, file.getName()), false));
				}
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

		String sizeText() {
			return object instanceof FileRecord f ? readable(f.getSizeBytes()) : "";
		}

		String modifiedText() {
			return object instanceof FileRecord f ? readable(f.getLastModifiedAt()) : "";
		}

		String sha1Text() {
			return object instanceof FileRecord f ? readable(f.getSha1hash()) : "";
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
