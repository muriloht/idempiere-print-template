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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.compiere.model.MSysConfig;
import org.idempiere.print.template.PdfFontCatalog;

/**
 * Builds the {@link PdfFontCatalog} for a PDF render from the fonts iDempiere has, and caches it.
 * <p>
 * This is the iDempiere-aware side of the font support, kept apart from the engine-agnostic
 * {@link PdfFontCatalog} / {@link org.idempiere.print.template.HtmlToPdfConverter}. Two font sources,
 * admin first so it wins a tie:
 * <ol>
 * <li>the directory in the core SysConfig {@code PDF_FONT_DIR} ({@link MSysConfig#PDF_FONT_DIR}), the same
 * key the classic iText print path has read since 2009, so a font an admin drops there serves both engines;</li>
 * <li>the DejaVu Sans/Serif/Mono faces bundled with this plugin — the baseline that keeps letters outside
 * Western Europe ({@code Č ľ ť ň ő ű ł …}) from printing as {@code #}. These are the same faces the Jasper
 * engine bundles, so a document renders the same across engines.</li>
 * </ol>
 * The plugin stays independent of the Jasper bundle: it ships its own copy of the fonts rather than reading
 * Jasper's font-extension jar (whose classpath an OSGi bundle cannot reach anyway).
 * <p>
 * A font problem never breaks a render: a source that cannot be read is logged and left out, and the render
 * falls back to openhtmltopdf's standard fonts.
 */
public final class PdfFonts {

	private static final Logger log = Logger.getLogger(PdfFonts.class.getName());

	/** Classpath folder (bundle root) that holds the bundled faces. */
	private static final String BUNDLED_PATH = "/fonts/";

	/** The bundled DejaVu faces, covering the three default logical families, normal/bold/italic. */
	private static final String[] BUNDLED_FACES = {
			"DejaVuSans.ttf", "DejaVuSans-Bold.ttf", "DejaVuSans-Oblique.ttf", "DejaVuSans-BoldOblique.ttf",
			"DejaVuSerif.ttf", "DejaVuSerif-Bold.ttf", "DejaVuSerif-Italic.ttf", "DejaVuSerif-BoldItalic.ttf",
			"DejaVuSansMono.ttf", "DejaVuSansMono-Bold.ttf", "DejaVuSansMono-Oblique.ttf",
			"DejaVuSansMono-BoldOblique.ttf" };

	/** Catalog per resolved {@code PDF_FONT_DIR} value; the bundled faces are the same for all. */
	private static final Map<String, PdfFontCatalog> CACHE = new ConcurrentHashMap<>();

	/** The bundled faces, extracted to a temp directory once per JVM so openhtmltopdf can read them as files. */
	private static volatile File bundledDir;

	private PdfFonts() {}

	/**
	 * The fonts a render should use for the given client: {@code PDF_FONT_DIR} (if set) over the bundled DejaVu.
	 * Built once per {@code PDF_FONT_DIR} value and cached.
	 *
	 * @param adClientId the render's client, for the {@code PDF_FONT_DIR} SysConfig scope
	 * @return the catalog, never null (empty only when nothing could be read)
	 */
	public static PdfFontCatalog get(int adClientId) {
		String fontDir = MSysConfig.getValue(MSysConfig.PDF_FONT_DIR, "", adClientId);
		String key = fontDir == null ? "" : fontDir.trim();
		return CACHE.computeIfAbsent(key, PdfFonts::build);
	}

	/** Drops the cached catalogs; the next {@link #get(int)} rebuilds. For tests and config changes. */
	public static void reset() {
		CACHE.clear();
	}

	private static PdfFontCatalog build(String fontDir) {
		List<File> directories = new ArrayList<>();
		if (fontDir != null && !fontDir.isBlank()) {
			File dir = new File(fontDir.trim());
			if (dir.isDirectory())
				directories.add(dir); // admin first: a face here wins over the bundled one
			else
				log.warning("PDF_FONT_DIR is not a directory, ignored: " + fontDir);
		}
		File bundled = bundledDir();
		if (bundled != null)
			directories.add(bundled);
		return PdfFontCatalog.scan(directories, PdfFontCatalog.DEFAULT_LOGICAL_FAMILIES);
	}

	/**
	 * Extracts the bundled faces to a temp directory once per JVM (openhtmltopdf reads a face from a file) and
	 * returns it. Returns null when nothing could be extracted, so the caller just skips the bundled source.
	 */
	private static File bundledDir() {
		File dir = bundledDir;
		if (dir != null)
			return dir;
		synchronized (PdfFonts.class) {
			if (bundledDir != null)
				return bundledDir;
			try {
				Path temp = Files.createTempDirectory("idempiere-pdf-fonts");
				int extracted = 0;
				for (String face : BUNDLED_FACES) {
					try (InputStream in = PdfFonts.class.getResourceAsStream(BUNDLED_PATH + face)) {
						if (in == null) {
							log.warning("Bundled font not on the classpath: " + BUNDLED_PATH + face);
							continue;
						}
						Files.copy(in, temp.resolve(face), StandardCopyOption.REPLACE_EXISTING);
						extracted++;
					}
				}
				if (extracted == 0) {
					log.warning("No bundled font could be extracted; PDFs keep the standard fonts");
					return null;
				}
				bundledDir = temp.toFile();
				return bundledDir;
			} catch (IOException e) {
				log.log(Level.WARNING, "Could not extract the bundled fonts: " + e.getMessage(), e);
				return null;
			}
		}
	}
}
