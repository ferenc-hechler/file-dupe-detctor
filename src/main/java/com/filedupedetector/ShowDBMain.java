package com.filedupedetector;

import com.filedupedetector.persistence.DBManager;

public final class ShowDBMain {

	private DBManager dbMgr;

    public ShowDBMain() {
    	this.dbMgr = new DBManager(false);
    }

    public static void main(String[] args) {
        ShowDBMain showDBApp = new ShowDBMain();
        showDBApp.dbMgr.showDBFiles(5);
    }


}
