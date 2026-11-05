<!DOCTYPE html>
<html lang="${ctx.language?keep_before('_')}">
<head>
  <meta charset="UTF-8" />
  <title>${msg("C_Order_ID")} ${DocumentNo!""}</title>
  <#include "styles.ftlh">
  <style>
    @page { size: A4; margin: 16mm 15mm 18mm; }
    body { margin: 0; font-size: 10pt; color: #1f2937; }
    .header { width: 100%; margin-bottom: 18mm; }
    .header td { border: 0; padding: 0; vertical-align: top; }
    .logo { max-width: 52mm; max-height: 22mm; }
    .sender { margin-bottom: 3mm; color: #6b7280; font-size: 8pt; }
    .address { min-height: 34mm; line-height: 1.45; }
    .document-meta { width: 76mm; margin-left: auto; }
    .document-meta td { border: 0; padding: 1.2mm 0; }
    .document-meta td:first-child { color: #6b7280; padding-right: 5mm; }
    h1 { margin: 0 0 8mm; font-size: 20pt; color: #111827; }
    .intro { margin: 0 0 7mm; line-height: 1.5; }
    .lines { table-layout: fixed; }
    .lines th { background: #e5e7eb; border-bottom: 1px solid #9ca3af; }
    .lines td { vertical-align: top; }
    .pos { width: 8mm; text-align: right; }
    .qty { width: 20mm; text-align: right; }
    .price { width: 25mm; text-align: right; }
    .amount { width: 28mm; text-align: right; }
    .description { margin-top: 1mm; color: #6b7280; font-size: 8.5pt; }
    .summary { width: 76mm; margin: 8mm 0 0 auto; }
    .summary td { padding: 1.5mm 0; border: 0; }
    .summary td:last-child { text-align: right; }
    .summary .grand-total td { padding-top: 2.5mm; border-top: 1px solid #374151; font-size: 12pt; font-weight: bold; }
    .notes { margin-top: 10mm; white-space: pre-wrap; line-height: 1.45; }
    .footer { position: running(footer); color: #6b7280; font-size: 8pt; }
  </style>
</head>
<body>
  <table class="header">
    <tr>
      <td>
        <#if assets["logo.png"]??>
          <img class="logo" src="data:image/png;base64,${assets["logo.png"]}" alt="Logo" />
        <#else>
          <strong>${ctx.org_name!ctx.client_name!""}</strong>
        </#if>
      </td>
      <td>
        <table class="document-meta">
          <tr><td>${msg("DocumentNo")}</td><td><strong>${DocumentNo!""}</strong></td></tr>
          <tr><td>${msg("DateOrdered")}</td><td>${(DateOrdered?date)!""}</td></tr>
          <tr><td>${msg("POReference")}</td><td>${POReference!"–"}</td></tr>
          <tr><td>${msg("SalesRep_ID")}</td><td>${(SalesRep_ID.display)!"–"}</td></tr>
        </table>
      </td>
    </tr>
  </table>

  <div class="sender">${ctx.org_name!ctx.client_name!""}</div>
  <div class="address">
    <strong>${(C_BPartner_ID.display)!""}</strong><br />
    <#if (C_BPartner_Location_ID.display)??>${C_BPartner_Location_ID.display}</#if>
  </div>

  <h1>${msg("C_Order_ID")} ${DocumentNo!""}</h1>

  <#if (DatePromised)??>
    <p class="intro">${msg("DatePromised")}: ${DatePromised?date}</p>
  </#if>

  <table class="lines">
    <thead>
      <tr>
        <th class="pos">${msg("Line")}</th>
        <th>${msg("M_Product_ID")} / ${msg("Description")}</th>
        <th class="qty">${msg("QtyOrdered")}</th>
        <th class="price">${msg("PriceActual")}</th>
        <th class="amount">${msg("LineNetAmt")}</th>
      </tr>
    </thead>
    <tbody>
      <#list lines![] as line>
        <tr>
          <td class="pos">${line.Line!((line?index + 1) * 10)}</td>
          <td>
            <strong>${(line.M_Product_ID.display)!(line.C_Charge_ID.display)!(msg("Line"))}</strong>
            <#if line.Description?? && line.Description?has_content>
              <div class="description">${line.Description}</div>
            </#if>
          </td>
          <td class="qty">
            ${(line.QtyOrdered?string["0.##"])!""}
            <#if (line.C_UOM_ID.display)??>${line.C_UOM_ID.display}</#if>
          </td>
          <td class="price">${(line.PriceActual?string[",##0.00"])!""}</td>
          <td class="amount">${(line.LineNetAmt?string[",##0.00"])!""}</td>
        </tr>
      <#else>
        <tr><td colspan="5" class="muted">–</td></tr>
      </#list>
    </tbody>
  </table>

  <table class="summary">
    <tr>
      <td>${msg("TotalLines")}</td>
      <td>${(TotalLines?string[",##0.00"])!""} ${(C_Currency_ID.display)!""}</td>
    </tr>
    <#list taxes![] as tax>
      <tr>
        <td>${(tax.C_Tax_ID.display)!(msg("C_Tax_ID"))}</td>
        <td>${(tax.TaxAmt?string[",##0.00"])!""} ${(C_Currency_ID.display)!""}</td>
      </tr>
    </#list>
    <tr class="grand-total">
      <td>${msg("GrandTotal")}</td>
      <td>${(GrandTotal?string[",##0.00"])!""} ${(C_Currency_ID.display)!""}</td>
    </tr>
  </table>

  <#if Description?? && Description?has_content>
    <div class="notes">${Description}</div>
  </#if>

  <div class="footer">
    ${ctx.org_name!ctx.client_name!""}
    <#if (M_Warehouse_ID.display)??> · ${msg("M_Warehouse_ID")}: ${M_Warehouse_ID.display}</#if>
  </div>
</body>
</html>
