package com.filedupedetector.utils;

import java.nio.file.Path;
import java.nio.file.Paths;

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

}
