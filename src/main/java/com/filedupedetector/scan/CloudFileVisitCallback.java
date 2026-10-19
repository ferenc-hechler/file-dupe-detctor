package com.filedupedetector.scan;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/** Callback invoked by a {@link FileWalker} for every path encountered during a walk. */
public interface CloudFileVisitCallback {

    void onFileFound(String absolutePath, long sizeBytes, FileTime lastModified);

    void onError(String absolutePath, IOException error);

    void onInaccessible(String absolutePath, InaccessibleReason reason);
}
