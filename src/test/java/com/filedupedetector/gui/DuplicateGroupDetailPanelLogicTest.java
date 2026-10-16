package com.filedupedetector.gui;

import com.filedupedetector.model.DuplicateGroup;
import com.filedupedetector.model.DuplicateGroup.DuplicateGroupKey;
import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.service.DuplicateQueryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless test (T043) for the selection-to-members mapping logic extracted from
 * {@link DuplicateGroupDetailPanel}, independent of actual Swing rendering.
 */
class DuplicateGroupDetailPanelLogicTest {

    @Test
    void returnsAllGroupMembersWithCorrectFullPaths() {
        ScanLocation locationA = newLocation(1L, "Drive A", "C:\\data\\a");
        ScanLocation locationB = newLocation(2L, "Drive B", "D:\\backup\\b");
        Map<Long, ScanLocation> locationsById = new LinkedHashMap<>();
        locationsById.put(locationA.getId(), locationA);
        locationsById.put(locationB.getId(), locationB);

        FileRecord selected = newRecord(locationA.getId(), "photo1.jpg", 100, "hash123");
        FileRecord other = newRecord(locationB.getId(), "photo-copy.jpg", 100, "hash123");

        DuplicateQueryService stubService = new StubDuplicateQueryService(
                new DuplicateGroupKey(100, "hash123"), List.of(selected, other));

        List<String> descriptions = DuplicateGroupDetailPanel
                .resolveMemberDescriptions(selected, stubService, locationsById);

        assertThat(descriptions).hasSize(2);
        assertThat(descriptions.get(0)).contains("photo1.jpg").contains("Drive A");
        assertThat(descriptions.get(1)).contains("photo-copy.jpg").contains("Drive B");
    }

    @Test
    void returnsEmptyListWhenFileIsNotPartOfADuplicateGroup() {
        ScanLocation location = newLocation(1L, "Drive A", "C:\\data\\a");
        FileRecord unique = newRecord(location.getId(), "unique.txt", 42, "uniquehash");

        DuplicateQueryService stubService = new StubDuplicateQueryService(
                new DuplicateGroupKey(42, "uniquehash"), List.of(unique)); // only 1 member -> not a group

        List<String> descriptions = DuplicateGroupDetailPanel
                .resolveMemberDescriptions(unique, stubService, Map.of(location.getId(), location));

        assertThat(descriptions).isEmpty();
    }

    @Test
    void returnsEmptyListWhenRecordHasNoHash() {
        FileRecord noHash = newRecord(1L, "pending.txt", 5, null);

        List<String> descriptions = DuplicateGroupDetailPanel
                .resolveMemberDescriptions(noHash, new StubDuplicateQueryService(null, List.of()), Map.of());

        assertThat(descriptions).isEmpty();
    }

    private ScanLocation newLocation(long id, String name, String path) {
        ScanLocation location = new ScanLocation();
        location.setId(id);
        location.setDisplayName(name);
        location.setRootPathWithVolInfo(path);
        location.setType(LocationType.LOCAL);
        return location;
    }

    private FileRecord newRecord(long locationId, String relativePath, long size, String hash) {
        FileRecord record = new FileRecord();
        record.setScanLocationId(locationId);
        record.setRelativePath(relativePath);
        record.setSizeBytes(size);
        record.setContentHash(hash);
        record.setLastModifiedAt(Instant.now());
        record.setLastSeenAt(Instant.now());
        return record;
    }

    /** Minimal stub returning a fixed member list for a single expected key. */
    private record StubDuplicateQueryService(DuplicateGroupKey expectedKey,
                                              List<FileRecord> members) implements DuplicateQueryService {
        @Override
        public List<DuplicateGroup> getAllDuplicateGroups() {
            if (members.size() < 2) {
                return List.of();
            }
            return List.of(new DuplicateGroup(expectedKey.sizeBytes(), expectedKey.contentHash(), members));
        }

        @Override
        public List<FileRecord> getGroupMembers(DuplicateGroupKey key) {
            if (expectedKey != null && expectedKey.equals(key) && members.size() >= 2) {
                return members;
            }
            return List.of();
        }
    }
}