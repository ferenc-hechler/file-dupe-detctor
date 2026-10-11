package com.filedupedetector.service;

import com.filedupedetector.model.DuplicateGroup;
import com.filedupedetector.model.DuplicateGroup.DuplicateGroupKey;
import com.filedupedetector.model.FileRecord;

import java.util.List;

/** Read-only query access to computed duplicate groups (contracts/internal-module-contracts.md). */
public interface DuplicateQueryService {

    List<DuplicateGroup> getAllDuplicateGroups();

    List<FileRecord> getGroupMembers(DuplicateGroupKey key);
}
