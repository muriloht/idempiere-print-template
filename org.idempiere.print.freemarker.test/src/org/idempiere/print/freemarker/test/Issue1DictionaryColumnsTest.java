package org.idempiere.print.freemarker.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.model.Query;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.idempiere.print.template.PO2MapBuilder;
import org.idempiere.test.AbstractTestCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * IDEMPIERE-7070 issue #1, problem 3: the result-set column set is dictionary-driven — virtual
 * (ColumnSQL) columns are shown, and a physical column the view does not return is reported instead of
 * silently dropped (unless the lenient SysConfig is on).
 */
@Tag("integration")
public class Issue1DictionaryColumnsTest extends AbstractTestCase {

	private static final int GARDEN_WORLD = 11;

	private PO anOrder() {
		return new Query(Env.getCtx(), "C_Order", "AD_Client_ID=?", getTrxName())
				.setParameters(GARDEN_WORLD).setOrderBy("C_Order_ID").first();
	}

	/** @return the name of the first non-encrypted virtual (ColumnSQL) column of the table, or null */
	private static String firstVirtual(POInfo info) {
		for (int i = 0; i < info.getColumnCount(); i++)
			if (info.isVirtualColumn(i) && !info.isEncrypted(i))
				return info.getColumnName(i);
		return null;
	}

	/**
	 * Reads one row of {@code tableName} with the dictionary's virtual columns appended, as the keyless
	 * path does. Returns null when the SELECT does not evaluate here (a candidate whose ColumnSQL cannot
	 * run against this table), so the caller can try the next candidate.
	 */
	private Map<String, Object> readRow(String tableName, POInfo info, String keyColumn, int id) {
		String sql = "SELECT *" + PO2MapBuilder.virtualColumns(info) + " FROM " + tableName
				+ " WHERE " + keyColumn + "=?";
		try (PreparedStatement ps = DB.prepareStatement(sql, getTrxName())) {
			ps.setInt(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? PO2MapBuilder.buildFlatFromRS(Env.getCtx(), info, rs, null, true) : null;
			}
		} catch (Exception e) {
			return null;
		}
	}

	private Map<String, Object> rowFrom(String sql, int id, boolean lenient) throws Exception {
		POInfo info = POInfo.getPOInfo(Env.getCtx(), MTable.getTable_ID("C_Order"));
		try (PreparedStatement ps = DB.prepareStatement(sql, getTrxName())) {
			ps.setInt(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				return PO2MapBuilder.buildFlatFromRS(Env.getCtx(), info, rs, null, lenient);
			}
		}
	}

	/**
	 * A SELECT that includes the dictionary's virtual (ColumnSQL) columns shows them in the row. Finds any
	 * base table that has an active virtual column, a single key and a row, so the check runs regardless of
	 * which tables this database happens to have virtual columns on.
	 */
	@Test
	void virtualColumnsAreShown() throws Exception {
		String candidates = "SELECT t.TableName FROM AD_Column c"
				+ " JOIN AD_Table t ON t.AD_Table_ID=c.AD_Table_ID"
				+ " WHERE c.IsActive='Y' AND c.IsEncrypted='N' AND COALESCE(c.ColumnSQL,'')<>''"
				+ " AND t.IsView='N' AND t.IsActive='Y'"
				+ " GROUP BY t.TableName ORDER BY t.TableName";
		try (PreparedStatement ps = DB.prepareStatement(candidates, getTrxName());
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				String tableName = rs.getString(1);
				int tableId = MTable.getTable_ID(tableName);
				if (tableId <= 0)
					continue;
				POInfo info = POInfo.getPOInfo(Env.getCtx(), tableId);
				String[] keys = MTable.get(Env.getCtx(), tableId).getKeyColumns();
				if (info == null || keys == null || keys.length != 1)
					continue;
				String virtual = firstVirtual(info);
				if (virtual == null)
					continue;
				int id = DB.getSQLValueEx(getTrxName(), "SELECT MIN(" + keys[0] + ") FROM " + tableName);
				if (id <= 0)
					continue;
				Map<String, Object> row = readRow(tableName, info, keys[0], id);
				if (row == null)
					continue; // this candidate's ColumnSQL did not evaluate — try the next
				assertTrue(row.containsKey(virtual), virtual + " should be present in " + tableName);
				return; // proven on a real table
			}
		}
		assumeTrue(false, "no base table with a usable virtual column and a row in this database");
	}

	/** A physical dictionary column the query does not return is reported in strict mode. */
	@Test
	void missingPhysicalColumnIsReportedInStrictMode() throws Exception {
		PO order = anOrder();
		assertNotNull(order, "no GardenWorld order");
		// a deliberately narrow SELECT: every other physical column of C_Order is "missing"
		String sql = "SELECT C_Order_ID, DocumentNo FROM C_Order WHERE C_Order_ID=?";
		AdempiereException ex = assertThrows(AdempiereException.class, () -> rowFrom(sql, order.get_ID(), false));
		assertTrue(ex.getMessage().contains("C_Order"), ex.getMessage());
	}

	/** The same mismatch is skipped (legacy) when PRT_FREEMARKER_LENIENT_COLUMNS is on. */
	@Test
	void missingPhysicalColumnIsSkippedWhenLenient() throws Exception {
		PO order = anOrder();
		assertNotNull(order, "no GardenWorld order");
		String sql = "SELECT C_Order_ID, DocumentNo FROM C_Order WHERE C_Order_ID=?";
		Map<String, Object> row = rowFrom(sql, order.get_ID(), true);
		assertEquals(order.get_ValueAsString("DocumentNo"), row.get("DocumentNo"));
		assertTrue(!row.containsKey("C_BPartner_ID"), "a missing column must be skipped when lenient");
	}
}
