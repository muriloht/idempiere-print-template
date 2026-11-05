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
import java.io.OutputStream;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import org.compiere.model.PO;
import org.compiere.util.Language;
import org.idempiere.print.renderer.IReportRendererConfiguration;
import org.idempiere.print.template.model.MPrintTemplate;

/**
 * Configuração base dos renderers template-based. Todo engine
 * (FreeMarker, Velocity, ...) declara uma subclasse com construtor
 * (MPrintTemplate, PO) — é esse contrato que o
 * {@link TemplateRenderDispatcher} instancia por reflexão.
 */
public class TemplateRendererConfiguration implements IReportRendererConfiguration {

	private final MPrintTemplate template;
	private final PO targetPO;
	/** Modo listagem (IsForm='N'): todos os registros num único render; null/vazio = modo documento */
	private List<PO> targetPOs;
	/** Modo listagem sobre tabela/view KEYLESS: linhas já montadas (sem PO); tem precedência sobre targetPOs */
	private List<Map<String, Object>> rowMaps;
	private File outputFile;
	private OutputStream outputStream;
	private Writer outputWriter;
	private String outputFormat;
	private Language language;

	public TemplateRendererConfiguration(MPrintTemplate template, PO targetPO) {
		this.template = template;
		this.targetPO = targetPO;
	}

	public MPrintTemplate getTemplate() {
		return template;
	}

	public PO getPO() {
		return targetPO;
	}

	/** Registros do modo listagem (IsForm='N'); null/vazio ⇒ modo documento (só getPO()) */
	public List<PO> getPOs() {
		return targetPOs;
	}

	public TemplateRendererConfiguration targetPOs(List<PO> targetPOs) {
		this.targetPOs = targetPOs;
		return this;
	}

	/** Linhas pré-montadas (view keyless); null = usa PO/targetPOs */
	public List<Map<String, Object>> getRowMaps() {
		return rowMaps;
	}

	public TemplateRendererConfiguration rowMaps(List<Map<String, Object>> rowMaps) {
		this.rowMaps = rowMaps;
		return this;
	}

	/**
	 * @return document language: the print format's language (iDempiere sets the business partner's
	 *         language there when the client prints multi-lingual documents); null = the language of
	 *         the request's context
	 */
	public Language getLanguage() {
		return language;
	}

	public TemplateRendererConfiguration language(Language language) {
		this.language = language;
		return this;
	}

	/** Extensão efetiva da saída ("pdf"/"html"); null = default do template */
	public String getOutputFormat() {
		return outputFormat;
	}

	public TemplateRendererConfiguration outputFormat(String format) {
		this.outputFormat = format;
		return this;
	}

	public TemplateRendererConfiguration outputFile(File file) {
		this.outputFile = file;
		return this;
	}

	public TemplateRendererConfiguration outputStream(OutputStream stream) {
		this.outputStream = stream;
		return this;
	}

	public TemplateRendererConfiguration outputWriter(Writer writer) {
		this.outputWriter = writer;
		return this;
	}

	@Override
	public File getOutputFile() {
		return outputFile;
	}

	@Override
	public OutputStream getOutputStream() {
		return outputStream;
	}

	@Override
	public Writer getOutputWriter() {
		return outputWriter;
	}
}
