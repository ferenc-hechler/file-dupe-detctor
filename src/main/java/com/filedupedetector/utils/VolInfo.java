package com.filedupedetector.utils;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;


public class VolInfo {

	private String volName;
	private String volType;
	private long volSize;
	private String volVSN;

	public VolInfo(Path path) {
		retrieveVolumeInfo(path);
	}
	
	public VolInfo(String volName, String volType, long volSize, String volVSN) {
		this.volName = volName;
		this.volType = volType;
		this.volSize = volSize;
		this.volVSN = volVSN;
	}
	
	private static String formatVSN(int vsnInt) {
		String result = Integer.toHexString(vsnInt);
		while (result.length()<8) { result= "0"+result; }
		result= result.substring(0, 4)+"-"+result.substring(4);
		return result;
	}
	
	private String getVSN(FileStore store) {
		try {
			Object rawVsn = store.getAttribute("volume:vsn");
			if (rawVsn instanceof Number numberValue) {
				return formatVSN(numberValue.intValue());
			}
		} catch (IOException | UnsupportedOperationException e) {
			// Keep fallback marker when the platform does not expose a volume serial number.
		}
		return "?";
	}

	public void retrieveVolumeInfo(Path path) {
		volName = "-";
		volType = "?";
		volSize = -1;
		volVSN = "?";
		if (path == null) {
			return;
		}
		try {
			FileStore store = Files.getFileStore(path.toAbsolutePath());
			String storeName = store.name();
			String storeType = store.type();
			volName = (storeName == null || storeName.isBlank()) ? "-" : storeName;
			volType = (storeType == null || storeType.isBlank()) ? "?" : storeType;
			try {
				volSize = store.getTotalSpace();
			} catch (IOException e) {
				volSize = -1;
			}
			volVSN = getVSN(store);
		} catch (IOException | RuntimeException e) {
			// Keep fallback values for unsupported/unavailable paths.
		}
	}

	public String getVolName() {
		return volName;
	}

	public String getVolType() {
		return volType;
	}

	public long getVolSize() {
		return volSize;
	}

	public String getVolVSN() {
		return volVSN;
	}

}
