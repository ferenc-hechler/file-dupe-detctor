package com.filedupedetector.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateMatcherTest {

    private final DuplicateMatcher matcher = new DuplicateMatcher();

    @Test
    void filesWithUniqueSizesAreExcludedFromCandidates() {
        var uniqueA = new DuplicateMatcher.SizedPath(Path.of("a.txt"), 100);
        var uniqueB = new DuplicateMatcher.SizedPath(Path.of("b.txt"), 200);

        List<DuplicateMatcher.SizedPath> candidates = matcher.filterHashCandidates(List.of(uniqueA, uniqueB));

        assertThat(candidates).isEmpty();
    }

    @Test
    void filesSharingASizeAreAllIncludedAsCandidates() {
        var sharedSize = 150L;
        var fileA = new DuplicateMatcher.SizedPath(Path.of("a.txt"), sharedSize);
        var fileB = new DuplicateMatcher.SizedPath(Path.of("b.txt"), sharedSize);
        var unique = new DuplicateMatcher.SizedPath(Path.of("c.txt"), 999);

        List<DuplicateMatcher.SizedPath> candidates = matcher.filterHashCandidates(List.of(fileA, fileB, unique));

        assertThat(candidates).containsExactlyInAnyOrder(fileA, fileB);
    }
}
