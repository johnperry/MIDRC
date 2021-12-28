<?xml version="1.0" encoding="iso-8859-1"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
<xsl:output method="xml" encoding="utf-8" omit-xml-declaration="yes" />

<xsl:param name="context"/>
<xsl:param name="name"/>
<xsl:param name="suppress"/>

<xsl:template match="/Patients">
	<html>
		<head>
			<title>Indexed Buffer (<xsl:value-of select="$context"/>)</title>
			<link rel="Stylesheet" type="text/css" media="all" href="/BaseStyles.css"></link>
			<script language="JavaScript" type="text/javascript" src="/JSUtil.js">;</script>
			<script language="JavaScript" type="text/javascript" src="/IndexedBufferServlet.js">;</script>
			<style>
				body {height: 100%; overflow-y: hidden;}
				div.scroll {overflow-y: auto;}
				h1 { padding-top: 10px; padding-bottom: 0px; margin-top: 0x; margin-bottom: 0px; text-align: center; }
				h2 { padding-top: 0px; padding-bottom: 0px; }
				table { margin-left: auto; margin-right: auto; }
				table.summary td {font-family: Helvetica, Arial, Sans-Serif; padding-left: 10px; padding-right: 10px; }
				table.summary td.datarow { background-color: white; }
				table.summary td.buttonrow { padding-top: 5px; text-align: center; }
				table.summary td.comment { padding: 0px; }
				table.summary input.comment { font-size: 12pt; }
				th { padding-left: 10px; padding-right: 10px; font-family: Helvetica, Arial, Sans-Serif; font-weight: bold; }
				td.data { padding-left: 10px; padding-right: 10px; background-color: white; }
				td.right { padding-left: 10px; padding-right: 10px; background-color: white; text-align: right; width: 50px; }
				td.center { padding-left: 10px; padding-right: 10px; text-align: center; }
				input.export { padding-left: 10px; padding-right: 10px; font-weight: bold; font-size: 12pt; }
			</style>
		</head>
		<body>
			<h1><xsl:value-of select="$name"/> - (<xsl:value-of select="$context"/>)</h1>
			<div>
				<table class="summary">
					<tr><td class="datarow">Selected patients</td><td class="right" id="nptCell"/></tr>
					<tr><td class="datarow">Selected studies</td><td class="right" id="nstCell"/></tr>
					<tr><td class="datarow">Selected images</td><td class="right" id="nimCell"/></tr>
					<tr>
						<td class="datarow">Export comment</td>
						<td class="comment">
							<input class="comment" type="text" id="commentCell"/>
						</td>
					</tr>
					<tr>
						<td class="buttonrow" colspan="2">
							<input class="export" type="button" value="Export" onclick="exportImages(event)"/>
							<xsl:text>&#160;&#160;&#160;</xsl:text>
							<input class="export" type="button" value="Reset Failures" onclick="resetFailures(event)"/>
						</td>
					</tr>
				</table>
				<br/>
			</div>
			<div class="scroll" id="datatable">
			<center>
				<xsl:choose>
					<xsl:when test="Patient">
						<table>
							<tr>
								<th><input type="checkbox" onclick="selectAll(event)"/></th>
								<th>PatientID</th>
								<th>LastModified</th>
								<th>Modality</th>
								<th>StudyDate</th>
								<th>NImages</th>
							</tr>
							<xsl:apply-templates select="Patient"/>
						</table>
					</xsl:when>
					<xsl:otherwise>
						<h2>There are no unqueued patients in the buffer.</h2>
					</xsl:otherwise>
				</xsl:choose>
			</center>
			</div>
		</body>
	</html>
</xsl:template>

<xsl:template match="Patient">
	<tr>
		<td class="center">
			<input type="checkbox" onclick="selectRange(event)" id="{position()}" 
				name="{@patientID}:{count(Study)}:{sum(Study/@nImages)}"/>
		</td>
		<td class="data"><xsl:value-of select="@patientID"/></td>
		<td class="data"><xsl:value-of select="@lastModifiedTime"/></td>
	</tr>
	<xsl:apply-templates select="Study"/>
</xsl:template>

<xsl:template match="Study">
	<tr>
		<td/><td/><td/>
		<td class="data"><xsl:value-of select="@modality"/></td>
		<td class="data"><xsl:value-of select="@studyDate"/></td>
		<td class="right"><xsl:value-of select="@nImages"/></td>
	</tr>
</xsl:template>

</xsl:stylesheet>
