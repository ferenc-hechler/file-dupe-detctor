package com.filedupedetector.scan;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.filedupedetector.scan.FolderVisitor.FileEntry;

/**
 * Traverses folders depth-first (pre-order) starting at a root path. For each folder the
 * {@link FolderVisitor} is called once with the list of its regular files.
 * Symbolic links are not followed; errors are reported and the walk continues.
 */
public final class FolderWalker {

    private static final Logger log = LoggerFactory.getLogger(FolderWalker.class);

    record FolderEvent(Path folder, List<FileEntry> files, List<Path> subFolders) {
    }
    
    public void walk(Path rootPath, FolderVisitor visitor) {
        if (!Files.exists(rootPath)) {
            log.warn("Scan root path does not exist or is not accessible: {}", rootPath);
            visitor.onError(rootPath, new IOException("Root path does not exist or is not accessible: " + rootPath));
            return;
        }
        LinkedList<Path> queue = new LinkedList<>();
        queue.add(rootPath);

        while (!queue.isEmpty()) {
            
            Path folder = queue.poll();
            if (folder == null) {
                visitor.postVisitDirectory(folder, null);
                continue;
            }
            
            BasicFileAttributes attrs = null;
            visitor.preVisitDirectory(folder, attrs);

            
            List<FileEntry> files = new ArrayList<>();
            List<Path> subFolders = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
                for (Path entry : stream) {
                    classify(entry, files, subFolders, visitor);
                }
            } catch (IOException | RuntimeException e) {
                visitor.postVisitDirectory(folder, toIo(e, folder));
                visitor.onError(folder, toIo(e, folder));
                continue;
            }

            files.sort((fe1,fe2) -> fe1.path().getFileName().toString().compareTo(fe2.path().getFileName().toString()));
            subFolders.sort((p1,p2)->p1.getFileName().toString().compareTo(p2.getFileName().toString()));
            
            try {
                visitor.visitFolder(folder, List.copyOf(files), List.copyOf(subFolders));
            } catch (RuntimeException e) {
                visitor.onError(folder, new IOException("Unexpected error visiting folder: " + folder, e));
            }
            // Insert at the front, keeping sorted order => DFS
            queue.addAll(0, null);
            queue.addAll(0, subFolders);
        }
    }

    private void classify(Path entry, List<FileEntry> files, List<Path> subFolders, FolderVisitor visitor) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(
                    entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) {
                visitor.onInaccessible(entry, InaccessibleReason.SYMLINK_SKIPPED);
            } else if (attrs.isDirectory()) {
                subFolders.add(entry);
            } else if (isCloudPlaceholder(entry)) {
                visitor.onInaccessible(entry, InaccessibleReason.CLOUD_PLACEHOLDER);
            } else if (!attrs.isRegularFile()) {
                visitor.onInaccessible(entry, InaccessibleReason.OTHER);
            } else {
                files.add(new FileEntry(entry, attrs.size(), attrs.lastModifiedTime()));
            }
        } catch (IOException | RuntimeException e) {
            visitor.onError(entry, toIo(e, entry));
        }
    }

    private static IOException toIo(Exception e, Path path) {
        return e instanceof IOException io ? io : new IOException("Unexpected error visiting: " + path, e);
    }

    private boolean isCloudPlaceholder(Path file) {
        try {
            DosFileAttributes dos = Files.readAttributes(file, DosFileAttributes.class);
            return dos.isSystem() && dos.size() == 0;
        } catch (UnsupportedOperationException | IOException notWindowsOrUnavailable) {
            return false;
        }
    }
}
