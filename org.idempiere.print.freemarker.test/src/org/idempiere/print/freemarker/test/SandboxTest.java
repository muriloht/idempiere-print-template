package org.idempiere.print.freemarker.test;

import static org.idempiere.print.freemarker.test.TemplateTestSupport.render;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.idempiere.print.freemarker.FreemarkerConfigurationFactory;
import org.idempiere.test.AbstractTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import freemarker.core.TemplateClassResolver;
import freemarker.template.TemplateException;

/** The default (strict) sandbox: no ?new, no ?api, ?eval_json still works. */
@Tag("integration")
public class SandboxTest extends AbstractTestCase {

	private static final int TENANT = 11;

	private final TemplateTestSupport support = new TemplateTestSupport();

	@BeforeEach
	void resetConfigurations() {
		FreemarkerConfigurationFactory.reset();
	}

	@AfterEach
	void restore() {
		support.deleteSysConfigs();
	}

	@Test
	void strictDefaultConfiguration() {
		assertSame(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER,
				FreemarkerConfigurationFactory.get(TENANT).getNewBuiltinClassResolver());
		assertFalse(FreemarkerConfigurationFactory.get(TENANT).isAPIBuiltinEnabled());
	}

	@Test
	void newBuiltinOfHarmlessClassIsBlockedWhenStrict() {
		assertThrows(TemplateException.class,
				() -> render(TENANT, "${'freemarker.template.SimpleScalar'?new('x')}"));
	}

	@Test
	void newBuiltinOfHarmlessClassWorksWhenStrictIsOff() throws Exception {
		// strict mode is a system setting (client 0)
		support.setSysConfig(0, FreemarkerConfigurationFactory.SYSCONFIG_STRICT, "N");
		assertEquals("x", render(TENANT, "${'freemarker.template.SimpleScalar'?new('x')}"));
	}

	@Test
	void tenantCannotTurnStrictOff() {
		support.setSysConfig(TENANT, FreemarkerConfigurationFactory.SYSCONFIG_STRICT, "N");
		assertSame(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER,
				FreemarkerConfigurationFactory.get(TENANT).getNewBuiltinClassResolver());
		assertThrows(TemplateException.class,
				() -> render(TENANT, "${'freemarker.template.SimpleScalar'?new('x')}"));
	}

	@Test
	void newBuiltinIsBlocked() {
		assertThrows(TemplateException.class,
				() -> render(TENANT, "${'freemarker.template.utility.Execute'?new()('id')}"));
	}

	@Test
	void apiBuiltinIsBlocked() {
		TemplateException e = assertThrows(TemplateException.class,
				() -> render(TENANT, "${map?api.size()}", Map.of("map", new HashMap<>())));
		assertTrue(e.getMessage().contains("api"), e.getMessage());
	}

	@Test
	void evalJsonStillWorks() throws Exception {
		assertEquals("3", render(TENANT, "${'{\"a\":3}'?eval_json.a}"));
	}
}
