package com.filedupedetector;

import com.filedupedetector.persistence.DBManager;

public final class CompactDBMain {

	private DBManager dbMgr;

    public CompactDBMain() {
    	this.dbMgr = new DBManager(false);
    }

    public static void main(String[] args) {
        CompactDBMain showDBApp = new CompactDBMain();
        showDBApp.dbMgr.shutdownCompact();
    }


}
