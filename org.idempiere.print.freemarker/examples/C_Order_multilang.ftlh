<#--
  One template, every language: labels, formats and content follow the document language.

  The document language is the print format's language: for a single document the business
  partner's language (C_BPartner.AD_Language), else the client's or the login language; the
  report viewer can switch it for clients that print multi-lingual documents.

  - labels:   msg("ColumnName") = the element's print name in AD_Element_Trl (sales or purchase
              wording of the record); msg("Key", arg…) = an AD_Message with {0}… filled in
  - content:  *.display of a foreign key comes from the _Trl table (M_Product_Trl, C_UOM_Trl, …);
              <List>_display is the name of a list value from AD_Ref_List_Trl
  - formats:  separators, month names and ?date follow the locale of the document language

  Own texts belong in AD_Message with a translation per language, e.g.
      ${msg("CLD_PrintThankYou")}
      ${msg("CLD_PrintDeliveryOn", DatePromised?date)}      (message text "… {0}")
-->
<!DOCTYPE html>
<html lang="${ctx.language?keep_before('_')}">
<head>
  <meta charset="UTF-8" />
  <title>${msg("C_Order_ID")} ${DocumentNo!""}</title>
  <style>
    @page { size: A4; margin: 16mm 15mm; }
    body { font-family: sans-serif; font-size: 10pt; }
    table { border-collapse: collapse; width: 100%; }
    th, td { padding: 2mm; text-align: left; }
    td.num, th.num { text-align: right; }
  </style>
</head>
<body>
  <h1>${msg("C_Order_ID")} ${DocumentNo!""}</h1>

  <table class="meta">
    <tr><td>${msg("DocumentNo")}</td><td id="documentno">${DocumentNo!""}</td></tr>
    <tr><td>${msg("DateOrdered")}</td><td id="dateordered">${(DateOrdered?date)!""}</td></tr>
    <tr><td>${msg("C_PaymentTerm_ID")}</td><td>${(C_PaymentTerm_ID.display)!"–"}</td></tr>
    <tr><td>${msg("PaymentRule")}</td><td>${PaymentRule_display!"–"}</td></tr>
    <tr><td>${msg("DocStatus")}</td><td id="docstatus">${DocStatus_display!""}</td></tr>
  </table>

  <h2>${msg("C_BPartner_ID")}: ${(C_BPartner_ID.display)!""}</h2>

  <table class="lines">
    <thead>
      <tr>
        <th>${msg("Line")}</th>
        <th>${msg("M_Product_ID")}</th>
        <th class="num">${msg("QtyOrdered")}</th>
        <th>${msg("C_UOM_ID")}</th>
        <th class="num">${msg("PriceActual")}</th>
        <th class="num">${msg("LineNetAmt")}</th>
      </tr>
    </thead>
    <tbody>
      <#list lines![] as line>
        <tr>
          <td>${line.Line!""}</td>
          <td class="product">${(line.M_Product_ID.display)!(line.C_Charge_ID.display)!""}</td>
          <td class="num">${(line.QtyOrdered?string[",##0.##"])!""}</td>
          <td>${(line.C_UOM_ID.display)!""}</td>
          <td class="num">${(line.PriceActual?string[",##0.00"])!""}</td>
          <td class="num">${(line.LineNetAmt?string[",##0.00"])!""}</td>
        </tr>
      </#list>
    </tbody>
  </table>

  <p>${msg("GrandTotal")}: <strong id="grandtotal">${(GrandTotal?string[",##0.00"])!""}</strong>
     ${(C_Currency_ID.display)!""}</p>
  <p id="longdate">${(DateOrdered?string["yyyy. MMMM d."])!""}</p>
</body>
</html>
