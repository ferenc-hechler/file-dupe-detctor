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
import com.filedupedetector.persistence.FileRecordRepository;
import com.filedupedetector.persistence.JdbcConnectionManager;
import com.filedupedetector.persistence.JdbcFileRecordRepository;
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

	ScanLocationRepository scanLocationRepository;
	FileRecordRepository fileRecordRepository;
    ScanService scanService;
            
	public FileScanCliMain() {
        JdbcConnectionManager connectionManager = new JdbcConnectionManager();
        this.scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
        this.fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
        var scanSessionRepository = new JdbcScanSessionRepository(connectionManager);

        this.scanService = new ScanServiceImpl(
                new NioFileWalker(),
                new Sha1HashService(),
                new DuplicateMatcher(),
                fileRecordRepository,
                scanLocationRepository,
                scanSessionRepository);
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
        location.setRootPathX(rootPath.toString());
        location.setType(LocationType.LOCAL);
        scanLocationRepository.save(location);		
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

	private static final ObjectMapper EXPORT_MAPPER = new ObjectMapper()
			.registerModule(new JavaTimeModule())
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
	}

	public static void main(String[] args) {
		if (args.length == 0) {
//			args = new String[] {"scan", "TestFolderCLI3", "./specs"};
//			args = new String[] {"scan", "TestFolderCLISpecify", "./.specify"};
//			args = new String[] {"list-roots"};
			args = new String[] {"export", "./output/export-specs-2d.json"};
		}
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

		Subparser exportParser = subparsers.addParser("export")
				.help("export scanned files into json file");
		exportParser.addArgument("exportFile")
				.help("path of the json export file");

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
			case "scan":
				String name = ns.getString("name");
				String folderPath = ns.getString("folderPath");
				cliScanner.scanNewLocation(name, folderPath);
				break;
			case "export":
				String exportFile = ns.getString("exportFile");
				cliScanner.export(exportFile);
				break;
			default:
				System.out.println("Unknown action: " + action);
				printUsage();
		}
	}

}
