package com.filedupedetector;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.persistence.DBManager;
import com.filedupedetector.persistence.SimpleDBFileWalker;
import com.filedupedetector.persistence.SimpleDBFileWalker.SimpleDBFileVisitor;
import com.filedupedetector.utils.AppUtils;


public class TextExporter {

	DBManager dbMgr;
            
	public TextExporter() {
		this.dbMgr = new DBManager(false);
	}
	

	private void export(String exportFile) {
		List<ScanLocation> scanLocations = dbMgr.getScanLocationRepository().findAll();
		System.out.println("Exporting " + scanLocations.size() + " scan locations to " + exportFile);

		try {
			Path targetPath = AppUtils.normAbsPath(exportFile);
			Path parentDir = targetPath.getParent();
			if (parentDir != null) {
				Files.createDirectories(parentDir);
			}
	
			try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(targetPath))) {
				for (ScanLocation location : scanLocations) {
					writer.println("Scan Location: " + location.disp());
					writer.println("TEXT EXPORT at " + java.time.LocalDateTime.now());
					SimpleDBFileWalker fileWalker = new SimpleDBFileWalker(dbMgr);
					fileWalker.walk(new SimpleDBFileVisitor() {
						@Override
						public void preVisitFolder(ScanLocation location, FolderRecord folderRecord, Path path) {}
						@Override
						public void folderVisit(ScanLocation location, FolderRecord folderRecord, Path path) {}
						@Override
						public void fileVisit(ScanLocation location, FolderRecord folderRecord, FileRecord fileRecord, Path path) {
							writer.println(AppUtils.normFilename(path));
						}
					});
				}
				writer.println("Finished");
			}
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to export scan data to " + exportFile, e);
		}
	}



	public static void main(String[] args) {
		TextExporter exporter = new TextExporter();
		exporter.export("output/db-export.txt");
	}
	
}