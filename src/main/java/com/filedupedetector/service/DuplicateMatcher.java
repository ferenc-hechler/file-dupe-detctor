package com.filedupedetector.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Size-first duplicate pre-filter (FR-002/FR-003, research.md decision 4): groups candidate
 * files by exact size and returns only those sharing a size with at least one other file,
 * avoiding wasteful hashing of files with a unique size.
 */
public final class DuplicateMatcher {

    public record SizedPath(Path path, long sizeBytes) {
    }

    /** Returns only the subset of input paths whose size is shared by 2+ files. */
    public List<SizedPath> filterHashCandidates(List<SizedPath> allFiles) {
        Map<Long, List<SizedPath>> bySize = new HashMap<>();
        for (SizedPath file : allFiles) {
            bySize.computeIfAbsent(file.sizeBytes(), k -> new ArrayList<>()).add(file);
        }
        List<SizedPath> candidates = new ArrayList<>();
        for (List<SizedPath> group : bySize.values()) {
            if (group.size() > 1) {
                candidates.addAll(group);
            }
        }
        return candidates;
    }
}
