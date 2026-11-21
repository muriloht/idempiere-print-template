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

import java.util.List;

import org.compiere.model.MSysConfig;

import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import freemarker.template.TemplateScalarModel;

/**
 * The template function {@code sysconfig}: an AD_SysConfig value in the printing context's client and
 * org — the template equivalent of a classic print format's {@code @$sysconfig.Name@} display logic.
 * <ul>
 * <li>{@code sysconfig("Key")}: the value, or an empty string when the key is not set;</li>
 * <li>{@code sysconfig("Key", "fallback")}: the value, or {@code fallback} when the key is not set.</li>
 * </ul>
 * The value is resolved org → client → system ({@link MSysConfig#getValue(String, String, int, int)}),
 * the same scope core uses for {@code $sysconfig.} (see {@code Env.getContext}). The result is a plain
 * string: the HTML output format escapes it like any other value.
 */
public final class SysConfigMethod implements TemplateMethodModelEx {

	private final int adClientId;
	private final int adOrgId;

	/**
	 * @param adClientId client of the printing context
	 * @param adOrgId org of the printing context
	 */
	public SysConfigMethod(int adClientId, int adOrgId) {
		this.adClientId = adClientId;
		this.adOrgId = adOrgId;
	}

	@Override
	public Object exec(@SuppressWarnings("rawtypes") List arguments) throws TemplateModelException {
		if (arguments.isEmpty())
			throw new TemplateModelException("sysconfig(key[, default]) needs a key");
		String key = asString(arguments.get(0), "key");
		String defaultValue = arguments.size() > 1 ? asString(arguments.get(1), "default") : "";
		return MSysConfig.getValue(key, defaultValue, adClientId, adOrgId);
	}

	private static String asString(Object argument, String what) throws TemplateModelException {
		if (argument instanceof TemplateScalarModel)
			return ((TemplateScalarModel) argument).getAsString();
		throw new TemplateModelException("sysconfig: the " + what + " must be a string");
	}
}
