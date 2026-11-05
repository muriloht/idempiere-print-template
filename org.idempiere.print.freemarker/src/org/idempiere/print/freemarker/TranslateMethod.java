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

import org.compiere.util.Msg;

import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateModelException;
import freemarker.template.TemplateScalarModel;
import freemarker.template.utility.DeepUnwrap;

/**
 * The template function {@code msg}: texts from the dictionary in the document language, so that
 * one template prints in every language the client uses.
 * <ul>
 * <li>{@code msg("DocumentNo")}: a message (AD_Message) of that value, else the print name of the
 * element (AD_Element) of that column name, translated ({@link Msg#translate(String, boolean, String,
 * boolean)}); the key itself when neither exists;</li>
 * <li>{@code msg("@DocumentNo@ / @DateOrdered@")}: every {@code @key@} replaced as above, the rest
 * kept;</li>
 * <li>{@code msg("Key", arg1, arg2…)}: the message with its {@code {0}}, {@code {1}}… placeholders
 * filled ({@link Msg#getMsg(String, String, Object[])}).</li>
 * </ul>
 * The result is a plain string: the HTML output format escapes it like any other value.
 */
public final class TranslateMethod implements TemplateMethodModelEx {

	private final String adLanguage;
	private final boolean isSOTrx;

	/**
	 * @param adLanguage document language (AD_Language)
	 * @param isSOTrx sales terminology of elements (Name vs PO_Name / PrintName vs PO_PrintName)
	 */
	public TranslateMethod(String adLanguage, boolean isSOTrx) {
		this.adLanguage = adLanguage;
		this.isSOTrx = isSOTrx;
	}

	@Override
	public Object exec(@SuppressWarnings("rawtypes") List arguments) throws TemplateModelException {
		if (arguments.isEmpty())
			throw new TemplateModelException("msg(key[, argument…]) needs a key");
		String key = asString(arguments.get(0));
		if (arguments.size() > 1) {
			Object[] args = new Object[arguments.size() - 1];
			for (int i = 1; i < arguments.size(); i++)
				args[i - 1] = DeepUnwrap.unwrap((TemplateModel) arguments.get(i));
			return Msg.getMsg(adLanguage, key, args);
		}
		if (key.indexOf('@') >= 0)
			return translateTokens(key);
		return translate(key);
	}

	private String translate(String key) {
		return Msg.translate(adLanguage, isSOTrx, key, true);
	}

	/** Same tokens as {@link Msg#parseTranslation}, translated in the document language. */
	private String translateTokens(String text) {
		StringBuilder out = new StringBuilder();
		String rest = text;
		int start = rest.indexOf('@');
		while (start >= 0) {
			int end = rest.indexOf('@', start + 1);
			if (end < 0)
				break;
			out.append(rest, 0, start).append(translate(rest.substring(start + 1, end)));
			rest = rest.substring(end + 1);
			start = rest.indexOf('@');
		}
		return out.append(rest).toString();
	}

	private static String asString(Object argument) throws TemplateModelException {
		if (argument instanceof TemplateScalarModel)
			return ((TemplateScalarModel) argument).getAsString();
		throw new TemplateModelException("msg: the key must be a string");
	}
}
