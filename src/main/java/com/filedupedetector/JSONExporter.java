package com.filedupedetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.persistence.FileRecordRepository;
import com.filedupedetector.persistence.FolderRecordRepository;
import com.filedupedetector.persistence.JdbcConnectionManager;
import com.filedupedetector.persistence.JdbcFileRecordRepository;
import com.filedupedetector.persistence.JdbcFolderRecordRepository;
import com.filedupedetector.persistence.JdbcScanLocationRepository;
import com.filedupedetector.persistence.ScanLocationRepository;
import com.filedupedetector.utils.AppUtils;


public class JSONExporter {

	ScanLocationRepository scanLocationRepository;
	FileRecordRepository fileRecordRepository;
	FolderRecordRepository folderRecordRepository;
            
	public JSONExporter() {
        JdbcConnectionManager connectionManager = new JdbcConnectionManager();
        this.scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
        this.fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
        this.folderRecordRepository = new JdbcFolderRecordRepository(connectionManager);
	}
	

	/** Jackson mixin: scanLocationId is redundant in the export, as file records are nested under their scan location. */
	@com.fasterxml.jackson.annotation.JsonIgnoreProperties("scanLocationId")
	private abstract static class FileRecordExportMixIn {}

	@com.fasterxml.jackson.annotation.JsonIgnoreProperties("parentFolderId")
	private abstract static class FolderRecordExportMixIn {}

	private static final ObjectMapper EXPORT_MAPPER = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.addMixIn(FileRecord.class, FileRecordExportMixIn.class)
			.addMixIn(FolderRecord.class, FolderRecordExportMixIn.class)
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(SerializationFeature.INDENT_OUTPUT);

	private void export(String exportFile) {
		List<ScanLocation> scanLocations = scanLocationRepository.findAll();
		System.out.println("Exporting " + scanLocations.size() + " scan locations to " + exportFile);

		Path targetPath = AppUtils.normAbsPath(exportFile);
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

		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", location.getId());
		map.put("displayName", location.getDisplayName());
		map.put("rootPath", location.getRootPath());
		map.put("volName", location.getVolName());
		map.put("volType", location.getVolType());
		map.put("volSize", location.getVolSize());
		map.put("volVSN", location.getVolVSN());
		map.put("type", location.getType());
		if (location.hasRootFolderId()) {
			FolderRecord rootFolderRecord = folderRecordRepository.findById(location.getRootFolderId());
			map.put("rootFolder", rootFolderRecord == null ? null : toExportMap(rootFolderRecord));
		}
		return map;
	}

	private Map<String, Object> toExportMap(FolderRecord folder) {
		List<FileRecord> childFiles = fileRecordRepository.findByParentFolder(folder.getId());
		List<FolderRecord> childFolders = folderRecordRepository.findByParentFolder(folder.getId());

		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", folder.getId());
		map.put("name", folder.getName());
		map.put("childFiles", childFiles.stream().map(this::toExportMap).toList());
		map.put("childFolders", childFolders.stream().map(this::toExportMap).toList());
		return map;
	}

	private Map<String, Object> toExportMap(FileRecord file) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", file.getId());
		map.put("name", file.getName());
		map.put("sizeBytes", file.getSizeBytes());
		map.put("lastModifiedAt", file.getLastModifiedAt());
		map.put("SHA1", file.getSha1hash());
		return map;
	}


	public static void main(String[] args) {
		JSONExporter exporter = new JSONExporter();
		exporter.export("output/db-export.json");
	}
	
}