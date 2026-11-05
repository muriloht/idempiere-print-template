package org.idempiere.print.freemarker.test;

import static org.idempiere.print.freemarker.test.TemplateTestSupport.PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MBPartner;
import org.compiere.model.MOrder;
import org.compiere.model.MOrg;
import org.compiere.model.POInfo;
import org.compiere.model.MRole;
import org.compiere.model.MTable;
import org.compiere.model.Query;
import org.compiere.process.DocAction;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.idempiere.print.template.PO2MapBuilder;
import org.idempiere.print.template.model.MPrintTemplateInclude;
import org.idempiere.test.AbstractTestCase;
import org.idempiere.test.DictionaryIDs;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The queries for the child rows of a template record add the client filter and the role's data
 * access filter, and the include WhereClause cannot carry a comment or a second statement.
 * <p>
 * The access test creates its order, lines and a new organization in the test transaction (rolled
 * back) and moves one line to that organization. The organization's access rows that
 * {@code MOrg.afterSave} adds for roles are deleted in the transaction, so no role sees it. The role
 * is an existing one, only read: it is loaded by {@code MRole.get} outside the test transaction, so
 * a role created in the transaction would not be seen by the access filter.
 */
@Tag("integration")
public class ChildQueryAccessTest extends AbstractTestCase {

	private static final int TENANT = 11;

	private MPrintTemplateInclude include(String where) {
		MPrintTemplateInclude include = new MPrintTemplateInclude(Env.getCtx(), 0, null);
		include.setAD_Table_ID(MTable.getTable_ID("C_OrderLine"));
		include.setTargetName("lines");
		if (where != null)
			include.setWhereClause(where);
		return include;
	}

	/**
	 * A draft order and copies of an existing GardenWorld order line, all in the test transaction.
	 * The lines are inserted by SQL because saving an MOrderLine runs a CLDE extension service that is
	 * not registered in this test runtime.
	 */
	private MOrder newOrderWithLines(int orgId, int lines) {
		MOrder order = new MOrder(Env.getCtx(), 0, getTrxName());
		order.setAD_Org_ID(orgId);
		order.setBPartner(MBPartner.get(Env.getCtx(), DictionaryIDs.C_BPartner.JOE_BLOCK.id));
		order.setC_DocTypeTarget_ID(DictionaryIDs.C_DocType.STANDARD_ORDER.id);
		order.setIsSOTrx(true);
		order.setDateOrdered(getLoginDate());
		order.setDocStatus(DocAction.STATUS_Drafted);
		order.setDocAction(DocAction.ACTION_Complete);
		order.saveEx();

		int source = DB.getSQLValueEx(null,
				"SELECT MIN(C_OrderLine_ID) FROM C_OrderLine WHERE AD_Client_ID=? AND IsActive='Y' AND QtyOrdered>0",
				TENANT);
		assertTrue(source > 0, "no GardenWorld order line to copy");
		POInfo info = POInfo.getPOInfo(Env.getCtx(), MTable.getTable_ID("C_OrderLine"));
		for (int i = 0; i < lines; i++) {
			// the values are numbers and a generated UUID, so they are written into the statement
			StringBuilder cols = new StringBuilder(), exprs = new StringBuilder();
			for (int c = 0; c < info.getColumnCount(); c++) {
				if (info.isVirtualColumn(c))
					continue;
				String col = info.getColumnName(c);
				String expr;
				switch (col) {
				case "C_OrderLine_ID": expr = String.valueOf(DB.getNextID(TENANT, "C_OrderLine", getTrxName())); break;
				case "C_OrderLine_UU": expr = "'" + UUID.randomUUID() + "'"; break;
				case "C_Order_ID": expr = String.valueOf(order.getC_Order_ID()); break;
				case "Line": expr = String.valueOf((i + 1) * 10); break;
				case "AD_Org_ID": expr = String.valueOf(orgId); break;
				default: expr = col;
				}
				if (cols.length() > 0) {
					cols.append(',');
					exprs.append(',');
				}
				cols.append(col);
				exprs.append(expr);
			}
			DB.executeUpdateEx("INSERT INTO C_OrderLine (" + cols + ") SELECT " + exprs
					+ " FROM C_OrderLine WHERE C_OrderLine_ID=?", new Object[] { source }, getTrxName());
		}
		return order;
	}

	@Test
	void commentInWhereClauseIsRejected() {
		MOrder order = newOrderWithLines(Env.getAD_Org_ID(Env.getCtx()), 1);
		AdempiereException e = assertThrows(AdempiereException.class,
				() -> PO2MapBuilder.build(order, List.of(include("1=1 /* x */"))));
		assertTrue(e.getMessage().contains("WhereClause"), e.getMessage());
	}

	@Test
	void semicolonAndLineCommentAreStillRejected() {
		MOrder order = newOrderWithLines(Env.getAD_Org_ID(Env.getCtx()), 1);
		assertThrows(AdempiereException.class,
				() -> PO2MapBuilder.build(order, List.of(include("1=1; DELETE FROM C_Order"))));
		assertThrows(AdempiereException.class,
				() -> PO2MapBuilder.build(order, List.of(include("1=1 -- x"))));
	}

	@Test
	void subqueryInIncludeSqlIsRejectedInStrictMode() {
		MOrder order = newOrderWithLines(Env.getAD_Org_ID(Env.getCtx()), 1);
		AdempiereException e = assertThrows(AdempiereException.class, () -> PO2MapBuilder.build(order,
				List.of(include("EXISTS (SELECT 1 FROM C_BPartner b WHERE b.C_BPartner_ID=C_OrderLine.C_BPartner_ID)"))));
		assertTrue(e.getMessage().contains("WhereClause"), e.getMessage());
		MPrintTemplateInclude byOrder = include(null);
		byOrder.setOrderByClause("(select max(Line) from C_OrderLine)");
		e = assertThrows(AdempiereException.class, () -> PO2MapBuilder.build(order, List.of(byOrder)));
		assertTrue(e.getMessage().contains("OrderByClause"), e.getMessage());
		// a word that only contains "select" is fine
		assertEquals(1, rows(PO2MapBuilder.build(order, List.of(include("'selected' <> 'x'")))).size());
	}

	@Test
	void childColumnsStillWork() {
		MOrder order = newOrderWithLines(Env.getAD_Org_ID(Env.getCtx()), 2);
		Map<String, Object> map = PO2MapBuilder.build(order,
				List.of(include("IsActive='Y' AND QtyOrdered>0")));
		assertEquals(2, rows(map).size());
	}

	@Test
	void conventionChildrenStillWork() {
		MOrder order = newOrderWithLines(Env.getAD_Org_ID(Env.getCtx()), 2);
		assertEquals(2, rows(PO2MapBuilder.build(order, null)).size());
	}

	@Test
	void childQueryAddsAccessFilter() {
		int visibleOrg = Env.getAD_Org_ID(Env.getCtx());
		// an org created in the test transaction: no role has access to it
		MOrg hiddenOrg = new MOrg(Env.getCtx(), 0, getTrxName());
		hiddenOrg.setValue(PREFIX + "org");
		hiddenOrg.setName(PREFIX + "org");
		hiddenOrg.saveEx();
		// MOrg.afterSave grants the roles access to the new org; take it away in this transaction
		DB.executeUpdateEx("DELETE FROM AD_Role_OrgAccess WHERE AD_Org_ID=?", new Object[] { hiddenOrg.getAD_Org_ID() },
				getTrxName());

		// an existing role of the tenant that sees the order's org but not the new one
		MRole role = null;
		for (int id : new Query(Env.getCtx(), MRole.Table_Name, "AD_Client_ID=? AND IsAccessAllOrgs='N' AND IsActive='Y'",
				null).setParameters(TENANT).getIDs()) {
			MRole candidate = MRole.get(Env.getCtx(), id);
			if (candidate.isOrgAccess(visibleOrg, false) && !candidate.isOrgAccess(hiddenOrg.getAD_Org_ID(), false)) {
				role = candidate;
				break;
			}
		}
		assertNotNull(role, "no role of client " + TENANT + " with restricted org access");

		MOrder order = newOrderWithLines(visibleOrg, 2);
		DB.executeUpdateEx("UPDATE C_OrderLine SET AD_Org_ID=? WHERE C_Order_ID=? AND Line=20",
				new Object[] { hiddenOrg.getAD_Org_ID(), order.getC_Order_ID() }, getTrxName());
		assertEquals(2, DB.getSQLValueEx(getTrxName(), "SELECT COUNT(*) FROM C_OrderLine WHERE C_Order_ID=?",
				order.getC_Order_ID()));

		// the same record, read under the restricted role
		Properties roleCtx = new Properties();
		roleCtx.putAll(Env.getCtx());
		Env.setContext(roleCtx, Env.AD_ROLE_ID, role.getAD_Role_ID());
		MOrder asRole = new MOrder(roleCtx, order.getC_Order_ID(), getTrxName());

		// MRole.getDefault(roleCtx, ...) also writes the default role into the shared Env ctx
		List<Map<String, Object>> viaInclude = rows(PO2MapBuilder.build(asRole, List.of(include(null))));
		assertEquals(1, viaInclude.size());
		assertEquals(10, ((Number) viaInclude.get(0).get("Line")).intValue(), "the moved line (20) must be the hidden one");
		List<Map<String, Object>> viaConvention = rows(PO2MapBuilder.build(asRole, null));
		assertEquals(1, viaConvention.size());
		assertEquals(10, ((Number) viaConvention.get(0).get("Line")).intValue());
		List<Map<String, Object>> viaFk = PO2MapBuilder.loadChildrenByFK(asRole, "C_OrderLine");
		assertEquals(1, viaFk.size());
		assertEquals(10, ((Number) viaFk.get(0).get("Line")).intValue());
	}

	@Test
	void orderByClauseWithCommentIsRejected() {
		MOrder order = newOrderWithLines(Env.getAD_Org_ID(Env.getCtx()), 1);
		MPrintTemplateInclude include = include(null);
		include.setOrderByClause("Line /* x */");
		assertThrows(AdempiereException.class, () -> PO2MapBuilder.build(order, List.of(include)));
		include.setOrderByClause("Line; DELETE FROM C_Order");
		assertThrows(AdempiereException.class, () -> PO2MapBuilder.build(order, List.of(include)));
	}

	@Test
	void noRoleInContextFailsInBothPaths() {
		MOrder order = newOrderWithLines(Env.getAD_Org_ID(Env.getCtx()), 1);
		Properties noRole = new Properties();
		noRole.putAll(Env.getCtx());
		noRole.remove("#AD_Role_ID");
		noRole.remove("#AD_User_ID");
		MOrder bare = new MOrder(noRole, order.getC_Order_ID(), getTrxName());
		String message = "Template child data of C_Order needs a role in the context; the user running this print needs a role";
		AdempiereException e = assertThrows(AdempiereException.class,
				() -> PO2MapBuilder.build(bare, List.of(include(null))));
		assertEquals(message, e.getMessage());
		e = assertThrows(AdempiereException.class, () -> PO2MapBuilder.build(bare, null));
		assertEquals(message, e.getMessage());
		e = assertThrows(AdempiereException.class, () -> PO2MapBuilder.loadChildrenByFK(bare, "C_OrderLine"));
		assertEquals(message, e.getMessage());
	}

	@Test
	void recordWithoutChildTablesNeedsNoRole() {
		Properties noRole = new Properties();
		noRole.putAll(Env.getCtx());
		noRole.remove("#AD_Role_ID");
		noRole.remove("#AD_User_ID");
		// C_BPartner has no C_BPartnerLine / C_BPartnerTax: no child query runs
		MBPartner bare = new MBPartner(noRole, DictionaryIDs.C_BPartner.JOE_BLOCK.id, getTrxName());
		Map<String, Object> map = PO2MapBuilder.build(bare, null);
		assertEquals(DictionaryIDs.C_BPartner.JOE_BLOCK.id, ((Number) map.get("C_BPartner_ID")).intValue());
		assertTrue(!map.containsKey("lines") && !map.containsKey("taxes"), map.keySet().toString());
		assertEquals(null, PO2MapBuilder.loadChildrenByFK(bare, "C_BPartnerLine"));
	}

	@Test
	void childRowsBelongToTheParentsClient() {
		MOrder order = newOrderWithLines(Env.getAD_Org_ID(Env.getCtx()), 2);
		for (Map<String, Object> row : rows(PO2MapBuilder.build(order, List.of(include(null)))))
			assertEquals(order.getAD_Client_ID(), ((Number) ((Map<?, ?>) row.get("AD_Client_ID")).get("id")).intValue());
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> rows(Map<String, Object> map) {
		Object v = map.containsKey("lines") ? map.get("lines") : null;
		assertNotNull(v, "no lines in " + map.keySet());
		return (List<Map<String, Object>>) v;
	}
}
