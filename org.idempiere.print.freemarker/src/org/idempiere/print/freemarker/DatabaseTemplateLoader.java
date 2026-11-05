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

import java.io.Reader;
import java.io.StringReader;
import java.util.Properties;

import org.compiere.model.Query;
import org.compiere.util.Env;
import org.idempiere.print.template.model.I_AD_PrintTemplate;
import org.idempiere.print.template.model.MPrintTemplate;

import freemarker.cache.TemplateLoader;

/**
 * Resolve nome → AD_PrintTemplate.TemplateBody (cascata client:
 * registro do tenant sobrepõe o de System, que sobrepõe o classpath —
 * este loader vem antes do ClassTemplateLoader no MultiTemplateLoader).
 * getLastModified usa Updated, então edição no banco invalida o cache
 * do FreeMarker dentro do update delay.
 */
public class DatabaseTemplateLoader implements TemplateLoader {

	private final int m_adClientId;

	/**
	 * @param adClientId cliente fixo deste loader; não depende do contexto da thread
	 */
	public DatabaseTemplateLoader(int adClientId) {
		m_adClientId = adClientId;
	}

	@Override
	public Object findTemplateSource(String name) {
		// Cópia do ctx com #AD_Client_ID do loader: o Query não pode lançar
		// CrossTenantException quando o cliente da thread difere do cliente deste loader
		Properties ctx = new Properties();
		ctx.putAll(Env.getCtx());
		Env.setContext(ctx, Env.AD_CLIENT_ID, m_adClientId);
		return new Query(ctx, I_AD_PrintTemplate.Table_Name,
				I_AD_PrintTemplate.COLUMNNAME_Name + "=? AND AD_Client_ID IN (0,?)", null)
			.setParameters(name, m_adClientId)
			.setOnlyActiveRecords(true)
			.setOrderBy("AD_Client_ID DESC, AD_Org_ID DESC")
			.first();
	}

	@Override
	public long getLastModified(Object templateSource) {
		return ((MPrintTemplate) templateSource).getUpdated().getTime();
	}

	@Override
	public Reader getReader(Object templateSource, String encoding) {
		return new StringReader(((MPrintTemplate) templateSource).getTemplateBody());
	}

	@Override
	public void closeTemplateSource(Object templateSource) {
		// nada a fechar
	}
}
