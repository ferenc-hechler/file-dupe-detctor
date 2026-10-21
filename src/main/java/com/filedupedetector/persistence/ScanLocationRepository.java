package com.filedupedetector.persistence;

import com.filedupedetector.model.ScanLocation;

import java.util.List;

public interface ScanLocationRepository {

    List<ScanLocation> findAll();

    ScanLocation save(ScanLocation location);

}
