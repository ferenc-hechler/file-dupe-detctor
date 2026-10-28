package com.filedupedetector.persistence;

import com.filedupedetector.model.ScanLocation;

import java.util.List;

public interface ScanLocationRepository {

    ScanLocation findByDisplayName(String displayName);

    List<ScanLocation> findAll();

    ScanLocation save(ScanLocation location);

    void rename(Long id, String newDisplayName);

	void delete(Long id);

}
