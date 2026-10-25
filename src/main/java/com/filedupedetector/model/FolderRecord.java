package com.filedupedetector.model;

import static com.filedupedetector.utils.AppUtils.padl;
import static com.filedupedetector.utils.AppUtils.padr;
import static com.filedupedetector.utils.AppUtils.readable;

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
    private transient boolean ROOT;

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
		this.ROOT = false;
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
    
    public boolean isROOT() {
        return ROOT;
    }

    public void setROOT(boolean ROOT) {
        this.ROOT = ROOT;
    }

    public Long getIdOrNull() {
        if (isROOT()) {
            return null;
        }
        return id;
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
		return "FolderRecord{id=%d, parentFolderId=%d, name='%s'}".formatted(getIdOrNull(), parentFolderId, name);
	}

	public String disp(String indent) {
		return disp(indent, 60);
	}
	public String disp(String indent, int nameWidth) {
		return indent+name;
	}


}