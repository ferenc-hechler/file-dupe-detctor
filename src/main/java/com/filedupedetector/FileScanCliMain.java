package com.filedupedetector;

import java.nio.file.Paths;
import java.util.List;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import net.sourceforge.argparse4j.inf.Subparsers;

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
	
	public void addScanFolder(String name, String folderPath) {
		ScanLocation location = new ScanLocation();
        location.setDisplayName(name);
        location.setRootPath(Paths.get(folderPath).toAbsolutePath().normalize().toString());
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

	private void export(String exportFile) {
		List<ScanLocation> scanLocations = scanLocationRepository.findAll();
		System.out.println("Exporting " + scanLocations.size() + " scan locations to " + exportFile);
	}


	private static void printUsage() {
		System.out.println("Usage: java -jar filedupedetector.jar <action> [<name> <folderPath>]");
		System.out.println("Actions:");
		System.out.println("  list-roots");
		System.out.println("  scan <name> <folderPath>");
	}

	public static void main(String[] args) {
		if (args.length == 0) {
//			args = new String[] {"scan", "TestFolderCLI", "./specs"};
//			args = new String[] {"list-roots"};
			args = new String[] {"export", "./export.json"};
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
			case "exort":
				String exportFile = ns.getString("exportFile");
				cliScanner.export(exportFile);
				break;
			default:
				System.out.println("Unknown action: " + action);
				printUsage();
		}
	}

}
