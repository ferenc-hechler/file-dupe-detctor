package com.filedupedetector.persistence;

import com.filedupedetector.model.ScanLocation;

import java.util.List;

public interface ScanLocationRepository {

    ScanLocation findByDisplayName(String displayName);

    List<ScanLocation> findAll();

    ScanLocation save(ScanLocation location);

	void delete(Long id);

}
