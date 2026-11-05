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

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MClient;
import org.compiere.model.MOrg;
import org.compiere.model.PO;
import org.compiere.print.ReportEngine;
import org.compiere.util.Env;
import org.compiere.util.Language;
import org.idempiere.print.renderer.IReportRenderer;
import org.idempiere.print.template.HtmlToPdfConverter;
import org.idempiere.print.template.PO2MapBuilder;
import org.idempiere.print.template.model.MPrintTemplate;
import org.idempiere.print.template.model.X_AD_PrintTemplate;

import freemarker.template.Template;

/**
 * Renderer FreeMarker (TemplateEngineType 'FM'). Consome só a
 * configuração — o ReportEngine não é necessário neste renderer.
 *
 * Data model: colunas do PO (FK como {id, display}) + coleções dos
 * includes + namespace {@code ctx} (client/org/user/idioma/now) +
 * {@code assets} (anexos do template em base64) + {@code msg} (texts of the dictionary,
 * {@link TranslateMethod}).
 * <p>
 * Document language: the print format's language when the request carries one (core sets the business
 * partner's language there for multi-lingual documents), else the language of the request's context.
 * It decides {@code ctx.language}, {@code msg}, the {@code display} of foreign keys, the locale and the
 * default date formats of the render; {@code ctx.login_language} keeps the language of the user printing.
 */
public class FreemarkerReportRenderer implements IReportRenderer<FreemarkerReportRendererConfiguration> {

	@Override
	public String getId() {
		return X_AD_PrintTemplate.TEMPLATEENGINETYPE_FreeMarker;
	}

	@Override
	public String getName() {
		return "FreeMarker Template";
	}

	@Override
	public String getContentType() {
		return "text/html";
	}

	@Override
	public String getFileExtension() {
		return "html";
	}

	@Override
	public boolean isBinary() {
		return false;
	}

	@Override
	public Class<FreemarkerReportRendererConfiguration> getConfigurationType() {
		return FreemarkerReportRendererConfiguration.class;
	}

	@Override
	public void renderReport(ReportEngine reportEngine, FreemarkerReportRendererConfiguration config) {
		MPrintTemplate printTemplate = config.getTemplate();
		PO targetPO = config.getPO();
		List<PO> listPOs = config.getPOs();
		List<Map<String, Object>> rowMaps = config.getRowMaps();
		try {
			// printTemplate vem de um cache por ID: seu ctx é de quem carregou primeiro,
			// então cliente e contexto saem da requisição, nunca do template
			Properties ctx = resolveRequestContext(reportEngine, config);
			// document and list mode: the configuration (templates, includes, limits) is that of the
			// record's client; keyless mode has no record and keeps the request's client
			int adClientId = resolveConfigurationClient(ctx, config);
			Language language = resolveLanguage(ctx, config);
			PO2MapBuilder.DisplayLanguage display = new PO2MapBuilder.DisplayLanguage(language);
			Template template = new Template(printTemplate.getName(),
					printTemplate.getTemplateBody(),
					FreemarkerConfigurationFactory.get(adClientId));

			Map<String, Object> dataModel;
			if (rowMaps != null) {
				// Modo listagem sobre view KEYLESS: linhas já montadas (sem PO)
				dataModel = new LinkedHashMap<>();
				dataModel.put("rows", rowMaps);
				dataModel.put("count", rowMaps.size());
			} else if (listPOs != null && !listPOs.isEmpty()) {
				// Modo listagem (IsForm='N'): todos os registros em rows; o
				// template itera (<#list rows as r>) e agrega na mão
				List<Map<String, Object>> rows = new ArrayList<>();
				for (PO po : listPOs)
					rows.add(PO2MapBuilder.build(po, printTemplate.getIncludes(), display));
				dataModel = new LinkedHashMap<>();
				dataModel.put("rows", rows);
				dataModel.put("count", rows.size());
			} else {
				// Modo documento (IsForm='Y'): colunas do PO no topo
				dataModel = PO2MapBuilder.build(targetPO, printTemplate.getIncludes(), display);
			}
			dataModel.put("ctx", contextMap(ctx, language));
			dataModel.put("msg", new TranslateMethod(language.getAD_Language(), isSOTrx(config)));
			dataModel.put("assets", AssetResolver.asBase64(ctx, printTemplate));

			if (isPdfOutput(config, printTemplate)) {
				// Fase 4: FreeMarker → HTML em memória → openhtmltopdf (@page CSS
				// controla tamanho/margens; jsoup tolera HTML não-XHTML)
				StringWriter html = new StringWriter();
				GuardedRender.process(template, dataModel, html, adClientId, ctx, language);
				if (config.getOutputStream() != null) {
					// the caller's stream is flushed, not closed
					HtmlToPdfConverter.convert(html.toString(), config.getOutputStream());
					config.getOutputStream().flush();
				} else {
					try (OutputStream output = resolveOutputStream(config)) {
						HtmlToPdfConverter.convert(html.toString(), output);
					}
				}
			} else {
				if (config.getOutputWriter() == null && config.getOutputStream() == null
						&& config.getOutputFile() != null) {
					// a writer the renderer opens on a file is its own to close
					try (Writer writer = new OutputStreamWriter(
							new FileOutputStream(config.getOutputFile(), false), printTemplate.getTemplateEncoding())) {
						GuardedRender.process(template, dataModel, writer, adClientId, ctx, language);
					}
				} else {
					// a writer or stream of the caller is flushed, not closed
					Writer writer = resolveWriter(config, printTemplate.getTemplateEncoding());
					GuardedRender.process(template, dataModel, writer, adClientId, ctx, language);
					writer.flush();
				}
			}
		} catch (AdempiereException e) {
			throw e;
		} catch (Exception e) {
			throw new AdempiereException("Falha renderizando template '" + printTemplate.getName()
					+ "': " + e.getMessage(), e);
		}
	}

	/**
	 * Contexto da requisição: do ReportEngine; senão do PO alvo (modo documento);
	 * senão do primeiro PO da listagem; senão Env.getCtx(). Define a configuração
	 * FreeMarker (cliente) e o mapa ctx do template.
	 */
	static Properties resolveRequestContext(ReportEngine reportEngine,
			FreemarkerReportRendererConfiguration config) {
		if (reportEngine != null && reportEngine.getCtx() != null)
			return reportEngine.getCtx();
		if (config.getPO() != null)
			return config.getPO().getCtx();
		List<PO> listPOs = config.getPOs();
		if (listPOs != null && !listPOs.isEmpty())
			return listPOs.get(0).getCtx();
		return Env.getCtx();
	}

	/**
	 * Client of the FreeMarker configuration: the target record's AD_Client_ID in document and list mode,
	 * the request context's client in keyless mode (row maps, no record).
	 */
	static int resolveConfigurationClient(Properties requestCtx, FreemarkerReportRendererConfiguration config) {
		if (config.getRowMaps() == null) {
			if (config.getPO() != null)
				return config.getPO().getAD_Client_ID();
			List<PO> listPOs = config.getPOs();
			if (listPOs != null && !listPOs.isEmpty())
				return listPOs.get(0).getAD_Client_ID();
		}
		return Env.getAD_Client_ID(requestCtx);
	}

	/**
	 * Document language: the configuration's (the print format's language), else the language of the
	 * request's context.
	 */
	static Language resolveLanguage(Properties requestCtx, FreemarkerReportRendererConfiguration config) {
		Language language = config.getLanguage();
		return language != null ? language : Env.getLanguage(requestCtx);
	}

	/**
	 * Sales terminology for {@code msg}: the record's IsSOTrx (document mode, or the first record of a
	 * list), else sales.
	 */
	static boolean isSOTrx(FreemarkerReportRendererConfiguration config) {
		PO po = config.getPO();
		if (po == null && config.getPOs() != null && !config.getPOs().isEmpty())
			po = config.getPOs().get(0);
		if (po != null && po.get_ColumnIndex("IsSOTrx") >= 0)
			return po.get_ValueAsBoolean("IsSOTrx");
		return true;
	}

	private boolean isPdfOutput(FreemarkerReportRendererConfiguration config, MPrintTemplate template) {
		if (config.getOutputFormat() != null)
			return "pdf".equalsIgnoreCase(config.getOutputFormat());
		// sem hint: QUALQUER sinal do template basta
		return "pdf".equalsIgnoreCase(template.getOutputFileExtension())
				|| "application/pdf".equalsIgnoreCase(template.getOutputContentType())
				|| template.isBinaryOutput();
	}

	private OutputStream resolveOutputStream(FreemarkerReportRendererConfiguration config) throws Exception {
		if (config.getOutputStream() != null)
			return config.getOutputStream();
		if (config.getOutputFile() != null)
			return new FileOutputStream(config.getOutputFile(), false);
		throw new AdempiereException("Saída binária exige stream ou file (writer não suportado)");
	}

	private Writer resolveWriter(FreemarkerReportRendererConfiguration config, String encoding)
			throws Exception {
		if (config.getOutputWriter() != null)
			return config.getOutputWriter();
		if (config.getOutputStream() != null)
			return new OutputStreamWriter(config.getOutputStream(), encoding);
		throw new AdempiereException("Nenhum output configurado (writer/stream/file)");
	}

	private Map<String, Object> contextMap(Properties ctx, Language language) {
		Map<String, Object> map = new LinkedHashMap<>();
		int orgId = Env.getAD_Org_ID(ctx);
		map.put("AD_Client_ID", Env.getAD_Client_ID(ctx));
		map.put("AD_Org_ID", orgId);
		map.put("AD_User_ID", Env.getAD_User_ID(ctx));
		map.put("AD_Role_ID", Env.getAD_Role_ID(ctx));
		map.put("language", language.getAD_Language());
		map.put("login_language", Env.getLanguage(ctx).getAD_Language());
		map.put("client_name", MClient.get(ctx).getName());
		map.put("org_name", orgId > 0 ? MOrg.get(orgId).getName() : "*");
		map.put("date", Env.getContextAsDate(ctx, "#Date"));
		map.put("now", new Timestamp(System.currentTimeMillis()));
		return map;
	}
}
