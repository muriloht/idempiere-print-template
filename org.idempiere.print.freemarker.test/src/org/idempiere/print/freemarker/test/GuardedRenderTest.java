package org.idempiere.print.freemarker.test;

import static org.idempiere.print.freemarker.test.TemplateTestSupport.PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.ServerContext;
import org.compiere.model.MClient;
import org.compiere.util.Env;
import org.idempiere.print.freemarker.FreemarkerConfigurationFactory;
import org.idempiere.print.freemarker.GuardedRender;
import org.idempiere.print.template.TemplateRenderDispatcher;
import org.idempiere.print.template.model.MPrintTemplate;
import org.idempiere.test.AbstractTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import freemarker.template.Template;

/** Output cap and time limit of {@link GuardedRender}. */
@Tag("integration")
public class GuardedRenderTest extends AbstractTestCase {

	private static final int TENANT = 11;

	private final TemplateTestSupport support = new TemplateTestSupport();

	@BeforeEach
	void resetConfigurations() {
		FreemarkerConfigurationFactory.reset();
		GuardedRender.reset();
	}

	/** @return true when no render worker thread is alive after at most 5 s */
	private static boolean workersEnd() throws InterruptedException {
		// shutting the pool down ends idle threads at once; a worker still running its template stays
		GuardedRender.reset();
		long end = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < end) {
			boolean alive = Thread.getAllStackTraces().keySet().stream()
					.anyMatch(t -> t.isAlive() && t.getName().startsWith("prt-freemarker-render-"));
			if (!alive)
				return true;
			Thread.sleep(50);
		}
		return false;
	}

	@AfterEach
	void cleanUp() {
		support.deleteCreated();
		support.deleteSysConfigs();
	}

	private static String guarded(int adClientId, String body, Map<String, Object> model) throws Exception {
		Template t = new Template("t", body, FreemarkerConfigurationFactory.get(adClientId));
		StringWriter w = new StringWriter();
		GuardedRender.process(t, model, w, adClientId);
		return w.toString();
	}

	private static String guarded(int adClientId, String body) throws Exception {
		return guarded(adClientId, body, Map.of());
	}

	@Test
	void outputCapStopsRunawayLoop() {
		support.setSysConfig(TENANT, "PRT_FREEMARKER_MAX_OUTPUT_KB", "1");
		AdempiereException e = assertThrows(AdempiereException.class,
				() -> guarded(TENANT, "<#list 1..100000 as i>xxxxxxxxxx</#list>"));
		assertEquals("Template output exceeds PRT_FREEMARKER_MAX_OUTPUT_KB=1", e.getMessage());
	}

	@Test
	void outputExactlyAtCapPasses() throws Exception {
		support.setSysConfig(TENANT, "PRT_FREEMARKER_MAX_OUTPUT_KB", "1");
		assertEquals(1024, guarded(TENANT, "<#list 1..1024 as i>x</#list>").length());
	}

	@Test
	void timeLimitStopsSilentLoop() {
		support.setSysConfig(TENANT, "PRT_FREEMARKER_TIMEOUT_SECONDS", "1");
		long t0 = System.currentTimeMillis();
		AdempiereException e = assertThrows(AdempiereException.class,
				() -> guarded(TENANT, "<#list 1..2000000000 as i></#list>"));
		assertTrue(System.currentTimeMillis() - t0 < 5000);
		assertEquals("Template render exceeded PRT_FREEMARKER_TIMEOUT_SECONDS=1", e.getMessage());
	}

	@Test
	void workerSeesContextOfCaller() throws Exception {
		Properties ctx = new Properties();
		ctx.putAll(Env.getCtx());
		ctx.setProperty("#GuardedRenderTest", "from-caller");
		Template t = new Template("t", "${probe()}", FreemarkerConfigurationFactory.get(TENANT));
		Map<String, Object> model = Map.of("probe",
				(freemarker.template.TemplateMethodModelEx) args -> Env.getCtx().getProperty("#GuardedRenderTest"));
		StringWriter w = new StringWriter();
		GuardedRender.process(t, model, w, TENANT, ctx);
		assertEquals("from-caller", w.toString());
		// the worker does not leave its context behind, and the caller's is untouched
		assertTrue(ServerContext.getCurrentInstance().getProperty("#GuardedRenderTest") == null);
	}

	@Test
	void pdfIsCappedBeforeConversion() throws Exception {
		support.setSysConfig(TENANT, "PRT_FREEMARKER_MAX_OUTPUT_KB", "1");
		MPrintTemplate pt = support.create(TENANT, PREFIX + "pdfcap",
				"<html><body><#list 1..100000 as i>xxxxxxxxxx</#list></body></html>");
		MClient target = new MClient(Env.getCtx(), TENANT, null);
		File out = File.createTempFile("cld_test_", ".pdf");
		try {
			AdempiereException e = assertThrows(AdempiereException.class,
					() -> TemplateRenderDispatcher.render(pt, target, out, "pdf"));
			assertEquals("Template output exceeds PRT_FREEMARKER_MAX_OUTPUT_KB=1", e.getMessage());
		} finally {
			out.delete();
		}
	}

	@Test
	void thousandRowListFitsDefaults() throws Exception {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("C_BPartner_ID", i);
			row.put("Name", "Partner " + i);
			row.put("Value", "BP" + i);
			rows.add(row);
		}
		String out = guarded(TENANT,
				"<#list rows as r><tr><td>${r.C_BPartner_ID?c}</td><td>${r.Name}</td><td>${r.Value}</td></tr></#list>",
				Map.of("rows", rows));
		assertTrue(out.contains("Partner 999"));
	}

	@Test
	void tenantTimeoutCannotExceedSystemValue() {
		support.setSysConfig(0, "PRT_FREEMARKER_TIMEOUT_SECONDS", "1");
		support.setSysConfig(TENANT, "PRT_FREEMARKER_TIMEOUT_SECONDS", "999");
		AdempiereException e = assertTimeoutPreemptively(Duration.ofSeconds(10),
				() -> assertThrows(AdempiereException.class,
						() -> guarded(TENANT, "<#list 1..2000000000 as i></#list>")));
		assertEquals("Template render exceeded PRT_FREEMARKER_TIMEOUT_SECONDS=1", e.getMessage());
	}

	@Test
	void tenantOutputCapCannotExceedSystemValue() {
		support.setSysConfig(0, "PRT_FREEMARKER_MAX_OUTPUT_KB", "1");
		support.setSysConfig(TENANT, "PRT_FREEMARKER_MAX_OUTPUT_KB", "999");
		AdempiereException e = assertThrows(AdempiereException.class,
				() -> guarded(TENANT, "<#list 1..2048 as i>x</#list>"));
		assertEquals("Template output exceeds PRT_FREEMARKER_MAX_OUTPUT_KB=1", e.getMessage());
	}

	@Test
	void lowerTenantValueTightensSystemValue() {
		support.setSysConfig(0, "PRT_FREEMARKER_TIMEOUT_SECONDS", "30");
		support.setSysConfig(TENANT, "PRT_FREEMARKER_TIMEOUT_SECONDS", "1");
		support.setSysConfig(0, "PRT_FREEMARKER_MAX_OUTPUT_KB", "4");
		support.setSysConfig(TENANT, "PRT_FREEMARKER_MAX_OUTPUT_KB", "1");
		AdempiereException e = assertThrows(AdempiereException.class,
				() -> guarded(TENANT, "<#list 1..2048 as i>x</#list>"));
		assertEquals("Template output exceeds PRT_FREEMARKER_MAX_OUTPUT_KB=1", e.getMessage());
		long t0 = System.currentTimeMillis();
		e = assertThrows(AdempiereException.class, () -> guarded(TENANT, "<#list 1..2000000000 as i></#list>"));
		assertTrue(System.currentTimeMillis() - t0 < 5000);
		assertEquals("Template render exceeded PRT_FREEMARKER_TIMEOUT_SECONDS=1", e.getMessage());
	}

	@Test
	void nonStrictHasNoCap() throws Exception {
		// strict mode is a system setting (client 0)
		support.setSysConfig(0, "PRT_FREEMARKER_STRICT", "N");
		support.setSysConfig(TENANT, "PRT_FREEMARKER_MAX_OUTPUT_KB", "1");
		assertEquals(100 * 1024, guarded(TENANT, "<#list 1..10240 as i>xxxxxxxxxx</#list>").length());
	}

	@Test
	void strictnessComesFromTheTemplatesConfiguration() throws Exception {
		// a template of a strict configuration stays guarded after STRICT is turned off
		Template strict = new Template("t", "<#list 1..10240 as i>xxxxxxxxxx</#list>",
				FreemarkerConfigurationFactory.get(TENANT));
		support.setSysConfig(0, "PRT_FREEMARKER_STRICT", "N");
		support.setSysConfig(TENANT, "PRT_FREEMARKER_MAX_OUTPUT_KB", "1");
		AdempiereException e = assertThrows(AdempiereException.class,
				() -> GuardedRender.process(strict, Map.of(), new StringWriter(), TENANT));
		assertEquals("Template output exceeds PRT_FREEMARKER_MAX_OUTPUT_KB=1", e.getMessage());
		// and a template of a non-strict configuration is not, whatever STRICT says now
		Template loose = new Template("t", "<#list 1..10240 as i>xxxxxxxxxx</#list>",
				FreemarkerConfigurationFactory.get(TENANT));
		support.deleteSysConfigs();
		support.setSysConfig(TENANT, "PRT_FREEMARKER_MAX_OUTPUT_KB", "1");
		StringWriter w = new StringWriter();
		GuardedRender.process(loose, Map.of(), w, TENANT);
		assertEquals(100 * 1024, w.toString().length());
	}

	@Test
	void silentLoopWorkerEndsAfterTimeout() throws Exception {
		support.setSysConfig(TENANT, "PRT_FREEMARKER_TIMEOUT_SECONDS", "1");
		assertThrows(AdempiereException.class, () -> guarded(TENANT, "<#list 1..2000000000 as i></#list>"));
		assertTrue(workersEnd(), "worker still running");
	}

	@Test
	void includedSilentLoopWorkerEndsAfterTimeout() throws Exception {
		support.setSysConfig(TENANT, "PRT_FREEMARKER_TIMEOUT_SECONDS", "1");
		support.create(TENANT, PREFIX + "loop", "<#list 1..2000000000 as i></#list>");
		AdempiereException e = assertThrows(AdempiereException.class,
				() -> guarded(TENANT, "<#include '" + PREFIX + "loop'>"));
		assertEquals("Template render exceeded PRT_FREEMARKER_TIMEOUT_SECONDS=1", e.getMessage());
		assertTrue(workersEnd(), "worker still running");
	}

	@Test
	void assignCaptureLoopEndsWithTimeout() throws Exception {
		support.setSysConfig(TENANT, "PRT_FREEMARKER_TIMEOUT_SECONDS", "1");
		AdempiereException e = assertThrows(AdempiereException.class,
				() -> guarded(TENANT, "<#assign b><#list 1..2000000000 as i>x</#list></#assign>"));
		assertEquals("Template render exceeded PRT_FREEMARKER_TIMEOUT_SECONDS=1", e.getMessage());
		assertTrue(workersEnd(), "worker still running");
	}

	@Test
	void renderWaitsForASlotWithinItsTimeLimit() throws Exception {
		support.setSysConfig(0, "PRT_FREEMARKER_MAX_CONCURRENT_RENDERS", "1");
		// the first render (system client) holds the only slot for 4 s; the tenant's limit is 1 s
		support.setSysConfig(0, "PRT_FREEMARKER_TIMEOUT_SECONDS", "4");
		support.setSysConfig(TENANT, "PRT_FREEMARKER_TIMEOUT_SECONDS", "1");
		Thread first = new Thread(() -> {
			try {
				guarded(0, "<#list 1..2000000000 as i></#list>");
			} catch (Exception e) {
				// expected: the timeout
			}
		});
		first.start();
		Thread.sleep(500);
		long t0 = System.currentTimeMillis();
		AdempiereException e = assertThrows(AdempiereException.class, () -> guarded(TENANT, "x"));
		long waited = System.currentTimeMillis() - t0;
		assertEquals("Too many template renders running (PRT_FREEMARKER_MAX_CONCURRENT_RENDERS=1)",
				e.getMessage());
		assertTrue(waited >= 900 && waited < 3000, "waited " + waited + " ms instead of about 1 s");
		first.join(10000);
		assertTrue(!first.isAlive(), "first render still running");
		// the slot is free again once the first worker ended
		assertEquals("x", guarded(TENANT, "x"));
		assertTrue(workersEnd(), "worker still running");
	}

	@Test
	void patchingTheSameTemplateTwiceIsANoOp() throws Exception {
		Template t = new Template("t", "<#list 1..3 as i>${i}</#list>", FreemarkerConfigurationFactory.get(TENANT));
		assertTrue(GuardedRender.makeInterruptible(t));
		assertTrue(!GuardedRender.makeInterruptible(t));
		// rendering through GuardedRender (which patches again) still works
		StringWriter w = new StringWriter();
		GuardedRender.process(t, Map.of(), w, TENANT);
		assertEquals("123", w.toString());
	}
}
