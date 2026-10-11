package com.filedupedetector.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;

/**
 * Walks a root path using {@code java.nio.file}, reporting per-file errors via the callback
 * instead of aborting the walk (FR-012, FR-014). Symbolic links are not followed.
 */
public final class NioFileWalker implements FileWalker {

    private static final Logger log = LoggerFactory.getLogger(NioFileWalker.class);

    @Override
    public void walk(Path rootPath, FileVisitCallback callback) {
        if (!Files.exists(rootPath)) {
            log.warn("Scan root path does not exist or is not accessible: {}", rootPath);
            callback.onError(rootPath, new IOException("Root path does not exist or is not accessible: " + rootPath));
            return;
        }
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        if (attrs.isSymbolicLink()) {
                            callback.onInaccessible(file, InaccessibleReason.SYMLINK_SKIPPED);
                            return FileVisitResult.CONTINUE;
                        }
                        if (isCloudPlaceholder(file)) {
                            callback.onInaccessible(file, InaccessibleReason.CLOUD_PLACEHOLDER);
                            return FileVisitResult.CONTINUE;
                        }
                        if (!attrs.isRegularFile()) {
                            callback.onInaccessible(file, InaccessibleReason.OTHER);
                            return FileVisitResult.CONTINUE;
                        }
                        callback.onFileFound(file, attrs.size(), attrs.lastModifiedTime());
                    } catch (RuntimeException e) {
                        callback.onError(file, new IOException("Unexpected error visiting file: " + file, e));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Permission errors, locked files, etc. - report and keep walking (FR-012, FR-014)
                    callback.onError(file, exc);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) {
                        callback.onError(dir, exc);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Thrown only for errors in the visitor contract itself; still report rather than propagate.
            callback.onError(rootPath, e);
        }
    }

    /**
     * Best-effort detection of cloud-sync "online-only"/placeholder files (e.g. OneDrive/Dropbox
     * reparse-point placeholders on Windows). On platforms without this attribute support, this
     * always returns false and such files are instead reported as read errors when hashing fails
     * (see research.md decision 6 / U1 follow-up).
     */
    private boolean isCloudPlaceholder(Path file) {
        try {
            DosFileAttributes dos = Files.readAttributes(file, DosFileAttributes.class);
            // Reparse-point-backed cloud placeholders typically report as "system" + "sparse"/offline-like.
            return dos.isSystem() && dos.size() == 0;
        } catch (UnsupportedOperationException | IOException notWindowsOrUnavailable) {
            return false;
        }
    }
}
