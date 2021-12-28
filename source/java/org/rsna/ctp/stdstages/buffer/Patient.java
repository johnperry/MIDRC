package org.rsna.ctp.stdstages.buffer;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Hashtable;
import org.rsna.ctp.pipeline.Status;
import org.rsna.util.StringUtil;
import org.w3c.dom.*;

public class Patient implements Serializable, Comparable<Patient> {
	String patientID;
	Hashtable<String,Study> studyTable;
	long lastModifiedTime = 0;
	String comment = "";
	String submissionID = "";
	Status status = Status.NONE;
	
	public Patient(String patientID) {
		this.patientID = patientID;
		studyTable = new Hashtable<String,Study>();
	}
	
	public synchronized String getPatientID() {
		return patientID;
	}
	
	public synchronized void setComment(String comment) {
		this.comment = comment;
	}
	
	public synchronized String getComment() {
		return comment;
	}
	
	public synchronized void setStatus(Status status) {
		this.status = status;
	}
	
	public synchronized Status getStatus() {
		return status;
	}
	
	public synchronized void setSubmissionID(String submissionID) {
		this.submissionID = submissionID;
	}
	
	public synchronized String getSubmissionID() {
		return submissionID;
	}
	
	public synchronized int getNumberOfStudies() {
		return studyTable.size();
	}
	
	public synchronized Study[] getStudies() {
		Study[] studies = new Study[studyTable.size()];
		studies = studyTable.values().toArray(studies);
		Arrays.sort(studies);
		return studies;
	}
	
	public synchronized Study getStudy(String studyInstanceUID) {
		return studyTable.get(studyInstanceUID);
	}
	
	public synchronized void addStudy(String studyInstanceUID, Study study) {
		studyTable.put(studyInstanceUID, study);
	}
	
	public void setLastModifiedTime() {
		lastModifiedTime = System.currentTimeMillis();
	}
	
	public int compareTo(Patient p) {
		if (lastModifiedTime < p.lastModifiedTime) return -1;
		if (lastModifiedTime > p.lastModifiedTime) return 1;
		return 0;
	}
	
	public synchronized void appendTo(Element parent) {
		Document doc = parent.getOwnerDocument();
		Element p = doc.createElement("Patient");
		p.setAttribute("patientID", patientID);
		p.setAttribute("lastModifiedTime", StringUtil.getDateTime(lastModifiedTime," - "));
		Study[] studies = studyTable.values().toArray(new Study[studyTable.size()]);
		Arrays.sort(studies);
		for (Study s : studies) {
			s.appendTo(p);
		}
		parent.appendChild(p);
	}
	
}

