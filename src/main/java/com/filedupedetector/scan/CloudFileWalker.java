package com.filedupedetector.scan;

/**
 * Walks a root path, invoking the callback for each regular file found.
 * Errors reading individual files/directories are reported via the callback and
 * MUST NOT abort the walk of remaining files (FR-012, FR-014).
 */
public interface CloudFileWalker {

    void walk(String rootPath, CloudFileVisitCallback callback);
}
