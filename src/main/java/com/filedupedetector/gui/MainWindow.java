package com.filedupedetector.gui;

import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.model.ScanSession;
import com.filedupedetector.persistence.FileRecordRepository;
import com.filedupedetector.persistence.ScanLocationRepository;
import com.filedupedetector.service.DuplicateQueryService;
import com.filedupedetector.service.DuplicateQueryServiceImpl;
import com.filedupedetector.service.ScanProgressListener;
import com.filedupedetector.service.ScanService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Main application window (FR-009, FR-010, FR-011): a lazy {@link JTree} of scan locations and
 * their files, a duplicate-group detail panel, and controls to add scan locations and run a
 * scan without blocking the Event Dispatch Thread.
 */
public final class MainWindow extends JFrame {

    private final ScanLocationRepository scanLocationRepository;
    private final FileRecordRepository fileRecordRepository;
    private final ScanService scanService;
    private final DuplicateQueryService duplicateQueryService;

    private JTree tree;
    private DuplicateAwareTreeCellRenderer renderer;
    private DuplicateGroupDetailPanel detailPanel;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private final Map<Long, ScanLocation> locationsById = new LinkedHashMap<>();

    public MainWindow(ScanLocationRepository scanLocationRepository, FileRecordRepository fileRecordRepository,
                       ScanService scanService) {
        super("File Dupe Detector");
        this.scanLocationRepository = scanLocationRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.scanService = scanService;
        this.duplicateQueryService = new DuplicateQueryServiceImpl(fileRecordRepository);

        buildUi();
        reloadTree();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 600);
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        setLayout(new BorderLayout());

        JPanel toolbar = new JPanel();
        JButton addLocationButton = new JButton("Add Scan Location...");
        addLocationButton.addActionListener(e -> onAddScanLocation());
        JButton scanButton = new JButton("Scan");
        scanButton.addActionListener(e -> onScan());
        toolbar.add(addLocationButton);
        toolbar.add(scanButton);
        add(toolbar, BorderLayout.NORTH);

        renderer = new DuplicateAwareTreeCellRenderer(duplicateQueryService);
        tree = new JTree();
        tree.setCellRenderer(renderer);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

        detailPanel = new DuplicateGroupDetailPanel(duplicateQueryService, locationsById);
        tree.addTreeSelectionListener(e -> {
            Object selected = e.getPath() == null ? null : e.getPath().getLastPathComponent();
            detailPanel.onNodeSelected(selected);
        });

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(tree), detailPanel);
        splitPane.setResizeWeight(0.6);
        add(splitPane, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        progressBar = new JProgressBar(0, 100);
        statusLabel = new JLabel("Ready");
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.add(progressBar, BorderLayout.CENTER);
        add(statusPanel, BorderLayout.SOUTH);
    }

    private void reloadTree() {
        List<ScanLocation> locations = scanLocationRepository.findAll();
        locationsById.clear();
        locations.forEach(l -> locationsById.put(l.getId(), l));

        tree.setModel(new LazyFileTreeModel(locations, fileRecordRepository));
        renderer.refreshDuplicateKeys();
    }

    private void onAddScanLocation() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select a folder to add as a scan location");
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            var selectedDir = chooser.getSelectedFile();
            ScanLocation location = new ScanLocation();
            location.setDisplayName(selectedDir.getName());
            location.setRootPathWithVolInfo(selectedDir.getAbsolutePath());
            location.setType(LocationType.LOCAL);
            scanLocationRepository.save(location);
            reloadTree();
        }
    }

    private void onScan() {
        List<ScanLocation> locations = scanLocationRepository.findAll();
        if (locations.isEmpty()) {
            statusLabel.setText("No scan locations configured - add one first.");
            return;
        }
        progressBar.setIndeterminate(true);
        statusLabel.setText("Scanning " + locations.size() + " location(s)...");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                scanService.scanAsync(locations, new ScanProgressListener() {
                    @Override
                    public void onProgress(int filesProcessed, int totalEstimate) {
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setIndeterminate(false);
                            int percent = totalEstimate <= 0 ? 0 : (int) (100.0 * filesProcessed / totalEstimate);
                            progressBar.setValue(percent);
                            statusLabel.setText("Processed " + filesProcessed + " / " + totalEstimate + " files");
                        });
                    }

                    @Override
                    public void onSessionComplete(ScanSession result) {
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setIndeterminate(false);
                            progressBar.setValue(100);
                            statusLabel.setText("Scan complete: " + result.getFilesProcessed() + " processed, "
                                    + result.getFilesSkippedErrored() + " skipped/errored, "
                                    + result.getFilesMissing() + " missing (" + result.getOutcome() + ")");
                            reloadTree();
                        });
                    }

                    @Override
                    public void onLocationUnavailable(ScanLocation location) {
                        SwingUtilities.invokeLater(() ->
                                statusLabel.setText("WARNING: location unavailable: " + location.getRootPath()));
                    }
                });
                return null;
            }
        }.execute();
    }
}
