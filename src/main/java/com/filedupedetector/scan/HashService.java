package com.filedupedetector.scan;

import java.io.IOException;
import java.nio.file.Path;

/** Computes a content hash sufficient to confirm whether two files have identical content. */
public interface HashService {

    /** Computes a SHA-1 hex digest by streaming file content; never loads the whole file into memory. */
    String computeHash(Path absolutePath) throws IOException;
}
