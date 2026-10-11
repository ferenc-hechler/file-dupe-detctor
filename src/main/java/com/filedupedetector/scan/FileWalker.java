package com.filedupedetector.scan;

import java.nio.file.Path;

/**
 * Walks a root path, invoking the callback for each regular file found.
 * Errors reading individual files/directories are reported via the callback and
 * MUST NOT abort the walk of remaining files (FR-012, FR-014).
 */
public interface FileWalker {

    void walk(Path rootPath, FileVisitCallback callback);
}
