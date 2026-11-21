package org.idempiere.print.freemarker.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.idempiere.print.freemarker.PdfFonts;
import org.idempiere.print.template.HtmlToPdfConverter;
import org.idempiere.print.template.PdfFontCatalog;
import org.idempiere.test.AbstractTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * IDEMPIERE-7070 (PR #7/#8): without a registered font openhtmltopdf falls back to the base-14 WinAnsi fonts
 * and letters outside Western Europe print as {@code #}. The bundled DejaVu baseline must embed them instead,
 * so the letters survive as recoverable text in the PDF.
 */
@Tag("integration")
public class PdfFontEmbeddingTest extends AbstractTestCase {

	/** Central/Eastern European Latin letters the base-14 WinAnsi fonts do not carry. */
	private static final String NON_WESTERN = "Č ľ ť ň ő ű ł";

	private static String html(String body) {
		return "<html><head><style>@page{size:A4;margin:1cm;} body{font-family:sans-serif;}</style></head>"
				+ "<body><p>" + body + "</p></body></html>";
	}

	private static byte[] toPdf(String body, PdfFontCatalog fonts) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		HtmlToPdfConverter.convert(html(body), out, fonts);
		return out.toByteArray();
	}

	@BeforeEach
	void freshFonts() {
		PdfFonts.reset();
	}

	@AfterEach
	void cleanUp() {
		PdfFonts.reset();
	}

	/**
	 * The plugin's own wiring: {@link PdfFonts} catalogues the bundled DejaVu faces (PDF_FONT_DIR is unset in
	 * GardenWorld), openhtmltopdf embeds them as a subset, and the accented letters come back as text — not {@code #}.
	 */
	@Test
	void bundledDejaVuEmbedsNonWesternLetters() throws Exception {
		PdfFontCatalog fonts = PdfFonts.get(0);
		assertFalse(fonts.isEmpty(), "the bundled DejaVu faces should be catalogued");

		byte[] pdf = toPdf(NON_WESTERN, fonts);
		try (PDDocument doc = PDDocument.load(pdf)) {
			assertTrue(embedsFontContaining(doc, "DejaVu"), "a DejaVu font should be embedded in the PDF");
			String text = new PDFTextStripper().getText(doc);
			assertTrue(text.contains("Č") && text.contains("ő") && text.contains("ł"),
					"the accented letters should be recoverable from the PDF, got: " + text.trim());
			assertFalse(text.contains("#"), "no letter should have been dropped to '#'");
		}
	}

	/**
	 * The contrast: with no font registered, the base-14 fonts cannot carry these letters — openhtmltopdf either
	 * replaces them (with {@code #}) or drops them, and never embeds a DejaVu font. Either outcome proves the point.
	 */
	@Test
	void withoutFontsNonWesternLettersDoNotSurvive() throws Exception {
		byte[] pdf;
		try {
			pdf = toPdf(NON_WESTERN, PdfFontCatalog.EMPTY);
		} catch (Exception theBase14CannotEncodeThem) {
			return; // the base-14 fonts cannot encode the letters at all — also proves the point
		}
		try (PDDocument doc = PDDocument.load(pdf)) {
			assertFalse(embedsFontContaining(doc, "DejaVu"), "no DejaVu font should be embedded without the catalog");
			String text = new PDFTextStripper().getText(doc);
			assertFalse(text.contains("Č") && text.contains("ő") && text.contains("ł"),
					"the base-14 fonts must not carry these letters, yet the text has them: " + text.trim());
		}
	}

	/** @return true when any page embeds a font whose base name contains the token (e.g. "DejaVu"). */
	private static boolean embedsFontContaining(PDDocument doc, String token) throws Exception {
		for (PDPage page : doc.getPages()) {
			PDResources res = page.getResources();
			if (res == null)
				continue;
			for (COSName name : res.getFontNames()) {
				PDFont font = res.getFont(name);
				if (font != null && font.getName() != null && font.getName().contains(token))
					return true;
			}
		}
		return false;
	}
}
