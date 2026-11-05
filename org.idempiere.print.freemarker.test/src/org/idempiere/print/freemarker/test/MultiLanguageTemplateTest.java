/**********************************************************************
* This file is part of the iDempiere Print Template Engine plugin.    *
*                                                                     *
* This program is free software; you can redistribute it and/or       *
* modify it under the terms of the GNU General Public License         *
* as published by the Free Software Foundation; either version 2      *
* of the License, or (at your option) any later version.              *
*                                                                     *
* This program is distributed in the hope that it will be useful,     *
* but WITHOUT ANY WARRANTY; without even the implied warranty of      *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
* GNU General Public License for more details.                        *
**********************************************************************/
package org.idempiere.print.freemarker.test;

import static org.idempiere.print.freemarker.test.TemplateTestSupport.PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;

import org.compiere.model.MColumn;
import org.compiere.model.MOrder;
import org.compiere.model.MPaymentTerm;
import org.compiere.model.MSysConfig;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Language;
import org.compiere.util.Msg;
import org.idempiere.print.freemarker.FreemarkerConfigurationFactory;
import org.idempiere.print.freemarker.FreemarkerReportRenderer;
import org.idempiere.print.freemarker.FreemarkerReportRendererConfiguration;
import org.idempiere.print.freemarker.GuardedRender;
import org.idempiere.print.freemarker.TranslateMethod;
import org.idempiere.print.template.PO2MapBuilder;
import org.idempiere.print.template.model.MPrintTemplate;
import org.idempiere.test.AbstractTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import freemarker.core.HTMLOutputFormat;
import freemarker.template.Template;

/**
 * One template in several languages: Hungarian labels, formats and content, as
 * {@code examples/C_Order_multilang.ftlh} prints them. The formats need no hu_HU in the database (the
 * language falls back to the hu_HU locale); the tests on translated texts skip themselves when the test
 * database has no hu_HU translation.
 */
@Tag("integration")
public class MultiLanguageTemplateTest extends AbstractTestCase {

	private static final int GARDEN_WORLD = 11;
	private static final String HU = "hu_HU";
	private static final String EXAMPLE = "org.idempiere.print.freemarker/examples/C_Order_multilang.ftlh";

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

	private static Language hungarian() {
		Language hu = Language.getLanguage(HU);
		assertEquals(HU, hu.getAD_Language());
		return hu;
	}

	private static String render(String body, Map<String, Object> model, Language language) throws Exception {
		Template t = new Template("t", body, FreemarkerConfigurationFactory.get(GARDEN_WORLD));
		StringWriter out = new StringWriter();
		GuardedRender.process(t, model, out, GARDEN_WORLD, Env.getCtx(), language);
		return out.toString();
	}

	/** A text as the HTML output format prints it. */
	private static String html(String text) throws Exception {
		return HTMLOutputFormat.INSTANCE.escapePlainText(text);
	}

	private static boolean hasHungarianElements() {
		return DB.getSQLValueEx(null, "SELECT COUNT(*) FROM AD_Element_Trl t JOIN AD_Element e"
				+ " ON (e.AD_Element_ID=t.AD_Element_ID) WHERE t.AD_Language=? AND e.ColumnName='DocumentNo'"
				+ " AND t.PrintName<>e.PrintName", HU) > 0;
	}

	private PO orderWithProductLines() {
		// an order with product lines, one whose product has a Hungarian name when there is one
		PO order = new Query(Env.getCtx(), "C_Order", "AD_Client_ID=? AND DocStatus IS NOT NULL AND EXISTS"
				+ " (SELECT 1 FROM C_OrderLine l WHERE l.C_Order_ID=C_Order.C_Order_ID AND l.M_Product_ID>0)",
				getTrxName()).setParameters(GARDEN_WORLD)
				.setOrderBy("CASE WHEN EXISTS (SELECT 1 FROM C_OrderLine l JOIN M_Product_Trl t"
						+ " ON (t.M_Product_ID=l.M_Product_ID AND t.AD_Language='" + HU + "')"
						+ " JOIN M_Product p ON (p.M_Product_ID=t.M_Product_ID AND t.Name<>p.Name)"
						+ " WHERE l.C_Order_ID=C_Order.C_Order_ID) THEN 0 ELSE 1 END, C_Order_ID").first();
		assertNotNull(order, "no GardenWorld order with product lines");
		return order;
	}

	private String renderDocument(MPrintTemplate template, PO order, Language language) throws Exception {
		StringWriter out = new StringWriter();
		new FreemarkerReportRenderer().renderReport(null,
				(FreemarkerReportRendererConfiguration) new FreemarkerReportRendererConfiguration(template, order)
						.language(language).outputWriter(out));
		return out.toString();
	}

	private static String example() throws Exception {
		Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
		for (Path p = dir; p != null; p = p.getParent()) {
			Path candidate = p.resolve(EXAMPLE);
			if (Files.isRegularFile(candidate))
				return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
			candidate = p.resolve("capabilities/idempiere-print-template").resolve(EXAMPLE);
			if (Files.isRegularFile(candidate))
				return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
		}
		throw new IllegalStateException(EXAMPLE + " not found above " + dir);
	}

	/** Name of a list value as {@link PO2MapBuilder} prints it: translation, else base name, else the code. */
	private static String listName(String adLanguage, String tableName, String columnName, String code) {
		int referenceId = MColumn.get(Env.getCtx(), tableName, columnName).getAD_Reference_Value_ID();
		return DocumentLanguageTest.listName(adLanguage, referenceId, code);
	}

	// --- formats --------------------------------------------------------------------------------------

	@Test
	void hungarianNumbersUseCommaDecimalsAndHungarianGrouping() throws Exception {
		Language hu = hungarian();
		BigDecimal amount = new BigDecimal("1234567.5");
		String expected = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(hu.getLocale())).format(amount);
		String printed = render("${n?string[',##0.00']}", Map.of("n", amount), hu);
		assertEquals(expected, printed);
		assertTrue(printed.endsWith(",50"), printed);
		assertEquals(-1, printed.indexOf('.'), printed);
	}

	@Test
	void hungarianDatesUseTheLanguagesPatternAndMonthNames() throws Exception {
		assumeTrue(MSysConfig.getValue(FreemarkerConfigurationFactory.SYSCONFIG_DATE_FORMAT, null,
				GARDEN_WORLD) == null, "PRT_FREEMARKER_DATE_FORMAT is set");
		Language hu = hungarian();
		Timestamp day = Timestamp.valueOf("2026-11-05 00:00:00");
		assertEquals(new SimpleDateFormat(FreemarkerConfigurationFactory.printFormatPattern(DisplayType.Date, hu),
				hu.getLocale()).format(day), render("${d?date}", Map.of("d", day), hu));
		assertEquals("2026. november 5.", render("${d?string['yyyy. MMMM d.']}", Map.of("d", day), hu));
	}

	@Test
	void theSameTemplatePrintsEnglishFormatsForAnEnglishDocument() throws Exception {
		Language en = Language.getLanguage("en_US");
		assertEquals("1,234,567.50",
				render("${n?string[',##0.00']}", Map.of("n", new BigDecimal("1234567.5")), en));
		assertEquals("2026. November 5.", render("${d?string['yyyy. MMMM d.']}",
				Map.of("d", Timestamp.valueOf("2026-11-05 00:00:00")), en));
	}

	@Test
	void htmlLangIsTheDocumentLanguage() throws Exception {
		MPrintTemplate template = support.create(GARDEN_WORLD, PREFIX + "htmllang",
				"${ctx.language?keep_before('_')}");
		assertEquals("hu", renderDocument(template, orderWithProductLines(), hungarian()));
	}

	// --- labels ---------------------------------------------------------------------------------------

	@Test
	void labelsAreTheHungarianElementPrintNames() throws Exception {
		assumeTrue(hasHungarianElements(), "no hu_HU translation of AD_Element in the test database");
		TranslateMethod msg = new TranslateMethod(HU, true);
		String hu = Msg.translate(HU, true, "DocumentNo", true);
		assertEquals(hu, msg.exec(List.of(new freemarker.template.SimpleScalar("DocumentNo"))));
		assertNotEquals(Msg.translate(Language.getBaseAD_Language(), true, "DocumentNo", true), hu);
	}

	@Test
	void messageArgumentsAreFormattedInHungarian() throws Exception {
		// MessageFormat with the hu locale: a number argument gets Hungarian separators
		String text = Msg.getMsg(HU, "SaveErrorNotUnique", new Object[] { new BigDecimal("1234.5") });
		assertEquals(html(text), render("${msg('SaveErrorNotUnique', n)}",
				Map.of("msg", new TranslateMethod(HU, true), "n", new BigDecimal("1234.5")), hungarian()));
	}

	// --- content --------------------------------------------------------------------------------------

	@Test
	void listValuesAndLinkedRecordsAreInHungarian() throws Exception {
		Language hu = hungarian();
		PO order = orderWithProductLines();
		Map<String, Object> data = PO2MapBuilder.build(order, null, new PO2MapBuilder.DisplayLanguage(hu));

		String code = order.get_ValueAsString("DocStatus");
		assertEquals(code, data.get("DocStatus"));
		assertEquals(listName(HU, "C_Order", "DocStatus", code), data.get("DocStatus_display"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> lines = (List<Map<String, Object>>) data.get("lines");
		assertNotNull(lines);
		// a line whose product has a Hungarian name
		Map<?, ?> product = null;
		String trl = null;
		for (Map<String, Object> l : lines) {
			if (!(l.get("M_Product_ID") instanceof Map))
				continue;
			Map<?, ?> p = (Map<?, ?>) l.get("M_Product_ID");
			String name = DB.getSQLValueStringEx(null, "SELECT t.Name FROM M_Product_Trl t JOIN M_Product p"
					+ " ON (p.M_Product_ID=t.M_Product_ID) WHERE t.M_Product_ID=? AND t.AD_Language=? AND t.Name<>p.Name",
					p.get("id"), HU);
			if (name != null) {
				product = p;
				trl = name;
				break;
			}
		}
		assumeTrue(product != null, "no line of order " + order.get_ID() + " has a product translated to hu_HU");
		assertTrue(String.valueOf(product.get("display")).contains(trl), product.get("display") + " / " + trl);
	}

	@Test
	void anUntranslatedRecordPrintsTheClientsTextInEveryLanguage() {
		// the client language is the reference: core keeps the client's text in the base table and copies it
		// into every _Trl row of a new record, whatever the base language (en_US, sy_SY, ...) and whether the
		// client prints multi-lingual documents or not
		Language other = DocumentLanguageTest.otherSystemLanguage();
		assumeTrue(other != null, "no system language besides the login and the base language");
		// a payment term: a translated record of the tenant with few mandatory columns
		MPaymentTerm term = new MPaymentTerm(Env.getCtx(), 0, getTrxName());
		term.setAD_Org_ID(0);
		term.setValue(PREFIX + "trl_" + System.nanoTime());
		term.setName(PREFIX + "client text");
		term.setNetDays(0);
		term.saveEx();

		MOrder order = new MOrder(Env.getCtx(), 0, getTrxName());
		order.set_ValueNoCheck("C_PaymentTerm_ID", term.getC_PaymentTerm_ID());
		Map<String, Object> data = PO2MapBuilder.buildFlat(order, new PO2MapBuilder.DisplayLanguage(other));
		assertTrue(String.valueOf(((Map<?, ?>) data.get("C_PaymentTerm_ID")).get("display")).contains(PREFIX + "client text"),
				other.getAD_Language() + ": " + data.get("C_PaymentTerm_ID"));
	}

	// --- translation views (_vt) -----------------------------------------------------------------------

	@Test
	void translationViewIsReadOnlyInANonBaseLanguageForAViewWithAVt() {
		assumeTrue(DB.isTableOrViewExists("C_Order_Header_vt"), "no C_Order_Header_vt");
		assertEquals("C_Order_Header_vt",
				new PO2MapBuilder.DisplayLanguage(hungarian()).translationView("C_Order_Header_v"));
		assertEquals(null, new PO2MapBuilder.DisplayLanguage(Language.getLanguage(Language.getBaseAD_Language()))
				.translationView("C_Order_Header_v"), "base language");
		assertEquals(null, new PO2MapBuilder.DisplayLanguage(hungarian()).translationView("C_Order"), "not a view");
		assertEquals(null, new PO2MapBuilder.DisplayLanguage(hungarian()).translationView(PREFIX + "none_v"), "no _vt");
	}

	@Test
	void aRecordOfAViewIsReadFromItsTranslationView() throws Exception {
		assumeTrue(!Language.isBaseLanguage(HU) && DB.isTableOrViewExists("C_Order_Header_vt"), "no hu_HU _vt");
		MTable view = MTable.get(Env.getCtx(), "C_Order_Header_v");
		assumeTrue(view != null && view.getKeyColumns() != null && view.getKeyColumns().length == 1,
				"C_Order_Header_v has no single key");
		String key = view.getKeyColumns()[0];
		int id = DB.getSQLValueEx(null, "SELECT MIN(" + key + ") FROM C_Order_Header_vt WHERE AD_Client_ID=? AND AD_Language=?",
				GARDEN_WORLD, HU);
		assumeTrue(id > 0, "no GardenWorld row in C_Order_Header_vt for hu_HU");
		PO record = view.getPO(id, getTrxName());
		assertNotNull(record);

		Map<String, Object> data = PO2MapBuilder.buildFlat(record, new PO2MapBuilder.DisplayLanguage(hungarian()));
		Map<String, Object> base = PO2MapBuilder.buildFlat(record);
		int translatedColumns = 0;
		try (PreparedStatement ps = DB.prepareStatement(
				"SELECT * FROM C_Order_Header_vt WHERE " + key + "=? AND AD_Language=?", getTrxName())) {
			ps.setInt(1, id);
			ps.setString(2, HU);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				for (Map.Entry<String, Object> e : data.entrySet()) {
					if (!(e.getValue() instanceof String) || e.getKey().endsWith(PO2MapBuilder.LIST_NAME_SUFFIX))
						continue;
					assertEquals(rs.getString(e.getKey()), e.getValue(), e.getKey());
					if (!e.getValue().equals(base.get(e.getKey())))
						translatedColumns++;
				}
			}
		}
		assumeTrue(translatedColumns > 0, "the hu_HU row of C_Order_Header_vt " + id + " translates no text column");
	}

	// --- the example ----------------------------------------------------------------------------------

	@Test
	void exampleTemplatePrintsLabelsFormatsAndContentInTheDocumentLanguage() throws Exception {
		MPrintTemplate template = support.create(GARDEN_WORLD, PREFIX + "multilang", example());
		PO order = orderWithProductLines();
		BigDecimal total = (BigDecimal) order.get_Value("GrandTotal");
		Timestamp ordered = (Timestamp) order.get_Value("DateOrdered");

		for (Language language : new Language[] { hungarian(), Env.getLanguage(Env.getCtx()) }) {
			String ad = language.getAD_Language();
			String out = renderDocument(template, order, language);

			assertTrue(out.contains("<html lang=\"" + ad.substring(0, 2) + "\">"), ad);
			// labels
			for (String key : new String[] { "DocumentNo", "DateOrdered", "QtyOrdered", "GrandTotal" })
				assertTrue(out.contains(html(Msg.translate(ad, true, key, true))), ad + ": label " + key);
			// formats
			String grandTotal = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(language.getLocale()))
					.format(total);
			assertTrue(out.contains("<strong id=\"grandtotal\">" + html(grandTotal) + "</strong>"),
					ad + ": total " + grandTotal);
			String longDate = new SimpleDateFormat("yyyy. MMMM d.", language.getLocale()).format(ordered);
			assertTrue(out.contains("<p id=\"longdate\">" + html(longDate) + "</p>"), ad + ": date " + longDate);
			// content
			String status = listName(ad, "C_Order", "DocStatus", order.get_ValueAsString("DocStatus"));
			assertTrue(out.contains("<td id=\"docstatus\">" + html(status) + "</td>"), ad + ": status " + status);
		}
	}
}
