package com.filedupedetector;

public class ScanSpecsMain {


	public static void main(String[] args) {
		args = new String[] {"scan", "TestFolderCLI3", "./specs"};
//		args = new String[] {"scan", "TestFolderCLISpecify", "./.specify"};
		FileScanCliMain.main(args);
	}

}
