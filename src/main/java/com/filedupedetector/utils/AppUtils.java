package com.filedupedetector.utils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class AppUtils {

	public static Path normPath(String pathStr) {
		return Paths.get(pathStr).normalize();
	}
	
	public static Path normPath(Path path) {
		return path.normalize();
	}
	
	public static String normFilename(String pathStr) {
		return Paths.get(pathStr).normalize().toString().replace('\\', '/');
	}
	
	public static String normFilename(Path path) {
		return path.normalize().toString().replace('\\', '/');
	}

	public static Path normAbsPath(String pathStr) {
		return Paths.get(pathStr).toAbsolutePath().normalize();
	}
	
	public static Path normAbsPath(Path path) {
		return path.toAbsolutePath().normalize();
	}
	
	public static String normAbsFilename(String pathStr) {
		return Paths.get(pathStr).toAbsolutePath().normalize().toString().replace('\\', '/');
	}
	
	public static String normAbsFilename(Path path) {
		return path.toAbsolutePath().normalize().toString().replace('\\', '/');
	}

	
	public static String readable(long sizeBytes) {
		if (sizeBytes < 1024) {
			return sizeBytes + " B";
		} else if (sizeBytes < 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f KB", sizeBytes / 1024.0);
		} else if (sizeBytes < 1024 * 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f MB", sizeBytes / (1024.0 * 1024.0));
		} else {
			return String.format(Locale.ROOT, "%.1f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0));
		}
	}

	private static DateTimeFormatter DATEFORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
	public static String readable(Instant instant) {
		if (instant == null) {
			return "-";
		}
		String formattedDate = DATEFORMAT.format(instant.atZone(ZoneId.systemDefault()));
		return formattedDate;
	}

	public static String readable(String sha1hash) {
		return (sha1hash == null) ? "-" : sha1hash.substring(0, 8);
	}
	
	static String SPACES = "                                        ";
	
	public static String spaces(int length) {
		if (length <= 0) {
			return "";
		}
		while (SPACES.length() < length) {
			SPACES = SPACES + SPACES;
		}
		return SPACES.substring(0, length);
	}

	public static String padr(String str, int length) {
		return str + spaces(length - str.length());
	}

	public static String padl(String str, int length) {
		return spaces(length - str.length()) + str;
	}
	
}
