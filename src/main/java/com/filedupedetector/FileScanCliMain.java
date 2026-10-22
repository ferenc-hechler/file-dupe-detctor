package com.filedupedetector;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.model.ScanSession;
import com.filedupedetector.pcloud.PCloudApiClient;
import com.filedupedetector.persistence.FileRecordRepository;
import com.filedupedetector.persistence.FolderRecordRepository;
import com.filedupedetector.persistence.JdbcConnectionManager;
import com.filedupedetector.persistence.JdbcFileRecordRepository;
import com.filedupedetector.persistence.JdbcFolderRecordRepository;
import com.filedupedetector.persistence.JdbcScanLocationRepository;
import com.filedupedetector.persistence.JdbcScanSessionRepository;
import com.filedupedetector.persistence.ScanLocationRepository;
import com.filedupedetector.scan.NioFileWalker;
import com.filedupedetector.scan.Sha1HashService;
import com.filedupedetector.service.DuplicateMatcher;
import com.filedupedetector.service.ScanProgressListener;
import com.filedupedetector.service.ScanService;
import com.filedupedetector.service.ScanServiceImpl;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import net.sourceforge.argparse4j.inf.Subparsers;


public class FileScanCliMain {

	{{
		PCloudApiClient.setConfigFilename("~/.env-pcloud-ro-dev");
	}}
	
	ScanLocationRepository scanLocationRepository;
	FileRecordRepository fileRecordRepository;
	FolderRecordRepository folderRecordRepository;
    ScanService scanService;
            
	public FileScanCliMain() {
        JdbcConnectionManager connectionManager = new JdbcConnectionManager();
        this.scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
        this.fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
        this.folderRecordRepository = new JdbcFolderRecordRepository(connectionManager);

        this.scanService = new ScanServiceImpl(
                new NioFileWalker(),
                new Sha1HashService(),
                new DuplicateMatcher(),
                fileRecordRepository,
                scanLocationRepository,
                folderRecordRepository,
                true);
	}
	
	record VolumeInfo(String volName, String volType, long volSize, String volVSN) {}
	
	private static String formatVSN(int vsnInt) {
		String result = Integer.toHexString(vsnInt);
		while (result.length()<8) { result= "0"+result; }
		result= result.substring(0, 4)+"-"+result.substring(4);
		return result;
	}
	
	private String getVSN(FileStore store) {
		try {
			return formatVSN((Integer) store.getAttribute("volume:vsn"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public VolumeInfo getVolumeInfo(Path path) {
		VolumeInfo result = null;
		Path root = path.getRoot();
		String volLetter = root.toString().substring(0,1);
		for (FileStore store: FileSystems.getDefault().getFileStores()) {
			String storeString = store.toString();
			if (storeString.indexOf("("+volLetter+":)") != -1) {
				String volName = store.name();
				String volType = store.type();
				long volSize = -1;
				try {
					volSize = store.getTotalSpace();
				} catch (IOException e) {}
				String volVSN = getVSN(store);
				result = new VolumeInfo(volName, volType, volSize, volVSN);
			}
		}
		return result;
	}
	
	public void addScanFolder(String name, String folderPath) {
		ScanLocation location = new ScanLocation();
		location.setDisplayName(name);
		Path rootPath = Paths.get(folderPath).toAbsolutePath().normalize();
		location.setType(LocationType.LOCAL);
		location.setRootPathWithVolInfo(rootPath.toString());

		var existing = scanLocationRepository.findAll().stream()
				.filter(loc -> loc.getDisplayName().equals(name))
				.findFirst();
		if (existing.isPresent()) {
			// The location already exists: it must still describe the same folder on the same volume.
			verifySameLocation(existing.get(), location);
			return;
		}
		scanLocationRepository.save(location);
	}

	public void addPCloudScanFolder(String name, String pcloudPath) {
		ScanLocation location = new ScanLocation();
		location.setDisplayName(name);
		String rootPath = "/"+pcloudPath.replaceAll("^/", "").replaceAll("/$", "");
		location.setType(LocationType.PCLOUD);
		location.setRootPathWithPCloudInfo(rootPath);

		var existing = scanLocationRepository.findAll().stream()
				.filter(loc -> loc.getDisplayName().equals(name))
				.findFirst();
		if (existing.isPresent()) {
			// The location already exists: it must still describe the same folder on the same volume.
			verifySameLocation(existing.get(), location);
			return;
		}
		scanLocationRepository.save(location);
	}

	private static void verifySameLocation(ScanLocation existing, ScanLocation requested) {
		List<String> differences = new java.util.ArrayList<>();
		addDifference(differences, "rootPath", existing.getRootPath(), requested.getRootPath());
		addDifference(differences, "volName", existing.getVolName(), requested.getVolName());
		addDifference(differences, "volType", existing.getVolType(), requested.getVolType());
		addDifference(differences, "volSize", existing.getVolSize(), requested.getVolSize());
		addDifference(differences, "volVSN", existing.getVolVSN(), requested.getVolVSN());
		if (!differences.isEmpty()) {
			throw new IllegalStateException("Scan location '" + existing.getDisplayName()
					+ "' already exists with different values: " + String.join("; ", differences));
		}
	}

	private static void addDifference(List<String> differences, String field, Object stored, Object requested) {
		if (!java.util.Objects.equals(stored, requested)) {
			differences.add(field + " (stored: " + stored + ", requested: " + requested + ")");
		}
	}
	
	static class ScanStatus {
		String status;
		int total;
		int processed;
		public ScanStatus() {
			this.status = "NEW";
			this.total = 0;
			this.processed = 0;
		}
		public void update(int total, int processed) {
			this.status = "IN_PROGRESS";
			this.total = total;
			this.processed = processed;
		}
		public void finished() {
			this.status = "FINISHED";
		}
		@Override
		public String toString() {
			return "ScanStatus[" + status + " " + processed + "/" +total + "]";
		}
	}
	
	public void scanLocation(String name) {
		final ScanStatus status = new ScanStatus();
		
		ScanProgressListener listener = new ScanProgressListener() {
			@Override
			public void onProgress(int filesProcessed, int totalEstimate) {
				status.update(totalEstimate, filesProcessed);
			}
			@Override
			public void onSessionComplete(ScanSession result) {
				synchronized (status) {
					status.finished();
					status.notifyAll();
				}
			}
			@Override
			public void onLocationUnavailable(ScanLocation location) {
				System.out.println("WARNING: location unavailable: " + location.getRootPath());
			}
		};
		List<ScanLocation> locations = scanLocationRepository.findAll();
        List<ScanLocation> locationsToScan = locations.stream()
				.filter(loc -> loc.getDisplayName().equals(name))
				.toList();
		scanService.scanAsync(locationsToScan, listener);
		while (!status.status.equals("FINISHED")) {
			System.out.println(status);
			try {
				synchronized (status) {
					status.wait(10000);
				}
			} catch (InterruptedException e) {}
		}
		System.out.println(status);
	}
	
	public void scanNewLocation(String name, String folderPath) {
		addScanFolder(name, folderPath);
		scanLocation(name);
	}

	public void scanPCloudLocation(String name) {
		List<ScanLocation> locations = scanLocationRepository.findAll();
        List<ScanLocation> locationsToScan = locations.stream()
				.filter(loc -> loc.getDisplayName().equals(name))
				.toList();
        for (ScanLocation loc : locationsToScan) {
			if (loc.getType() != LocationType.PCLOUD) {
				throw new IllegalStateException("Scan location '" + name + "' is not a pCloud location: " + loc.getType());
			}
			System.out.println("Scanning pCloud location '" + name + "' at " + loc.getRootPath());
		}
	}
	
	public void scanNewPCloudLocation(String name, String pcloudPath) {
		pcloudPath = pcloudPath.replaceAll("^/", "").replaceAll("/$", "");
		addPCloudScanFolder(name, pcloudPath);
		scanPCloudLocation(name);
	}

	public List<String> getLocationNames() {
		List<ScanLocation> scanLocations = scanLocationRepository.findAll();
		List<String> result = scanLocations.stream().map(loc -> loc.getDisplayName()).toList();
		return result;
	}
	
	private void listRoots() {
		List<String> roots = getLocationNames();
		System.out.println("Scan roots:");
		for (String root : roots) {
			System.out.println(" - " + root);
		}
	}

	/**
	 * Imports scan locations and file records from a JSON file created with the export action.
	 * Existing locations (matched by display name) must have the same rootPath and volume info;
	 * otherwise nothing is imported and an error is thrown.
	 */
	private void importFile(String importFile) {
		Path source = Paths.get(importFile).toAbsolutePath().normalize();
		com.fasterxml.jackson.databind.JsonNode root;
		try {
			root = EXPORT_MAPPER.readTree(source.toFile());
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read import file " + source, e);
		}
		com.fasterxml.jackson.databind.JsonNode locationNodes = root == null ? null : root.get("scanLocations");
		if (locationNodes == null || !locationNodes.isArray()) {
			throw new IllegalStateException("Invalid import file " + source + ": missing 'scanLocations' array");
		}

		// Pass 1: parse and validate everything before changing the database.
		List<ScanLocation> importedLocations = new java.util.ArrayList<>();
		for (com.fasterxml.jackson.databind.JsonNode node : locationNodes) {
			ScanLocation imported = toScanLocation(node);
			scanLocationRepository.findAll().stream()
					.filter(loc -> loc.getDisplayName().equals(imported.getDisplayName()))
					.findFirst()
					.ifPresent(existing -> verifySameLocation(existing, imported));
			importedLocations.add(imported);
		}

		// Pass 2: persist locations and their file records.
		int fileCount = 0;
		for (int i = 0; i < importedLocations.size(); i++) {
			ScanLocation imported = importedLocations.get(i);
			ScanLocation target = scanLocationRepository.findAll().stream()
					.filter(loc -> loc.getDisplayName().equals(imported.getDisplayName()))
					.findFirst()
					.orElseGet(() -> scanLocationRepository.save(imported));
			for (com.fasterxml.jackson.databind.JsonNode recordNode : locationNodes.get(i).path("fileRecords")) {
				FileRecord record = new FileRecord();
				record.setScanLocationId(target.getId());
				record.setRelativePath(requireText(recordNode, "relativePath"));
				record.setSizeBytes(recordNode.path("sizeBytes").asLong());
				record.setContentHash(recordNode.hasNonNull("contentHash") ? recordNode.get("contentHash").asText() : null);
				record.setLastModifiedAt(java.time.Instant.parse(requireText(recordNode, "lastModifiedAt")));
				record.setLastSeenAt(recordNode.hasNonNull("lastSeenAt")
						? java.time.Instant.parse(recordNode.get("lastSeenAt").asText())
						: java.time.Instant.now());
				fileRecordRepository.upsert(record);
				fileCount++;
			}
		}
		System.out.println("Imported " + importedLocations.size() + " scan locations and " + fileCount
				+ " file records from " + source);
	}

	private static ScanLocation toScanLocation(com.fasterxml.jackson.databind.JsonNode node) {
		ScanLocation location = new ScanLocation();
		location.setDisplayName(requireText(node, "displayName"));
		location.setRootPathX(requireText(node, "rootPath"));
		location.setVolName(requireText(node, "volName"));
		location.setVolType(requireText(node, "volType"));
		location.setVolSize(node.path("volSize").asLong(-1));
		location.setVolVSN(requireText(node, "volVSN"));
		location.setType(LocationType.valueOf(requireText(node, "type")));
		location.setAvailable(node.path("available").asBoolean(true));
		if (node.hasNonNull("lastScannedAt")) {
			location.setLastScannedAt(java.time.Instant.parse(node.get("lastScannedAt").asText()));
		}
		return location;
	}

	private static String requireText(com.fasterxml.jackson.databind.JsonNode node, String field) {
		com.fasterxml.jackson.databind.JsonNode value = node.get(field);
		if (value == null || value.isNull() || value.asText().isBlank()) {
			throw new IllegalStateException("Invalid import file: missing field '" + field + "' in " + node);
		}
		return value.asText();
	}
	
	/** Jackson mixin: scanLocationId is redundant in the export, as file records are nested under their scan location. */
	@com.fasterxml.jackson.annotation.JsonIgnoreProperties("scanLocationId")
	private abstract static class FileRecordExportMixIn {}

	private static final ObjectMapper EXPORT_MAPPER = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.addMixIn(FileRecord.class, FileRecordExportMixIn.class)
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(SerializationFeature.INDENT_OUTPUT);

	private void export(String exportFile) {
		List<ScanLocation> scanLocations = scanLocationRepository.findAll();
		System.out.println("Exporting " + scanLocations.size() + " scan locations to " + exportFile);

		Path targetPath = Paths.get(exportFile).toAbsolutePath().normalize();
		try {
			Path parentDir = targetPath.getParent();
			if (parentDir != null) {
				Files.createDirectories(parentDir);
			}

			List<Map<String, Object>> exportedLocations = scanLocations.stream()
					.map(this::toExportMap)
					.toList();
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("scanLocations", exportedLocations);

			EXPORT_MAPPER.writeValue(targetPath.toFile(), root);
			System.out.println("Export complete: " + targetPath);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to export scan data to " + exportFile, e);
		}
	}

	private Map<String, Object> toExportMap(ScanLocation location) {
		List<FileRecord> fileRecords = fileRecordRepository.findByLocation(location.getId());

		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", location.getId());
		map.put("displayName", location.getDisplayName());
		map.put("rootPath", location.getRootPath());
		map.put("volName", location.getVolName());
		map.put("volType", location.getVolType());
		map.put("volSize", location.getVolSize());
		map.put("volVSN", location.getVolVSN());
		map.put("type", location.getType());
		map.put("lastScannedAt", location.getLastScannedAt());
		map.put("available", location.isAvailable());
		map.put("fileRecords", fileRecords);
		return map;
	}


	private static void printUsage() {
		System.out.println("Usage: java -jar filedupedetector.jar <action> [<name> <folderPath>]");
		System.out.println("Actions:");
		System.out.println("  list-roots");
		System.out.println("  scan <name> <folderPath>");
		System.out.println("  scanpcloud <name> <pcloudPath>");
		System.out.println("  export <exportFile>");
		System.out.println("  import <importFile>");
	}

	public static void main(String[] args) {
		if (args.length == 0) {
			printUsage();
			return;
		}
		ArgumentParser parser = ArgumentParsers.newFor("filedupedetector")
				.build()
				.defaultHelp(true)
				.description("Detects duplicate files across local volumes and cloud storage.");

		Subparsers subparsers = parser.addSubparsers()
				.title("actions")
				.dest("action")
				.help("action to perform");

		subparsers.addParser("list-roots")
				.help("list all configured scan roots");

		Subparser scanParser = subparsers.addParser("scan")
				.help("add (if new) and scan a folder");
		scanParser.addArgument("name")
				.help("display name of the scan location");
		scanParser.addArgument("folderPath")
				.help("path of the folder to scan");

		Subparser scanpcloudParser = subparsers.addParser("scanpcloud")
				.help("add (if new) and scan a pcloud folder");
		scanpcloudParser.addArgument("name")
				.help("display name of the scan location");
		scanpcloudParser.addArgument("pcloudPath")
				.help("path of the cloud folder to scan");


		Subparser exportParser = subparsers.addParser("export")
				.help("export scanned files into json file");
		exportParser.addArgument("exportFile")
				.help("path of the json export file");

		Subparser importParser = subparsers.addParser("import")
				.help("import scanned files from a json file created with export");
		importParser.addArgument("importFile")
				.help("path of the json file to import");

		Namespace ns;
		try {
			ns = parser.parseArgs(args);
		} catch (ArgumentParserException e) {
			parser.handleError(e);
			System.exit(1);
			return;
		}

		String action = ns.getString("action");
		if (action == null) {
			printUsage();
			return;
		}

		FileScanCliMain cliScanner = new FileScanCliMain();

		switch (action) {
			case "list-roots":
				cliScanner.listRoots();
				break;
			case "scan": {
				String name = ns.getString("name");
				String folderPath = ns.getString("folderPath");
				cliScanner.scanNewLocation(name, folderPath);
				break;
			}
			case "scanpcloud": {
				String name = ns.getString("name");
				String pcloudPath = ns.getString("pcloudPath");
				cliScanner.scanNewPCloudLocation(name, pcloudPath);
				break;
			}
			case "export":
				String exportFile = ns.getString("exportFile");
				cliScanner.export(exportFile);
				break;
			case "import":
				cliScanner.importFile(ns.getString("importFile"));
				break;
			default:
				System.out.println("Unknown action: " + action);
				printUsage();
		}
	}

}