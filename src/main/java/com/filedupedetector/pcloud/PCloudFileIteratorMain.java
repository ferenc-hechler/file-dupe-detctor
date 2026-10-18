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
            long targetFolderId = RemoteFolder.ROOT_FOLDER_ID; // oder eine beliebige ID
            
            System.out.println("Versuche rekursiven Laden via SDK (Ansatz 1)...");
            try {
                // Versuche den schnellen nativen Weg
                RemoteFolder rootFolder = apiClient.listFolder(targetFolderId, true).execute();
                printFolderContentsRecursive(rootFolder, 0);
                
            } catch (IOException | RuntimeException | ApiError e) {
                // Prüfen, ob es sich um den Fehler 1101 handelt (oder pauschal als Fallback)
                System.err.println("Natürliche Rekursion fehlgeschlagen (Vermutlich Fehler 1101 'Invalid Request').");
                System.err.println("Wechsle automatisch zum stabilen manuellen Durchlauf (Ansatz 2)...");
                
                // Fallback auf manuelle Rekursion
                listRecursivelyManual(targetFolderId, 0);
            }

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
                    System.out.println(indent + " - [Datei] " + entry.name());
                } else if (entry.isFolder()) {
                    printFolderContentsRecursive((RemoteFolder) entry, depth + 1);
                }
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
	                    RemoteFile file = (RemoteFile) entry;
	                    System.out.println(indent + " - [Datei] " + file.name() + " (" + file.size() + " Bytes)");
	                } else if (entry.isFolder()) {
	                    // Rekursiver Schritt für die Unterordner-ID
	                	RemoteFolder subFolder = (RemoteFolder) entry;
	                    listRecursivelyManual(subFolder.folderId(), depth + 1);
	                }
	            }
			} 
		}catch (IOException | ApiError e) {
			System.err.println("Fehler beim Laden des Ordners mit ID " + folderId + ": " + e.getMessage());
		}
    }
    
}

