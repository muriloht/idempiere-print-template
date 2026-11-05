package org.idempiere.print.freemarker.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringWriter;
import java.util.Map;

import org.idempiere.print.freemarker.FreemarkerConfigurationFactory;
import org.idempiere.test.AbstractTestCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import freemarker.template.Configuration;
import freemarker.template.Template;

/**
 * Smoke test: the plugin's FreeMarker configuration renders a template and the embedded
 * FreeMarker jar is the expected version.
 */
@Tag("integration")
public class FreemarkerSmokeTest extends AbstractTestCase {

	@Test
	void rendersVariable() throws Exception {
		Template t = new Template("smoke", "Hello ${name}", FreemarkerConfigurationFactory.get(getAD_Client_ID()));
		StringWriter w = new StringWriter();
		t.process(Map.of("name", "World"), w);
		assertEquals("Hello World", w.toString());
	}

	@Test
	void runsOnFreemarker2334() {
		assertEquals("2.3.34", Configuration.getVersion().toString());
	}
}
