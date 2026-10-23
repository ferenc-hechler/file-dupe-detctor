package com.filedupedetector.scan;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;

/**
 * Visitor invoked once per folder by {@link FolderWalker}.
 */
public interface FolderVisitor {

    /** A single regular file inside a folder. */
    record FileEntry(Path path, long size, FileTime lastModified) {
    }

    void preVisitDirectory(Path folder, BasicFileAttributes attrs);

    /**
     * Called once per folder (depth-first order).
     *
     * @param folder     the visited folder
     * @param files      regular files directly contained in the folder (no subfolder content)
     * @param subFolders direct sub folders that will be visited later
     */
    void visitFolder(Path folder, List<FileEntry> files, List<Path> subFolders);
    
    void postVisitDirectory(Path folder, IOException exc);


    /** Called for entries or folders that are skipped (symlink, special file, ...). */
    default void onInaccessible(Path path, InaccessibleReason reason) {
    }

    /** Called when a folder or entry could not be read. The walk continues. */
    default void onError(Path path, IOException error) {
    }
    
}
