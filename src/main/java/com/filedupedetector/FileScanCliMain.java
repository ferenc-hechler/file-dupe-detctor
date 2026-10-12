package com.filedupedetector;

import java.nio.file.Paths;
import java.util.List;

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
	
	public void scanLocation(String name, ScanProgressListener listener) {
        List<ScanLocation> locations = scanLocationRepository.findAll();
        List<ScanLocation> locationsToScan = locations.stream()
				.filter(loc -> loc.getDisplayName().equals(name))
				.toList();
		scanService.scanAsync(locationsToScan, listener);
		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {}
	}
	
	public static void main(String[] args) {
		String name = "TestFolderCLI";
		String folderPath = "./specs";
		if (args.length >= 2) {
			name = args[0];
			folderPath = args[1];
		}
		FileScanCliMain cliScanner = new FileScanCliMain();
		cliScanner.addScanFolder(name, folderPath);
		cliScanner.scanLocation(name, new ScanProgressListener() {
			@Override
			public void onProgress(int filesProcessed, int totalEstimate) {
				System.out.println(filesProcessed + " / " + totalEstimate + " files processed");
			}
			@Override
			public void onSessionComplete(ScanSession result) {
				System.out.println("Scan complete: " + result);
			}
			@Override
			public void onLocationUnavailable(ScanLocation location) {
				System.out.println("WARNING: location unavailable: " + location.getRootPath());
			}
		});
	}
}
