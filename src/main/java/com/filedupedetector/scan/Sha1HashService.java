package com.filedupedetector.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes SHA-1 hex digests by streaming file content in fixed-size buffered chunks,
 * never loading whole files into memory (FR-002, research.md decision 4).
 */
public final class Sha1HashService implements HashService {

    private static final String ALGORITHM = "SHA-1";
    private static final int BUFFER_SIZE = 8192;

    @Override
    public String computeHash(Path absolutePath) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " algorithm not available on this JVM", e);
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(absolutePath)) {
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
