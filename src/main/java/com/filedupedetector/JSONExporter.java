package com.filedupedetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.persistence.DBManager;
import com.filedupedetector.utils.AppUtils;


public class JSONExporter {

	DBManager dbMgr;
            
	public JSONExporter() {
		this.dbMgr = new DBManager(false);
	}
	

	private static final ObjectMapper EXPORT_MAPPER = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(SerializationFeature.INDENT_OUTPUT);

	private void export(String exportFile) {
		List<ScanLocation> scanLocations = dbMgr.getScanLocationRepository().findAll();
		System.out.println("Exporting " + scanLocations.size() + " scan locations to " + exportFile);

		Path targetPath = AppUtils.normAbsPath(exportFile);
		try {
			Path parentDir = targetPath.getParent();
			if (parentDir != null) {
				Files.createDirectories(parentDir);
			}

			try (JsonGenerator gen = EXPORT_MAPPER.createGenerator(targetPath.toFile(), JsonEncoding.UTF8)) {
				gen.writeStartObject();
				gen.writeArrayFieldStart("scanLocations");
				for (ScanLocation location : scanLocations) {
					writeLocation(gen, location);
				}
				gen.writeEndArray();
				gen.writeEndObject();
			}
			System.out.println("Export complete: " + targetPath);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to export scan data to " + exportFile, e);
		}
	}

	private void writeField(JsonGenerator gen, String name, Object value) throws IOException {
		gen.writeFieldName(name);
		EXPORT_MAPPER.writeValue(gen, value);
	}

	private void writeLocation(JsonGenerator gen, ScanLocation location) throws IOException {
		gen.writeStartObject();
		writeField(gen, "id", location.getId());
		writeField(gen, "displayName", location.getDisplayName());
		writeField(gen, "rootPath", location.getRootPath());
		writeField(gen, "volName", location.getVolName());
		writeField(gen, "volType", location.getVolType());
		writeField(gen, "volSize", location.getVolSize());
		writeField(gen, "volVSN", location.getVolVSN());
		writeField(gen, "type", location.getType());
		if (location.hasRootFolderId()) {
			FolderRecord root = dbMgr.getFolderRecordRepository().findById(location.getRootFolderId());
			gen.writeFieldName("rootFolder");
			if (root == null) {
				gen.writeNull();
			} else {
				writeFolder(gen, root);
			}
		}
		gen.writeEndObject();
	}

	private void writeFolder(JsonGenerator gen, FolderRecord folder) throws IOException {
		gen.writeStartObject();
		writeField(gen, "id", folder.getId());
		writeField(gen, "name", folder.getName());

		gen.writeArrayFieldStart("childFiles");
		for (FileRecord file : dbMgr.getFileRecordRepository().findByParentFolder(folder.getId())) {
			writeFile(gen, file);
		}
		gen.writeEndArray();

		gen.writeArrayFieldStart("childFolders");
		for (FolderRecord child : dbMgr.getFolderRecordRepository().findByParentFolder(folder.getId())) {
			writeFolder(gen, child);
		}
		gen.writeEndArray();
		gen.writeEndObject();
	}

	private void writeFile(JsonGenerator gen, FileRecord file) throws IOException {
		gen.writeStartObject();
		writeField(gen, "id", file.getId());
		writeField(gen, "name", file.getName());
		writeField(gen, "sizeBytes", file.getSizeBytes());
		writeField(gen, "lastModifiedAt", file.getLastModifiedAt());
		writeField(gen, "SHA1", file.getSha1hash());
		gen.writeEndObject();
	}


	public static void main(String[] args) {
		JSONExporter exporter = new JSONExporter();
		exporter.export("output/db-export.json");
	}
	
}