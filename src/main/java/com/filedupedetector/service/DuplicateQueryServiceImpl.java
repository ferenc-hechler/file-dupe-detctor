package com.filedupedetector.service;

import com.filedupedetector.model.DuplicateGroup;
import com.filedupedetector.model.DuplicateGroup.DuplicateGroupKey;
import com.filedupedetector.model.FileRecord;
import com.filedupedetector.persistence.FileRecordRepository;

import java.util.List;

public final class DuplicateQueryServiceImpl implements DuplicateQueryService {

    private final FileRecordRepository fileRecordRepository;

    public DuplicateQueryServiceImpl(FileRecordRepository fileRecordRepository) {
        this.fileRecordRepository = fileRecordRepository;
    }

    @Override
    public List<DuplicateGroup> getAllDuplicateGroups() {
        return fileRecordRepository.findDuplicateGroups();
    }

    @Override
    public List<FileRecord> getGroupMembers(DuplicateGroupKey key) {
        return getAllDuplicateGroups().stream()
                .filter(g -> g.key().equals(key))
                .findFirst()
                .map(DuplicateGroup::getMembers)
                .orElseGet(List::of);
    }
}
