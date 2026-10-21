package com.filedupedetector.model;

import java.nio.file.Path;
import java.util.Objects;

import com.filedupedetector.pcloud.PCloudApiClient;
import com.filedupedetector.utils.VolInfo;

/**
 * Represents a root path (local drive, external volume, or cloud storage
 * folder) designated by the user for scanning.
 */
public final class ScanLocation {

	public enum LocationType {
		LOCAL, REMOVABLE, PCLOUD
	}

	private long id;
	private String displayName;
	private String rootPath;
	private String volName;
	private String volType;
	private long volSize;
	private String volVSN;
	private LocationType type;
	private long rootFolderId;

	public ScanLocation() {
		this.volName = "?";
		this.volType = "?";
		this.volSize = -1;
		this.volVSN = "?";
	}

	public ScanLocation(long id, String displayName, String rootPath, LocationType type) {
		this.id = id;
		this.displayName = Objects.requireNonNull(displayName, "displayName must not be null");
		setRootPathWithVolInfo(rootPath);
		this.type = Objects.requireNonNull(type, "type must not be null");
	}

	private static String requireNonBlank(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must be non-blank");
		}
		return value;
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getRootPath() {
		return rootPath;
	}

	public void setRootPath(String rootPath) {
		this.rootPath = requireNonBlank(rootPath, "rootPath");
	}

	public void setRootPathWithVolInfo(String rootPath) {
		this.rootPath = requireNonBlank(rootPath, "rootPath");
		populateVolumeInfo(this.rootPath);
	}

	public void setRootPathWithPCloudInfo(String rootPath) {
		this.rootPath = requireNonBlank(rootPath, "rootPath");
		populatePCloudVolumeInfo(this.rootPath);
	}

	private void populateVolumeInfo(String rootPathValue) {
		try {
			VolInfo info = new VolInfo(Path.of(rootPathValue));
			this.volName = PCloudApiClient.getPCloudEnv();
			this.volType = info.getVolType();
			this.volSize = info.getVolSize();
			this.volVSN = info.getVolVSN();
		} catch (RuntimeException e) {
			this.volName = "?";
			this.volType = "?";
			this.volSize = -1;
			this.volVSN = "?";
		}
	}

	private void populatePCloudVolumeInfo(String rootPathValue) {
		try {
			this.volName = PCloudApiClient.getPCloudEnv();
			if (this.volName == null || this.volName.isBlank()) {
				this.volName = PCloudApiClient.getUserInfo().email();
			}
			this.volType = PCloudApiClient.getAPIHost().replace(".pcloud.com", "");
			this.volSize = PCloudApiClient.getUserInfo().totalQuota();
			this.volVSN = Long.toHexString(PCloudApiClient.getUserInfo().userId());
		} catch (RuntimeException e) {
			this.volName = "pcloud?";
			this.volType = "pcloud?";
			this.volSize = -2;
			this.volVSN = "pcloud?";
		}
	}

	public String getVolName() {
		return volName;
	}

	public void setVolName(String volName) {
		this.volName = volName;
	}

	public String getVolType() {
		return volType;
	}

	public void setVolType(String volType) {
		this.volType = volType;
	}

	public long getVolSize() {
		return volSize;
	}

	public void setVolSize(long volSize) {
		this.volSize = volSize;
	}

	public String getVolVSN() {
		return volVSN;
	}

	public void setVolVSN(String volVSN) {
		this.volVSN = volVSN;
	}

	public LocationType getType() {
		return type;
	}

	public void setType(LocationType type) {
		this.type = type;
	}

	public long getRootFolderId() {
		return rootFolderId;
	}

	public void setRootFolderId(long rootFolderId) {
		this.rootFolderId = rootFolderId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof ScanLocation that))
			return false;
		return id == that.id;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	@Override
	public String toString() {
		return "ScanLocation{id=%d, displayName='%s', rootPath='%s', volName='%s', volType='%s', volSize=%d, volVSN='%s', type=%s}"
				.formatted(id, displayName, rootPath, volName, volType, volSize, volVSN, type);
	}
}
