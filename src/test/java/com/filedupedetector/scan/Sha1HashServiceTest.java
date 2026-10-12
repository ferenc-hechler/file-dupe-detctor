package com.filedupedetector.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Sha1HashServiceTest {

    private final Sha1HashService hashService = new Sha1HashService();

    @Test
    void identicalContentDifferentNamesProduceIdenticalHashes(@TempDir Path tempDir) throws IOException {
        Path file1 = tempDir.resolve("photo1.jpg");
        Path file2 = tempDir.resolve("photo-copy.jpg");
        byte[] content = "identical content for duplicate test".getBytes(StandardCharsets.UTF_8);
        Files.write(file1, content);
        Files.write(file2, content);

        String hash1 = hashService.computeHash(file1);
        String hash2 = hashService.computeHash(file2);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(40); // SHA-1 hex digest length
    }

    @Test
    void filesDifferingBySingleByteProduceDifferentHashes(@TempDir Path tempDir) throws IOException {
        Path file1 = tempDir.resolve("a.bin");
        Path file2 = tempDir.resolve("b.bin");
        Files.write(file1, "content-A".getBytes(StandardCharsets.UTF_8));
        Files.write(file2, "content-B".getBytes(StandardCharsets.UTF_8));

        String hash1 = hashService.computeHash(file1);
        String hash2 = hashService.computeHash(file2);

        assertThat(hash1).isNotEqualTo(hash2);
    }
}
