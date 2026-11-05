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
package org.idempiere.print.template;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.adempiere.base.Core;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.PO;
import org.compiere.util.Language;
import org.compiere.util.CLogger;
import org.idempiere.print.renderer.IReportRenderer;
import org.idempiere.print.renderer.IReportRendererConfiguration;
import org.idempiere.print.template.model.MPrintTemplate;

/**
 * Resolve o {@link IReportRenderer} cujo getId() casa com o
 * TemplateEngineType do template (lookup OSGi via Core) e dispara a
 * renderização. Renderers template-based consomem só a configuração;
 * por isso não precisam do ReportEngine da API de conteúdo.
 *
 * Spec: riskora/docs/SPEC_IDEMPIERE_PRINT_TEMPLATE_ENGINE.md §7.3
 */
public class TemplateRenderDispatcher {

	private static final CLogger log = CLogger.getCLogger(TemplateRenderDispatcher.class);

	private TemplateRenderDispatcher() {}

	/**
	 * Decisão ÚNICA de formato: tipo pedido (viewer/batch, "PDF"/"HTML")
	 * manda; senão QUALQUER sinal do template basta pra pdf (extension,
	 * content type ou Binary Output).
	 */
	public static String resolveOutputExtension(String requestedType, MPrintTemplate template) {
		if ("PDF".equalsIgnoreCase(requestedType))
			return "pdf";
		if ("HTML".equalsIgnoreCase(requestedType))
			return "html";
		if ("pdf".equalsIgnoreCase(template.getOutputFileExtension())
				|| "application/pdf".equalsIgnoreCase(template.getOutputContentType())
				|| template.isBinaryOutput())
			return "pdf";
		String ext = template.getOutputFileExtension();
		return ext == null || ext.isEmpty() ? "html" : ext;
	}

	/**
	 * @return false quando não há renderer registrado pro engine type
	 *         (caller decide a mensagem); demais falhas lançam exceção
	 */
	public static boolean render(MPrintTemplate template, PO targetPO, File outputFile) {
		return render(template, targetPO, outputFile, null);
	}

	/**
	 * @param outputFormat extensão efetiva ("pdf"/"html"); null = default do template
	 */
	public static boolean render(MPrintTemplate template, PO targetPO, File outputFile, String outputFormat) {
		return render(template, targetPO, outputFile, outputFormat, null);
	}

	/**
	 * @param language document language; null = the language of the request's context
	 */
	public static boolean render(MPrintTemplate template, PO targetPO, File outputFile, String outputFormat,
			Language language) {
		String engineType = template.getTemplateEngineType();
		IReportRenderer<IReportRendererConfiguration> renderer = getRenderer(engineType);
		if (renderer == null)
			return false;
		TemplateRendererConfiguration config = newConfig(renderer, engineType, template, targetPO);
		config.outputFile(outputFile);
		config.outputFormat(outputFormat);
		config.language(language);
		renderer.renderReport(null, config);
		return true;
	}

	/**
	 * Modo listagem (IsForm='N'): um único render com TODOS os registros
	 * expostos ao template como a coleção {@code rows} (o autor itera,
	 * soma e agrupa). O PO representativo (primeiro) alimenta o ctx.
	 *
	 * @return false quando não há renderer registrado ou a lista é vazia
	 */
	public static boolean renderList(MPrintTemplate template, List<PO> targetPOs, File outputFile, String outputFormat) {
		return renderList(template, targetPOs, outputFile, outputFormat, null);
	}

	/**
	 * @param language document language; null = the language of the request's context
	 */
	public static boolean renderList(MPrintTemplate template, List<PO> targetPOs, File outputFile,
			String outputFormat, Language language) {
		if (targetPOs == null || targetPOs.isEmpty())
			return false;
		String engineType = template.getTemplateEngineType();
		IReportRenderer<IReportRendererConfiguration> renderer = getRenderer(engineType);
		if (renderer == null)
			return false;
		TemplateRendererConfiguration config = newConfig(renderer, engineType, template, targetPOs.get(0));
		config.targetPOs(targetPOs);
		config.outputFile(outputFile);
		config.outputFormat(outputFormat);
		config.language(language);
		renderer.renderReport(null, config);
		return true;
	}

	/**
	 * Modo listagem sobre tabela/view KEYLESS: as linhas já vêm montadas
	 * (sem PO, pois {@code PO.setKeyInfo} exige PK/UU/FK). O ctx sai do
	 * próprio template (não há PO representativo).
	 *
	 * @return false quando não há renderer registrado ou a lista é null
	 */
	public static boolean renderListMaps(MPrintTemplate template, List<Map<String, Object>> rowMaps,
			File outputFile, String outputFormat) {
		return renderListMaps(template, rowMaps, outputFile, outputFormat, null);
	}

	/**
	 * @param language document language; null = the language of the request's context
	 */
	public static boolean renderListMaps(MPrintTemplate template, List<Map<String, Object>> rowMaps,
			File outputFile, String outputFormat, Language language) {
		if (rowMaps == null)
			return false;
		String engineType = template.getTemplateEngineType();
		IReportRenderer<IReportRendererConfiguration> renderer = getRenderer(engineType);
		if (renderer == null)
			return false;
		TemplateRendererConfiguration config = newConfig(renderer, engineType, template, null);
		config.rowMaps(rowMaps);
		config.outputFile(outputFile);
		config.outputFormat(outputFormat);
		config.language(language);
		renderer.renderReport(null, config);
		return true;
	}

	private static IReportRenderer<IReportRendererConfiguration> getRenderer(String engineType) {
		IReportRenderer<IReportRendererConfiguration> renderer = Core.getReportRenderer(engineType);
		if (renderer == null)
			log.warning("Nenhum IReportRenderer registrado pra TemplateEngineType=" + engineType);
		return renderer;
	}

	/** Instancia a config template-based pelo contrato (MPrintTemplate, PO) via reflexão */
	private static TemplateRendererConfiguration newConfig(IReportRenderer<IReportRendererConfiguration> renderer,
			String engineType, MPrintTemplate template, PO targetPO) {
		Class<?> configType = renderer.getConfigurationType();
		if (!TemplateRendererConfiguration.class.isAssignableFrom(configType))
			throw new AdempiereException("Renderer '" + engineType + "' não é template-based (config "
					+ configType.getName() + " não estende TemplateRendererConfiguration)");
		try {
			return (TemplateRendererConfiguration) configType
					.getDeclaredConstructor(MPrintTemplate.class, PO.class)
					.newInstance(template, targetPO);
		} catch (ReflectiveOperationException e) {
			throw new AdempiereException("Config do renderer '" + engineType
					+ "' sem construtor (MPrintTemplate, PO)", e);
		}
	}
}
