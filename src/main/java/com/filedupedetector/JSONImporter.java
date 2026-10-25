package com.filedupedetector;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.persistence.FileRecordRepository;
import com.filedupedetector.persistence.FolderRecordRepository;
import com.filedupedetector.persistence.JdbcConnectionManager;
import com.filedupedetector.persistence.JdbcFileRecordRepository;
import com.filedupedetector.persistence.JdbcFolderRecordRepository;
import com.filedupedetector.persistence.JdbcScanLocationRepository;
import com.filedupedetector.persistence.ScanLocationRepository;
import com.filedupedetector.utils.AppUtils;


public class JSONImporter {

	ScanLocationRepository scanLocationRepository;
	FileRecordRepository fileRecordRepository;
	FolderRecordRepository folderRecordRepository;

	private int fileCount;
	private int locationCount;

	public JSONImporter() {
		JdbcConnectionManager connectionManager = new JdbcConnectionManager(false);
		this.scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
		this.fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
		this.folderRecordRepository = new JdbcFolderRecordRepository(connectionManager);
	}

	private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

	/**
	 * Imports scan locations, folders and file records from a JSON file created with the export action.
	 * The file is read as a stream, so only the current path of the folder hierarchy is held in memory.
	 * Per location, the scalar fields are read first (the exporter writes them before "rootFolder") and
	 * validated against an existing location with the same display name before anything is written
	 * for that location. IDs in the JSON are ignored.
	 */
	private void importFile(String importFile) {
		Path source = AppUtils.normAbsPath(importFile);
		fileCount = 0;
		locationCount = 0;
		try (JsonParser p = MAPPER.getFactory().createParser(source.toFile())) {
			if (p.nextToken() != JsonToken.START_OBJECT) {
				throw new IllegalStateException("Invalid import file " + source + ": JSON object expected");
			}
			boolean found = false;
			while (p.nextToken() == JsonToken.FIELD_NAME) {
				String field = p.currentName();
				p.nextToken();
				if ("scanLocations".equals(field) && p.currentToken() == JsonToken.START_ARRAY) {
					found = true;
					while (p.nextToken() == JsonToken.START_OBJECT) {
						importLocation(p);
					}
				} else {
					p.skipChildren();
				}
			}
			if (!found) {
				throw new IllegalStateException("Invalid import file " + source + ": missing 'scanLocations' array");
			}
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read import file " + source, e);
		}
		System.out.println("Imported " + locationCount + " scan locations and " + fileCount
				+ " file records from " + source);
	}

	private void importLocation(JsonParser p) throws IOException {
		Map<String, String> values = new HashMap<>();
		ScanLocation target = null;
		while (p.nextToken() == JsonToken.FIELD_NAME) {
			String field = p.currentName();
			JsonToken token = p.nextToken();
			if ("rootFolder".equals(field)) {
				if (target == null) {
					target = resolveLocation(values);
				}
				if (token == JsonToken.START_OBJECT) {
					importRootFolder(p, target);
				}
			} else if (token.isScalarValue()) {
				values.put(field, p.getValueAsString());
			} else {
				p.skipChildren();
			}
		}
		if (target == null) {
			resolveLocation(values);
		}
	}

	private ScanLocation resolveLocation(Map<String, String> values) {
		ScanLocation imported = toScanLocation(values);
		ScanLocation existing = scanLocationRepository.findByDisplayName(imported.getDisplayName());
		locationCount++;
		if (existing != null) {
			verifySameLocation(existing, imported);
			return existing;
		}
		return scanLocationRepository.save(imported);
	}

	private void importRootFolder(JsonParser p, ScanLocation target) throws IOException {
		FolderRecord preset = null;
		if (target.getRootFolderId() != null && target.getRootFolderId() > 0) {
			preset = folderRecordRepository.findById(target.getRootFolderId());
		}
		importFolder(p, null, preset, root -> {
			if (target.getRootFolderId() == null || target.getRootFolderId() != root.getId()) {
				target.setRootFolderId(root.getId());
				scanLocationRepository.save(target);
			}
		});
	}

	/**
	 * Streams one folder object (parser is positioned on its START_OBJECT). The folder is created or
	 * looked up as soon as its "name" is read; the exporter writes it before the child lists.
	 */
	private void importFolder(JsonParser p, Long parentId, FolderRecord preset, Consumer<FolderRecord> onResolved)
			throws IOException {
		FolderRecord folder = preset;
		if (folder != null) {
			onResolved.accept(folder);
		}
		while (p.nextToken() == JsonToken.FIELD_NAME) {
			String field = p.currentName();
			JsonToken token = p.nextToken();
			switch (field) {
			case "name" -> {
				if (folder == null) {
					folder = findOrCreateFolder(parentId, p.getValueAsString());
					onResolved.accept(folder);
				}
			}
			case "childFiles" -> {
				requireFolder(folder, field);
				if (token == JsonToken.START_ARRAY) {
					while (p.nextToken() == JsonToken.START_OBJECT) {
						importFileRecord(p, folder);
					}
				} else {
					p.skipChildren();
				}
			}
			case "childFolders" -> {
				requireFolder(folder, field);
				if (token == JsonToken.START_ARRAY) {
					while (p.nextToken() == JsonToken.START_OBJECT) {
						importFolder(p, folder.getId(), null, f -> {
						});
					}
				} else {
					p.skipChildren();
				}
			}
			default -> p.skipChildren();
			}
		}
	}

	private static void requireFolder(FolderRecord folder, String field) {
		if (folder == null) {
			throw new IllegalStateException("Invalid import file: '" + field + "' found before folder 'name'");
		}
	}

	private void importFileRecord(JsonParser p, FolderRecord folder) throws IOException {
		Map<String, String> values = new HashMap<>();
		while (p.nextToken() == JsonToken.FIELD_NAME) {
			String field = p.currentName();
			if (p.nextToken().isScalarValue()) {
				values.put(field, p.getValueAsString());
			} else {
				p.skipChildren();
			}
		}
		FileRecord record = new FileRecord();
		record.setParentFolderId(folder.getId());
		record.setName(requireText(values, "name"));
		record.setSizeBytes(Long.parseLong(values.getOrDefault("sizeBytes", "0")));
		record.setSha1hash(values.get("SHA1"));
		record.setLastModifiedAt(Instant.parse(requireText(values, "lastModifiedAt")));
		fileRecordRepository.upsert(record);
		fileCount++;
	}

	private FolderRecord findOrCreateFolder(Long parentId, String name) {
		FolderRecord folder = folderRecordRepository.findByParentFolderAndName(parentId, name);
		if (folder == null) {
			folder = folderRecordRepository.upsert(new FolderRecord(-1, parentId, name));
		}
		return folder;
	}

	private static ScanLocation toScanLocation(Map<String, String> values) {
		ScanLocation location = new ScanLocation();
		location.setDisplayName(requireText(values, "displayName"));
		location.setRootPath(requireText(values, "rootPath"));
		location.setVolName(requireText(values, "volName"));
		location.setVolType(requireText(values, "volType"));
		location.setVolSize(Long.parseLong(values.getOrDefault("volSize", "-1")));
		location.setVolVSN(requireText(values, "volVSN"));
		location.setType(LocationType.valueOf(requireText(values, "type")));
		return location;
	}

	private static String requireText(Map<String, String> values, String field) {
		String value = values.get(field);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Invalid import file: missing field '" + field + "' in " + values);
		}
		return value;
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
		JSONImporter importer = new JSONImporter();
		importer.importFile("output/db-export.json");
	}

}