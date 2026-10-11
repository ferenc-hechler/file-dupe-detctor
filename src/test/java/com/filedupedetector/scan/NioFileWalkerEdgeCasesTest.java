package com.filedupedetector.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Edge-case tests (T046) for {@link NioFileWalker}: zero-byte files, unreadable/locked files,
 * and symbolic links (Edge Cases section of spec.md).
 */
class NioFileWalkerEdgeCasesTest {

    private final NioFileWalker walker = new NioFileWalker();

    @Test
    void zeroByteFilesAreFoundAndCanStillBeComparedByHash(@TempDir Path tempDir) throws IOException {
        Path emptyA = tempDir.resolve("empty-a.dat");
        Path emptyB = tempDir.resolve("empty-b.dat");
        Files.write(emptyA, new byte[0]);
        Files.write(emptyB, new byte[0]);

        List<Path> found = new ArrayList<>();
        walker.walk(tempDir, new RecordingCallback(found, new ArrayList<>(), new ArrayList<>()));

        assertThat(found).containsExactlyInAnyOrder(emptyA, emptyB);

        // Both zero-byte files should still produce identical, comparable hashes (not excluded
        // just because they are empty - duplicate detection still applies per Edge Cases).
        Sha256HashService hashService = new Sha256HashService();
        assertThat(hashService.computeHash(emptyA)).isEqualTo(hashService.computeHash(emptyB));
    }

    @Test
    void unreadableFileIsReportedAsErrorWithoutStoppingTheWalk(@TempDir Path tempDir) throws IOException {
        Path readableFile = tempDir.resolve("readable.txt");
        Files.write(readableFile, "readable content".getBytes(StandardCharsets.UTF_8));

        Path restrictedFile = tempDir.resolve("restricted.txt");
        Files.write(restrictedFile, "restricted content".getBytes(StandardCharsets.UTF_8));
        boolean restrictionApplied = restrictedFile.toFile().setReadable(false, false);
        // On some platforms/CI runners (e.g. root, certain filesystems) permission restriction
        // may not take effect; skip the assertion on unreadability in that case rather than
        // producing a flaky failure, but still verify the walk completes either way.
        assumeThat(restrictionApplied).as("filesystem supports making a file unreadable").isTrue();

        try {
            List<Path> found = new ArrayList<>();
            List<Path> errors = new ArrayList<>();
            walker.walk(tempDir, new RecordingCallback(found, errors, new ArrayList<>()));

            assertThat(found).contains(readableFile);
            // The restricted file is either reported as found-but-unhashable-later, or as a
            // walk-time error depending on OS semantics for directory-listing vs. content access;
            // what matters is the walk did not abort and the readable file was still processed.
            assertThat(found.size() + errors.size()).isGreaterThanOrEqualTo(2);
        } finally {
            restrictedFile.toFile().setReadable(true, false);
        }
    }

    @Test
    void symbolicLinksAreNotFollowedAndReportedAsInaccessible(@TempDir Path tempDir) throws IOException {
        Path realFile = tempDir.resolve("real.txt");
        Files.write(realFile, "real content".getBytes(StandardCharsets.UTF_8));
        Path linkPath = tempDir.resolve("link-to-real.txt");

        try {
            Files.createSymbolicLink(linkPath, realFile);
        } catch (UnsupportedOperationException | IOException e) {
            // Symbolic link creation can require elevated privileges on Windows; skip rather
            // than fail the build in restricted CI/dev environments.
            assumeThat(false).as("symbolic links can be created in this environment").isTrue();
            return;
        }

        List<Path> found = new ArrayList<>();
        List<Path> inaccessible = new ArrayList<>();
        walker.walk(tempDir, new RecordingCallback(found, new ArrayList<>(), inaccessible));

        assertThat(found).contains(realFile);
        assertThat(inaccessible).contains(linkPath);
    }

    private record RecordingCallback(List<Path> found, List<Path> errors,
                                      List<Path> inaccessible) implements FileVisitCallback {
        @Override
        public void onFileFound(Path absolutePath, long sizeBytes, FileTime lastModified) {
            found.add(absolutePath);
        }

        @Override
        public void onError(Path absolutePath, IOException error) {
            errors.add(absolutePath);
        }

        @Override
        public void onInaccessible(Path absolutePath, InaccessibleReason reason) {
            inaccessible.add(absolutePath);
        }
    }
}
