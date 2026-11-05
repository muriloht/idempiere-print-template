package org.idempiere.print.freemarker.test;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.compiere.model.MSysConfig;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.CacheMgt;
import org.compiere.util.Env;
import org.idempiere.print.freemarker.FreemarkerConfigurationFactory;
import org.idempiere.print.freemarker.GuardedRender;
import org.idempiere.print.template.model.MPrintTemplate;
import org.idempiere.print.template.model.X_AD_PrintTemplate;

import freemarker.template.Template;

/**
 * Helpers for the FreeMarker hardening tests: renders a template body with the configuration of a
 * client and creates AD_PrintTemplate rows outside the test transaction (trxName=null), because the
 * template loader reads on its own connection. Rows are named with the prefix cld_test_ and must be
 * removed with {@link #deleteCreated()}.
 */
final class TemplateTestSupport {

	static final String PREFIX = "cld_test_";

	private final List<MPrintTemplate> created = new ArrayList<>();

	/** Value of a SysConfig before the test changed it; null original = the test created the row. */
	private static final class SysConfigChange {
		final int id;
		final String original;

		SysConfigChange(int id, String original) {
			this.id = id;
			this.original = original;
		}
	}

	private final List<SysConfigChange> sysConfigChanges = new ArrayList<>();

	/**
	 * Sets a client level AD_SysConfig (org 0) outside the test transaction. An existing row keeps its
	 * original value for {@link #deleteSysConfigs()}; a new row is tagged Description=cld_test_.
	 */
	void setSysConfig(int adClientId, String name, String value) {
		PO.setCrossTenantSafe();
		try {
			MSysConfig sc = new Query(Env.getCtx(), MSysConfig.Table_Name, "AD_Client_ID=? AND AD_Org_ID=0 AND Name=?",
					null).setParameters(adClientId, name).first();
			String original = null;
			if (sc != null) {
				original = sc.getValue();
			} else {
				sc = new MSysConfig(Env.getCtx(), 0, null);
				sc.set_ValueNoCheck("AD_Client_ID", adClientId);
				sc.setAD_Org_ID(0);
				sc.setName(name);
				sc.setConfigurationLevel(MSysConfig.CONFIGURATIONLEVEL_Client);
				sc.setDescription(PREFIX);
			}
			sc.setValue(value);
			sc.saveEx();
			sysConfigChanges.add(new SysConfigChange(sc.getAD_SysConfig_ID(), original));
		} finally {
			PO.clearCrossTenantSafe();
		}
		CacheMgt.get().reset(MSysConfig.Table_Name);
		FreemarkerConfigurationFactory.reset();
		GuardedRender.reset();
	}

	/** Restores the rows the test changed and deletes only the rows it created (tagged cld_test_). */
	void deleteSysConfigs() {
		for (SysConfigChange c : sysConfigChanges) {
			try {
				if (c.original == null) {
					// deleteEx refuses system client rows, so remove by SQL, by ID and tag
					DB.executeUpdateEx("DELETE FROM AD_SysConfig WHERE AD_SysConfig_ID=? AND Description=?",
							new Object[] { c.id, PREFIX }, null);
				} else {
					PO.setCrossTenantSafe();
					try {
						MSysConfig sc = new MSysConfig(Env.getCtx(), c.id, null);
						sc.setValue(c.original);
						sc.saveEx();
					} finally {
						PO.clearCrossTenantSafe();
					}
				}
			} catch (Exception e) {
				CLogger.getCLogger(TemplateTestSupport.class).log(Level.WARNING,
						"Could not restore test SysConfig " + c.id, e);
			}
		}
		sysConfigChanges.clear();
		CacheMgt.get().reset(MSysConfig.Table_Name);
		FreemarkerConfigurationFactory.reset();
		GuardedRender.reset();
	}

	static String render(int adClientId, String body) throws Exception {
		return render(adClientId, body, Map.of());
	}

	static String render(int adClientId, String body, Map<String, Object> model) throws Exception {
		Template t = new Template("t", body, FreemarkerConfigurationFactory.get(adClientId));
		StringWriter w = new StringWriter();
		t.process(model, w);
		return w.toString();
	}

	MPrintTemplate create(int adClientId, String name, String body) {
		return create(adClientId, name, body, 0);
	}

	/** Same as {@link #create(int, String, String)}, bound to a table when adTableId &gt; 0. */
	MPrintTemplate create(int adClientId, String name, String body, int adTableId) {
		MPrintTemplate pt = new MPrintTemplate(Env.getCtx(), 0, null);
		pt.set_ValueNoCheck("AD_Client_ID", adClientId);
		if (adTableId > 0)
			pt.setAD_Table_ID(adTableId);
		pt.setAD_Org_ID(0);
		pt.setName(name);
		pt.setTemplateBody(body);
		pt.setTemplateEngineType(X_AD_PrintTemplate.TEMPLATEENGINETYPE_FreeMarker);
		save(pt);
		created.add(pt);
		return pt;
	}

	/** Saves outside the test transaction; a system-client row needs the cross tenant flag. */
	static void save(MPrintTemplate pt) {
		PO.setCrossTenantSafe();
		try {
			pt.saveEx();
		} finally {
			PO.clearCrossTenantSafe();
		}
	}

	/**
	 * Deletes the templates this helper created, by ID and only while the name still starts with the
	 * test prefix. {@code deleteEx} is not used: it fails with DeleteError on client 0 rows.
	 */
	void deleteCreated() {
		for (MPrintTemplate pt : created) {
			try {
				DB.executeUpdateEx("DELETE FROM AD_PrintTemplate WHERE AD_PrintTemplate_ID=? AND Name LIKE ?",
						new Object[] { pt.getAD_PrintTemplate_ID(), PREFIX + "%" }, null);
			} catch (Exception e) {
				// best effort cleanup of cld_test_ rows, but do not hide why it failed
				CLogger.getCLogger(TemplateTestSupport.class).log(Level.WARNING,
						"Could not delete test template " + pt.getName(), e);
			}
		}
		created.clear();
		CacheMgt.get().reset(MPrintTemplate.Table_Name);
	}
}
