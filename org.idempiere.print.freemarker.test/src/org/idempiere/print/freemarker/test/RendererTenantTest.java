package org.idempiere.print.freemarker.test;

import static org.idempiere.print.freemarker.test.TemplateTestSupport.PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.List;
import java.util.Properties;

import org.compiere.model.MClient;
import org.compiere.model.MQuery;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.PrintInfo;
import org.compiere.print.MPrintFormat;
import org.compiere.print.ReportEngine;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.idempiere.print.IReportContentRenderer;
import org.idempiere.print.ReportContentRequest;
import org.idempiere.print.freemarker.FreemarkerConfigurationFactory;
import org.idempiere.print.freemarker.FreemarkerReportRenderer;
import org.idempiere.print.freemarker.FreemarkerReportRendererConfiguration;
import org.idempiere.print.template.PrintTemplateContentRendererFactory;
import org.idempiere.print.template.model.MPrintTemplate;
import org.idempiere.test.AbstractTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The renderer takes the client from the request, not from the cached template's context.
 */
@Tag("integration")
public class RendererTenantTest extends AbstractTestCase {

	private final TemplateTestSupport support = new TemplateTestSupport();

	@BeforeEach
	void resetConfigurations() {
		FreemarkerConfigurationFactory.reset();
	}

	@AfterEach
	void cleanUp() {
		support.deleteCreated();
		FreemarkerConfigurationFactory.reset();
	}

	@Test
	void cachedSystemTemplateRendersWithRequestClient() throws Exception {
		String part = PREFIX + "part";
		support.create(0, part, "SYSTEM");
		support.create(11, part, "TENANT");
		MPrintTemplate created = support.create(0, PREFIX + "main", "<#include '" + part + "'>");

		// the template cache keeps the context of whoever loads it first: client 0
		Properties ctx0 = new Properties();
		ctx0.putAll(Env.getCtx());
		Env.setContext(ctx0, Env.AD_CLIENT_ID, 0);
		MPrintTemplate cached = MPrintTemplate.get(ctx0, created.getAD_PrintTemplate_ID());
		assertEquals(0, Env.getAD_Client_ID(cached.getCtx()));

		// request for a client 11 record
		MClient target = new MClient(Env.getCtx(), 11, null);
		StringWriter out = new StringWriter();
		new FreemarkerReportRenderer().renderReport(null,
				(FreemarkerReportRendererConfiguration) new FreemarkerReportRendererConfiguration(cached, target)
						.outputWriter(out));
		assertEquals("TENANT", out.toString());
	}

	@Test
	void configurationClientIsTheTargetRecordsClient() throws Exception {
		String part = PREFIX + "part";
		support.create(0, part, "SYSTEM");
		support.create(11, part, "TENANT");
		MPrintTemplate main = support.create(0, PREFIX + "main", "<#include '" + part + "'>");

		// a client 11 record read through a client 0 context (a system process printing it)
		Properties ctx0 = new Properties();
		ctx0.putAll(Env.getCtx());
		Env.setContext(ctx0, Env.AD_CLIENT_ID, 0);
		MClient target = new MClient(ctx0, 11, null);
		assertEquals(0, Env.getAD_Client_ID(target.getCtx()));

		StringWriter doc = new StringWriter();
		new FreemarkerReportRenderer().renderReport(null,
				(FreemarkerReportRendererConfiguration) new FreemarkerReportRendererConfiguration(main, target)
						.outputWriter(doc));
		assertEquals("TENANT", doc.toString());

		StringWriter list = new StringWriter();
		new FreemarkerReportRenderer().renderReport(null,
				(FreemarkerReportRendererConfiguration) new FreemarkerReportRendererConfiguration(main, target)
						.targetPOs(List.<PO>of(target)).outputWriter(list));
		assertEquals("TENANT", list.toString());
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

	@Test
	void printAllScopeReadsWithRequestClient() throws Exception {
		int tableId = MTable.getTable_ID("AD_PrintFormat");
		int systemFormat = DB.getSQLValueEx(null,
				"SELECT MIN(AD_PrintFormat_ID) FROM AD_PrintFormat WHERE AD_Client_ID=0 AND IsActive='Y'");
		int tenantFormat = DB.getSQLValueEx(null,
				"SELECT MIN(AD_PrintFormat_ID) FROM AD_PrintFormat WHERE AD_Client_ID=11 AND IsActive='Y'");
		assertTrue(systemFormat > 0 && tenantFormat > 0, "no print formats of client 0 and 11");
		MPrintTemplate created = support.create(0, PREFIX + "scope", "x", tableId);

		// the template cache is primed by a client 0 context
		Properties ctx0 = new Properties();
		ctx0.putAll(Env.getCtx());
		Env.setContext(ctx0, Env.AD_CLIENT_ID, 0);
		MPrintTemplate.get(ctx0, created.getAD_PrintTemplate_ID());

		// a client 11 request that prints "all" records of the scope: one of client 0, one of client 11
		Properties ctx11 = new Properties();
		ctx11.putAll(Env.getCtx());
		assertEquals(11, Env.getAD_Client_ID(ctx11));
		MPrintFormat format = new MPrintFormat(ctx11, tenantFormat, null);
		format.set_ValueNoCheck("AD_PrintTemplate_ID", created.getAD_PrintTemplate_ID());
		format.setIsForm(true);
		MQuery query = new MQuery(tableId);
		query.addRestriction("AD_PrintFormat_ID IN (" + systemFormat + "," + tenantFormat + ")");
		PrintInfo info = new PrintInfo("scope", tableId, 0);
		ReportEngine engine = new QueryOnlyEngine(ctx11, format, query, info);

		IReportContentRenderer renderer = new PrintTemplateContentRendererFactory()
				.createRenderer(new ReportContentRequest(engine, null, "scope"));
		assertNotNull(renderer);
		// read with client 11: the client 0 row is not in the scope
		assertEquals(1, renderer.getRowCount());
	}
}
