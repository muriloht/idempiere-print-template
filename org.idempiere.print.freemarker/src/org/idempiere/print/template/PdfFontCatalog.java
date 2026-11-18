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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.NamingTable;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.TrueTypeFont;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FSFontUseCase;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

/**
 * The fonts a PDF render may use: every TrueType/OpenType face found in a list of directories, keyed by
 * family, weight and style as openhtmltopdf matches them.
 * <p>
 * Without registered fonts openhtmltopdf falls back to the 14 standard PDF fonts. Those are not embedded and
 * use the WinAnsi encoding, so letters outside Western Europe (Č ľ ť ň ő ű ł …) print as {@code #}.
 * {@link #registerOn(PdfRendererBuilder)} registers every face embedded as a subset, so all letters the font
 * has survive.
 * <p>
 * The CSS generic families {@code sans-serif}, {@code serif} and {@code monospace} work as well: openhtmltopdf
 * turns them into the Java logical names {@code SansSerif}, {@code Serif} and {@code Monospaced} before it looks
 * a font up (but stores registered names as given), so the faces of the family mapped to each logical name are
 * registered under that name too. The sans-serif family is also registered as the fallback openhtmltopdf tries
 * before the standard fonts, for letters the chosen font does not have.
 * <p>
 * Immutable and safe to share between threads and renders. No iDempiere dependency, testable on its own.
 */
public final class PdfFontCatalog {

	private static final Logger log = Logger.getLogger(PdfFontCatalog.class.getName());

	/** Java logical font name for CSS {@code sans-serif}. */
	public static final String LOGICAL_SANS_SERIF = "SansSerif";
	/** Java logical font name for CSS {@code serif}. */
	public static final String LOGICAL_SERIF = "Serif";
	/** Java logical font name for CSS {@code monospace}. */
	public static final String LOGICAL_MONOSPACED = "Monospaced";

	/** Default families for the logical names, used when the directories hold them. */
	public static final Map<String, String> DEFAULT_LOGICAL_FAMILIES;
	static {
		Map<String, String> m = new LinkedHashMap<>();
		m.put(LOGICAL_SANS_SERIF, "DejaVu Sans");
		m.put(LOGICAL_SERIF, "DejaVu Serif");
		m.put(LOGICAL_MONOSPACED, "DejaVu Sans Mono");
		DEFAULT_LOGICAL_FAMILIES = Collections.unmodifiableMap(m);
	}

	/** A catalog without fonts: the render keeps openhtmltopdf's standard fonts. */
	public static final PdfFontCatalog EMPTY = new PdfFontCatalog(List.of(), Map.of());

	/**
	 * One font face.
	 *
	 * @param family the family CSS names it by, for example "Helvetica Neue" or "DejaVu Sans Condensed"
	 * @param weight 100 … 900
	 * @param italic italic or oblique
	 * @param file   the font file
	 */
	public record Face(String family, int weight, boolean italic, File file) {}

	private final List<Face> faces;
	private final Map<String, String> logicalFamilies;

	private PdfFontCatalog(List<Face> faces, Map<String, String> logicalFamilies) {
		this.faces = List.copyOf(faces);
		this.logicalFamilies = Collections.unmodifiableMap(new LinkedHashMap<>(logicalFamilies));
	}

	/**
	 * Reads every {@code .ttf} and {@code .otf} file of the directories (not their subdirectories).
	 * <p>
	 * When two files give the same family, weight and style, the first one wins: directories in the given order,
	 * files by name. Symbol fonts (no Latin letters) and files that cannot be read are left out and logged.
	 *
	 * @param directories     font directories; null entries and missing directories are ignored
	 * @param logicalFamilies family to use for {@link #LOGICAL_SANS_SERIF}, {@link #LOGICAL_SERIF} and
	 *                        {@link #LOGICAL_MONOSPACED}; a name whose family is not found is not mapped
	 * @return the catalog, never null
	 */
	public static PdfFontCatalog scan(List<File> directories, Map<String, String> logicalFamilies) {
		Map<String, Face> byKey = new LinkedHashMap<>();
		for (File dir : directories) {
			if (dir == null || !dir.isDirectory())
				continue;
			File[] files = dir.listFiles((d, n) -> isFontFile(n));
			if (files == null)
				continue;
			Arrays.sort(files);
			for (File file : files) {
				Face face = readFace(file);
				if (face == null)
					continue;
				String key = key(face.family(), face.weight(), face.italic());
				Face previous = byKey.putIfAbsent(key, face);
				if (previous != null && log.isLoggable(Level.FINE))
					log.fine(file + " not used: " + previous.file() + " gives the same face");
			}
		}

		Map<String, String> mapped = new LinkedHashMap<>();
		if (logicalFamilies != null) {
			for (Map.Entry<String, String> e : logicalFamilies.entrySet()) {
				String family = e.getValue();
				if (family == null || family.isBlank())
					continue;
				boolean found = byKey.values().stream().anyMatch(f -> f.family().equalsIgnoreCase(family.trim()));
				if (found)
					mapped.put(e.getKey(), family.trim());
				else
					log.warning("PDF fonts: no font file for family \"" + family + "\", " + e.getKey()
							+ " keeps the standard PDF font");
			}
		}
		return new PdfFontCatalog(new ArrayList<>(byKey.values()), mapped);
	}

	/** @return the faces, in the order they were found */
	public List<Face> getFaces() {
		return faces;
	}

	/** @return logical name ({@code SansSerif}, {@code Serif}, {@code Monospaced}) → family it is mapped to */
	public Map<String, String> getLogicalFamilies() {
		return logicalFamilies;
	}

	/** @return true when the catalog has no fonts */
	public boolean isEmpty() {
		return faces.isEmpty();
	}

	/**
	 * Registers every face on the builder, embedded as a subset. The builder reads a file only when the document
	 * uses that face.
	 *
	 * @param builder a new builder; one builder serves one render
	 */
	public void registerOn(PdfRendererBuilder builder) {
		String fallbackFamily = logicalFamilies.get(LOGICAL_SANS_SERIF);
		for (Face face : faces) {
			FontStyle style = face.italic() ? FontStyle.ITALIC : FontStyle.NORMAL;
			builder.useFont(face.file(), face.family(), face.weight(), style, true);
			for (Map.Entry<String, String> e : logicalFamilies.entrySet()) {
				if (!e.getValue().equalsIgnoreCase(face.family()))
					continue;
				EnumSet<FSFontUseCase> useCases = EnumSet.of(FSFontUseCase.DOCUMENT);
				if (LOGICAL_SANS_SERIF.equals(e.getKey()) && fallbackFamily != null)
					useCases.add(FSFontUseCase.FALLBACK_PRE);
				builder.useFont(face.file(), e.getKey(), face.weight(), style, true, useCases);
			}
		}
	}

	static boolean isFontFile(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		return n.endsWith(".ttf") || n.endsWith(".otf");
	}

	private static String key(String family, int weight, boolean italic) {
		return family.toLowerCase(Locale.ROOT) + '|' + weight + '|' + italic;
	}

	/**
	 * Family, weight and style of a font file, from its own tables:
	 * <ul>
	 * <li>family: the typographic family (name ID 16), else the family (ID 1). A condensed face whose family does
	 * not say so (DejaVu, Helvetica Neue) gets " Condensed" appended: openhtmltopdf does not match on width, so it
	 * would otherwise replace the normal face;</li>
	 * <li>weight: OS/2 {@code usWeightClass}, rounded to 100 … 900;</li>
	 * <li>italic: the OS/2 italic bit, or "Italic"/"Oblique" in the subfamily name (some Helvetica Neue files do
	 * not set the bit).</li>
	 * </ul>
	 *
	 * @return the face, or null for a symbol font or a file that cannot be read
	 */
	static Face readFace(File file) {
		try (TrueTypeFont font = new OTFParser(false, true).parse(file)) {
			if (font instanceof OpenTypeFont otf && otf.isPostScript()) {
				// PDFBox 2 embeds TrueType outlines only; an OpenType font with CFF outlines cannot be embedded
				log.warning("PDF fonts: " + file + " not used: OpenType font with CFF outlines");
				return null;
			}
			NamingTable naming = font.getNaming();
			if (naming == null)
				return null;
			String family = firstNonBlank(naming.getName(16, 3, 1, 0x409), naming.getName(16, 1, 0, 0),
					naming.getFontFamily());
			String subfamily = firstNonBlank(naming.getName(17, 3, 1, 0x409), naming.getName(17, 1, 0, 0),
					naming.getFontSubFamily(), "");
			if (family == null)
				return null;
			CmapLookup cmap = font.getUnicodeCmapLookup(false);
			if (cmap == null || cmap.getGlyphId('a') == 0) {
				log.info("PDF fonts: " + file.getName() + " not used: symbol font without Latin letters");
				return null;
			}

			OS2WindowsMetricsTable os2 = font.getOS2Windows();
			int weight = os2 != null ? roundWeight(os2.getWeightClass()) : weightFromName(subfamily);
			String sub = subfamily.toLowerCase(Locale.ROOT);
			boolean italic = (os2 != null && (os2.getFsSelection() & 1) != 0) || sub.contains("italic")
					|| sub.contains("oblique");
			if (sub.contains("condensed") && !family.toLowerCase(Locale.ROOT).contains("condensed"))
				family = family + " Condensed";
			return new Face(family.trim(), weight, italic, file);
		} catch (Exception | LinkageError e) {
			log.log(Level.WARNING, "PDF fonts: " + file + " not used: " + e.getMessage(), e);
			return null;
		}
	}

	static int roundWeight(int weightClass) {
		if (weightClass <= 0)
			return 400;
		int w = Math.round(weightClass / 100f) * 100;
		return Math.max(100, Math.min(900, w));
	}

	private static int weightFromName(String subfamily) {
		String s = subfamily.toLowerCase(Locale.ROOT);
		if (s.contains("bold"))
			return 700;
		if (s.contains("light"))
			return 300;
		return 400;
	}

	private static String firstNonBlank(String... values) {
		for (String v : values)
			if (v != null && !v.isBlank())
				return v;
		return null;
	}
}
