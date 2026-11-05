# iDempiere Print Template Engine

OSGi plugin that plugs declarative template engines (FreeMarker first)
into iDempiere's print format engine — HTML/PDF/CSV/XLSX output driven by
`AD_PrintFormat`, with no core code patches.

- Rendering hooks through the report-content API introduced by
  [IDEMPIERE-7070](https://idempiere.atlassian.net/browse/IDEMPIERE-7070);
  templates live in the database (`AD_PrintTemplate`) with classpath fallback.
- **Document mode** (`IsForm='Y'`): one rendering per record; multi-record
  selections are merged (PDF pages / HTML page-breaks).
- **List/report mode** (`IsForm='N'`): a single rendering over all records,
  exposed to the template as the `rows` collection — tables, grouping,
  subtotals and totals, all in FreeMarker.
- Output honors the report viewer's type selection (UI, batch, scheduler).
  PDF via openhtmltopdf; list formats also **export to CSV and XLSX**.
- Bonus: JSON export of any record in the native Export dialog
  (`IGridTabExporter`).

Dictionary (`AD_PrintTemplate` + window) ships as an embedded incremental
2Pack, installed automatically on bundle start.

> 🧪 **Beta.** Works end-to-end on iDempiere 14. Usable, but the APIs and
> dictionary may still change — pin a commit if you depend on it.

## Templates

Each template is FreeMarker. The data model exposes the record's columns at
top level (foreign keys as `{id, display}`), plus `ctx` (client/org/user/
language/now) and `assets` (template attachments, base64). List mode adds
`rows` (one map per record) and `count`.

### Languages

A template prints in the **document language**: the print format's language, chosen by iDempiere the
same way for a template as for a native print format.

| Print | Document language |
| --- | --- |
| a document (`ReportEngine.get`: order, invoice, shipment, …) | the tenant's language (`AD_Client.AD_Language`), or the business partner's (`C_BPartner.AD_Language`) when it has one |
| a report or list | the login language: the user's preference |
| the report viewer's language selector (tenants with *Multi Lingual Documents*) | the language picked |

The tenant's language is the reference language of its own data. When the tenant saves a product,
payment term or document type, core keeps its text in the base table and in the `_Trl` row of the
tenant's language; a new record copies it into every `_Trl` row (`PO`, `TranslationDocSync`). Without
*Multi Lingual Documents* every `_Trl` row keeps the same text. So a record nobody translated prints the
tenant's text in every language, whatever the base language of the system is. Labels and list values
come from the dictionary's translations, which fall back to the base language's text.

- `msg("DocumentNo")` — the dictionary text of a message or element (print name), translated;
  `msg("@DocumentNo@ / @DateOrdered@")` replaces each `@key@`; `msg("Key", arg…)` fills a message's
  `{0}`, `{1}`… placeholders.
- `ctx.language` is the document language, `ctx.login_language` the language of the user printing.
- The language picked in the report viewer applies (it sets the print format's language). The language
  of the process dialog does not, as for a native print format: core passes it only to Jasper.
- Foreign keys (`*.display`) are shown in the document language.
- A template on a view `X_v` (such as `C_Order_Header_v`) reads its translation view `X_vt` in the document
  language when that is not the base language, as a print format does: the document type, payment term,
  product and other texts of the view come translated.
- List columns keep their code (`${DocStatus}` = `CO`) and get its name in the document language next
  to it (`${DocStatus_display}` = `Completed`), as a print format prints a list column.
- The locale of the render follows the document language: decimal and grouping separators (also in
  `?string[",##0.00"]`), month names, and the default `?date`, `?time` and `?datetime` patterns, which are
  the ones a print format uses (`AD_Language.DatePattern` / `TimePattern`, else the language's formats).
  SysConfig `PRT_FREEMARKER_DATE_FORMAT` / `_TIME_FORMAT` / `_DATETIME_FORMAT` pin a pattern for a tenant.

Write labels with `msg(…)` instead of fixed text, and dates with `?date` instead of a fixed pattern,
and one template serves every language. `examples/C_Order.ftlh` does this; `examples/C_Order_multilang.ftlh`
shows labels, formats and content together.

Working examples live in [`examples/`](examples/):

- `C_Order.ftlh` — document (invoice-style form)
- `C_Order_multilang.ftlh` — document in the document language: labels, formats, translated content
- `C_BPartner_list.ftlh` — flat listing with a total
- `C_BPartner_grouped.ftlh` — grouped by BP Group with subtotals
- `C_BPartner_customers_grouped.ftlh` — filtered + grouped + calculated column

A fuller authoring guide (data model, available built-ins, and gotchas) is
kept in `spec/` (not versioned).

## Build & install

Requires iDempiere 14 / Java 17. Import `org.idempiere.print.freemarker`
as an Eclipse PDE project, or build headless with `mvn -U verify` (embedded
runtime libraries are fetched by Maven — `lib/` is not committed).

## Credits

Report-content integration (IDEMPIERE-7070) was contributed by
**Markus Bozem** (@mbozem); authorship is preserved in the git history.
This fork extends that work with multi-record documents, list/report mode
and CSV/XLSX export.

License: GPL-2.0.
