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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;

import org.compiere.model.MClient;
import org.compiere.model.MColumn;
import org.compiere.model.MLanguage;
import org.compiere.model.MLookupFactory;
import org.compiere.model.MRefList;
import org.compiere.model.MSysConfig;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
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
import org.idempiere.print.template.TemplateRenderDispatcher;
import org.idempiere.print.template.model.MPrintTemplate;
import org.idempiere.test.AbstractTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import freemarker.template.SimpleNumber;
import freemarker.template.SimpleScalar;
import freemarker.template.Template;
import freemarker.template.TemplateModelException;

/**
 * A template prints in the document language (CLD-2557): locale and default date formats of the
 * render, {@code msg}, {@code ctx.language} and the {@code display} of foreign keys follow the
 * language the renderer is given; without one, everything stays as in the login language.
 */
@Tag("integration")
public class DocumentLanguageTest extends AbstractTestCase {

	private static final int GARDEN_WORLD = 11;
	/** a language with other separators and another date pattern than en_US */
	private static final Language GERMAN = Language.getLanguage("de_DE");

	private final TemplateTestSupport support = new TemplateTestSupport();

	@BeforeEach
	void resetConfigurations() {
		FreemarkerConfigurationFactory.reset();
	}

	@AfterEach
	void cleanUp() {
		support.deleteSysConfigs();
		support.deleteCreated();
		FreemarkerConfigurationFactory.reset();
	}

	/**
	 * A language with translations to test against: a system language other than the login and the base
	 * language, from AD_Language. Which language is the base (en_US, sy_SY, …) does not matter.
	 *
	 * @return null when the database has none
	 */
	static Language otherSystemLanguage() {
		String ad = DB.getSQLValueStringEx(null, "SELECT AD_Language FROM AD_Language WHERE IsSystemLanguage='Y'"
				+ " AND IsBaseLanguage='N' AND IsActive='Y' AND AD_Language<>? ORDER BY AD_Language",
				Env.getAD_Language(Env.getCtx()));
		return ad == null ? null : Language.getLanguage(ad);
	}

	private static String render(String body, Map<String, Object> model, Language language) throws Exception {
		Template t = new Template("t", body, FreemarkerConfigurationFactory.get(GARDEN_WORLD));
		StringWriter out = new StringWriter();
		GuardedRender.process(t, model, out, GARDEN_WORLD, Env.getCtx(), language);
		return out.toString();
	}

	@Test
	void numberSeparatorsFollowTheDocumentLanguage() throws Exception {
		BigDecimal amount = new BigDecimal("1234.5");
		String pattern = FreemarkerConfigurationFactory.get(GARDEN_WORLD).getNumberFormat();
		String expected = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(GERMAN.getLocale()))
				.format(amount);

		assertEquals(expected, render("${n}", Map.of("n", amount), GERMAN));
		assertEquals(new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(GERMAN.getLocale()))
				.format(amount), render("${n?string[',##0.00']}", Map.of("n", amount), GERMAN));
	}

	@Test
	void withoutLanguageTheRenderIsUnchanged() throws Exception {
		Map<String, Object> model = Map.of("n", new BigDecimal("1234.5"), "d", new Timestamp(0));
		String body = "${n} ${d?date}";
		Template t = new Template("t", body, FreemarkerConfigurationFactory.get(GARDEN_WORLD));
		StringWriter plain = new StringWriter();
		t.process(model, plain);

		assertEquals(plain.toString(), render(body, model, null));
	}

	@Test
	void datePatternIsTheLanguagesUnlessSysConfigSetsOne() throws Exception {
		Timestamp day = Timestamp.valueOf("2026-11-05 10:30:00");
		// no SysConfig pattern (client or System): the language's pattern
		if (MSysConfig.getValue(FreemarkerConfigurationFactory.SYSCONFIG_DATE_FORMAT, null, GARDEN_WORLD) == null) {
			String expected = new SimpleDateFormat(
					FreemarkerConfigurationFactory.printFormatPattern(DisplayType.Date, GERMAN), GERMAN.getLocale())
					.format(day);
			assertEquals(expected, render("${d?date}", Map.of("d", day), GERMAN));
		}

		// an explicit SysConfig pattern stays
		support.setSysConfig(GARDEN_WORLD, FreemarkerConfigurationFactory.SYSCONFIG_DATE_FORMAT, "yyyy-MM-dd");
		assertEquals("2026-11-05", render("${d?date}", Map.of("d", day), GERMAN));
	}

	@Test
	void msgTranslatesKeysTokensAndArguments() throws Exception {
		TranslateMethod msg = new TranslateMethod("de_DE", true);

		assertEquals(Msg.translate("de_DE", true, "DocumentNo", true),
				msg.exec(List.of(new SimpleScalar("DocumentNo"))));
		assertEquals(Msg.translate("de_DE", true, "DocumentNo", true) + " / "
				+ Msg.translate("de_DE", true, "DateOrdered", true),
				msg.exec(List.of(new SimpleScalar("@DocumentNo@ / @DateOrdered@"))));
		assertEquals(Msg.getMsg("de_DE", "SaveErrorNotUnique", new Object[] { 7 }),
				msg.exec(List.of(new SimpleScalar("SaveErrorNotUnique"), new SimpleNumber(7))));
		// an unknown key comes back as it is
		assertEquals(PREFIX + "nokey", msg.exec(List.of(new SimpleScalar(PREFIX + "nokey"))));
	}

	@Test
	void rendererUsesTheConfigurationLanguage() throws Exception {
		MPrintTemplate template = support.create(GARDEN_WORLD, PREFIX + "lang",
				"${ctx.language}|${ctx.login_language}|${msg('DocumentNo')}");
		MClient target = new MClient(Env.getCtx(), GARDEN_WORLD, null);
		String login = Env.getAD_Language(Env.getCtx());

		StringWriter german = new StringWriter();
		new FreemarkerReportRenderer().renderReport(null,
				(FreemarkerReportRendererConfiguration) new FreemarkerReportRendererConfiguration(template, target)
						.language(GERMAN).outputWriter(german));
		assertEquals("de_DE|" + login + "|" + Msg.translate("de_DE", true, "DocumentNo", true), german.toString());

		// no language in the configuration: the request's (login) language
		StringWriter plain = new StringWriter();
		new FreemarkerReportRenderer().renderReport(null,
				(FreemarkerReportRendererConfiguration) new FreemarkerReportRendererConfiguration(template, target)
						.outputWriter(plain));
		assertEquals(login + "|" + login + "|" + Msg.translate(login, true, "DocumentNo", true), plain.toString());
	}

	@Test
	void displayInTheLoginLanguageIsCoresLookup() {
		PO order = new Query(Env.getCtx(), "C_Order", "AD_Client_ID=?", getTrxName())
				.setParameters(GARDEN_WORLD).setOrderBy("C_Order_ID").first();
		assertNotNull(order, "no GardenWorld order");

		Map<String, Object> core = PO2MapBuilder.buildFlat(order);
		Map<String, Object> sameLanguage = PO2MapBuilder.buildFlat(order,
				new PO2MapBuilder.DisplayLanguage(Env.getLanguage(Env.getCtx())));
		assertEquals(core, sameLanguage);

		// another language builds its own lookups: every foreign key still has a display
		Language other = otherSystemLanguage();
		assumeTrue(other != null, "no system language besides the login and the base language");
		Map<String, Object> translated = PO2MapBuilder.buildFlat(order, new PO2MapBuilder.DisplayLanguage(other));
		assertEquals(core.keySet(), translated.keySet());
		for (Map.Entry<String, Object> e : core.entrySet()) {
			if (e.getValue() instanceof Map)
				assertNotNull(((Map<?, ?>) translated.get(e.getKey())).get("display"), e.getKey());
		}
	}

	@Test
	void dateTimeAndTimePatternsAreThePrintFormats() throws Exception {
		assumeTrue(MSysConfig.getValue(FreemarkerConfigurationFactory.SYSCONFIG_DATETIME_FORMAT, null,
				GARDEN_WORLD) == null, "PRT_FREEMARKER_DATETIME_FORMAT is set");
		assumeTrue(MSysConfig.getValue(FreemarkerConfigurationFactory.SYSCONFIG_TIME_FORMAT, null,
				GARDEN_WORLD) == null, "PRT_FREEMARKER_TIME_FORMAT is set");
		Timestamp at = Timestamp.valueOf("2026-11-05 10:30:00");
		for (int displayType : new int[] { DisplayType.DateTime, DisplayType.Time }) {
			String expected = new SimpleDateFormat(
					FreemarkerConfigurationFactory.printFormatPattern(displayType, GERMAN), GERMAN.getLocale()).format(at);
			String builtIn = displayType == DisplayType.Time ? "time" : "datetime";
			assertEquals(expected, render("${d?" + builtIn + "}", Map.of("d", at), GERMAN), builtIn);
		}
	}

	@Test
	void printFormatPatternIsCoresForALanguageWithARecord() {
		for (String ad : new String[] { "en_US", "de_DE", "hu_HU" }) {
			Language language = Language.getLanguage(ad);
			if (MLanguage.get(Env.getCtx(), ad) == null)
				continue;
			for (int displayType : new int[] { DisplayType.Date, DisplayType.Time, DisplayType.DateTime })
				assertEquals(DisplayType.getDateFormat(displayType, language).toPattern(),
						FreemarkerConfigurationFactory.printFormatPattern(displayType, language), ad + "/" + displayType);
		}
	}

	@Test
	void printFormatPatternWithoutARecordIsTheLanguagesFormats() {
		Language language = Language.getLanguage("qq_QQ");
		assumeTrue(MLanguage.get(Env.getCtx(), "qq_QQ") == null, "qq_QQ exists in AD_Language");
		assertEquals(language.getDateFormat().toPattern(),
				FreemarkerConfigurationFactory.printFormatPattern(DisplayType.Date, language));
		assertEquals(language.getTimeFormat().toPattern(),
				FreemarkerConfigurationFactory.printFormatPattern(DisplayType.Time, language));
		assertEquals(language.getDateTimeFormat().toPattern(),
				FreemarkerConfigurationFactory.printFormatPattern(DisplayType.DateTime, language));
	}

	@Test
	void nonStrictRenderAlsoFollowsTheLanguage() throws Exception {
		support.setSysConfig(0, FreemarkerConfigurationFactory.SYSCONFIG_STRICT, "N");
		BigDecimal amount = new BigDecimal("1234.5");
		assertEquals(new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(GERMAN.getLocale()))
				.format(amount), render("${n?string[',##0.00']}", Map.of("n", amount), GERMAN));
	}

	@Test
	void msgRejectsAMissingOrNonStringKey() {
		TranslateMethod msg = new TranslateMethod("de_DE", true);
		assertThrows(TemplateModelException.class, () -> msg.exec(List.of()));
		assertThrows(TemplateModelException.class, () -> msg.exec(List.of(new SimpleNumber(1))));
	}

	@Test
	void msgKeepsAnUnclosedToken() throws Exception {
		TranslateMethod msg = new TranslateMethod("de_DE", true);
		assertEquals(Msg.translate("de_DE", true, "DocumentNo", true) + " @rest",
				msg.exec(List.of(new SimpleScalar("@DocumentNo@ @rest"))));
	}

	@Test
	void msgOutputIsEscapedInHtml() throws Exception {
		String key = "<b>" + PREFIX + "x</b>";
		assertEquals("&lt;b&gt;" + PREFIX + "x&lt;/b&gt;",
				render("${msg(k)}", Map.of("msg", new TranslateMethod("de_DE", true), "k", key), GERMAN));
	}

	@Test
	void msgUsesTheTerminologyOfTheRecord() throws Exception {
		MPrintTemplate template = support.create(GARDEN_WORLD, PREFIX + "sotrx", "${msg('C_BPartner_ID')}");
		String login = Env.getAD_Language(Env.getCtx());
		for (boolean isSOTrx : new boolean[] { true, false }) {
			PO order = new Query(Env.getCtx(), "C_Order", "AD_Client_ID=? AND IsSOTrx=?", getTrxName())
					.setParameters(GARDEN_WORLD, isSOTrx ? "Y" : "N").setOrderBy("C_Order_ID").first();
			assumeTrue(order != null, "no GardenWorld order with IsSOTrx=" + isSOTrx);
			StringWriter out = new StringWriter();
			new FreemarkerReportRenderer().renderReport(null,
					(FreemarkerReportRendererConfiguration) new FreemarkerReportRendererConfiguration(template, order)
							.outputWriter(out));
			assertEquals(Msg.translate(login, isSOTrx, "C_BPartner_ID", true), out.toString(), "IsSOTrx=" + isSOTrx);
		}
	}

	@Test
	void listAndKeylessModesUseTheLanguage() throws Exception {
		MPrintTemplate template = support.create(GARDEN_WORLD, PREFIX + "listlang", "${ctx.language}|${count}");
		MClient client = new MClient(Env.getCtx(), GARDEN_WORLD, null);

		StringWriter list = new StringWriter();
		new FreemarkerReportRenderer().renderReport(null,
				(FreemarkerReportRendererConfiguration) new FreemarkerReportRendererConfiguration(template, client)
						.targetPOs(List.of(client)).language(GERMAN).outputWriter(list));
		assertEquals("de_DE|1", list.toString());

		StringWriter keyless = new StringWriter();
		new FreemarkerReportRenderer().renderReport(null,
				(FreemarkerReportRendererConfiguration) new FreemarkerReportRendererConfiguration(template, null)
						.rowMaps(List.of()).language(GERMAN).outputWriter(keyless));
		assertEquals("de_DE|0", keyless.toString());
	}

	@Test
	void dispatcherPassesTheLanguage() throws Exception {
		MPrintTemplate template = support.create(GARDEN_WORLD, PREFIX + "displang", "${ctx.language}");
		MClient target = new MClient(Env.getCtx(), GARDEN_WORLD, null);
		File out = File.createTempFile(PREFIX, ".html");
		try {
			assertTrue(TemplateRenderDispatcher.render(template, target, out, "html", GERMAN));
			assertTrue(new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8).contains("de_DE"));

			// the old signature keeps the login language
			assertTrue(TemplateRenderDispatcher.render(template, target, out, "html"));
			assertTrue(new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8)
					.contains(Env.getAD_Language(Env.getCtx())));
		} finally {
			out.delete();
		}
	}

	@Test
	void displayIsTheTranslationOfTheDocumentLanguage() {
		Language other = otherSystemLanguage();
		assumeTrue(other != null, "no system language besides the login and the base language");
		// an order whose document type has a translation that differs from its name
		int orderId = DB.getSQLValueEx(null, "SELECT MIN(o.C_Order_ID) FROM C_Order o"
				+ " JOIN C_DocType d ON (d.C_DocType_ID=o.C_DocTypeTarget_ID)"
				+ " JOIN C_DocType_Trl t ON (t.C_DocType_ID=d.C_DocType_ID AND t.AD_Language=? AND t.Name<>d.Name)"
				+ " WHERE o.AD_Client_ID=?", other.getAD_Language(), GARDEN_WORLD);
		assumeTrue(orderId > 0, "no GardenWorld order with a document type translated to " + other.getAD_Language());
		PO order = new Query(Env.getCtx(), "C_Order", "C_Order_ID=?", getTrxName()).setParameters(orderId).first();
		String trl = DB.getSQLValueStringEx(null, "SELECT Name FROM C_DocType_Trl WHERE C_DocType_ID=? AND AD_Language=?",
				order.get_ValueAsInt("C_DocTypeTarget_ID"), other.getAD_Language());

		Map<String, Object> translated = PO2MapBuilder.buildFlat(order, new PO2MapBuilder.DisplayLanguage(other));
		String display = (String) ((Map<?, ?>) translated.get("C_DocTypeTarget_ID")).get("display");
		assertTrue(display.contains(trl), display + " does not contain " + trl);
	}

	@Test
	void anotherLanguageNeitherReadsNorFillsCoresLookupCache() throws Exception {
		Language other = otherSystemLanguage();
		assumeTrue(other != null, "no system language besides the login and the base language");
		Language login = Env.getLanguage(Env.getCtx());
		int orderId = DB.getSQLValueEx(null, "SELECT MIN(o.C_Order_ID) FROM C_Order o"
				+ " JOIN C_DocType d ON (d.C_DocType_ID=o.C_DocTypeTarget_ID)"
				+ " JOIN C_DocType_Trl t ON (t.C_DocType_ID=d.C_DocType_ID AND t.AD_Language=? AND t.Name<>d.Name)"
				+ " LEFT JOIN C_DocType_Trl lt ON (lt.C_DocType_ID=d.C_DocType_ID AND lt.AD_Language=?)"
				+ " WHERE o.AD_Client_ID=? AND t.Name<>COALESCE(lt.Name, d.Name)",
				other.getAD_Language(), login.getAD_Language(), GARDEN_WORLD);
		assumeTrue(orderId > 0, "no order whose document type differs between " + other.getAD_Language()
				+ " and " + login.getAD_Language());
		PO order = new Query(Env.getCtx(), "C_Order", "C_Order_ID=?", getTrxName()).setParameters(orderId).first();
		int docTypeId = order.get_ValueAsInt("C_DocTypeTarget_ID");
		String otherName = DB.getSQLValueStringEx(null,
				"SELECT Name FROM C_DocType_Trl WHERE C_DocType_ID=? AND AD_Language=?", docTypeId, other.getAD_Language());
		MColumn column = MColumn.get(Env.getCtx(), "C_Order", "C_DocTypeTarget_ID");

		// core's lookup in the login language fills its shared cache …
		String loginName = MLookupFactory.get(Env.getCtx(), 0, column.getAD_Column_ID(), column.getAD_Reference_ID(),
				login, "C_DocTypeTarget_ID", column.getAD_Reference_Value_ID(), false, "").getDisplay(docTypeId);
		assertTrue(!loginName.contains(otherName), loginName);
		// … the render in another language does not read it …
		Map<String, Object> printed = PO2MapBuilder.buildFlat(order, new PO2MapBuilder.DisplayLanguage(other));
		String display = (String) ((Map<?, ?>) printed.get("C_DocTypeTarget_ID")).get("display");
		assertTrue(display.contains(otherName), display + " does not contain " + otherName);
		// … and does not change it
		assertEquals(loginName, MLookupFactory.get(Env.getCtx(), 0, column.getAD_Column_ID(), column.getAD_Reference_ID(),
				login, "C_DocTypeTarget_ID", column.getAD_Reference_Value_ID(), false, "").getDisplay(docTypeId));
	}

	@Test
	void keylessRowsTakeTheDisplayLanguage() throws Exception {
		PO order = new Query(Env.getCtx(), "C_Order", "AD_Client_ID=?", getTrxName())
				.setParameters(GARDEN_WORLD).setOrderBy("C_Order_ID").first();
		assertNotNull(order, "no GardenWorld order");
		POInfo info = POInfo.getPOInfo(Env.getCtx(), order.get_Table_ID());
		Language other = otherSystemLanguage();
		assumeTrue(other != null, "no system language besides the login and the base language");

		Map<String, Object> core = keylessRow(info, order.get_ID(), null);
		assertEquals(core, keylessRow(info, order.get_ID(), new PO2MapBuilder.DisplayLanguage(Env.getLanguage(Env.getCtx()))));
		Map<String, Object> translated = keylessRow(info, order.get_ID(), new PO2MapBuilder.DisplayLanguage(other));
		assertEquals(core.keySet(), translated.keySet());
		for (Map.Entry<String, Object> e : core.entrySet()) {
			if (e.getValue() instanceof Map) {
				assertEquals(((Map<?, ?>) e.getValue()).get("id"), ((Map<?, ?>) translated.get(e.getKey())).get("id"));
				assertNotNull(((Map<?, ?>) translated.get(e.getKey())).get("display"), e.getKey());
			}
		}
	}

	@Test
	void listValuesGetTheirNameInTheDocumentLanguage() throws Exception {
		PO order = new Query(Env.getCtx(), "C_Order", "AD_Client_ID=? AND DocStatus IS NOT NULL", getTrxName())
				.setParameters(GARDEN_WORLD).setOrderBy("C_Order_ID").first();
		assertNotNull(order, "no GardenWorld order");
		String code = order.get_ValueAsString("DocStatus");
		int referenceId = MColumn.get(Env.getCtx(), "C_Order", "DocStatus").getAD_Reference_Value_ID();
		String login = Env.getAD_Language(Env.getCtx());
		Language other = otherSystemLanguage();
		assumeTrue(other != null, "no system language besides the login and the base language");

		// the code stays under the column name, its name comes next to it, as a print format prints it
		Map<String, Object> core = PO2MapBuilder.buildFlat(order);
		assertEquals(code, core.get("DocStatus"));
		assertEquals(listName(login, referenceId, code), core.get("DocStatus" + PO2MapBuilder.LIST_NAME_SUFFIX));

		Map<String, Object> translated = PO2MapBuilder.buildFlat(order, new PO2MapBuilder.DisplayLanguage(other));
		assertEquals(code, translated.get("DocStatus"));
		assertEquals(listName(other.getAD_Language(), referenceId, code),
				translated.get("DocStatus" + PO2MapBuilder.LIST_NAME_SUFFIX));

		// keyless rows get the same name
		POInfo info = POInfo.getPOInfo(Env.getCtx(), order.get_Table_ID());
		assertEquals(translated.get("DocStatus" + PO2MapBuilder.LIST_NAME_SUFFIX),
				keylessRow(info, order.get_ID(), new PO2MapBuilder.DisplayLanguage(other))
						.get("DocStatus" + PO2MapBuilder.LIST_NAME_SUFFIX));
	}

	/** Name of a list value as PO2MapBuilder prints it: translation, else base name, else the code. */
	static String listName(String adLanguage, int referenceId, String code) {
		String name = MRefList.getListName(adLanguage, referenceId, code);
		if ((name == null || name.isEmpty()) && !Language.isBaseLanguage(adLanguage))
			name = MRefList.getListName(Language.getBaseAD_Language(), referenceId, code);
		return name == null || name.isEmpty() ? code : name;
	}

	@Test
	void listValueWithoutATranslationPrintsTheBaseName() {
		PO order = new Query(Env.getCtx(), "C_Order", "AD_Client_ID=? AND DocStatus IS NOT NULL", getTrxName())
				.setParameters(GARDEN_WORLD).setOrderBy("C_Order_ID").first();
		assertNotNull(order, "no GardenWorld order");
		String code = order.get_ValueAsString("DocStatus");
		int referenceId = MColumn.get(Env.getCtx(), "C_Order", "DocStatus").getAD_Reference_Value_ID();
		// a language with no AD_Ref_List_Trl row for this value
		String untranslated = DB.getSQLValueStringEx(null, "SELECT l.AD_Language FROM AD_Language l"
				+ " WHERE l.IsBaseLanguage='N' AND NOT EXISTS (SELECT 1 FROM AD_Ref_List_Trl t"
				+ " JOIN AD_Ref_List r ON (r.AD_Ref_List_ID=t.AD_Ref_List_ID) WHERE r.AD_Reference_ID=?"
				+ " AND r.Value=? AND t.AD_Language=l.AD_Language) ORDER BY l.AD_Language", referenceId, code);
		assumeTrue(untranslated != null, "every language translates DocStatus " + code);

		Map<String, Object> row = PO2MapBuilder.buildFlat(order,
				new PO2MapBuilder.DisplayLanguage(Language.getLanguage(untranslated)));
		assertEquals(MRefList.getListName(Language.getBaseAD_Language(), referenceId, code),
				row.get("DocStatus" + PO2MapBuilder.LIST_NAME_SUFFIX), untranslated);
	}

	@Test
	void keylessRowsSkipColumnsTheQueryDoesNotReturn() throws Exception {
		PO order = new Query(Env.getCtx(), "C_Order", "AD_Client_ID=?", getTrxName())
				.setParameters(GARDEN_WORLD).setOrderBy("C_Order_ID").first();
		assertNotNull(order, "no GardenWorld order");
		POInfo info = POInfo.getPOInfo(Env.getCtx(), order.get_Table_ID());
		String virtual = null;
		for (int i = 0; i < info.getColumnCount() && virtual == null; i++)
			if (info.isVirtualColumn(i))
				virtual = info.getColumnName(i);
		assumeTrue(virtual != null, "C_Order has no virtual column in this database");

		// SELECT * does not return a virtual column; the row is built from what the query returns
		Map<String, Object> row = keylessRow(info, order.get_ID(), null);
		assertTrue(!row.containsKey(virtual), virtual);
		assertEquals(order.get_ValueAsString("DocumentNo"), row.get("DocumentNo"));
	}

	private Map<String, Object> keylessRow(POInfo info, int id, PO2MapBuilder.DisplayLanguage display)
			throws Exception {
		try (PreparedStatement ps = DB.prepareStatement("SELECT * FROM C_Order WHERE C_Order_ID=?", getTrxName())) {
			ps.setInt(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				return PO2MapBuilder.buildFlatFromRS(Env.getCtx(), info, rs, display);
			}
		}
	}
}
