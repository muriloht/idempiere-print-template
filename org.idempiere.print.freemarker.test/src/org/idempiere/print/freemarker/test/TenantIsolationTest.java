package org.idempiere.print.freemarker.test;

import static org.idempiere.print.freemarker.test.TemplateTestSupport.PREFIX;
import static org.idempiere.print.freemarker.test.TemplateTestSupport.render;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.idempiere.print.freemarker.FreemarkerConfigurationFactory;
import org.idempiere.print.template.model.MPrintTemplate;
import org.idempiere.test.AbstractTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * One FreeMarker configuration per client; the include loader is bound to that client and does not
 * depend on the thread's context.
 */
@Tag("integration")
public class TenantIsolationTest extends AbstractTestCase {

	private static final int TENANT = 11;

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
	void includeResolvesPerClient() throws Exception {
		String part = PREFIX + "part";
		support.create(0, part, "SYSTEM");
		support.create(TENANT, part, "TENANT");
		String include = "<#include '" + part + "'>";
		assertEquals("TENANT", render(TENANT, include));
		assertEquals("SYSTEM", render(0, include));
		assertEquals("TENANT", render(TENANT, include));
	}

	@Test
	void systemOnlyPartIsFoundByTenant() throws Exception {
		String part = PREFIX + "sys";
		support.create(0, part, "SYSTEM");
		assertEquals("SYSTEM", render(TENANT, "<#include '" + part + "'>"));
	}

	@Test
	void editIsSeenAfterUpdateDelay() throws Exception {
		String part = PREFIX + "edit";
		support.create(0, part, "SYSTEM");
		MPrintTemplate tenant = support.create(TENANT, part, "ONE");
		String include = "<#include '" + part + "'>";
		assertEquals("ONE", render(TENANT, include));
		tenant.setTemplateBody("TWO");
		TemplateTestSupport.save(tenant);
		Thread.sleep(5500);
		assertEquals("TWO", render(TENANT, include));
		assertEquals("SYSTEM", render(0, include));
	}

	@Test
	void distinctConfigurationsPerClient() {
		assertNotSame(FreemarkerConfigurationFactory.get(0), FreemarkerConfigurationFactory.get(TENANT));
	}
}
