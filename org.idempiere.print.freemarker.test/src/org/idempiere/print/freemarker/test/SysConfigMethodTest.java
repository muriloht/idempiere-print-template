package org.idempiere.print.freemarker.test;

import static org.idempiere.print.freemarker.test.TemplateTestSupport.PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringWriter;

import org.compiere.model.MClient;
import org.compiere.util.Env;
import org.idempiere.print.freemarker.FreemarkerConfigurationFactory;
import org.idempiere.print.freemarker.FreemarkerReportRenderer;
import org.idempiere.print.freemarker.FreemarkerReportRendererConfiguration;
import org.idempiere.print.template.model.MPrintTemplate;
import org.idempiere.test.AbstractTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * IDEMPIERE-7070 issue #1, problem 4: templates can read AD_SysConfig values through
 * {@code ${sysconfig("Key"[, "default"])}}, the equivalent of a classic {@code @$sysconfig.Name@}.
 */
@Tag("integration")
public class SysConfigMethodTest extends AbstractTestCase {

	private static final String KEY = "CLD_TEST_SYSCONFIG_VALUE";

	private final TemplateTestSupport support = new TemplateTestSupport();

	@BeforeEach
	void reset() {
		FreemarkerConfigurationFactory.reset();
	}

	@AfterEach
	void cleanUp() {
		support.deleteCreated();
		support.deleteSysConfigs();
		FreemarkerConfigurationFactory.reset();
	}

	private String render(MPrintTemplate template) throws Exception {
		MClient target = new MClient(Env.getCtx(), Env.getAD_Client_ID(Env.getCtx()), null);
		StringWriter out = new StringWriter();
		new FreemarkerReportRenderer().renderReport(null,
				(FreemarkerReportRendererConfiguration) new FreemarkerReportRendererConfiguration(template, target)
						.outputWriter(out));
		return out.toString();
	}

	@Test
	void valueIsResolvedForTheContextClient() throws Exception {
		support.setSysConfig(Env.getAD_Client_ID(Env.getCtx()), KEY, "ACME");
		MPrintTemplate template = support.create(0, PREFIX + "sysconf", "${sysconfig(\"" + KEY + "\")}");
		assertEquals("ACME", render(template));
	}

	@Test
	void defaultIsUsedWhenTheKeyIsNotSet() throws Exception {
		MPrintTemplate template = support.create(0, PREFIX + "sysconf_def",
				"${sysconfig(\"CLD_TEST_MISSING_KEY\", \"fallback\")}");
		assertEquals("fallback", render(template));
	}

	@Test
	void emptyStringWhenTheKeyIsNotSetAndNoDefault() throws Exception {
		MPrintTemplate template = support.create(0, PREFIX + "sysconf_empty",
				"[${sysconfig(\"CLD_TEST_MISSING_KEY\")}]");
		assertEquals("[]", render(template));
	}
}
