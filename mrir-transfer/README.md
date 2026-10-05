# MRIR to Free Issue Transfer

A fresh local Java application for uploading an MRIR workbook and a Free Issue workbook, selecting MRIR sheets, reviewing the mapping, and downloading an updated destination workbook. This folder is a complete standalone app. Do not merge it into the previous Spring Boot/PDF project.

## Run on Windows

1. Extract the complete ZIP into a normal folder.
2. Ensure your Java installation is available: open PowerShell and run `java -version`. JDK 25 is supported. The app is compiled for Java 17 and also runs on newer Java versions.
3. Double-click `start.bat`. Keep the terminal open while using the app.
4. The app normally opens your browser. If not, visit http://127.0.0.1:8080.
5. Upload the MRIR source workbook and the Free Issue destination workbook.
6. Select the source sheets. Use Select all, Clear selection, or the sheet-name search.
7. Review or change the source selector for each destination field.
8. Click Preview transfer. Check destination row numbers and transferred values.
9. Click Transfer and download Excel. Open the downloaded copy in Excel to review it.
10. Press Ctrl+C in the terminal to stop. Closing the browser alone does not stop the app.

If port 8080 is occupied, stop the previous app or run this from the extracted folder:

```powershell
java -Xmx1024m -jar .\mrir-transfer.jar 8081
```

Then visit http://127.0.0.1:8081. No Maven, Node.js, database, Excel installation, LibreOffice, internet connection, or additional Java dependency is needed to run the transfer application. Excel is useful for reviewing the output. The original upload files are not overwritten.

## Default mapping for the supplied workbook layouts

Destination worksheet: `Packing list Detail`. The first row contains the headers; material records begin at row 2.

| Destination field | Destination column | MRIR source |
|---|---|---|
| Job No. | A | Header E4, leading colon removed |
| PO No. | C | Header O3, leading colon removed |
| MRIR No. | D | Header O1 |
| Item number | E | Item column A |
| Material specification | F | Item column B |
| MRF item | J | Item column P (MRIR Remarks), per the requested mapping |
| Unit | K | Item column G |
| Quantity | L | Item column H, stored as a number |
| Heat No. | M | Item column I |
| Certificate No. | O | Item column K |
| Remarks | P | Left blank by default; choose a source in the UI if needed |
| Shipment No. | Q | MRIR sheet name; `SHIPMENT 1191-001` becomes `Shipment 1191` |

Header values repeat for each material item in the selected sheet. The source row scan starts at row 9, scans the remaining rows, and transfers rows with a positive integer item number in column A and a nonblank material specification in B. Blank padding, inspection notes, signatures, and report footers are excluded. No rows are inferred from visual appearance alone.

Workshop, type, material, size, part number, storage, rack/line, issue slip, date received, OS&D, and job status have no confirmed direct MRIR source and are not filled automatically. In particular, the inspection/signature date is not assumed to be a goods-received date. Some source sheets contain unlabeled extra values in Q; these are not automatically interpreted as per-item PO numbers. If they are the intended PO field, change the PO source selector to Item column Q before previewing.

## Empty-row behavior

The engine scans destination rows from row 2 and fills the first available empty row for each record. It fills gaps and continues searching if another occupied row follows a gap. It never assumes that a blank description alone means the whole row is available.

Any entered data or formula in the regular fields blocks that row. Rows intersecting merged cells are skipped. Existing formulas in inventory columns X, Z, and AA, together with zero/blank inventory placeholders in X/Y/Z/AA, do not count as material data. A nonzero manual inventory value blocks the row. Therefore the supplied destination workbook's first available row is 6957, even though it already contains stock formulas and zero placeholders.

Existing destination inventory formulas are retained; cached X and Z results are updated for transferred quantities. If X or Z does not contain a formula on an empty target row, the app adds X=`L[row]` and Z=`X[row]-Y[row]`, with Y defaulting to zero only where it was blank. An existing AA status formula gets an updated cached status. The app requests Excel recalculation when the workbook is opened.

The destination's autofilter range is extended to include inserted records. New rows beyond the preformatted area use available cell styles from the last identified MRIR material row. Custom formulas elsewhere, cross-sheet summary ranges, print areas, charts, or VBA business rules are not extended or rewritten.

## Duplicate handling

The checked duplicate option matches MRIR number, item number, description, unit, and quantity. Matching existing items and matching items within the selected source records are skipped. Preview shows the number skipped. This is a five-field match, not an audit of every cell: differences only in PO, heat, remarks, or other fields do not cause an existing item to be updated. Turn off the option if you intentionally want another appended copy. Existing records are never updated in place.

## Workbook preservation and supported files

- Supports unencrypted `.xlsm` and `.xlsx` in the supplied layouts. Old `.xls`, password-encrypted files, protected destination sheets, and digitally signed workbook packages are rejected.
- The destination workbook is treated as an OOXML ZIP package. Only the target worksheet and workbook recalculation settings are rewritten. Macro binaries, drawings, other sheets, styles, named ranges, relationships, and other parts are copied unchanged at the decompressed-part byte level.
- Macros are preserved, but never run by the app. Macro execution and Excel's own repair/render behavior must still be checked in desktop Excel.
- Source formulas use their saved results. Open and save a source workbook in Excel first if formula results are missing or outdated. This application does not calculate arbitrary Excel formulas.
- Quantity is transferred as a numeric value. Identifiers such as PO and MRIR numbers are written as text, preserving leading zeros in text identifiers.
- Limit: 20 MB compressed per upload; 256 MB expanded package. At most eight workbooks can remain loaded simultaneously. Uploads are kept only in memory and may expire after 30 minutes when new uploads arrive; restarting clears them.
- Mapping choices and uploaded workbooks are not persisted after closing/reloading the page. Download the completed workbook to save the result.

## Source and rebuilding

The complete source is in `src/com/tuan/transfer`, with the interface in `web`. `Workbook.java` reads/patches OOXML, `TransferEngine.java` owns mapping and row rules, and `App.java` serves the local HTTP interface using Java's built-in server. To rebuild with a JDK installed, double-click `build.bat`, then `start.bat`. Default mappings are defined in `TransferEngine.DEFAULT_MAPPING`; the browser lets users change them for the current transfer.

## Validation performed

Tested against the two supplied macro-enabled workbooks:

- Enumerated all 164 MRIR sheets; all passed the expected-layout check.
- Read 1,367 numbered material items, excluding footer/signature rows.
- Default five-field duplicate checking identified 1,164 matching records and 203 new records.
- Verified initial allocation at row 6957, sequential allocation, partially populated-row protection, repeat-import skipping, and the duplicate override.
- Exercised all-sheet transfer and re-import, numeric quantities, shipment-name parsing, and the field mappings.
- Verified every untouched workbook ZIP part, including `xl/vbaProject.bin` and all other sheets, remained byte-for-byte equal after decompression.
- Checked Java compilation and JavaScript syntax.
- Tested HTTP uploads, sheet enumeration, preview, download, and UI asset serving.

This environment did not provide a browser executable or desktop Excel. Visual browser testing, native Excel opening/recalculation, and execution of existing macros were not performed. Start with one selected sheet, inspect its downloaded workbook in Excel, and then use larger batches.
