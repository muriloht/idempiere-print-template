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

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.compiere.model.MAttachment;
import org.compiere.model.MAttachmentEntry;
import org.idempiere.print.template.model.MPrintTemplate;

/**
 * Assets (logos, CSS, fontes) = AD_Attachment do AD_PrintTemplate,
 * expostos no data model como {@code assets[nome]} já em base64 —
 * no template: {@code <img src="data:image/png;base64,${assets['logo.png']}"/>}
 */
public final class AssetResolver {

	private AssetResolver() {}

	/**
	 * @param ctx context of the request (the cached template's context may be another client's)
	 * @param template the template
	 * @return attachment name to base64 content
	 */
	public static Map<String, String> asBase64(Properties ctx, MPrintTemplate template) {
		Map<String, String> assets = new LinkedHashMap<>();
		MAttachment attachment = MAttachment.get(ctx,
				MPrintTemplate.Table_ID, template.getAD_PrintTemplate_ID());
		if (attachment != null) {
			for (MAttachmentEntry entry : attachment.getEntries()) {
				byte[] data = entry.getData();
				if (data != null)
					assets.put(entry.getName(), Base64.getEncoder().encodeToString(data));
			}
		}
		return assets;
	}
}
