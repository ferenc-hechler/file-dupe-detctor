package com.filedupedetector.model;

import java.util.Objects;

/**
 * Persisted information about a single scanned file (FR-005).
 */
public final class FolderRecord {

	private long id;
	private String name;
	private long parentFolderId;

	public FolderRecord() {
	}

	public FolderRecord(long id, long parentFolderId, String name) {
		this.id = id;
		this.name = Objects.requireNonNull(name, "name must not be null");
		this.parentFolderId = parentFolderId;
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public long getParentFolderId() {
		return parentFolderId;
	}

	public void setParentFolderId(long parentFolderId) {
		this.parentFolderId = parentFolderId;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof FolderRecord that))
			return false;
		return id == that.id;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	@Override
	public String toString() {
		return "FileRecord{id=%d, parentFolderId=%d, name='%s'}".formatted(id, parentFolderId, name);
	}
}