package com.filedupedetector.pcloud;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Stack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.filedupedetector.scan.FolderVisitor;
import com.filedupedetector.scan.FolderVisitor.FileEntry;
import com.filedupedetector.utils.PerfMonitor;
import com.pcloud.sdk.ApiClient;
import com.pcloud.sdk.ApiError;
import com.pcloud.sdk.RemoteEntry;
import com.pcloud.sdk.RemoteFile;
import com.pcloud.sdk.RemoteFolder;

/**
 * Traverses a pCloud folder tree depth-first (pre-order), analogous to
 * {@link com.filedupedetector.scan.FolderWalker}. Each folder is reported once to the
 * {@link FolderVisitor}. Paths passed to the visitor are logical pCloud paths
 * (e.g. {@code /Documents/Sub}). Errors are reported and the walk continues.
 */
public final class PCloudFolderWalker {

    private static final Logger log = LoggerFactory.getLogger(PCloudFolderWalker.class);

    private static final long WARN_MILLIS_THRESHOLD = 100;
    
    private final ApiClient apiClient;
    private PerfMonitor perfMonitor;
    

    public PCloudFolderWalker() {
        this(PCloudApiClient.getApiClient());
    }

    public PCloudFolderWalker(ApiClient apiClient) {
        this.apiClient = apiClient;
    	this.perfMonitor = new PerfMonitor();
    }

    private record FolderAction(long folderId, Path folder, BasicFileAttributes attrs, boolean leave) {}

    /** Walks the complete pCloud tree starting at the root folder. */
    public void walk(FolderVisitor visitor) {
        walk(RemoteFolder.ROOT_FOLDER_ID, Path.of("/"), visitor);
    }

    public long getFolderIdForPath(Path path) {
		String normalizedPath = path.toString().replace("\\", "/");
		if (normalizedPath.equals("/") || normalizedPath.isEmpty()) {
			return RemoteFolder.ROOT_FOLDER_ID;
		}
		String[] parts = normalizedPath.split("/");
		long currentFolderId = RemoteFolder.ROOT_FOLDER_ID;
		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			try {
				RemoteFolder folder = listPCloudFolder(currentFolderId, part + " of " + normalizedPath);
				boolean found = false;
				if (folder.children() != null) {
					for (RemoteEntry entry : folder.children()) {
						if (entry.isFolder() && entry.name().equals(part)) {
							currentFolderId = entry.asFolder().folderId();
							found = true;
							break;
						}
					}
				}
				if (!found) {
					throw new IllegalArgumentException("Folder not found in pCloud: " + normalizedPath);
				}
			} catch (IOException | ApiError e) {
				throw new RuntimeException("Error accessing pCloud API for path: " + normalizedPath, e);
			}
		}
		return currentFolderId;
	}

	private RemoteFolder listPCloudFolder(long folderId, Object folderPath) throws IOException, ApiError {
		try {
			perfMonitor.reset();
			RemoteFolder folder = apiClient.listFolder(folderId).execute();
			perfMonitor.warnIfElapsedSecondsExceeds(WARN_MILLIS_THRESHOLD, "listFolder for " + folderId + " " + folderPath);
			return folder;
		}
		catch (Exception e) {
			System.out.printf("EXCEPTION: %s (elapsed time: %.3f sec): %s%n", "listFolder for " + folderId + " " + folderPath, perfMonitor.getElapsedSeconds(), e.toString());
			throw e;
		}
		
	}
    
    public void walk(Path rootPath, FolderVisitor visitor) {
    	long folderID = getFolderIdForPath(rootPath);
    	walk(folderID, rootPath, visitor);
    }

    /**
     * Walks the tree starting at the given folder.
     *
     * @param rootFolderId pCloud folder id
     * @param rootPath     logical path used for the root folder in visitor callbacks
     * @param visitor      callback
     */
    public void walk(long rootFolderId, Path rootPath, FolderVisitor visitor) {
        Stack<FolderAction> stack = new Stack<>();
        stack.push(new FolderAction(rootFolderId, rootPath, null, true));
        stack.push(new FolderAction(rootFolderId, rootPath, null, false));

        while (!stack.isEmpty()) {
            FolderAction action = stack.pop();
            Path folder = action.folder();
            if (action.leave()) {
                visitor.postVisitDirectory(folder, null);
                continue;
            }
            visitor.preVisitDirectory(folder, action.attrs());

            List<FileEntry> files = new ArrayList<>();
            List<FolderAction> subFolders = new ArrayList<>();

            try {
                RemoteFolder remote = listPCloudFolder(action.folderId(), action.folder);
                if (remote.children() != null) {
                    for (RemoteEntry entry : remote.children()) {
                        classify(folder, entry, files, subFolders, visitor);
                    }
                }
            } catch (IOException | ApiError | RuntimeException e) {
                IOException io = toIo(e, folder);
                log.warn("Could not list pCloud folder {}: {}", folder, e.getMessage());
                visitor.postVisitDirectory(folder, io);
                visitor.onError(folder, io);
                continue;
            }

            files.sort((a, b) -> a.path().getFileName().toString().compareTo(b.path().getFileName().toString()));
            subFolders.sort((a, b) -> a.folder().getFileName().toString()
                    .compareTo(b.folder().getFileName().toString()));

            try {
                List<Path> subPaths = subFolders.stream().map(FolderAction::folder).toList();
                visitor.visitFolder(folder, List.copyOf(files), subPaths);
            } catch (RuntimeException e) {
                visitor.onError(folder, new IOException("Unexpected error visiting folder: " + folder, e));
            }

            // Push in reverse order so that the first sub folder is processed first => DFS
            for (FolderAction sub : subFolders.reversed()) {
                stack.push(new FolderAction(sub.folderId(), sub.folder(), sub.attrs(), true));
                stack.push(sub);
            }
        }
    }

    private void classify(Path parent, RemoteEntry entry, List<FileEntry> files, List<FolderAction> subFolders,
            FolderVisitor visitor) {
        try {
            Path path = parent.resolve(entry.name());
            if (entry.isFolder()) {
                RemoteFolder sub = entry.asFolder();
                subFolders.add(new FolderAction(sub.folderId(), path, new PCloudAttributes(entry, true, 0), false));
            } else if (entry.isFile()) {
                RemoteFile file = entry.asFile();
                files.add(new FileEntry(path, file.size(), toFileTime(file.lastModified()), file.fileId()));
            } else {
                visitor.onInaccessible(path, com.filedupedetector.scan.InaccessibleReason.OTHER);
            }
        } catch (RuntimeException e) {
            visitor.onError(parent, toIo(e, parent));
        }
    }

    private static FileTime toFileTime(Date date) {
        return date == null ? FileTime.fromMillis(0) : FileTime.fromMillis(date.getTime());
    }

    private static IOException toIo(Exception e, Path path) {
        return e instanceof IOException io ? io : new IOException("Unexpected error visiting: " + path, e);
    }

    /** Minimal {@link BasicFileAttributes} view of a pCloud entry. */
    private static final class PCloudAttributes implements BasicFileAttributes {
        private final FileTime modified;
        private final FileTime created;
        private final boolean directory;
        private final long size;

        PCloudAttributes(RemoteEntry entry, boolean directory, long size) {
            this.modified = toFileTime(entry.lastModified());
            this.created = toFileTime(entry.created());
            this.directory = directory;
            this.size = size;
        }

        @Override public FileTime lastModifiedTime() { return modified; }
        @Override public FileTime lastAccessTime() { return modified; }
        @Override public FileTime creationTime() { return created; }
        @Override public boolean isRegularFile() { return !directory; }
        @Override public boolean isDirectory() { return directory; }
        @Override public boolean isSymbolicLink() { return false; }
        @Override public boolean isOther() { return false; }
        @Override public long size() { return size; }
        @Override public Object fileKey() { return null; }
    }
}
