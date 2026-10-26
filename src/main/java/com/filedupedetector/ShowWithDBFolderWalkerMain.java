package com.filedupedetector;

import com.filedupedetector.persistence.DBFolderWalker;
import com.filedupedetector.scan.FolderVisitor;
import com.filedupedetector.scan.FolderVisitor.FileEntry;

public final class ShowWithDBFolderWalkerMain {

	class DBFolderWalkerVisitor implements FolderVisitor {

		String indent = "";
		
		@Override
		public void preVisitDirectory(java.nio.file.Path folder, java.nio.file.attribute.BasicFileAttributes attrs) {
			indent = indent + "  ";
		}

		@Override
		public void visitFolder(java.nio.file.Path folder, java.util.List<FileEntry> files,
				java.util.List<java.nio.file.Path> subFolders) {
			System.out.println(indent + folder.getFileName());
			for (FileEntry file : files) {
				System.out.println(indent + "  " + file.path().getFileName() + " (size: " + file.size() + ", lastModified: "
						+ file.lastModified() + ", remoteId: " + file.remoteId() + ")");
			}
		}

		@Override
		public void postVisitDirectory(java.nio.file.Path folder, java.io.IOException exc) {
			indent = indent.substring(0, indent.length() - 2);
		}
		
	}
	
    public void showDBFiles() {
    	DBFolderWalker dbFolderWalker = new DBFolderWalker();
    	dbFolderWalker.walk(new DBFolderWalkerVisitor());
    	
    }

    public static void main(String[] args) {
        ShowWithDBFolderWalkerMain showDBApp = new ShowWithDBFolderWalkerMain();
        showDBApp.showDBFiles();
    }


}
