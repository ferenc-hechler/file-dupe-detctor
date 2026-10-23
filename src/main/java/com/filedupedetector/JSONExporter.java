package com.filedupedetector;

import java.io.IOException;
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
import com.filedupedetector.persistence.FileRecordRepository;
import com.filedupedetector.persistence.FolderRecordRepository;
import com.filedupedetector.persistence.JdbcConnectionManager;
import com.filedupedetector.persistence.JdbcFileRecordRepository;
import com.filedupedetector.persistence.JdbcFolderRecordRepository;
import com.filedupedetector.persistence.JdbcScanLocationRepository;
import com.filedupedetector.persistence.ScanLocationRepository;


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
//				record.setScanLocationId(target.getId());
//				record.setRelativePath(requireText(recordNode, "relativePath"));
				record.setSizeBytes(recordNode.path("sizeBytes").asLong());
//				record.setContentHash(recordNode.hasNonNull("contentHash") ? recordNode.get("contentHash").asText() : null);
				record.setLastModifiedAt(java.time.Instant.parse(requireText(recordNode, "lastModifiedAt")));
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
//		location.setRootPathX(requireText(node, "rootPath"));
		location.setVolName(requireText(node, "volName"));
		location.setVolType(requireText(node, "volType"));
		location.setVolSize(node.path("volSize").asLong(-1));
		location.setVolVSN(requireText(node, "volVSN"));
		location.setType(LocationType.valueOf(requireText(node, "type")));
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
		List<FileRecord> fileRecords = fileRecordRepository.findByParentFolder(location.getRootFolderId());

		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", location.getId());
		map.put("displayName", location.getDisplayName());
		map.put("rootPath", location.getRootPath());
		map.put("volName", location.getVolName());
		map.put("volType", location.getVolType());
		map.put("volSize", location.getVolSize());
		map.put("volVSN", location.getVolVSN());
		map.put("type", location.getType());
		map.put("fileRecords", fileRecords);
		return map;
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

	public static void main(String[] args) {
		JSONExporter exporter = new JSONExporter();
		exporter.export("output/db-export.json");
	}
	
}