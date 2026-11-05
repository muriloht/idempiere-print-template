package org.idempiere.print.freemarker.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.adempiere.base.Core;
import org.compiere.model.MOrder;
import org.compiere.model.MQuery;
import org.compiere.model.PrintInfo;
import org.compiere.print.MPrintFormat;
import org.compiere.print.ReportEngine;
import org.compiere.process.ProcessInfo;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.idempiere.print.ReportContentRequest;
import org.idempiere.print.template.PrintTemplateContentRendererFactory;
import org.idempiere.test.AbstractTestCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A print format without a print template still renders through the standard report engine: the
 * FreeMarker factory declines, Core falls back, and the result is a PDF. Nothing is changed in the
 * database. Print format 118 is linked to a test template on purpose and is not used here.
 */
@Tag("integration")
public class FallbackRenderTest extends AbstractTestCase {

	@Test
	void formatWithoutTemplateRendersWithTheStandardEngine() throws Exception {
		// a GardenWorld print format over a table with C_Order_ID and no template (100 first)
		int formatId = DB.getSQLValueEx(null,
				"SELECT pf.AD_PrintFormat_ID FROM AD_PrintFormat pf WHERE pf.IsActive='Y'"
						+ " AND COALESCE(pf.AD_PrintTemplate_ID,0)=0 AND pf.AD_PrintFormat_ID<>118"
						+ " AND pf.AD_Client_ID IN (0,11) AND pf.JasperProcess_ID IS NULL AND pf.IsForm='Y'"
						+ " AND EXISTS (SELECT 1 FROM AD_Column c WHERE c.AD_Table_ID=pf.AD_Table_ID AND c.ColumnName='C_Order_ID')"
						+ " ORDER BY CASE WHEN pf.AD_PrintFormat_ID=100 THEN 0 ELSE 1 END, pf.AD_PrintFormat_ID");
		assertTrue(formatId > 0, "No GardenWorld order print format without a template");

		MOrder order = new MOrder(Env.getCtx(), 102, getTrxName());
		assertEquals(102, order.get_ID(), "Order 102 not found");
		MPrintFormat format = MPrintFormat.get(Env.getCtx(), formatId, false);
		assertNotNull(format);
		assertEquals(0, format.get_ValueAsInt("AD_PrintTemplate_ID"));

		// built as ServerReportCtl does, but with this format's own table and a query on the order
		MQuery query = new MQuery(format.getAD_Table_ID());
		query.addRestriction("C_Order_ID", MQuery.EQUAL, order.get_ID());
		PrintInfo info = new PrintInfo("Fallback render", format.getAD_Table_ID(), order.get_ID());
		ReportEngine engine = new ReportEngine(Env.getCtx(), format, query, info);
		ProcessInfo pi = new ProcessInfo("Fallback render", 0);
		pi.setRecord_ID(order.get_ID());
		pi.setTable_ID(order.get_Table_ID());
		pi.setIsBatch(true);
		engine.setProcessInfo(pi);
		ReportContentRequest request = new ReportContentRequest(engine, pi, "Fallback render");

		// the FreeMarker factory does not take over
		assertNull(new PrintTemplateContentRendererFactory().createRenderer(request));
		// core finds a renderer (the standard engine) and produces a PDF
		assertNotNull(Core.getReportContentRenderer(request));
		File pdf = Core.getReportContent(request, "application/pdf", "pdf");
		assertNotNull(pdf, "No PDF");
		assertTrue(pdf.exists() && pdf.length() > 0, "Empty PDF");
		byte[] head = new byte[4];
		try (java.io.FileInputStream in = new java.io.FileInputStream(pdf)) {
			assertEquals(4, in.read(head));
		}
		assertEquals("%PDF", new String(head, java.nio.charset.StandardCharsets.US_ASCII));
	}
}
