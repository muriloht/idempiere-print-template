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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.Lookup;
import org.compiere.model.MColumn;
import org.compiere.model.MLookup;
import org.compiere.model.MLookupFactory;
import org.compiere.model.MLookupInfo;
import org.compiere.model.MRefList;
import org.compiere.model.MRole;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.model.Query;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Language;
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

	/**
	 * Suffix of the key that holds the name of a list value in the document language: a list column
	 * keeps its code under the column name ({@code DocStatus = "CO"}) and gets its name next to it
	 * ({@code DocStatus_display = "Completed"}).
	 */
	public static final String LIST_NAME_SUFFIX = "_display";

	private static final CLogger log = CLogger.getCLogger(PO2MapBuilder.class);

	private PO2MapBuilder() {}

	/**
	 * Registro + filhos. Includes declarados mandam; lista vazia cai na
	 * convenção &lt;Master&gt;Line / &lt;Master&gt;Tax (spec §6.2).
	 */
	public static Map<String, Object> build(PO po, List<MPrintTemplateInclude> includes) {
		return build(po, includes, null);
	}

	/**
	 * Same as {@link #build(PO, List)} with the {@code display} of foreign keys in a document language.
	 *
	 * @param display document language of lookup values; null = the language of the record's context
	 */
	public static Map<String, Object> build(PO po, List<MPrintTemplateInclude> includes, DisplayLanguage display) {
		Map<String, Object> map = buildFlat(po, display);
		if (includes == null || includes.isEmpty()) {
			addChildrenByConvention(map, po, po.get_TableName() + "Line", "lines", display);
			addChildrenByConvention(map, po, po.get_TableName() + "Tax", "taxes", display);
		} else {
			for (MPrintTemplateInclude include : includes)
				map.put(include.getTargetName(), loadChildren(po, include, display));
		}
		return map;
	}

	/** Só as colunas do registro, sem filhos. */
	public static Map<String, Object> buildFlat(PO po) {
		return buildFlat(po, null);
	}

	/**
	 * Same as {@link #buildFlat(PO)} with the {@code display} of foreign keys in a document language.
	 *
	 * @param display document language of lookup values; null = the language of the record's context
	 */
	public static Map<String, Object> buildFlat(PO po, DisplayLanguage display) {
		Map<String, Object> viewRow = fromTranslationView(po, display);
		if (viewRow != null)
			return viewRow;
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
				String translated = display == null ? null : display.display(po.getCtx(), info, i, value, po.get_TrxName());
				fk.put("display", translated != null ? translated : po.get_DisplayValue(columnName, true));
				map.put(columnName, fk);
			} else {
				map.put(columnName, value);
				putListName(map, po.getCtx(), info, i, value, display);
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
		return buildFlatFromRS(null, info, rs, null);
	}

	/**
	 * Same as {@link #buildFlatFromRS(POInfo, ResultSet)} with the {@code display} of foreign keys in a
	 * document language.
	 *
	 * @param ctx context of the request (client, role); needed with {@code display}
	 * @param display document language of lookup values; null = the language of the POInfo's context
	 */
	public static Map<String, Object> buildFlatFromRS(Properties ctx, POInfo info, ResultSet rs,
			DisplayLanguage display) throws SQLException {
		Map<String, Object> map = new LinkedHashMap<>();
		Set<String> returned = columnLabels(rs);
		for (int i = 0; i < info.getColumnCount(); i++) {
			if (info.isEncrypted(i))
				continue;
			String columnName = info.getColumnName(i);
			// SELECT * returns no virtual (ColumnSQL) column: only what the query returns is read
			if (!returned.contains(columnName.toLowerCase()))
				continue;
			int displayType = info.getColumnDisplayType(i);
			Object value = readValue(rs, columnName, info.getColumnClass(i));
			if (value instanceof Integer && DisplayType.isLookup(displayType) && !info.isKey(i)) {
				Map<String, Object> fk = new LinkedHashMap<>();
				fk.put("id", value);
				String translated = display == null || ctx == null ? null : display.display(ctx, info, i, value);
				if (translated == null) {
					Lookup lookup = info.getColumnLookup(i);
					translated = lookup != null ? lookup.getDisplay(value) : String.valueOf(value);
				}
				fk.put("display", translated);
				map.put(columnName, fk);
			} else {
				map.put(columnName, value);
				putListName(map, ctx != null ? ctx : Env.getCtx(), info, i, value, display);
			}
		}
		return map;
	}

	/**
	 * Adds {@code <Column>_display}: the name of a list value (AD_Ref_List) in the document language,
	 * else in the language of the context. Without a translation in that language it is the base name;
	 * an inactive value keeps its name without core's {@code ~} markers; a value the list does not know
	 * prints as its code.
	 */
	private static void putListName(Map<String, Object> map, Properties ctx, POInfo info, int index, Object value,
			DisplayLanguage display) {
		if (!(value instanceof String) || ((String) value).isEmpty())
			return;
		int displayType = info.getColumnDisplayType(index);
		if (displayType != DisplayType.List && displayType != DisplayType.RadiogroupList)
			return;
		String columnName = info.getColumnName(index);
		int columnId = info.getAD_Column_ID(columnName);
		if (columnId <= 0)
			return;
		int referenceId = MColumn.get(ctx, columnId).getAD_Reference_Value_ID();
		if (referenceId <= 0)
			return;
		String adLanguage = display != null && display.getLanguage() != null
				? display.getLanguage().getAD_Language() : Env.getAD_Language(ctx);
		String code = (String) value;
		String name = MRefList.getListName(adLanguage, referenceId, code);
		// no translation in the document language: the base name (core's NVL(trl.Name, rl.Name));
		// a value the list does not know: its code
		if (Util.isEmpty(name) && !Language.isBaseLanguage(adLanguage))
			name = MRefList.getListName(Language.getBaseAD_Language(), referenceId, code);
		if (Util.isEmpty(name))
			name = code;
		else if (name.length() > MLookup.INACTIVE_S.length() + MLookup.INACTIVE_E.length()
				&& name.startsWith(MLookup.INACTIVE_S) && name.endsWith(MLookup.INACTIVE_E))
			name = name.substring(MLookup.INACTIVE_S.length(), name.length() - MLookup.INACTIVE_E.length());
		map.put(columnName + LIST_NAME_SUFFIX, name);
	}

	/**
	 * A record of a view {@code X_v} in a language other than the base language, read from its
	 * translation view {@code X_vt} as a print format reads it ({@code DataEngine}): the row of the same
	 * key in the document language. Its translated columns (document type, payment term, product…) carry
	 * the translation.
	 *
	 * @return the row's columns; null when there is no translation view, no single key or no row in the
	 *         document language (the caller reads the record itself)
	 */
	private static Map<String, Object> fromTranslationView(PO po, DisplayLanguage display) {
		if (display == null)
			return null;
		String tableName = po.get_TableName();
		String view = display.translationView(tableName);
		if (view == null)
			return null;
		String[] keys = po.get_KeyColumns();
		if (keys == null || keys.length != 1 || po.get_ID() <= 0)
			return null;
		// the view keeps the name of the table, so qualified column names read as they do on X_v
		String sql = "SELECT * FROM " + view + " " + tableName + " WHERE " + tableName + "." + keys[0] + "=? AND "
				+ tableName + ".AD_Language=?";
		try (PreparedStatement pstmt = DB.prepareStatement(sql, po.get_TrxName())) {
			pstmt.setInt(1, po.get_ID());
			pstmt.setString(2, display.getLanguage().getAD_Language());
			try (ResultSet rs = pstmt.executeQuery()) {
				if (rs.next())
					return buildFlatFromRS(po.getCtx(), POInfo.getPOInfo(po.getCtx(), po.get_Table_ID()), rs, display);
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, "No translated row in " + view + " for " + tableName + " " + po.get_ID()
					+ "; the record itself is used", e);
		}
		return null;
	}

	/** Lower-case labels of the columns a result set returns. */
	private static Set<String> columnLabels(ResultSet rs) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		Set<String> labels = new HashSet<>();
		for (int c = 1; c <= meta.getColumnCount(); c++)
			labels.add(meta.getColumnLabel(c).toLowerCase());
		return labels;
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

	private static List<Map<String, Object>> loadChildren(PO po, MPrintTemplateInclude include,
			DisplayLanguage display) {
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
			result.add(buildFlat(row, display));
		}
		return result;
	}

	private static void addChildrenByConvention(Map<String, Object> map, PO po,
			String childTableName, String targetName, DisplayLanguage display) {
		List<Map<String, Object>> result = loadChildrenByFK(po, childTableName, display);
		if (result != null)
			map.put(targetName, result);
	}

	/**
	 * Filhos de uma tabela arbitrária via FK reversa (&lt;master&gt;_ID).
	 * @return null se a tabela não existe ou não tem a FK
	 */
	public static List<Map<String, Object>> loadChildrenByFK(PO po, String childTableName) {
		return loadChildrenByFK(po, childTableName, null);
	}

	/**
	 * Same as {@link #loadChildrenByFK(PO, String)} with the {@code display} of foreign keys in a
	 * document language.
	 */
	public static List<Map<String, Object>> loadChildrenByFK(PO po, String childTableName,
			DisplayLanguage display) {
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
			result.add(buildFlat(row, display));
		}
		return result;
	}

	/**
	 * Lookup values ({@code display} of a foreign key) in a document language, for one render. Core's
	 * lookups of a PO and of a POInfo use the language of their context (the login language); a
	 * document printed in its business partner's language needs lookups built in that language.
	 * <p>
	 * The values are read with the lookup's own direct query, never through {@code MLookup.getDirect}:
	 * core keys its shared direct-value cache by the login language of the global context, not by the
	 * lookup's language, so a lookup in another language would store its names under the login
	 * language, and other users would see them (and this render would read theirs).
	 * <p>
	 * One instance per render: the lookups it keeps hold the context of the user printing, so an
	 * instance is never shared between requests or clients.
	 */
	public static final class DisplayLanguage {

		private final Language language;
		/** AD_Column_ID → lookup in {@link #language}; null value = no lookup for that column */
		private final Map<Integer, MLookupInfo> lookups = new HashMap<>();
		/** AD_Column_ID|key → name in {@link #language}; null value = not found */
		private final Map<String, String> values = new HashMap<>();
		/** lower-case view name → its translation view; "" = none */
		private final Map<String, String> views = new HashMap<>();

		/**
		 * @param language document language
		 */
		public DisplayLanguage(Language language) {
			this.language = language;
		}

		/** @return the document language */
		public Language getLanguage() {
			return language;
		}

		/**
		 * The translation view a print format reads instead of a view in this language: {@code X_vt} for
		 * {@code X_v}, when the language is not the base language and {@code X_vt} exists.
		 *
		 * @param tableName table or view the template reads
		 * @return the translation view; null = read {@code tableName} itself
		 */
		public String translationView(String tableName) {
			if (language == null || language.isBaseLanguage() || tableName == null
					|| !tableName.toLowerCase().endsWith("_v"))
				return null;
			String view = views.computeIfAbsent(tableName.toLowerCase(),
					t -> DB.isTableOrViewExists(tableName + "t") ? tableName + "t" : "");
			return view.isEmpty() ? null : view;
		}

		/**
		 * @param ctx context of the record (client, role)
		 * @param info table of the record
		 * @param index column index in {@code info}
		 * @param value key value of the foreign key
		 * @return the lookup value in the document language; null when the document language is the
		 *         context's (the caller keeps core's lookup) or no lookup can be built
		 */
		String display(Properties ctx, POInfo info, int index, Object value) {
			return display(ctx, info, index, value, null);
		}

		/**
		 * @param trxName transaction of the record; null outside one
		 */
		String display(Properties ctx, POInfo info, int index, Object value, String trxName) {
			if (language == null || value == null || language.getAD_Language().equals(Env.getAD_Language(ctx)))
				return null;
			int columnId = info.getAD_Column_ID(info.getColumnName(index));
			if (columnId <= 0)
				return null;
			MLookupInfo lookup;
			if (lookups.containsKey(columnId)) {
				lookup = lookups.get(columnId);
			} else {
				lookup = create(ctx, info, index, columnId);
				lookups.put(columnId, lookup);
			}
			if (lookup == null || Util.isEmpty(lookup.QueryDirect, true))
				return null;
			String key = columnId + "|" + value;
			if (values.containsKey(key))
				return values.get(key);
			String name = queryDirect(lookup, value, trxName);
			values.put(key, name);
			return name;
		}

		/** The name of one key with the lookup's direct query (SELECT Key, Value, Name, IsActive …). */
		private String queryDirect(MLookupInfo lookup, Object value, String trxName) {
			try (PreparedStatement pstmt = DB.prepareStatement(lookup.QueryDirect, trxName)) {
				if (value instanceof Integer)
					pstmt.setInt(1, (Integer) value);
				else
					pstmt.setString(1, value.toString());
				try (ResultSet rs = pstmt.executeQuery()) {
					return rs.next() ? rs.getString(3) : null;
				}
			} catch (SQLException e) {
				log.log(Level.WARNING, "No " + language.getAD_Language() + " value of " + lookup.KeyColumn + "=" + value
						+ "; login language used", e);
				return null;
			}
		}

		private MLookupInfo create(Properties ctx, POInfo info, int index, int columnId) {
			try {
				MColumn column = MColumn.get(ctx, columnId);
				MLookup lookup = MLookupFactory.get(ctx, 0, columnId, info.getColumnDisplayType(index), language,
						info.getColumnName(index), column.getAD_Reference_Value_ID(), false, "");
				return lookup == null ? null : lookup.getLookupInfo();
			} catch (Exception e) {
				log.log(Level.WARNING, "No lookup in " + language.getAD_Language() + " for "
						+ info.getTableName() + "." + info.getColumnName(index) + "; login language used", e);
				return null;
			}
		}
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
