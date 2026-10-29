package com.filedupedetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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


public class JSONFilterExporter {

	DBManager dbMgr;
	Set<String> filterExtensions;
	
            
	public JSONFilterExporter(List<String>... extensions) {
		this.dbMgr = new DBManager(false);
		this.filterExtensions = new HashSet<>();
		for (List<String> extList : extensions) {
			filterExtensions.addAll(extList);
		}
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
			if (filter(file)) {
				writeFile(gen, file);
			}
		}
		gen.writeEndArray();

		gen.writeArrayFieldStart("childFolders");
		for (FolderRecord child : dbMgr.getFolderRecordRepository().findByParentFolder(folder.getId())) {
			writeFolder(gen, child);
		}
		gen.writeEndArray();
		gen.writeEndObject();
	}

	public static List<String> IMAGE_EXTENSIONS = List.of(
		"jpg", "jpeg", "png", "gif",
		"bmp", "tiff", "webp", "svg",
		"heic", "heif", "raw", "cr2", 
		"nef", "orf", "arw", "dng"
	);

	public static List<String> VIDEO_EXTENSIONS = List.of(
		"mp4", "avi", "mov", "mkv",
		"wmv", "flv", "webm", "mpeg",
		"3gp", "m4v", "mts", "ts",
		"mpg", "vob", "rm", "rmvb"
	);

	public static List<String> AUDIO_EXTENSIONS = List.of(
		"mp3", "wav", "flac", "aac",
		"ogg", "wma", "alac", "aiff",
		"m4a", "opus", "amr", "mid"
	);
	
	public static List<String> DOCUMENT_EXTENSIONS = List.of(
		"pdf", "doc", "docx", "xls",
		"xlsx", "ppt", "pptx", "txt",
		"rtf", "odt", "ods", "odp",
		"epub", "mobi", "djvu"
	);
	
	public static List<String> ARCHIVE_EXTENSIONS = List.of(
		"zip", "rar", "7z", "tar",
		"gz", "bz2", "xz", "iso",
		"cab", "arj", "lzh"
	);
	
	public static List<String> CODE_EXTENSIONS = List.of(
		"java", "py", "js", "html",
		"css", "c", "cpp", "cs",
		"rb", "php", "go", "rs",
		"swift", "kt", "ts", "sh"
	);
	
	public static List<String> BINARY_EXTENSIONS = List.of(
		"exe", "dll", "bin", "iso",
		"img", "vhd", "vmdk", "ova",
		"apk", "ipa", "deb", "rpm"
	);
	
	
		

	
	private boolean filter(FileRecord file) {
		if (file == null) {
			return false;
		}
		String fileName = file.getName();
		int dotPos = fileName.lastIndexOf('.');
		if (dotPos > 0 && dotPos < fileName.length() - 1) {
			String ext = fileName.substring(dotPos + 1).toLowerCase();
			if (filterExtensions.contains(ext)) {
				return true;
			}
		}
		return false;
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
		JSONFilterExporter exporter = new JSONFilterExporter(IMAGE_EXTENSIONS, VIDEO_EXTENSIONS);
		exporter.export("output/db-filter-export.json");
	}
	
}