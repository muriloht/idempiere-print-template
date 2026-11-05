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

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.Lookup;
import org.compiere.model.MRole;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.model.Query;
import org.compiere.util.CLogger;
import org.compiere.util.DisplayType;
import org.compiere.util.Util;
import org.idempiere.print.freemarker.FreemarkerConfigurationFactory;
import org.idempiere.print.template.model.MPrintTemplateInclude;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * PO + filhos → árvore navegável (Map) pro data model dos templates.
 * Mesma árvore serializa em JSON (Export JSON / prompt de IA — Fase 3).
 *
 * Conversões: BigDecimal/Timestamp/Boolean preservados (o engine
 * formata); FK vira {id, display}; colunas encriptadas ficam de fora.
 *
 * Spec: riskora/docs/SPEC_IDEMPIERE_PRINT_TEMPLATE_ENGINE.md §10
 */
public final class PO2MapBuilder {

	/** Cap defensivo quando o include não declara MaxRows */
	public static final int MAX_ROWS_DEFAULT = 1000;

	private static final CLogger log = CLogger.getCLogger(PO2MapBuilder.class);

	private PO2MapBuilder() {}

	/**
	 * Registro + filhos. Includes declarados mandam; lista vazia cai na
	 * convenção &lt;Master&gt;Line / &lt;Master&gt;Tax (spec §6.2).
	 */
	public static Map<String, Object> build(PO po, List<MPrintTemplateInclude> includes) {
		Map<String, Object> map = buildFlat(po);
		if (includes == null || includes.isEmpty()) {
			addChildrenByConvention(map, po, po.get_TableName() + "Line", "lines");
			addChildrenByConvention(map, po, po.get_TableName() + "Tax", "taxes");
		} else {
			for (MPrintTemplateInclude include : includes)
				map.put(include.getTargetName(), loadChildren(po, include));
		}
		return map;
	}

	/** Só as colunas do registro, sem filhos. */
	public static Map<String, Object> buildFlat(PO po) {
		POInfo info = POInfo.getPOInfo(po.getCtx(), po.get_Table_ID());
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < info.getColumnCount(); i++) {
			if (info.isEncrypted(i))
				continue;
			String columnName = info.getColumnName(i);
			Object value = po.get_Value(i);
			int displayType = info.getColumnDisplayType(i);
			if (value instanceof Integer && DisplayType.isLookup(displayType)
					&& !info.isKey(i)) {
				Map<String, Object> fk = new LinkedHashMap<>();
				fk.put("id", value);
				fk.put("display", po.get_DisplayValue(columnName, true));
				map.put(columnName, fk);
			} else {
				map.put(columnName, value);
			}
		}
		return map;
	}

	/**
	 * Igual ao {@link #buildFlat(PO)} mas a partir de um {@link ResultSet}
	 * (linha corrente) — pro caso de VIEW keyless, onde não dá pra construir
	 * PO ({@code PO.setKeyInfo} exige PK/UU/FK). Tipos via POInfo (Integer/
	 * BigDecimal/Timestamp/Boolean/String); FK vira {id, display} usando o
	 * lookup do POInfo (sem instância de PO).
	 */
	public static Map<String, Object> buildFlatFromRS(POInfo info, ResultSet rs) throws SQLException {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < info.getColumnCount(); i++) {
			if (info.isEncrypted(i))
				continue;
			String columnName = info.getColumnName(i);
			int displayType = info.getColumnDisplayType(i);
			Object value = readValue(rs, columnName, info.getColumnClass(i));
			if (value instanceof Integer && DisplayType.isLookup(displayType) && !info.isKey(i)) {
				Map<String, Object> fk = new LinkedHashMap<>();
				fk.put("id", value);
				Lookup lookup = info.getColumnLookup(i);
				fk.put("display", lookup != null ? lookup.getDisplay(value) : String.valueOf(value));
				map.put(columnName, fk);
			} else {
				map.put(columnName, value);
			}
		}
		return map;
	}

	/** Lê a coluna do ResultSet no tipo Java do POInfo (mesma convenção do PO.load) */
	private static Object readValue(ResultSet rs, String columnName, Class<?> clazz) throws SQLException {
		if (clazz == Integer.class) {
			int v = rs.getInt(columnName);
			return rs.wasNull() ? null : Integer.valueOf(v);
		}
		if (clazz == BigDecimal.class)
			return rs.getBigDecimal(columnName);
		if (clazz == Timestamp.class)
			return rs.getTimestamp(columnName);
		if (clazz == Boolean.class) {
			String s = rs.getString(columnName);
			return s == null ? null : Boolean.valueOf("Y".equals(s));
		}
		return rs.getString(columnName);
	}

	/**
	 * The access filter of Query needs a role; without one (no #AD_Role_ID/#AD_User_ID in the context)
	 * MRole.getDefault returns null and Query would skip the filter, so fail instead. Called just
	 * before a child query runs: a record without child tables needs no role.
	 */
	private static void requireRole(PO po) {
		requireRole(po.getCtx(), po.get_TableName());
	}

	/**
	 * @param ctx context of the query
	 * @param tableName table whose data is read, named in the message
	 * @return the role of the context
	 * @throws AdempiereException when the context has no role
	 */
	public static MRole requireRole(Properties ctx, String tableName) {
		MRole role = MRole.getDefault(ctx, false);
		if (role == null)
			throw new AdempiereException("Template child data of " + tableName
					+ " needs a role in the context; the user running this print needs a role");
		return role;
	}

	/** The word SELECT, any case, as a whole word: a subquery. */
	private static final Pattern SELECT_WORD = Pattern.compile("(?i)\\bselect\\b");

	/**
	 * SQL fragment of an include (WhereClause, OrderByClause): no second statement and no comment; in
	 * strict mode ({@link FreemarkerConfigurationFactory#isStrict()}) also no subquery, since a subquery
	 * would read other tables without the role's access filter.
	 */
	private static void rejectUnsafeSql(MPrintTemplateInclude include, String field, String sql) {
		if (Util.isEmpty(sql, true))
			return;
		if (sql.contains(";") || sql.contains("--") || sql.contains("/*")
				|| (FreemarkerConfigurationFactory.isStrict() && SELECT_WORD.matcher(sql).find()))
			throw new AdempiereException("Include '" + include.getTargetName() + "': " + field + " inválida");
	}

	private static List<Map<String, Object>> loadChildren(PO po, MPrintTemplateInclude include) {
		MTable childTable = MTable.get(po.getCtx(), include.getAD_Table_ID());
		String fkColumn = po.get_TableName() + "_ID";
		POInfo childInfo = POInfo.getPOInfo(po.getCtx(), childTable.getAD_Table_ID());
		if (childInfo.getColumnIndex(fkColumn) < 0)
			throw new AdempiereException("Include '" + include.getTargetName() + "': tabela "
					+ childTable.getTableName() + " não tem FK " + fkColumn);

		String extraWhere = include.getWhereClause();
		rejectUnsafeSql(include, "WhereClause", extraWhere);
		rejectUnsafeSql(include, "OrderByClause", include.getOrderByClause());

		requireRole(po);
		StringBuilder where = new StringBuilder(fkColumn).append("=? AND AD_Client_ID=?");
		if (!Util.isEmpty(extraWhere, true))
			where.append(" AND (").append(extraWhere).append(")");

		Query query = new Query(po.getCtx(), childTable.getTableName(), where.toString(), po.get_TrxName())
				.setParameters(po.get_ID(), po.getAD_Client_ID())
				.setOnlyActiveRecords(true)
				.setApplyAccessFilter(true);
		if (!Util.isEmpty(include.getOrderByClause(), true))
			query.setOrderBy(include.getOrderByClause());

		int cap = include.getMaxRows() > 0 ? include.getMaxRows() : MAX_ROWS_DEFAULT;
		List<PO> rows = query.list();
		List<Map<String, Object>> result = new ArrayList<>();
		for (PO row : rows) {
			if (result.size() >= cap) {
				log.warning("Include '" + include.getTargetName() + "' truncado em " + cap
						+ " linhas (" + rows.size() + " no total)");
				break;
			}
			result.add(buildFlat(row));
		}
		return result;
	}

	private static void addChildrenByConvention(Map<String, Object> map, PO po,
			String childTableName, String targetName) {
		List<Map<String, Object>> result = loadChildrenByFK(po, childTableName);
		if (result != null)
			map.put(targetName, result);
	}

	/**
	 * Filhos de uma tabela arbitrária via FK reversa (&lt;master&gt;_ID).
	 * @return null se a tabela não existe ou não tem a FK
	 */
	public static List<Map<String, Object>> loadChildrenByFK(PO po, String childTableName) {
		int childTableId = MTable.getTable_ID(childTableName);
		if (childTableId <= 0)
			return null;
		String fkColumn = po.get_TableName() + "_ID";
		if (POInfo.getPOInfo(po.getCtx(), childTableId).getColumnIndex(fkColumn) < 0)
			return null;
		requireRole(po);
		List<PO> rows = new Query(po.getCtx(), childTableName, fkColumn + "=? AND AD_Client_ID=?", po.get_TrxName())
				.setParameters(po.get_ID(), po.getAD_Client_ID())
				.setOnlyActiveRecords(true)
				.setApplyAccessFilter(true)
				.list();
		List<Map<String, Object>> result = new ArrayList<>();
		for (PO row : rows) {
			if (result.size() >= MAX_ROWS_DEFAULT) {
				log.warning(childTableName + " truncado em " + MAX_ROWS_DEFAULT + " linhas");
				break;
			}
			result.add(buildFlat(row));
		}
		return result;
	}

	/** Mesma árvore como JSON (Export JSON / prompt de IA). */
	public static String toJson(Map<String, Object> data, boolean pretty) {
		return toJson((Object) data, pretty);
	}

	/** Variante pra listas (export multi-registro). */
	public static String toJson(Object data, boolean pretty) {
		GsonBuilder builder = new GsonBuilder().serializeNulls();
		if (pretty)
			builder.setPrettyPrinting();
		Gson gson = builder.create();
		return gson.toJson(data);
	}
}
