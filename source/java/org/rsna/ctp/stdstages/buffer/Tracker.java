package org.rsna.ctp.stdstages.buffer;

import java.io.Serializable;
import java.io.File;

public class Tracker implements Serializable {
	public long fileID;
	public File baseDir;
	
	public Tracker(File baseDir) {
		this.baseDir = baseDir;
		fileID = Long.valueOf(0);
	}
	
	public synchronized File getNextFile() {
		String path = String.format("%02X/%02X/%02X/%02X.dcm",
							(fileID >> 24) & 0xFF,
							(fileID >> 16) & 0xFF,
							(fileID >> 8) & 0xFF,
							 fileID & 0xFF);
		fileID++;
		return new File(baseDir, path);
	}
	
	public synchronized void purge() {
		for (File f : baseDir.listFiles()) {
			purge(f);
		}
	}
	
	private void purge(File dir) {
		for (File file : dir.listFiles()) {
			if (file.isDirectory()) {
				purge(file);
			}
		}
		if (dir.listFiles().length == 0) dir.delete();
	}
			
}
	
