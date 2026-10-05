package com.tuan.transfer;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;

public final class SelfTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        Workbook source=new Workbook(Files.readAllBytes(Path.of(args[0])),"source.xlsm");
        Workbook destination=new Workbook(Files.readAllBytes(Path.of(args[1])),"destination.xlsm");
        TransferEngine engine=new TransferEngine();
        var names=new ArrayList<>(source.sheets.keySet());
        var metadata=engine.sourceSheets(source);
        check(metadata.size()==164,"Expected 164 source sheets.");
        check(metadata.stream().allMatch(m->Boolean.TRUE.equals(m.get("compatible"))),"Source layout coverage.");
        var selected=names.subList(0,2);
        var plan=engine.plan(source,destination,selected,TransferEngine.DEFAULT_MAPPING,false);
        System.out.println("First two sheets: "+plan.records().size()+" records.");
        check(plan.records().size()==3,"Two first sheets should contain three items.");
        check(plan.records().get(0).destinationRow()==6957,"First empty row must ignore inventory zero placeholders.");
        check(plan.records().get(2).destinationRow()==6959,"Sequential allocation of three empty rows.");
        check(plan.records().get(0).item().values().get("Q").equals("Shipment 1191"),"Shipment suffix removed.");
        check(plan.records().get(0).item().values().get("A").equals("AL-5627"),"Job mapped from E4.");
        check(plan.records().get(0).item().values().get("L").equals(BigDecimal.ONE),"Quantity remains numeric.");
        check(plan.records().get(1).item().values().get("J").equals(source.sheet(names.get(1)).text(9,"P")),"MRIR Remarks P maps to destination MRF Item J.");
        check(plan.records().get(1).item().values().get("P").equals(""),"Destination Remarks is blank by default.");
        byte[] result=engine.transfer(source,destination,selected,TransferEngine.DEFAULT_MAPPING,false);
        Workbook output=new Workbook(result,"output.xlsm");
        var out=output.sheet(TransferEngine.TARGET);
        var before=destination.sheet(TransferEngine.TARGET);
        for(var entry:destination.parts.entrySet()) {
            if(Set.of("xl/workbook.xml",before.path).contains(entry.getKey()))continue;
            check(Arrays.equals(entry.getValue(),output.parts.get(entry.getKey())),"Unrelated ZIP part changed: "+entry.getKey());
        }
        check(output.parts.keySet().equals(destination.parts.keySet()),"No workbook parts lost.");
        for(int row:before.rows.keySet()) {
            if(row>=6957&&row<=6959)continue;
            for(var c:before.rows.get(row).entrySet()) {
                var written=out.cell(row,c.getKey());
                check(written!=null&&written.value.equals(c.getValue().value),"Existing value changed: "+c.getKey()+row);
                check(written.formula==c.getValue().formula,"Existing formula changed: "+c.getKey()+row);
            }
        }
        check(out.text(6957,"F").equals(source.sheet(names.get(0)).text(9,"B")),"Description mapping.");
        check(out.text(6957,"X").equals("1")&&out.text(6957,"Z").equals("1"),"Inventory caches updated.");
        check(out.cell(6957,"X").formula,"Inventory formula retained.");
        check(engine.plan(source,output,selected,TransferEngine.DEFAULT_MAPPING,true).records().isEmpty(),"Repeat import should skip exact duplicates.");
        check(engine.plan(source,output,selected,TransferEngine.DEFAULT_MAPPING,false).records().get(0).destinationRow()==6960,"Duplicate override appends without overwriting.");
        Workbook secondDownload=new Workbook(engine.transfer(source,destination,selected,TransferEngine.DEFAULT_MAPPING,false),"second.xlsm");
        for(var entry:output.parts.entrySet())check(Arrays.equals(entry.getValue(),secondDownload.parts.get(entry.getKey())),"Repeat download must use untouched baseline: "+entry.getKey());
        // A partial row blocks transfer even when its description cell is empty.
        Workbook partial=new Workbook(destination.original,"partial.xlsm");
        partial.sheet(TransferEngine.TARGET).put(6957,"S","Reserved storage entry",6956);
        check(engine.plan(source,partial,selected,TransferEngine.DEFAULT_MAPPING,false).records().get(0).destinationRow()==6958,"Partial row protection.");
        var all=engine.plan(source,destination,names,TransferEngine.DEFAULT_MAPPING,true);
        System.out.println("All-sheet preview: "+all.records().size()+" new records; "+all.duplicateCount()+" exact duplicates skipped.");
        // Exercise transfer of every sheet, allocation across occupied islands, and duplicate re-import.
        byte[] allBytes=engine.transfer(source,destination,names,TransferEngine.DEFAULT_MAPPING,true);
        Workbook allOutput=new Workbook(allBytes,"all.xlsm");
        check(engine.plan(source,allOutput,names,TransferEngine.DEFAULT_MAPPING,true).records().isEmpty(),"All-sheet repeat import.");
        System.out.println("PASS: mapping, footer exclusion, numeric quantities, empty and partially populated rows, duplicate handling, immutable downloads, all 164 sheets, and unchanged macro/other workbook parts.");
        // This diagnostic output is temporary test data and is not included in the deliverable.
        if(args.length>2)Files.write(Path.of(args[2]),result);
    }
}
