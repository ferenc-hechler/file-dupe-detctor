package com.filedupedetector.model;

import java.awt.Color;
import java.nio.file.Path;
import java.util.Objects;

import com.filedupedetector.utils.AppUtils;

/**
 * Persisted information about a folder
 */
public final class FolderRecord {

	private long id;
	private String name;
	private Long parentFolderId;
	
	private transient boolean isNew;
	private transient String fullPathstr;
	private transient Path fullPath;
    private transient boolean ROOT;

	public FolderRecord() {
	    this.isNew = false;
	    this.fullPathstr = null;
	    this.fullPath = null;
	}

	public FolderRecord(long id, Long parentFolderId, String name) {
		this.id = id;
		this.name = Objects.requireNonNull(name, "name must not be null");
		this.parentFolderId = parentFolderId;
		this.isNew = (id == -1);
		this.fullPathstr = null;
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
    
    public void setFullPathstr(String fullPathstr) {
        this.fullPathstr = fullPathstr;   
   }
    public void setFullPath(Path fullPath) {
        this.fullPath = fullPath;   
   }
   
    public String getFullPathstr() {
    	// Lazy initialization of fullPathstr from fullPath if needed
    	if (fullPathstr == null && fullPath != null) {
    		fullPathstr = AppUtils.normFilename(fullPath);
    	}
        return fullPathstr;
    }
    
    public Path getFullPath() {
    	// Lazy initialization of fullPath from fullPathstr if needed
    	if (fullPath == null && fullPathstr != null) {
    		fullPath = AppUtils.normPath(fullPathstr);
    	}
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
	
	public String disp() {
		return disp("", 60);
	}
	public String disp(String indent) {
		return disp(indent, 60);
	}
	public String disp(String indent, int nameWidth) {
		return indent+name;
	}

	public Color getColor() {
		if (name.toLowerCase().startsWith("f")) {
			return Color.GREEN;
		}
		if (name.toLowerCase().startsWith("p")) {
			return Color.RED;
		}
		return null;
	}

}