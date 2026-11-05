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
import java.nio.file.Files;
import java.util.Collections;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.process.SvrProcess;
import org.idempiere.print.template.model.MPrintTemplate;
import org.idempiere.print.template.model.X_AD_PrintTemplate;

/**
 * Botão "Validar/Prever" da janela Print Template (Fase 3):
 * 1. valida a sintaxe do template (erro de parse aborta com linha/coluna);
 * 2. renderiza uma prévia com o registro mais recente da tabela do
 *    template (respeitando o client da sessão) e baixa o resultado.
 *
 * Sem AD_Table_ID no template ou sem registro de amostra: só a validação
 * de sintaxe roda.
 */
public class TemplateValidateProcess extends SvrProcess {

	/** AD_Process.Value — lookup sempre por Value, nunca por ID (§6.6) */
	public static final String PROCESS_VALUE = "PrintTemplate_Validate";

	@Override
	protected void prepare() {
		// botão de registro — contexto vem do ProcessInfo
	}

	@Override
	protected String doIt() throws Exception {
		int templateId = getRecord_ID();
		if (templateId <= 0)
			throw new AdempiereException("Executar pelo botão do registro de Print Template");
		MPrintTemplate template = new MPrintTemplate(getCtx(), templateId, get_TrxName());
		if (template.get_ID() != templateId)
			throw new AdempiereException("Print Template " + templateId + " não encontrado");

		validateSyntax(template);

		PO samplePO = findSamplePO(template);
		if (samplePO == null)
			return "Sintaxe OK — sem registro de amostra pra prévia"
					+ (template.getAD_Table_ID() > 0 ? "" : " (defina a Tabela do template)");

		// Prévia SEMPRE em PDF: abre inline no viewer (showReports) em vez de
		// download — o formato de produção do template segue intocado.
		String extension = "pdf";
		File outputDir = Files.createTempDirectory("printtemplate").toFile();
		File outputFile = new File(outputDir, "preview_" + templateId + "." + extension);
		boolean handled = TemplateRenderDispatcher.render(template, samplePO, outputFile, extension);
		if (!handled)
			return "Sintaxe OK — engine '" + template.getTemplateEngineType()
					+ "' sem renderer instalado pra prévia";

		getProcessInfo().setExportFile(outputFile);
		getProcessInfo().setExportFileExtension(extension);
		if (processUI != null) {
			try {
				processUI.showReports(Collections.singletonList(outputFile));
			} catch (RuntimeException e) {
				// UI sem suporte a viewer inline — degrada pro download
				processUI.download(outputFile);
			}
		}

		return "@OK@ - prévia gerada com " + samplePO.get_TableName() + " #" + samplePO.get_ID();
	}

	/**
	 * Validação de sintaxe explícita — só o FreeMarker tem parse offline
	 * aqui (import cross-package consciente: mesmo bundle, spec §9.1; num
	 * split futuro cada engine expõe seu validate). Outros engines validam
	 * pelo próprio render da prévia.
	 */
	private void validateSyntax(MPrintTemplate template) {
		if (!X_AD_PrintTemplate.TEMPLATEENGINETYPE_FreeMarker.equals(template.getTemplateEngineType()))
			return;
		try {
			new freemarker.template.Template(template.getName(), template.getTemplateBody(),
					org.idempiere.print.freemarker.FreemarkerConfigurationFactory.get(getAD_Client_ID()));
		} catch (Exception e) {
			throw new AdempiereException("Sintaxe inválida no template: " + e.getMessage());
		}
	}

	private PO findSamplePO(MPrintTemplate template) {
		if (template.getAD_Table_ID() <= 0)
			return null;
		MTable table = MTable.get(getCtx(), template.getAD_Table_ID());
		String[] keyColumns = table.getKeyColumns();
		if (keyColumns == null || keyColumns.length != 1)
			return null;
		return new Query(getCtx(), table.getTableName(), null, get_TrxName())
				.setClient_ID()
				.setOnlyActiveRecords(true)
				.setOrderBy(keyColumns[0] + " DESC")
				.first();
	}
}
