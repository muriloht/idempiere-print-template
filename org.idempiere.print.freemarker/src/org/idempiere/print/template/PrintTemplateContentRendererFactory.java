/**********************************************************************
* This file is part of the iDempiere Print Template Engine plugin.    *
*                                                                     *
* This program is free software; you can redistribute it and/or       *
* modify it under the terms of the GNU General Public License         *
* as published by the Free Software Foundation; either version 2      *
* of the License, or (at your option) any later version.              *
**********************************************************************/
package org.idempiere.print.template;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.adempiere.exceptions.AdempiereException;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.compiere.model.MQuery;
import org.compiere.model.MRole;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.model.PrintInfo;
import org.compiere.model.Query;
import org.compiere.print.MPrintFormat;
import org.compiere.print.ReportEngine;
import org.compiere.process.ProcessInfo;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Language;
import org.compiere.util.Util;
import org.idempiere.print.IReportContentRenderer;
import org.idempiere.print.IReportContentRendererFactory;
import org.idempiere.print.ReportContentRequest;
import org.idempiere.print.ReportContentType;
import org.idempiere.print.template.model.I_AD_PrintTemplate;
import org.idempiere.print.template.model.MPrintTemplate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** Integrates print templates with the report-content API from IDEMPIERE-7070. */
public class PrintTemplateContentRendererFactory implements IReportContentRendererFactory {

	private static final CLogger log = CLogger.getCLogger(PrintTemplateContentRendererFactory.class);
	/** Cap defensivo quando a impressão "de todos" cai num escopo grande */
	private static final int MAX_BATCH_ROWS = 1000;

	@Override
	public IReportContentRenderer createRenderer(ReportContentRequest request) {
		if (request == null || request.reportEngine() == null)
			return null;
		MPrintFormat printFormat = request.printFormat();
		if (printFormat == null
				|| printFormat.get_ColumnIndex(I_AD_PrintTemplate.COLUMNNAME_AD_PrintTemplate_ID) < 0)
			return null;
		int templateId = printFormat.get_ValueAsInt(I_AD_PrintTemplate.COLUMNNAME_AD_PrintTemplate_ID);
		if (templateId <= 0)
			return null;
		// every data read uses the request's context (client, role), never the one the cache kept
		Properties ctx = request.reportEngine().getCtx() != null ? request.reportEngine().getCtx()
				: printFormat.getCtx();
		MPrintTemplate template = MPrintTemplate.get(ctx, templateId);
		// document language as core resolved it: ReportEngine.get sets the business partner's language
		// on the print format when the client prints multi-lingual documents
		Language language = printFormat.getLanguage();
		if (template == null || !template.isActive())
			throw new AdempiereException("Print Template: template " + templateId + " does not exist or is inactive");

		// Tabela/view KEYLESS (ex.: RV_OpenItem): não dá pra construir PO
		// (PO.setKeyInfo exige PK/UU/FK) → lê as linhas direto do ResultSet.
		int tableId = resolveTableId(request, template);
		if (tableId > 0 && !(template.getAD_Table_ID() > 0 && template.getAD_Table_ID() != tableId)) {
			MTable table = MTable.get(ctx, tableId);
			String[] keys = table.getKeyColumns();
			if (keys == null || keys.length == 0) {
				List<Map<String, Object>> rowMaps = resolveKeylessRows(ctx, request, table, language);
				if (rowMaps.isEmpty())
					return null;
				return new PrintTemplateContentRenderer(template, rowMaps, language); // keyless = sempre listagem
			}
		}

		List<PO> targets = resolveTargetPOs(ctx, request, template);
		if (targets.isEmpty())
			return null; // sem contexto de registro (ex.: report avulso, Record_ID=0) → declina; o core segue no fluxo normal
		// IsForm='Y' → documento (1 render por registro + merge); IsForm='N' → listagem (1 render, rows = todos)
		boolean listMode = !printFormat.isForm();
		return new PrintTemplateContentRenderer(template, targets, listMode, language);
	}

	private static final class PrintTemplateContentRenderer implements IReportContentRenderer {
		/** Modo documento: só renderiza o template como PDF/HTML */
		private static final ReportContentType[] DOC_CONTENT_TYPES = {
				new ReportContentType("PDF", "pdf", "application/pdf"),
				new ReportContentType("HTML", "html", "text/html; charset=utf-8") };
		/** Modo listagem: além de PDF/HTML, exporta a grade pra CSV/XLSX (via TabularExporter) */
		private static final ReportContentType[] LIST_CONTENT_TYPES = {
				new ReportContentType("PDF", "pdf", "application/pdf"),
				new ReportContentType("HTML", "html", "text/html; charset=utf-8"),
				new ReportContentType("CSV", "csv", "text/csv; charset=utf-8"),
				new ReportContentType("XLSX", "xlsx",
						"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") };

		private final MPrintTemplate template;
		/** Caminho keyed (PO). Pode ser null quando keyless. */
		private final List<PO> targets;
		/** Caminho keyless (linhas do ResultSet). Pode ser null quando keyed. */
		private final List<Map<String, Object>> rowMaps;
		/** IsForm='N': um único render com todos os registros (rows); false = 1 doc por registro + merge */
		private final boolean listMode;
		/** document language of the print format; null = the language of the request's context */
		private final Language language;

		/** keyed: 1 doc por registro (documento) ou 1 render com todos (listagem) */
		private PrintTemplateContentRenderer(MPrintTemplate template, List<PO> targets, boolean listMode,
				Language language) {
			this.template = template;
			this.targets = targets;
			this.rowMaps = null;
			this.listMode = listMode;
			this.language = language;
		}

		/** keyless (view sem PK/UU/FK): sempre listagem, com linhas já montadas */
		private PrintTemplateContentRenderer(MPrintTemplate template, List<Map<String, Object>> rowMaps,
				Language language) {
			this.template = template;
			this.targets = null;
			this.rowMaps = rowMaps;
			this.listMode = true;
			this.language = language;
		}

		/** Dispatch da renderização de listagem: keyless (maps) ou keyed (POs) */
		private void renderListTo(File output, String extension) {
			boolean ok = (rowMaps != null)
					? TemplateRenderDispatcher.renderListMaps(template, rowMaps, output, extension, language)
					: TemplateRenderDispatcher.renderList(template, targets, output, extension, language);
			if (!ok)
				throw new AdempiereException("Print Template: no renderer installed for engine '"
						+ template.getTemplateEngineType() + "'");
		}

		@Override
		public File getContent(String contentType, String fileExtension) {
			String extension = normalizeExtension(contentType, fileExtension);
			try {
				if (listMode) {
					if ("csv".equals(extension) || "xlsx".equals(extension))
						return exportTabular(extension);
					File output = Files.createTempFile("print-template-list-", "." + extension).toFile();
					renderListTo(output, extension);
					return output;
				}
				List<File> rendered = new ArrayList<>();
				for (PO target : targets) {
					File output = Files.createTempFile("print-template-" + target.get_ID() + "-", "." + extension).toFile();
					if (!TemplateRenderDispatcher.render(template, target, output, extension, language))
						throw new AdempiereException("Print Template: no renderer installed for engine '"
								+ template.getTemplateEngineType() + "'");
					rendered.add(output);
				}
				if (rendered.size() == 1)
					return rendered.get(0);
				try {
					return merge(rendered, extension);
				} finally {
					for (File file : rendered)
						Files.deleteIfExists(file.toPath());
				}
			} catch (AdempiereException e) {
				throw e;
			} catch (Exception e) {
				throw new AdempiereException("Print Template: unable to create report content", e);
			}
		}

		@Override
		public ReportContentType[] getSupportedContentTypes() {
			return (listMode ? LIST_CONTENT_TYPES : DOC_CONTENT_TYPES).clone();
		}

		@Override
		public int getRowCount() {
			return rowMaps != null ? rowMaps.size() : targets.size();
		}

		/** Renderiza a listagem como HTML, extrai o {@code <table>} e reescreve em CSV/XLSX */
		private File exportTabular(String extension) throws Exception {
			File html = Files.createTempFile("print-template-list-", ".html").toFile();
			try {
				renderListTo(html, "html");
				List<List<String>> table = TabularExporter.extractTable(
						Files.readString(html.toPath(), StandardCharsets.UTF_8));
				File output = Files.createTempFile("print-template-list-", "." + extension).toFile();
				if ("csv".equals(extension))
					TabularExporter.writeCsv(table, output);
				else
					TabularExporter.writeXlsx(table, output);
				return output;
			} finally {
				Files.deleteIfExists(html.toPath());
			}
		}

		private File merge(List<File> rendered, String extension) throws Exception {
			File combined = Files.createTempFile("print-template-combined-", "." + extension).toFile();
			if ("pdf".equals(extension)) {
				PDFMergerUtility merger = new PDFMergerUtility();
				for (File file : rendered)
					merger.addSource(file);
				merger.setDestinationFileName(combined.getAbsolutePath());
				merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly());
				return combined;
			}

			Document document = Jsoup.parse(rendered.get(0), StandardCharsets.UTF_8.name());
			for (int i = 1; i < rendered.size(); i++) {
				Document next = Jsoup.parse(rendered.get(i), StandardCharsets.UTF_8.name());
				document.body().append("<div style=\"page-break-before: always\"></div>");
				document.body().append(next.body().html());
			}
			Files.writeString(combined.toPath(), document.outerHtml(), StandardCharsets.UTF_8);
			return combined;
		}
	}

	private static String normalizeExtension(String contentType, String fileExtension) {
		if ("pdf".equalsIgnoreCase(fileExtension)
				|| (contentType != null && contentType.toLowerCase().startsWith("application/pdf")))
			return "pdf";
		if ("html".equalsIgnoreCase(fileExtension)
				|| (contentType != null && contentType.toLowerCase().startsWith("text/html")))
			return "html";
		if ("csv".equalsIgnoreCase(fileExtension)
				|| (contentType != null && contentType.toLowerCase().startsWith("text/csv")))
			return "csv";
		if ("xlsx".equalsIgnoreCase(fileExtension)
				|| (contentType != null && contentType.toLowerCase().contains("spreadsheetml.sheet")))
			return "xlsx";
		throw new AdempiereException("Print Template: unsupported output type " + contentType + " (" + fileExtension + ")");
	}

	/** Resolve a tabela do report: printInfo → processInfo → printFormat → template */
	private static int resolveTableId(ReportContentRequest request, MPrintTemplate template) {
		PrintInfo printInfo = request.printInfo();
		ProcessInfo processInfo = request.processInfo();
		MPrintFormat printFormat = request.printFormat();
		int tableId = printInfo != null ? printInfo.getAD_Table_ID() : 0;
		if (tableId <= 0 && processInfo != null)
			tableId = processInfo.getTable_ID();
		if (tableId <= 0 && printFormat != null)
			tableId = printFormat.getAD_Table_ID();
		if (tableId <= 0 && template.getAD_Table_ID() > 0)
			tableId = template.getAD_Table_ID();
		return tableId;
	}

	/**
	 * Lê uma tabela/view KEYLESS direto do ResultSet (sem construir PO) e
	 * monta as linhas do data model. Respeita o escopo do report (MQuery),
	 * IsActive e o acesso do papel (MRole.addAccessSQL). Cap MAX_BATCH_ROWS.
	 */
	private static List<Map<String, Object>> resolveKeylessRows(Properties ctx, ReportContentRequest request,
			MTable table, Language language) {
		PO2MapBuilder.DisplayLanguage display = language == null ? null : new PO2MapBuilder.DisplayLanguage(language);
		List<Map<String, Object>> rows = new ArrayList<>();
		// without a role addAccessSQL cannot filter: fail closed
		MRole role = PO2MapBuilder.requireRole(ctx, table.getTableName());
		POInfo info = POInfo.getPOInfo(ctx, table.getAD_Table_ID());
		if (info == null)
			return rows;
		ReportEngine re = request.reportEngine();
		MQuery mq = (re != null) ? re.getQuery() : null;
		String mqWhere = (mq != null && mq.getRestrictionCount() > 0) ? mq.getWhereClause(false) : null;
		if (mqWhere != null && (mqWhere.contains(";") || mqWhere.contains("--")))
			mqWhere = null; // guard defensivo

		String tableName = table.getTableName();
		// a view X_v in a language other than the base language: its translation view X_vt in that language,
		// as a print format reads it; the view keeps the name of the table, so the report's restriction and
		// the role's access SQL read as they do on X_v
		String view = display == null ? null : display.translationView(tableName);
		StringBuilder sql = new StringBuilder("SELECT * FROM ");
		if (view != null)
			sql.append(view).append(" ");
		sql.append(tableName).append(" WHERE 1=1");
		if (view != null)
			sql.append(" AND ").append(tableName).append(".AD_Language=?");
		if (info.getColumnIndex("IsActive") >= 0)
			sql.append(" AND ").append(tableName).append(".IsActive='Y'");
		if (!Util.isEmpty(mqWhere, true))
			sql.append(" AND (").append(mqWhere).append(")");
		String finalSql = role
				.addAccessSQL(sql.toString(), tableName, MRole.SQL_FULLYQUALIFIED, MRole.SQL_RO);

		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(finalSql, null);
			if (view != null)
				pstmt.setString(1, language.getAD_Language());
			rs = pstmt.executeQuery();
			while (rs.next()) {
				if (rows.size() >= MAX_BATCH_ROWS) {
					log.warning("Print Template: view " + tableName + " truncada em " + MAX_BATCH_ROWS
							+ " linhas (filtre o report antes)");
					break;
				}
				rows.add(PO2MapBuilder.buildFlatFromRS(ctx, info, rs, display));
			}
		} catch (Exception e) {
			log.warning("Print Template: não li a view keyless " + tableName + " (" + e.getMessage() + ") — declinando");
			return new ArrayList<>();
		} finally {
			DB.close(rs, pstmt);
		}
		return rows;
	}

	private static List<PO> resolveTargetPOs(Properties ctx, ReportContentRequest request,
			MPrintTemplate template) {
		PrintInfo printInfo = request.printInfo();
		ProcessInfo processInfo = request.processInfo();
		MPrintFormat printFormat = request.printFormat();

		int tableId = printInfo != null ? printInfo.getAD_Table_ID() : 0;
		int recordId = printInfo != null ? printInfo.getRecord_ID() : 0;
		if (tableId <= 0 && processInfo != null)
			tableId = processInfo.getTable_ID();
		if (recordId <= 0 && processInfo != null)
			recordId = processInfo.getRecord_ID();
		if (tableId <= 0 && printFormat != null)
			tableId = printFormat.getAD_Table_ID();
		if (tableId <= 0 && template.getAD_Table_ID() > 0)
			tableId = template.getAD_Table_ID();

		List<Integer> recordIds = new ArrayList<>();
		if (processInfo != null && processInfo.getRecord_IDs() != null) {
			for (Integer id : processInfo.getRecord_IDs()) {
				if (id != null && id.intValue() > 0)
					recordIds.add(id);
			}
		}
		if (recordId > 0)
			recordIds.add(recordId);
		recordIds = new ArrayList<>(new LinkedHashSet<>(recordIds));

		if (tableId <= 0)
			return new ArrayList<>(); // sem tabela → declina (createRenderer devolve null, o core segue normal)
		if (template.getAD_Table_ID() > 0 && template.getAD_Table_ID() != tableId)
			return new ArrayList<>(); // template é de outra tabela → declina

		MTable table = MTable.get(ctx, tableId);
		List<PO> targets = new ArrayList<>();

		if (!recordIds.isEmpty()) {
			// "current row" (Record_ID) e/ou multi-seleção do grid (ProcessInfo.Record_IDs)
			for (int id : recordIds) {
				PO po = table.getPO(id, null);
				if (po != null)
					targets.add(po);
			}
			return targets;
		}

		// Sem registro específico: imprime "todos" — o escopo do report (query do ReportEngine),
		// em vez de erro. Cada registro é renderizado e os PDFs/HTMLs são unidos (merge no getContent).
		ReportEngine re = request.reportEngine();
		MQuery mq = (re != null) ? re.getQuery() : null;
		String where = (mq != null && mq.getRestrictionCount() > 0) ? mq.getWhereClause(false) : null;
		// the access filter of Query is skipped without a role: fail closed
		PO2MapBuilder.requireRole(ctx, table.getTableName());
		try {
			List<PO> rows = new Query(ctx, table.getTableName(), where, null)
					.setOnlyActiveRecords(true)
					.setClient_ID()
					.setApplyAccessFilter(true)
					.list();
			if (rows.size() > MAX_BATCH_ROWS) {
				log.warning("Print Template: escopo com " + rows.size() + " registros — truncado em " + MAX_BATCH_ROWS
						+ " (filtre a aba/report antes de imprimir todos)");
				rows = new ArrayList<>(rows.subList(0, MAX_BATCH_ROWS));
			}
			targets.addAll(rows);
		} catch (Exception e) {
			log.warning("Print Template: não resolvi o escopo do report (" + e.getMessage() + ") — declinando");
			return new ArrayList<>();
		}
		return targets;
	}
}
