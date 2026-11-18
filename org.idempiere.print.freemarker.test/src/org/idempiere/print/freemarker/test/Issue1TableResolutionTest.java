package org.idempiere.print.freemarker.test;

import static org.idempiere.print.freemarker.test.TemplateTestSupport.PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.compiere.model.MQuery;
import org.compiere.model.MTable;
import org.compiere.model.PrintInfo;
import org.compiere.print.MPrintFormat;
import org.compiere.print.ReportEngine;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.idempiere.print.IReportContentRenderer;
import org.idempiere.print.ReportContentRequest;
import org.idempiere.print.freemarker.FreemarkerConfigurationFactory;
import org.idempiere.print.template.PrintTemplateContentRendererFactory;
import org.idempiere.print.template.model.MPrintTemplate;
import org.idempiere.test.AbstractTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * IDEMPIERE-7070 issue #1, problem 1: the data table is the print format's, not the caller window's.
 * A report started from a window toolbar carries that window's table and record in PrintInfo; when the
 * print format is on another table (e.g. a view) the factory used to compare the two and decline
 * silently, so core printed the classic layout. It must instead print the print format's table, scoped
 * by the report query, and honour the caller's record only when the caller prints that same table.
 */
@Tag("integration")
public class Issue1TableResolutionTest extends AbstractTestCase {

	private final TemplateTestSupport support = new TemplateTestSupport();

	@BeforeEach
	void reset() {
		FreemarkerConfigurationFactory.reset();
	}

	@AfterEach
	void cleanUp() {
		support.deleteCreated();
		FreemarkerConfigurationFactory.reset();
	}

	/** A report engine over a fixed query that loads no data (the factory only reads the query). */
	private static final class QueryOnlyEngine extends ReportEngine {
		private final MQuery query;

		QueryOnlyEngine(Properties ctx, MPrintFormat format, MQuery query, PrintInfo info) {
			super(ctx, format, null, info);
			this.query = query;
		}

		@Override
		public MQuery getQuery() {
			return query;
		}
	}

	private MPrintFormat formatOn(Properties ctx, int printFormatId, int templateId, int dataTableId, boolean form) {
		MPrintFormat format = new MPrintFormat(ctx, printFormatId, null);
		format.set_ValueNoCheck("AD_PrintTemplate_ID", templateId);
		// the data table is the print format's: put this reused format on the template's table
		format.set_ValueNoCheck("AD_Table_ID", dataTableId);
		format.setIsForm(form);
		return format;
	}

	/**
	 * Caller window is on another table (AD_Table) than the print format (AD_PrintFormat): the template
	 * must still render, from the report's scope, not decline.
	 */
	@Test
	void callerOnAnotherTableUsesThePrintFormatTableAndReportScope() throws Exception {
		int dataTableId = MTable.getTable_ID("AD_PrintFormat");
		int callerTableId = MTable.getTable_ID("AD_Table");
		int systemFormat = DB.getSQLValueEx(null,
				"SELECT MIN(AD_PrintFormat_ID) FROM AD_PrintFormat WHERE AD_Client_ID=0 AND IsActive='Y'");
		int tenantFormat = DB.getSQLValueEx(null,
				"SELECT MIN(AD_PrintFormat_ID) FROM AD_PrintFormat WHERE AD_Client_ID=11 AND IsActive='Y'");
		assertTrue(systemFormat > 0 && tenantFormat > 0, "no print formats of client 0 and 11");
		MPrintTemplate created = support.create(0, PREFIX + "p1", "x", dataTableId);

		Properties ctx11 = new Properties();
		ctx11.putAll(Env.getCtx());
		assertEquals(11, Env.getAD_Client_ID(ctx11));
		MPrintFormat format = formatOn(ctx11, tenantFormat, created.getAD_PrintTemplate_ID(), dataTableId, true);
		MQuery query = new MQuery(dataTableId);
		query.addRestriction("AD_PrintFormat_ID IN (" + systemFormat + "," + tenantFormat + ")");
		// caller is a window on AD_Table with its own (foreign) record id — must be ignored
		PrintInfo info = new PrintInfo("p1", callerTableId, systemFormat);
		ReportEngine engine = new QueryOnlyEngine(ctx11, format, query, info);

		IReportContentRenderer renderer = new PrintTemplateContentRendererFactory()
				.createRenderer(new ReportContentRequest(engine, null, "p1"));
		assertNotNull(renderer, "must not decline when the caller table differs from the print format's");
		// rows come from the report query (client 11 sees only its own row), not from the caller record
		assertEquals(1, renderer.getRowCount());
	}

	/**
	 * Caller window is on the same table as the print format: the caller's record is honoured (the
	 * ordinary document case must keep working).
	 */
	@Test
	void callerOnSameTableHonoursItsRecord() throws Exception {
		int dataTableId = MTable.getTable_ID("AD_PrintFormat");
		int tenantFormat = DB.getSQLValueEx(null,
				"SELECT MIN(AD_PrintFormat_ID) FROM AD_PrintFormat WHERE AD_Client_ID=11 AND IsActive='Y'");
		assertTrue(tenantFormat > 0, "no print format of client 11");
		MPrintTemplate created = support.create(0, PREFIX + "p1same", "x", dataTableId);

		Properties ctx11 = new Properties();
		ctx11.putAll(Env.getCtx());
		MPrintFormat format = formatOn(ctx11, tenantFormat, created.getAD_PrintTemplate_ID(), dataTableId, true);
		MQuery query = new MQuery(dataTableId);
		query.addRestriction("1=1");
		// caller is a window on the SAME table (AD_PrintFormat), printing one record
		PrintInfo info = new PrintInfo("p1same", dataTableId, tenantFormat);
		ReportEngine engine = new QueryOnlyEngine(ctx11, format, query, info);

		IReportContentRenderer renderer = new PrintTemplateContentRendererFactory()
				.createRenderer(new ReportContentRequest(engine, null, "p1same"));
		assertNotNull(renderer);
		// exactly the caller's record, not the whole scope
		assertEquals(1, renderer.getRowCount());
	}
}
