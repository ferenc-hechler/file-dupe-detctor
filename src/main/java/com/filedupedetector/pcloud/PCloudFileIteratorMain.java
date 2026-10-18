package com.filedupedetector.pcloud;


import java.io.IOException;

import com.pcloud.sdk.ApiClient;
import com.pcloud.sdk.ApiError;
import com.pcloud.sdk.RemoteEntry;
import com.pcloud.sdk.RemoteFile;
import com.pcloud.sdk.RemoteFolder;

public class PCloudFileIteratorMain {

	private ApiClient apiClient;
	
	PCloudFileIteratorMain() {
		PCloudApiClient.setConfigFilename("~/.env-pcloud-ro-dev");
		apiClient = PCloudApiClient.getApiClient();
	}
	
	public void shutdown() {
		apiClient = null;
		PCloudApiClient.shutdownApiClient();
	}
	
    public static void main(String[] args) {
    	PCloudFileIteratorMain main = new PCloudFileIteratorMain();
    	main.listFiles();
    }
    	
    public void listFiles() {
        try {
            listRecursively(RemoteFolder.ROOT_FOLDER_ID, 0);
        } finally {
        	PCloudApiClient.shutdownApiClient();
        }
    }

    private static void printFolderContentsRecursive(RemoteFolder folder, int depth) {
        String indent = " ".repeat(depth);
        System.out.println(indent + "[Ordner] " + folder.name());

        if (folder.children() != null) {
            for (RemoteEntry entry : folder.children()) {
                if (entry.isFile()) {
                	RemoteFile file = entry.asFile();
                    System.out.println(indent + " - [Datei] " + file.name());
                } else if (entry.isFolder()) {
                    printFolderContentsRecursive((RemoteFolder) entry, depth + 1);
                }
            }
        }
    }

    private void listRecursively(long folderId, int depth) {
        try {
            // Versuche den schnellen nativen Weg
            RemoteFolder folder = apiClient.listFolder(folderId, true).execute();
            printFolderContentsRecursive(folder, depth);
            
        } catch (IOException | RuntimeException | ApiError e) {
        	if ((e instanceof ApiError) && (((ApiError) e).errorCode() == 1101)) {
                listRecursivelyManual(folderId, depth);
			} else {
				System.err.println("API-Fehler beim nativen rekursiven Aufruf: " + e.getMessage());
        	}
        }
    }

    private void listRecursivelyManual(long folderId, int depth) {
		try {
	        // Lädt immer nur einen Ordner, was API-seitig stabiler ist
	        RemoteFolder folder = apiClient.listFolder(folderId).execute();
	        String indent = " ".repeat(depth);
	        
	        System.out.println(indent + "[Ordner-Manuell] " + folder.name() + " (ID: " + folder.folderId() + ")");
	
	        if (folder.children() != null) {
	            for (RemoteEntry entry : folder.children()) {
	                if (entry.isFile()) {
	                    RemoteFile file = entry.asFile();
	                    System.out.println(indent + " - [Datei] " + file.name() + " (" + file.size() + " Bytes)");
	                } else if (entry.isFolder()) {
	                    // Rekursiver Schritt für die Unterordner-ID
	                	RemoteFolder subFolder = entry.asFolder();
	                    listRecursively(subFolder.folderId(), depth + 1);
	                }
	            }
			} 
		}catch (IOException | ApiError e) {
			System.err.println("Fehler beim Laden des Ordners mit ID " + folderId + ": " + e.getMessage());
		}
    }
    
}

