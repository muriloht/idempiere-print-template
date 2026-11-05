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
package org.idempiere.print.freemarker;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.compiere.model.MLanguage;
import org.compiere.model.MSysConfig;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Language;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.core.Environment;
import freemarker.core.HTMLOutputFormat;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;

/**
 * Configuração do FreeMarker, uma por cliente (AD_Client_ID):
 * - MultiTemplateLoader: banco (AD_PrintTemplate, ligado ao cliente) → classpath do bundle
 *   (/templates) — spec §4.8;
 * - HTMLOutputFormat (auto-escape XSS em ${var}; ${var?no_esc} opt-out);
 * - RETHROW: erro de template quebra a renderização com causa real
 *   (plugin genérico não engole erro);
 * - sandbox: ?new bloqueado (ALLOWS_NOTHING_RESOLVER) e ?api desligado; com
 *   SysConfig PRT_FREEMARKER_STRICT=N do System (client 0), ?new usa SAFER_RESOLVER.
 *   A tenant row of PRT_FREEMARKER_STRICT is ignored: a tenant cannot relax the sandbox;
 * - formatos default BR, sobrescrevíveis via SysConfig PRT_FREEMARKER_* (por cliente).
 * <p>
 * A configuração é construída uma vez por cliente. Mudança em SysConfig
 * (PRT_FREEMARKER_STRICT e formatos) só vale após {@link #reset()} ou restart.
 */
public final class FreemarkerConfigurationFactory {

	public static final String SYSCONFIG_DATE_FORMAT = "PRT_FREEMARKER_DATE_FORMAT";
	public static final String SYSCONFIG_TIME_FORMAT = "PRT_FREEMARKER_TIME_FORMAT";
	public static final String SYSCONFIG_DATETIME_FORMAT = "PRT_FREEMARKER_DATETIME_FORMAT";
	public static final String SYSCONFIG_NUMBER_FORMAT = "PRT_FREEMARKER_NUMBER_FORMAT";
	public static final String SYSCONFIG_STRICT = "PRT_FREEMARKER_STRICT";

	/** Uma configuração por cliente; limitada pelo número de clientes. */
	private static final ConcurrentHashMap<Integer, Configuration> s_configurations = new ConcurrentHashMap<>();

	private FreemarkerConfigurationFactory() {}

	/**
	 * @param adClientId cliente cujos templates, includes e SysConfig valem
	 * @return a configuração do cliente (criada na primeira chamada)
	 */
	public static Configuration get(int adClientId) {
		return s_configurations.computeIfAbsent(adClientId, FreemarkerConfigurationFactory::build);
	}

	/**
	 * @return true (padrão) se ?new não pode instanciar nenhuma classe; false
	 *         só com SysConfig PRT_FREEMARKER_STRICT=N do System (client 0); a tenant row is
	 *         ignored. Lido na criação da configuração: a mudança vale após {@link #reset()} ou
	 *         restart.
	 */
	public static boolean isStrict() {
		return MSysConfig.getBooleanValue(SYSCONFIG_STRICT, true, 0);
	}

	/** Descarta as configurações construídas. Só para testes. */
	public static void reset() {
		s_configurations.clear();
	}

	/**
	 * Strict configuration: every template it loads (includes and imports too) gets interrupt checks,
	 * so that {@link GuardedRender} can stop a loop that writes nothing. {@link GuardedRender} applies
	 * its limits exactly to the templates whose configuration is of this class, so the sandbox and the
	 * limits of a template are decided once, when its configuration is built.
	 */
	static final class StrictConfiguration extends Configuration {
		StrictConfiguration() {
			super(Configuration.VERSION_2_3_34);
		}

		@Override
		public Template getTemplate(String name, Locale locale, Object customLookupCondition, String encoding,
				boolean parseAsFTL, boolean ignoreMissing) throws IOException {
			Template template = super.getTemplate(name, locale, customLookupCondition, encoding, parseAsFTL,
					ignoreMissing);
			if (template != null) {
				GuardedRender.makeInterruptible(template);
			}
			return template;
		}
	}

	/**
	 * Locale and default date formats of one render, from the document language. The configuration is
	 * shared by every render of a client, so the language is set on the render's {@link Environment},
	 * never on the configuration.
	 * <ul>
	 * <li>the locale decides decimal and grouping separators, month and day names, also in explicit
	 * patterns such as {@code ?string[",##0.00"]};</li>
	 * <li>the default date, time and date-time patterns are those a print format uses
	 * ({@link DisplayType#getDateFormat(int, Language)}: {@code AD_Language.DatePattern} /
	 * {@code TimePattern}, else the language's formats), unless the client (or the System) set
	 * {@code PRT_FREEMARKER_DATE_FORMAT} / {@code _TIME_FORMAT} / {@code _DATETIME_FORMAT}: an explicit
	 * SysConfig pattern stays;</li>
	 * <li>the default number pattern stays {@code PRT_FREEMARKER_NUMBER_FORMAT}; its separators follow
	 * the locale.</li>
	 * </ul>
	 *
	 * @param env the render's environment, before {@link Environment#process()}
	 * @param language document language; null leaves the configuration's locale and formats
	 * @param adClientId client whose SysConfig formats apply
	 */
	public static void applyLanguage(Environment env, Language language, int adClientId) {
		if (language == null)
			return;
		env.setLocale(language.getLocale());
		if (MSysConfig.getValue(SYSCONFIG_DATE_FORMAT, null, adClientId) == null)
			env.setDateFormat(printFormatPattern(DisplayType.Date, language));
		if (MSysConfig.getValue(SYSCONFIG_TIME_FORMAT, null, adClientId) == null)
			env.setTimeFormat(printFormatPattern(DisplayType.Time, language));
		if (MSysConfig.getValue(SYSCONFIG_DATETIME_FORMAT, null, adClientId) == null)
			env.setDateTimeFormat(printFormatPattern(DisplayType.DateTime, language));
	}

	/**
	 * The pattern a print format uses for a date display type in a language. Core reads the language's
	 * AD_Language record for its patterns and fails without one; a language that has no record (only a
	 * locale) gets the language's own formats.
	 *
	 * @param displayType {@link DisplayType#Date}, {@link DisplayType#Time} or {@link DisplayType#DateTime}
	 */
	public static String printFormatPattern(int displayType, Language language) {
		if (MLanguage.get(Env.getCtx(), language.getAD_Language()) != null)
			return DisplayType.getDateFormat(displayType, language).toPattern();
		if (displayType == DisplayType.Time)
			return language.getTimeFormat().toPattern();
		if (displayType == DisplayType.DateTime)
			return language.getDateTimeFormat().toPattern();
		return language.getDateFormat().toPattern();
	}

	private static Configuration build(int adClientId) {
		boolean strict = isStrict();
		Configuration cfg = strict ? new StrictConfiguration()
				: new Configuration(Configuration.VERSION_2_3_34);
		cfg.setTemplateLoader(new MultiTemplateLoader(new TemplateLoader[] {
				new DatabaseTemplateLoader(adClientId),
				new ClassTemplateLoader(FreemarkerConfigurationFactory.class, "/templates")
		}));
		cfg.setLocalizedLookup(false);
		cfg.setTemplateUpdateDelayMilliseconds(5000);
		cfg.setDefaultEncoding("UTF-8");
		cfg.setOutputFormat(HTMLOutputFormat.INSTANCE);
		cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
		cfg.setLogTemplateExceptions(false);
		cfg.setWrapUncheckedExceptions(true);
		cfg.setAPIBuiltinEnabled(false);
		cfg.setNewBuiltinClassResolver(strict
				? TemplateClassResolver.ALLOWS_NOTHING_RESOLVER
				: TemplateClassResolver.SAFER_RESOLVER);
		cfg.setDateFormat(MSysConfig.getValue(SYSCONFIG_DATE_FORMAT, "dd/MM/yyyy", adClientId));
		cfg.setTimeFormat(MSysConfig.getValue(SYSCONFIG_TIME_FORMAT, "HH:mm:ss", adClientId));
		cfg.setDateTimeFormat(MSysConfig.getValue(SYSCONFIG_DATETIME_FORMAT, "dd/MM/yyyy HH:mm", adClientId));
		cfg.setNumberFormat(MSysConfig.getValue(SYSCONFIG_NUMBER_FORMAT, "#,##0.##", adClientId));
		return cfg;
	}
}
