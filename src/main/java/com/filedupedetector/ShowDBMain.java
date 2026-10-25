package com.filedupedetector;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.persistence.FileRecordRepository;
import com.filedupedetector.persistence.FolderRecordRepository;
import com.filedupedetector.persistence.JdbcConnectionManager;
import com.filedupedetector.persistence.JdbcFileRecordRepository;
import com.filedupedetector.persistence.JdbcFolderRecordRepository;
import com.filedupedetector.persistence.JdbcScanLocationRepository;
import com.filedupedetector.persistence.ScanLocationRepository;
import com.filedupedetector.scan.FolderVisitor;
import com.filedupedetector.scan.FolderWalker;
import com.filedupedetector.scan.HashService;
import com.filedupedetector.scan.Sha1HashService;
import com.filedupedetector.utils.AppUtils;

public final class ShowDBMain {

    ScanLocationRepository scanLocationRepository;
    FileRecordRepository fileRecordRepository;
    FolderRecordRepository folderRecordRepository;
    HashService hashService;
    JSONExporter jsonExporter;

    public ShowDBMain() {
        JdbcConnectionManager connectionManager = new JdbcConnectionManager(false);
        this.scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
        this.fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
        this.folderRecordRepository = new JdbcFolderRecordRepository(connectionManager);
        this.hashService = new Sha1HashService();
    }

    public void showDBFiles(int maxDepth) {
        scanLocationRepository.findAll().forEach(location -> {
            System.out.println(location.disp());
            FolderRecord folderRecord = folderRecordRepository.findById(location.getRootFolderId());
            recursiveShowDBFiles(folderRecord, maxDepth, "  ");
        });
    }

    private void recursiveShowDBFiles(FolderRecord folderRecord, int maxDepth, String indent) {
        if (maxDepth < 0) {
            return;
        }
        System.out.println(folderRecord.disp(indent));
        fileRecordRepository.findByParentFolder(folderRecord.getId()).forEach(file -> {
            System.out.println(file.disp(indent+"  "));
		});       
        folderRecordRepository.findByParentFolder(folderRecord.getId()).forEach(folder -> {
            recursiveShowDBFiles(folder, maxDepth - 1, indent + "  ");
        });
    }

    public static void main(String[] args) {
        ShowDBMain scanApp = new ShowDBMain();
        
        scanApp.showDBFiles(5);
    }


}
