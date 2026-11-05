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
package org.idempiere.print.template.model;

import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;

import org.compiere.model.Query;
import org.compiere.util.Env;
import org.idempiere.cache.ImmutableIntPOCache;
import org.idempiere.cache.ImmutablePOSupport;

/**
 * Template declarativo de impressão. Templates ficam quentes (chamados a
 * cada print), então há cache — invalidado em qualquer save/delete.
 * <p>
 * The cache keeps immutable instances; {@link #get(Properties, int)} returns a copy bound to the
 * caller's context (as {@code MTable.get} does), so data read through the template's context uses
 * the caller's client and role, not those of whoever loaded it first.
 *
 * Spec: riskora/docs/SPEC_IDEMPIERE_PRINT_TEMPLATE_ENGINE.md §8.4
 */
public class MPrintTemplate extends X_AD_PrintTemplate implements ImmutablePOSupport {

	private static final long serialVersionUID = 1L;

	private static final ImmutableIntPOCache<Integer, MPrintTemplate> s_cache =
			new ImmutableIntPOCache<>(Table_Name, 20);

	/**
	 * Busca com cache. The returned instance is immutable and bound to {@code ctx} (a copy when the
	 * cached one has another context); to edit, load a fresh one with the constructor and a trxName.
	 *
	 * @param ctx context of the caller: client, role and language of every read through the template
	 */
	public static MPrintTemplate get(Properties ctx, int AD_PrintTemplate_ID) {
		if (AD_PrintTemplate_ID <= 0)
			return null;
		Integer key = Integer.valueOf(AD_PrintTemplate_ID);
		MPrintTemplate template = s_cache.get(ctx, key, e -> new MPrintTemplate(ctx, e));
		if (template != null)
			return template;
		template = new MPrintTemplate(ctx, AD_PrintTemplate_ID, null);
		if (template.get_ID() != AD_PrintTemplate_ID)
			return null;
		s_cache.put(key, template, e -> new MPrintTemplate(Env.getCtx(), e));
		return template;
	}

	public MPrintTemplate(Properties ctx, int AD_PrintTemplate_ID, String trxName) {
		super(ctx, AD_PrintTemplate_ID, trxName);
	}

	public MPrintTemplate(Properties ctx, String AD_PrintTemplate_UU, String trxName) {
		super(ctx, AD_PrintTemplate_UU, trxName);
	}

	public MPrintTemplate(Properties ctx, ResultSet rs, String trxName) {
		super(ctx, rs, trxName);
	}

	/**
	 * Copy bound to another context. The includes are not copied: {@link #getIncludes()} reads them
	 * when asked, with the copy's context.
	 *
	 * @param ctx context of the copy
	 * @param copy template to copy
	 */
	public MPrintTemplate(Properties ctx, MPrintTemplate copy) {
		this(ctx, copy, (String) null);
	}

	/**
	 * @param ctx context of the copy
	 * @param copy template to copy
	 * @param trxName transaction of the copy
	 */
	public MPrintTemplate(Properties ctx, MPrintTemplate copy, String trxName) {
		this(ctx, 0, trxName);
		copyPO(copy);
	}

	@Override
	public MPrintTemplate markImmutable() {
		if (is_Immutable())
			return this;
		makeImmutable();
		return this;
	}

	/**
	 * Includes declarados (filhos a carregar no data model), ordenados por
	 * SeqNo. Lista vazia = caller aplica o fallback de convenção
	 * (&lt;Master&gt;Line / &lt;Master&gt;Tax — spec §6.2).
	 */
	public List<MPrintTemplateInclude> getIncludes() {
		return new Query(getCtx(), I_AD_PrintTemplate_Include.Table_Name,
				I_AD_PrintTemplate_Include.COLUMNNAME_AD_PrintTemplate_ID + "=?", get_TrxName())
			.setParameters(get_ID())
			.setOnlyActiveRecords(true)
			.setOrderBy(I_AD_PrintTemplate_Include.COLUMNNAME_SeqNo)
			.list();
	}

	@Override
	protected boolean afterSave(boolean newRecord, boolean success) {
		if (success)
			s_cache.reset();
		return success;
	}

	@Override
	protected boolean afterDelete(boolean success) {
		if (success)
			s_cache.reset();
		return success;
	}
}
