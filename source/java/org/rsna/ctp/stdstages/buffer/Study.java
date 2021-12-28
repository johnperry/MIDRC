package org.rsna.ctp.stdstages.buffer;

import java.io.Serializable;
import java.util.HashSet;
import org.w3c.dom.*;

public class Study implements Comparable<Study>, Serializable {
	String studyInstanceUID;
	String studyDate;
	String modality;
	HashSet<String> instanceTable;
	
	public Study(String studyInstanceUID, String studyDate, String modality) {
		this.studyInstanceUID = studyInstanceUID;
		this.studyDate = studyDate;
		this.modality = modality;
		instanceTable = new HashSet<String>();
	}
	
	public synchronized void addInstanceUID(String sopInstanceUID) {
		instanceTable.add(sopInstanceUID);
	}
	
	public synchronized String[] getInstanceUIDs() {
		return instanceTable.toArray(new String[instanceTable.size()]);
	}
	
	public synchronized int getNumberOfInstances() {
		return instanceTable.size();
	}
	
	public int compareTo(Study s) {
		return studyDate.compareTo(s.studyDate);
	}
	
	public synchronized void  appendTo(Element parent) {
		Document doc = parent.getOwnerDocument();
		Element s = doc.createElement("Study");
		s.setAttribute("studyInstanceUID", studyInstanceUID);
		s.setAttribute("studyDate", studyDate);
		s.setAttribute("modality", modality);
		s.setAttribute("nImages", Integer.toString(instanceTable.size()));
		parent.appendChild(s);
	}

}

