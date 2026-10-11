package com.filedupedetector.scan;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/** Callback invoked by a {@link FileWalker} for every path encountered during a walk. */
public interface FileVisitCallback {

    void onFileFound(Path absolutePath, long sizeBytes, FileTime lastModified);

    void onError(Path absolutePath, IOException error);

    void onInaccessible(Path absolutePath, InaccessibleReason reason);
}
