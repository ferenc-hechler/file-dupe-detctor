package com.filedupedetector.model;

import java.util.Objects;

/**
 * Persisted information about a folder
 */
public final class FolderRecord {

	private long id;
	private String name;
	private Long parentFolderId;
	
	private transient boolean isNew;
	private transient String fullPath;

	public FolderRecord() {
	    this.isNew = false;
	    this.fullPath = null;
	}

	public FolderRecord(long id, Long parentFolderId, String name) {
		this.id = id;
		this.name = Objects.requireNonNull(name, "name must not be null");
		this.parentFolderId = parentFolderId;
		this.isNew = (id == -1);
		this.fullPath = null;
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public Long getParentFolderId() {
		return parentFolderId;
	}

	public void setParentFolderId(Long parentFolderId) {
		this.parentFolderId = parentFolderId;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
    public void setNew(boolean isNew) {
        this.isNew = isNew;
    }

    public boolean isNew() {
        return isNew;
    }
    
    public void setFullPath(String fulltPath) {
         this.fullPath = fulltPath;   
    }
    
    public String getFullPath() {
        return fullPath;
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
		return "FolderRecord{id=%d, parentFolderId=%d, name='%s'}".formatted(id, parentFolderId, name);
	}


}