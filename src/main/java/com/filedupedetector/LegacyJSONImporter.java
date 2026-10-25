package com.filedupedetector;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * Imports the legacy export format (see output/export-specs.json). In that
 * format a scan location has a flat list of "fileRecords" and the folder
 * hierarchy is only implicit in each record's "relativePath". The folders are
 * recreated while importing, so the result is identical to an import of the
 * current format done by {@link JSONImporter}. IDs in the JSON are ignored.
 */
public class LegacyJSONImporter {

	private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

	private final ScanLocationRepository scanLocationRepository;
	private final FileRecordRepository fileRecordRepository;
	private final FolderRecordRepository folderRecordRepository;

	private int fileCount;
	private int locationCount;

	public LegacyJSONImporter() {
		JdbcConnectionManager connectionManager = new JdbcConnectionManager(true);
		this.scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
		this.fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
		this.folderRecordRepository = new JdbcFolderRecordRepository(connectionManager);
	}

	/**
	 * Streams the legacy JSON file; file records are read one at a time.
	 */
	public void importFile(String importFile) {
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
		Map<String, FolderRecord> folderCache = new HashMap<>();
		while (p.nextToken() == JsonToken.FIELD_NAME) {
			String field = p.currentName();
			JsonToken token = p.nextToken();
			if ("fileRecords".equals(field) && token == JsonToken.START_ARRAY) {
				if (target == null) {
					target = resolveLocation(values);
				}
				FolderRecord root = resolveRootFolder(target);
				folderCache.put("", root);
				while (p.nextToken() == JsonToken.START_OBJECT) {
					importFileRecord(MAPPER.readTree(p), root, folderCache);
				}
			} else if (token.isScalarValue()) {
				values.put(field, p.getValueAsString());
			} else {
				p.skipChildren();
			}
		}
		if (target == null) {
			target = resolveLocation(values);
			resolveRootFolder(target);
		}
	}

	private ScanLocation resolveLocation(Map<String, String> values) {
		String displayName = requireText(values, "displayName");
		locationCount++;
		ScanLocation existing = scanLocationRepository.findByDisplayName(displayName);
		if (existing != null) {
			return existing;
		}
		ScanLocation location = new ScanLocation();
		location.setDisplayName(displayName);
		location.setRootPath(requireText(values, "rootPath"));
		location.setType(LocationType.valueOf(values.getOrDefault("type", "LOCAL")));
		return scanLocationRepository.save(location);
	}

	private FolderRecord resolveRootFolder(ScanLocation target) {
		if (target.getRootFolderId() != null && target.getRootFolderId() > 0) {
			FolderRecord root = folderRecordRepository.findById(target.getRootFolderId());
			if (root != null) {
				return root;
			}
		}
		FolderRecord root = findOrCreateFolder(null, target.getRootPath().replace('\\', '/'));
		target.setRootFolderId(root.getId());
		scanLocationRepository.save(target);
		return root;
	}

	private void importFileRecord(JsonNode node, FolderRecord root, Map<String, FolderRecord> folderCache) {
		String relativePath = requireText(node, "relativePath").replace('\\', '/');
		int idx = relativePath.lastIndexOf('/');
		String dir = idx < 0 ? "" : relativePath.substring(0, idx);
		String name = relativePath.substring(idx + 1);
		FolderRecord folder = resolveFolder(dir, root, folderCache);

		FileRecord record = new FileRecord();
		record.setParentFolderId(folder.getId());
		record.setName(name);
		record.setSizeBytes(node.path("sizeBytes").asLong(0));
		JsonNode hash = node.get("contentHash");
		record.setSha1hash(hash == null || hash.isNull() ? null : hash.asText());
		record.setLastModifiedAt(Instant.parse(requireText(node, "lastModifiedAt")));
		fileRecordRepository.upsert(record);
		fileCount++;
	}

	private FolderRecord resolveFolder(String dir, FolderRecord root, Map<String, FolderRecord> folderCache) {
		FolderRecord cached = folderCache.get(dir);
		if (cached != null) {
			return cached;
		}
		int idx = dir.lastIndexOf('/');
		FolderRecord parent = resolveFolder(idx < 0 ? "" : dir.substring(0, idx), root, folderCache);
		FolderRecord folder = findOrCreateFolder(parent.getId(), dir.substring(idx + 1));
		folderCache.put(dir, folder);
		return folder;
	}

	private FolderRecord findOrCreateFolder(Long parentId, String name) {
		FolderRecord folder = folderRecordRepository.findByParentFolderAndName(parentId, name);
		if (folder == null) {
			folder = folderRecordRepository.upsert(new FolderRecord(-1, parentId, name));
		}
		return folder;
	}

	private static String requireText(Map<String, String> values, String field) {
		String value = values.get(field);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Invalid import file: missing field '" + field + "' in " + values);
		}
		return value;
	}

	private static String requireText(JsonNode node, String field) {
		String value = node.path(field).asText(null);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Invalid import file: missing field '" + field + "' in " + node);
		}
		return value;
	}

	public static void main(String[] args) {
		LegacyJSONImporter importer = new LegacyJSONImporter();
		importer.importFile(args.length > 0 ? args[0] : "output/export-specs.json");
	}

}
