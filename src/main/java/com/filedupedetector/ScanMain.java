package com.filedupedetector;

public class ScanMain {


	public static void main(String[] args) {
        args = new String[] {"scan", "SkipRuntime_DOS", "C:\\SkipRuntime\\DOS"};
//        args = new String[] {"scan", "RelativeSpecsFolder", "./specs"};
//        args = new String[] {"scan", "InputTestFolder", "./input/test"};
//		args = new String[] {"scan", "TestFolderCLISpecify", "./.specify"};
        
		FileScanCliMain.main(args);
	}

}
