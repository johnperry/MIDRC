/*---------------------------------------------------------------
*  Copyright 2021 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
*----------------------------------------------------------------*/

package org.rsna.ctp.stdstages;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import jdbm.RecordManager;
import jdbm.htree.HTree;
import jdbm.helper.FastIterator;
import jdbm.helper.Tuple;
import jdbm.helper.TupleBrowser;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.pipeline.AbstractPipelineStage;
import org.rsna.ctp.pipeline.Status;
import org.rsna.ctp.pipeline.StorageService;
import org.rsna.ctp.stdstages.buffer.*;
import org.rsna.server.User;
import org.rsna.ctp.servlets.IndexedBufferServlet;
import org.rsna.ctp.servlets.SummaryLink;
import org.rsna.server.HttpResponse;
import org.rsna.server.HttpServer;
import org.rsna.server.ServletSelector;
import org.rsna.util.FileUtil;
import org.rsna.util.HttpUtil;
import org.rsna.util.JdbmUtil;
import org.rsna.util.StringUtil;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/**
 * A class to store objects in a file system.
 */
public class IndexedDicomBuffer extends AbstractPipelineStage implements StorageService {

	static final Logger logger = Logger.getLogger(IndexedDicomBuffer.class);

	static final int oneSecond = 1000;
	final int connectionTimeout = 20 * oneSecond;
	final int readTimeout = 120 * oneSecond;

	String url;
	String apikey;
	String protocol;
	volatile File lastFileStored = null;
	volatile long lastTime = 0;
	Tracker tracker = null;
    RecordManager recman = null;
    HTree patientIndex = null;
    HTree uidIndex = null;
    ExportThread exporter;
    
    volatile int totalCount = 0;
    volatile int acceptedCount = 0;
    
    File indexDir;
    File storeDir;

	/**
	 * Construct an IndexedBufferService.
	 * @param element the XML element from the configuration file
	 * specifying the configuration of the stage.
	 */
	public IndexedDicomBuffer(Element element) {
		super(element);
		String indexPath = element.getAttribute("index").trim();
		indexDir = (indexPath.equals("")) ? new File(root, "index") : getDirectory(indexPath);
		indexDir.mkdirs();
		String storePath = element.getAttribute("store").trim();
		storeDir = (storePath.equals("")) ? new File(root, "store") : getDirectory(storePath);
		storeDir.mkdirs();
		
		File indexFile = new File(indexDir, "__index");
		recman = JdbmUtil.getRecordManager( indexFile.getPath() );
		patientIndex = JdbmUtil.getHTree( recman, "patientIndex" );
		uidIndex = JdbmUtil.getHTree( recman, "uidIndex" );
		if (uidIndex == null) logger.warn("Unable to load the index.");
		else {
			try { 
				tracker = (Tracker)uidIndex.get("__tracker");
				if (tracker == null) {
					tracker = new Tracker(storeDir);
					uidIndex.put("__tracker", tracker);
					commit();
				}
			}
			catch (Exception ex) {
				logger.warn("Unable to load the Tracker.", ex);
			}
		}
		//Get the destination url
		url = element.getAttribute("url").trim();
		apikey = element.getAttribute("apikey").trim();
		//logger.info(name+": url: "+url);
		//logger.info(name+": apikey: \""+apikey+"\"");
		//Check that we have an id for use as the context or the servlet
		if (id.equals("")) logger.error(name+": No id attribute was specified.");
	}
	
	/**
	 * Start the stage.
	 */
	public synchronized void start() {
		//Insert the servlet into the HttpServer's ServletSelector
		//with the id of this stage as the context. This must be done
		//here because the Configuration object was not fully instantiated
		//when the constructor was called.
		HttpServer server = Configuration.getInstance().getServer();
		ServletSelector selector = server.getServletSelector();
		if (!id.equals("")) selector.addServlet(id, IndexedBufferServlet.class);
		
		//Start the export thread
		exporter = new ExportThread();
		exporter.start();
	}

	/**
	 * Stop the stage.
	 */
	public synchronized void shutdown() {
		try {
			exporter.interrupt();
			exporter.join();
			if (recman != null) {
				recman.commit();
				recman.close();
			}
		}
		catch (Exception failed) {
			logger.warn("Unable to shut down.", failed);
		}
		super.shutdown();
	}
	
	private void commit() {
		try { recman.commit(); }
		catch (Exception ignore) { logger.warn("Commit failed"); }
	}
	
	/**
	 * Export an array of patients.
	 * @param ptids the array of patientIDs to export.
	 */
	public synchronized void export(String[] ptids, String comment) {
		for (String ptid : ptids) {
			try {
				String submissionID = getImportEventID(comment);
				Patient patient = (Patient)patientIndex.get(ptid);
				patient.setComment(comment);
				patient.setSubmissionID(submissionID);
				patient.setStatus(Status.PENDING);
				patientIndex.put(ptid, patient);
			}
			catch (Exception ex) {
				logger.warn("Unable to serve export request for "+id);
			}
		}
		commit();
	}
	
	/**
	 * Get the File corresponding to a UID.
	 * @param uid the UID of the object to find.
	 * @return the File corresponding to the stored object with the requested UID,
	 * or null if no object corresponding to the UID is stored.
	 */
	public File getFileForUID(String uid) {
		try { return (File)uidIndex.get(uid); }
		catch (Exception noFile) {
			logger.info("Unable to find UID ("+uid+") in the uidIndex.");
			return null;
		}
	}

	/**
	 * Store a DicomObject object.
	 * If the storage attempt fails, quarantine the input object if a quarantine
	 * was defined in the configuration, and return null to stop further processing.
	 * @param fileObject the object to store.
	 * @return the original FileObject, or null if the object was a DiocomObject
	 * and the storage attempt failed.
	 */
	public synchronized FileObject store(FileObject fileObject) {

		//Count all the files
		totalCount++;

		//This StorageService is configured to accept only DicomObjects.
		if (!(fileObject instanceof DicomObject)) return fileObject;
		DicomObject dicomObject = (DicomObject)fileObject;

		//Count the accepted files
		acceptedCount++;

		//The object is acceptable; get a place to store it.
		//First, see if the object is already in the store;
		File savedFile;
		String uid = dicomObject.getSOPInstanceUID();
		String path = null;
		try { path = (String)uidIndex.get(uid); }
		catch (Exception notThere) { }

		if (path != null) {
			//This file is already in the store
			savedFile = new File(path);
		}
		else {
			//This is a new file, get the next open location.
			savedFile = tracker.getNextFile();
		}

		//At this point, savedFile points to where the file is to be stored.
		//Make sure the parent directory exists.
		File parent = savedFile.getAbsoluteFile().getParentFile();
		parent.mkdirs();

		//Store the object
		if (fileObject.copyTo(savedFile)) {
			//The store worked; update the index
			String patientID = dicomObject.getPatientID();
			String studyInstanceUID = dicomObject.getStudyInstanceUID();
			String studyDate = dicomObject.getStudyDate();
			String modality = dicomObject.getModality();
			try {
				Patient pt = (Patient)patientIndex.get(patientID);
				if (pt == null) pt = new Patient(patientID);
				pt.setLastModifiedTime();
				Study st = pt.getStudy(studyInstanceUID);
				if (st == null) st = new Study(studyInstanceUID, studyDate, modality);
				st.addInstanceUID(uid);
				pt.addStudy(studyInstanceUID, st);
				patientIndex.put(patientID, pt);
				uidIndex.put(uid, savedFile.getAbsoluteFile());
				commit();
			}
			catch (Exception ex) {
				logger.warn("Unable to update the index for "+uid+" ("+savedFile.getAbsolutePath()+")", ex);
			}
		}
		else {
			if (quarantine != null) quarantine.insert(fileObject);
			return null;
		}

		lastFileStored = fileObject.getFile();
		lastTime = System.currentTimeMillis();
		lastFileOut = lastFileStored;
		lastTimeOut = lastTime;
		return fileObject;
	}
	
	/**
	 * Get a Patient from the patientIndex by patientID.
	 * @return the Patient.
	 */
	public synchronized Patient getPatient(String patientID) {
		try { return (Patient)patientIndex.get(patientID); }
		catch (Exception ex) { logger.warn("Unable to fetch patient "+patientID, ex); }
		return null;
	}

	/**
	 * Store a Patient in the patientIndex by patientID.
	 */
	public synchronized void putPatient(Patient patient) {
		try { 
			patientIndex.put(patient.getPatientID(), patient); 
			commit();
		}
		catch (Exception ex) { logger.warn("Unable to store patient "+patient.getPatientID(), ex); }
	}

	/**
	 * Delete a Patient from the database, including all the studies and the instances.
	 */
	public synchronized void deletePatient(Patient patient) {
		try { 
			//First delete all the instances in the patient's studies
			//from the uidIndex.
			for (Study study : patient.getStudies()) {
				for (String uid : study.getInstanceUIDs()) {
					File file = (File)uidIndex.get(uid);
					uidIndex.remove(uid); //remove the reference
					file.delete();
				}
			}
			//Now delete the patient from the patientIndex.
			patientIndex.remove(patient.getPatientID());
		}
		catch (Exception ex) { logger.warn("Unable to delete patient "+patient.getPatientID(), ex); }
		commit();
	}

	/**
	 * Get an array of Patients that have not been exported.
	 * @return the Patients who have not been exported
	 * (so they have Status.NONE).
	 */
	public synchronized Patient[] getPatients() {
		LinkedList<Patient> ptList = new LinkedList<Patient>();
		try {
			FastIterator fit = patientIndex.values();
			Patient p;
			while ( (p=(Patient)fit.next()) != null) {
				if (p.getStatus().is(Status.NONE)) ptList.add(p);
			}
		}
		catch (Exception ex) { logger.warn("Unable to get list of patients", ex); }
		Patient[] pts = ptList.toArray( new Patient[ptList.size()] );
		Arrays.sort(pts, new PatientIDComparator());
		return pts;
	}
	
	class PatientIDComparator implements Comparator<Patient> {
		public int compare(Patient p1, Patient p2) {
			return p1.getPatientID().compareTo(p2.getPatientID());
		}
	}

	/**
	 * Get an array of Patients that are ready for export.
	 * To be ready, a Patient must have a submissionID and 
	 * the status must be Status.PENDING.
	 * If the patient has a status, the export must have failed.
	 * @return the Patients who are ready for export.
	 */
	public synchronized Patient[] getPatientsForExport() {
		LinkedList<Patient> ptList = new LinkedList<Patient>();
		try {
			FastIterator fit = patientIndex.values();
			Patient p;
			while ( (p=(Patient)fit.next()) != null) {
				if (!p.getSubmissionID().equals("") && p.getStatus().is(Status.PENDING)) {
					ptList.add(p);
				}
			}
		}
		catch (Exception ex) { logger.warn("Unable to get list of patients for export", ex); }
		return ptList.toArray( new Patient[ptList.size()] );
	}
	
	/**
	 * Reset the status for Patients that have status other than
	 * Status.NONE or Status PENDING.
	 * This has the effect of allowing retries in case of export failures.
	 */
	public synchronized void reset() {
		//First, get a list of patients to reset
		LinkedList<Patient> ptList = new LinkedList<Patient>();
		try {
			FastIterator fit = patientIndex.values();
			Patient p;
			while ( (p=(Patient)fit.next()) != null) {
				Status s = p.getStatus();
				if (!s.is(Status.PENDING) && !s.is(Status.NONE)) {
					ptList.add(p);
				}
			}
		}
		catch (Exception ex) { logger.warn("Unable to get list of patients for export", ex); }
		//Now reset the patients in the list.
		for (Patient p : ptList) {
			p.setStatus(Status.NONE);
			putPatient(p);
		}
	}
	
	/**
	 * Get HTML text displaying the current status of the stage.
	 * @return HTML text displaying the current status of the stage.
	 */
	public synchronized String getStatusHTML() {
		int nUnqueuedPatients = 0;
		int nUnqueuedStudies = 0;
		int nUnqueuedInstances = 0;
		int nQueuedPatients = 0;
		int nQueuedStudies = 0;
		int nQueuedInstances = 0;
		int nFailedPatients = 0;
		int nFailedStudies = 0;
		int nFailedInstances = 0;
		try {
			FastIterator fit = patientIndex.values();
			Patient p;
			while ( (p=(Patient)fit.next()) != null) {
				int nInstances = 0;
				for (Study s : p.getStudies()) {
					nInstances += s.getNumberOfInstances();
				}
				if (p.getStatus().is(Status.NONE)) {
					nUnqueuedPatients++;
					nUnqueuedStudies += p.getNumberOfStudies();
					nUnqueuedInstances += nInstances;
				}
				else if (p.getStatus().is(Status.PENDING)) {
					nQueuedPatients++;
					nQueuedStudies += p.getNumberOfStudies();
					nQueuedInstances += nInstances;
				}
				else {
					nFailedPatients++;
					nFailedStudies += p.getNumberOfStudies();
					nFailedInstances += nInstances;
				}
			}
		}
		catch (Exception ignore) { }
		
		StringBuffer sb = new StringBuffer();
		sb.append("<h3>"+name+"</h3>");
		sb.append("<table border=\"1\" width=\"100%\">");

		sb.append("<tr><td width=\"20%\">Files received for storage:</td>"
			+ "<td>" + totalCount + "</td></tr>");
		sb.append("<tr><td width=\"20%\">Files accepted for storage:</td>"
			+ "<td>" + acceptedCount + "</td></tr>");

		sb.append("<tr><td width=\"20%\">Last file stored:</td>");
		if (lastTime != 0) {
			sb.append("<td>"+lastFileStored+"</td></tr>");
			sb.append("<tr><td width=\"20%\">Last file stored at:</td>");
			sb.append("<td>"+StringUtil.getDateTime(lastTime,"&nbsp;&nbsp;&nbsp;")+"</td></tr>");
		}
		else sb.append("<td>No activity</td></tr>");
		
		sb.append("<tr><td width=\"20%\">Unqueued patients:</td>"
			+ "<td>" + nUnqueuedPatients + "</td></tr>");
		sb.append("<tr><td width=\"20%\">Unqueued studies:</td>"
			+ "<td>" + nUnqueuedStudies + "</td></tr>");
		sb.append("<tr><td width=\"20%\">Unqueued images:</td>"
			+ "<td>" + nUnqueuedInstances + "</td></tr>");
		sb.append("<tr><td width=\"20%\">Queued patients:</td>"
			+ "<td>" + nQueuedPatients + "</td></tr>");
		sb.append("<tr><td width=\"20%\">Queued studies:</td>"
			+ "<td>" + nQueuedStudies + "</td></tr>");
		sb.append("<tr><td width=\"20%\">Queued images:</td>"
			+ "<td>" + nQueuedInstances + "</td></tr>");
		sb.append("<tr><td width=\"20%\">Failed patients:</td>"
			+ "<td>" + nFailedPatients + "</td></tr>");
		sb.append("<tr><td width=\"20%\">Failed studies:</td>"
			+ "<td>" + nFailedStudies + "</td></tr>");
		sb.append("<tr><td width=\"20%\">Failed images:</td>"
			+ "<td>" + nFailedInstances + "</td></tr>");

		sb.append("</table>");
		return sb.toString();
	}
	
	/**
	 * Get the list of links for display on the summary page.
	 * @param user the requesting user.
	 * @return the list of links for display on the summary page.
	 */
	public synchronized LinkedList<SummaryLink> getLinks(User user) {
		LinkedList<SummaryLink> links = super.getLinks(user);
		if (allowsAdminBy(user)) {
			links.addFirst( new SummaryLink("/"+id, null, "Manage the Image Buffer", false) );
		}
		return links;
	}
	
	//======================
	//    POSDA export
	//======================
	
	class ExportThread extends Thread {
		public ExportThread() {
			super(id + "_exporter");
		}
		public void run() {
			logger.info("ExportThread "+getName()+" started");
			try {
				Thread.sleep(10000); //wait 10 secs to start
				while (!isInterrupted()) {
					exportPatients();
					if (isInterrupted()) break;
					tracker.purge();
					Thread.sleep(10000); //wait 10 secs before polling the index again
				}
			}
			catch (Exception ex) {
				logger.info(getName() + " interrupted");
			}
		}
		
		private void purge() {
			
		}
	
		private void exportPatients() {
			//logger.info("exportPatients called: "+getPatientsForExport().length + " available for export");
			for (Patient p : getPatientsForExport()) {
				//logger.info("Exporting patient "+p.getPatientID());
				if (isInterrupted()) break;
				exportPatient(p);
			}
		}

		private void exportPatient(Patient p) {
			Status status = null; 
			File file = null;
			try {
				String submissionID = p.getSubmissionID();
				for (Study study : p.getStudies()) {
					for (String uid : study.getInstanceUIDs()) {
						file = getFileForUID(uid);
						if (file != null) {
							status = export(file, submissionID);
							if (!status.is(Status.OK)) {
								throw new Exception("Export failed");
							}
						}
					}
				}
				//If we get here, everything worked.
				//Flush the patient from the buffer.
				deletePatient(p);
			}
			catch (Exception ex) {
				String ptid = "?";
				ptid = p.getPatientID();
				p.setStatus(status);
				putPatient(p);
				logger.warn("Export failed: id="+ptid+"; status="+status+"; "+file);			
			}
		}
		
		private Status export(File fileToExport, String importEventID) {
			//Do not export zero-length files
			long fileLength = fileToExport.length();
			if (fileLength == 0) return Status.OK;

			HttpURLConnection conn = null;
			OutputStream svros = null;
			try {
				FileObject fileObject = FileObject.getInstance( fileToExport );
				String patientID = fileObject.getPatientID();

				String hash = getDigest(fileToExport).toLowerCase();
				String query = "";
				if (!apikey.equals("")) {
					query = "?import_event_id="+importEventID+"&digest="+hash+"&apikey="+apikey;
				}
				URL u = new URL(getURL() + query);
				logger.debug("Export URL: "+u.toString());

				//Establish the connection
				conn = HttpUtil.getConnection(u);
				conn.setReadTimeout(connectionTimeout);
				conn.setConnectTimeout(readTimeout);
				if (!apikey.equals("")) conn.setRequestMethod("PUT"); //POSDA requires PUT
				conn.connect();

				//Send the file to the server
				svros = conn.getOutputStream();
				FileUtil.streamFile(fileToExport, svros);

				//Get the response
				Status result = Status.OK;
				int responseCode = conn.getResponseCode();
				String responseText = "";
				try { responseText = FileUtil.getTextOrException( conn.getInputStream(), FileUtil.utf8, false ); }
				catch (Exception ex) { logger.warn("Unable to read response: "+ex.getMessage()); }
				conn.disconnect();
				if (responseCode == HttpResponse.unprocessable) {
					logger.warn("Unprocessable response from server for: " + fileToExport);
					logger.warn("Response text: "+responseText);
					result = Status.FAIL;
				}
				else if (responseCode != HttpResponse.ok) {
					logger.warn("Failure response from server ("+responseCode+") for: " + fileToExport);
					logger.warn("Response text: "+responseText);
					result = Status.RETRY;
				}
				conn.disconnect();
				return result;
			}
			catch (Exception e) {
				if (logger.isDebugEnabled()) logger.debug(name+": export failed: " + e.getMessage(), e);
				else logger.warn(name+": export failed: " + e.getMessage());
				return logger.isDebugEnabled() ? Status.FAIL : Status.RETRY;
			}
		}

		private String getDigest(File file) {
			String result = "";
			BufferedInputStream bis = null;
			DigestInputStream dis = null;
			try {
				MessageDigest md = MessageDigest.getInstance("MD5");
				md.reset();
				bis = new BufferedInputStream( new FileInputStream( file ) );
				dis = new DigestInputStream( bis, md );
				while (dis.read() != -1) ; //empty loop
				result = bytesToHex(md.digest());
			}
			catch (Exception ex) { result = ""; }
			finally {
				try { dis.close(); }
				catch (Exception ignore) { }
				try { bis.close(); }
				catch (Exception ignore) { }
			}
			return result.toString();
		}

		private String bytesToHex(byte[] bytes) {
			StringBuilder sb = new StringBuilder();
			for (byte b : bytes) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}
	}
	
	private String getURL() throws Exception {
		return url + "/v1/import/file";
	}	
	
	private String getEventIDRequestURL(String message) throws Exception {
		String u = url + "/v1/import/event?source=" + message;
		if (!apikey.equals("")) u += "&apikey="+apikey;
		return u;
	}
		
	//PUT http://localhost/.../v1/import/event?source=some+useful+message
	//{"status":"success","import_event_id":15}
	private String getImportEventID(String message) throws Exception {
		
		//If no apikey, consider this to be a CTP HTTP Export
		if (apikey.equals("")) return "0";
		
		//\Get the event ID from the POSDA site
		HttpURLConnection conn = null;
		URL u = new URL(getEventIDRequestURL(message));
		logger.debug("getImportEventID");
		logger.debug("...URL: "+u.toString());
		conn = HttpUtil.getConnection(u);
		conn.setReadTimeout(connectionTimeout);
		conn.setConnectTimeout(readTimeout);
		conn.setRequestMethod("PUT");
		conn.connect();
		int responseCode = conn.getResponseCode();
		logger.debug("...responseCode: " + responseCode);
		String text = FileUtil.getTextOrException( conn.getInputStream(), FileUtil.utf8, false );
		conn.disconnect();
		logger.debug("...response text: \""+text+"\"");
		if (text.contains("\"status\":\"success\"") && text.contains("\"import_event_id\":")) {
			text = text.replaceAll("[^0-9]", "");
		}
		else text = "0";
		logger.debug("...returning "+text);
		return text;
	}
	
}