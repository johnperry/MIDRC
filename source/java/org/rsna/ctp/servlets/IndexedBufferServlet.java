/*---------------------------------------------------------------
*  Copyright 2021 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
*----------------------------------------------------------------*/

package org.rsna.ctp.servlets;

import java.io.File;
import java.util.Arrays;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.pipeline.PipelineStage;
import org.rsna.ctp.stdstages.IndexedDicomBuffer;
import org.rsna.ctp.stdstages.buffer.*;
import org.rsna.ctp.servlets.CTPServlet;
import org.rsna.server.HttpRequest;
import org.rsna.server.HttpResponse;
import org.rsna.server.Path;
import org.rsna.servlets.Servlet;
import org.rsna.util.FileUtil;
import org.rsna.util.StringUtil;
import org.rsna.util.XmlUtil;
import org.w3c.dom.*;

/**
 * A Servlet which provides web access to the studies stored in an IndexedDicomBufferService.
 */
public class IndexedBufferServlet extends CTPServlet {

	static final Logger logger = Logger.getLogger(IndexedBufferServlet.class);

	/**
	 * Construct an IndexedBufferServlet.
	 * @param root the root directory of the server.
	 * @param context the path identifying the servlet.
	 */
	public IndexedBufferServlet(File root, String context) {
		super(root, context);
	}

	/**
	 * The GET handler.
	 * @param req the request object
	 * @param res the response object
	 */
	public void doGet(HttpRequest req, HttpResponse res) {
		super.loadParameters(req);
		res.setContentEncoding(req);

		//Make sure the user is authorized to do this.
		if (!userIsAuthorized) {
			res.setResponseCode(res.forbidden);
			res.send();
			return;
		}
		
		Configuration config = Configuration.getInstance();
		PipelineStage stage = config.getRegisteredStage(context);
		if ((stage == null) || !(stage instanceof IndexedDicomBuffer)) {
			res.setResponseCode( res.unprocessable );
			res.send();
			return;
		}
		IndexedDicomBuffer idb = (IndexedDicomBuffer)stage;
		
		//If this is an export, do it now
		String exportParam = req.getParameter("export");
		String comment = req.getParameter("comment");
		if ((exportParam != null) && (comment != null)) {
			String[] ptids = exportParam.split("[|]");
			idb.export(ptids, comment);
		}
		
		//If this is a resetFailures, do it now
		if (req.hasParameter("reset")) {
			idb.reset();
		}
		
		//Now return the index of patients and studies
		Patient[] pts = idb.getPatients();
		try {
			Document doc = XmlUtil.getDocument();
			Element root = doc.createElement("Patients");
			doc.appendChild(root);
			for (Patient p : pts) {
				p.appendTo(root);
			}

			Document xsl = XmlUtil.getDocument( FileUtil.getStream( "/IndexedBufferServlet.xsl" ) );
			Object[] params = {
				"context", context,
				"name", idb.getName(),
				"suppress", suppress
			};
			res.write( XmlUtil.getTransformedText( doc, xsl, params ) );
			res.setContentType("html");
			res.disableCaching();
		}
		catch (Exception ex) {
			logger.warn("Unable to create XML document", ex);
			res.setResponseCode(res.servererror);
		}
		res.send();
	}

}

