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

import java.io.IOException;
import java.io.OutputStream;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

/**
 * HTML → PDF via openhtmltopdf (Apache 2.0 — redistribuível; o iText do
 * Riskora é AGPL e ficou de fora de propósito, spec §13 Fase 4).
 *
 * O HTML passa pelo jsoup antes (parser leniente → DOM W3C), então
 * template não precisa ser XHTML estrito. Tamanho de página/margens via
 * CSS {@code @page} (default A4 no styles.ftlh do classpath).
 *
 * Sem dependência de iDempiere de propósito — testável isolado.
 */
public final class HtmlToPdfConverter {

	private HtmlToPdfConverter() {}

	/** Converts with the standard PDF fonts only (no font registered). */
	public static void convert(String html, OutputStream output) throws IOException {
		convert(html, output, PdfFontCatalog.EMPTY);
	}

	/**
	 * Converts with the fonts of the catalog registered, embedded as subsets.
	 *
	 * @param fonts the fonts; {@link PdfFontCatalog#EMPTY} keeps the standard PDF fonts
	 */
	public static void convert(String html, OutputStream output, PdfFontCatalog fonts) throws IOException {
		org.jsoup.nodes.Document jsoupDocument = Jsoup.parse(html);
		org.w3c.dom.Document w3cDocument = new W3CDom().fromJsoup(jsoupDocument);

		PdfRendererBuilder builder = new PdfRendererBuilder();
		builder.useFastMode();
		if (fonts != null)
			fonts.registerOn(builder);
		builder.withW3cDocument(w3cDocument, null);
		builder.toStream(output);
		builder.run();
	}
}
