package com.filedupedetector.service;


import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Stack;

import com.filedupedetector.scan.FileVisitCallback;
import com.filedupedetector.scan.FileWalker;
import com.filedupedetector.scan.InaccessibleReason;
import com.filedupedetector.scan.NioFileWalker;

public final class FileWalkerTestMain  {

    private void testWalk(FileWalker fileWalker, String rootPath) {
        fileWalker.walk(Path.of(rootPath), new FileVisitCallback() {
            @Override
            public void onFileFound(Path absolutePath, long sizeBytes, FileTime lastModified) {
                System.out.println("Found file: " + absolutePath + " size=" + sizeBytes + " lastModified=" + lastModified);
            }

            @Override
            public void onError(Path path, IOException error) {
                System.err.println("Error accessing path: " + path + " error=" + error.toString());
            }

            @Override
            public void onInaccessible(Path absolutePath, InaccessibleReason reason) {
                System.err.println("Inaccessible path: " + absolutePath + " reason=" + reason);
            }
        });
        
    }
    
    
    static class MyFileVisitor extends SimpleFileVisitor<Path> {
        private int counter;
        private Stack<Integer> dirStack;
        private int currentDir;
        public MyFileVisitor() {
            counter = 0;
            currentDir = -1;
            dirStack = new Stack<>();
        }
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            int oldCurrentDir = currentDir;
            dirStack.push(currentDir);
            currentDir = counter++;
            System.out.println("P["+oldCurrentDir+"]/DIR["+currentDir+"]: " + dir);
            return FileVisitResult.CONTINUE;
        }
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            System.out.println("  DIR["+currentDir+"]/FILE: " + file + " size=" + attrs.size() + " lastModified=" + attrs.lastModifiedTime());
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            System.err.println("  Error accessing path: " + file + " error=" + exc.toString());
            return FileVisitResult.CONTINUE;
        }
        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            if (exc != null) {
                System.err.println("Error accessing directory: " + dir + " error=" + exc.toString());
            }
            int oldCurrentDir = currentDir;
            currentDir = dirStack.pop();
            System.out.println("<-- leaving DIR["+oldCurrentDir+"] back to DIR["+currentDir+"]");
            return FileVisitResult.CONTINUE;
        }
    }
    
    
    private void testWalk2(String rootPath) {
        Path root = Path.of(rootPath);
        if (!java.nio.file.Files.exists(root)) {
            System.err.println("Root path does not exist: " + rootPath);
            return;
        }
        if (!java.nio.file.Files.isDirectory(root)) {
            System.err.println("Root path is not a directory: " + rootPath);
            return;
        }
        try {
            Files.walkFileTree(root, new MyFileVisitor());
         } catch (IOException e) {
            System.err.println("Error walking file tree: " + e.toString());
        }
    }

    public static void main(String[] args) {
        FileWalkerTestMain test = new FileWalkerTestMain();
        FileWalker fileWalker = new NioFileWalker();
//        test.testWalk(fileWalker, "./specs");
        test.testWalk2("./src");
    }

}