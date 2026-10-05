package com.tuan.transfer;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

public final class TransferEngine {
    public static final String TARGET="Packing list Detail";
    // Ordered mapping: destination column -> source selector.
    public static final LinkedHashMap<String,String> DEFAULT_MAPPING=new LinkedHashMap<>();
    static {
        DEFAULT_MAPPING.put("A","header:E4"); DEFAULT_MAPPING.put("C","header:O3");
        DEFAULT_MAPPING.put("D","header:O1"); DEFAULT_MAPPING.put("E","row:A");
        DEFAULT_MAPPING.put("F","row:B"); DEFAULT_MAPPING.put("J","row:P");
        DEFAULT_MAPPING.put("K","row:G"); DEFAULT_MAPPING.put("L","row:H");
        DEFAULT_MAPPING.put("M","row:I"); DEFAULT_MAPPING.put("O","row:K");
        DEFAULT_MAPPING.put("P","blank"); DEFAULT_MAPPING.put("Q","shipment");
    }
    public record Item(String sheet,int sourceRow,LinkedHashMap<String,Object> values) {}
    public record Planned(Item item,int destinationRow) {}
    public record Plan(List<Planned> records,int duplicateCount,List<String> warnings) {}

    public List<Map<String,Object>> sourceSheets(Workbook book) throws Exception {
        var result=new ArrayList<Map<String,Object>>();
        for(String name:book.sheets.keySet()) {
            var sheet=book.sheet(name);boolean compatible=compatible(sheet);
            int count=0;
            if(compatible) for(int r:sheet.rows.keySet())if(r>=9 && isItem(sheet,r))count++;
            result.add(Map.of("name",name,"count",count,"compatible",compatible));
        }
        return result;
    }
    private boolean compatible(Workbook.Sheet s) {
        return s.text(7,"B").toLowerCase(Locale.ROOT).contains("material specification")
            && s.text(7,"G").toLowerCase(Locale.ROOT).contains("unit")
            && !s.text(1,"O").isBlank();
    }
    private boolean isItem(Workbook.Sheet s,int row) {
        String number=s.text(row,"A").strip();
        if(s.text(row,"B").isBlank())return false;
        var cell=s.cell(row,"A");
        if(cell!=null && cell.formula && number.isBlank())throw new IllegalArgumentException(s.name+"!A"+row+" has no saved formula result. Open and save the source in Excel first.");
        try{return new BigDecimal(number).signum()>0 && new BigDecimal(number).stripTrailingZeros().scale()<=0;}
        catch(NumberFormatException e){return false;}
    }
    public void validateTarget(Workbook book) throws Exception {
        var s=book.sheet(TARGET);
        for(var check:Map.of("D","mrir","F","material specification","K","unit","L","q'ty").entrySet())
            if(!s.text(1,check.getKey()).toLowerCase(Locale.ROOT).contains(check.getValue()))
                throw new IllegalArgumentException("Unexpected destination header in "+TARGET+"!"+check.getKey()+"1.");
        if(Workbook.child(s.document.getDocumentElement(),"sheetProtection")!=null)
            throw new IllegalArgumentException("Destination sheet is protected. Upload an editable copy.");
    }
    public List<Item> readItems(Workbook source,List<String> selected,Map<String,String> mapping) throws Exception {
        if(selected.isEmpty())throw new IllegalArgumentException("Select at least one MRIR sheet.");
        var result=new ArrayList<Item>();
        for(String name:new LinkedHashSet<>(selected)) {
            var s=source.sheet(name);
            if(!compatible(s))throw new IllegalArgumentException("Unsupported MRIR layout: "+name);
            for(int r:s.rows.keySet()) {
                if(r<9 || !isItem(s,r))continue;
                var values=new LinkedHashMap<String,Object>();
                for(var m:mapping.entrySet()) {
                    String selector=m.getValue(), value;
                    if(selector.equals("shipment"))value=shipment(name);
                    else if(selector.startsWith("row:"))value=s.text(r,selector.substring(4));
                    else if(selector.startsWith("header:")) {
                        String address=selector.substring(7);value=cleanHeader(s.text(Workbook.rowNumber(address),Workbook.column(address)));
                    } else if(selector.equals("blank"))value="";
                    else throw new IllegalArgumentException("Invalid mapping: "+selector);
                    if(value.length()>32767)throw new IllegalArgumentException("Text exceeds Excel's cell limit in "+name+" row "+r);
                    if(m.getKey().equals("L")) {
                        try {values.put(m.getKey(),new BigDecimal(value.strip()));}
                        catch(Exception e) {throw new IllegalArgumentException(name+" row "+r+": quantity must be a saved number, found '"+value+"'.");}
                    } else if(m.getKey().equals("E"))values.put(m.getKey(),new BigDecimal(value.strip()));
                    else values.put(m.getKey(),value);
                }
                for(String required:List.of("D","F","K")) if(values.get(required)==null||values.get(required).toString().isBlank())throw new IllegalArgumentException("Missing required field "+required+" in "+name+" row "+r);
                result.add(new Item(name,r,values));
            }
        }
        if(result.isEmpty())throw new IllegalArgumentException("The selected sheets contain no numbered material records from row 9 onward.");
        return result;
    }
    /** Ignore formula-only inventory placeholders and literal inventory zeros. */
    public boolean isEmptyDestinationRow(Workbook.Sheet s,int row) {
        if(row<2 || s.mergedRow(row))return false;
        for(var entry:s.rows.getOrDefault(row,new LinkedHashMap<>()).entrySet()) {
            String col=entry.getKey();var c=entry.getValue();
            if(Workbook.columnNumber(col)>28) {
                if(!c.value.isBlank()||c.formula)return false;
                continue;
            }
            if(Set.of("X","Y","Z","AA").contains(col)) {
                if(c.formula && Set.of("X","Z","AA").contains(col))continue;
                if(c.value.isBlank()||Workbook.zero(c.value))continue;
                return false;
            }
            if(c.formula||!c.value.isBlank())return false;
        }
        return true;
    }
    public Plan plan(Workbook source,Workbook destination,List<String> selected,Map<String,String> mapping,boolean skipDuplicates) throws Exception {
        validateTarget(destination);
        var target=destination.sheet(TARGET);var records=readItems(source,selected,mapping);
        var keys=new HashSet<String>();
        if(skipDuplicates) for(int r:target.rows.keySet())if(r>=2 && !target.text(r,"D").isBlank())keys.add(existingKey(target,r));
        var planned=new ArrayList<Planned>();int duplicates=0,cursor=2;
        for(Item item:records) {
            if(skipDuplicates && !keys.add(itemKey(item))){duplicates++;continue;}
            while(cursor<=1048576&&!isEmptyDestinationRow(target,cursor))cursor++;
            if(cursor>1048576)throw new IllegalArgumentException("Destination exceeds Excel's row limit.");
            planned.add(new Planned(item,cursor++));
        }
        var warnings=new ArrayList<String>();
        warnings.add("Only mapped fields are transferred. Storage, workshop, date received, type, material, size, and part number remain unchanged or blank.");
        warnings.add("PO uses header O3; unlabeled values in source Q are not interpreted as per-item PO numbers.");
        if(planned.stream().anyMatch(p->p.destinationRow()>target.lastRow()))warnings.add("New rows will be added after the formatted area using existing cell styles. Review formatting in Excel.");
        return new Plan(planned,duplicates,warnings);
    }
    public byte[] transfer(Workbook source,Workbook destination,List<String> selected,Map<String,String> mapping,boolean skip) throws Exception {
        // Fresh target DOM each time: repeated preview/download never mutates the uploaded baseline.
        Workbook copy=new Workbook(destination.original,destination.filename);
        Plan plan=plan(source,copy,selected,mapping,skip);
        if(plan.records.isEmpty())return destination.original;
        var target=copy.sheet(TARGET);
        int styleRow=2;
        for(int r:target.rows.keySet())if(!target.text(r,"D").isBlank()&&!target.text(r,"F").isBlank())styleRow=r;
        for(Planned p:plan.records) {
            int r=p.destinationRow;
            if(!isEmptyDestinationRow(target,r))throw new IllegalStateException("Destination row is no longer empty: "+r);
            for(var v:p.item.values.entrySet())if(!v.getValue().toString().isEmpty())target.put(r,v.getKey(),v.getValue(),styleRow);
            BigDecimal quantity=(BigDecimal)p.item.values.get("L");
            target.formula(r,"X","L"+r,quantity.toPlainString(),styleRow);
            if(target.cell(r,"Y")==null||target.text(r,"Y").isBlank())target.put(r,"Y",BigDecimal.ZERO,styleRow);
            target.formula(r,"Z","X"+r+"-Y"+r,quantity.toPlainString(),styleRow);
            // Preserve an existing status formula; do not invent a new status field.
            if(target.cell(r,"AA")!=null && target.cell(r,"AA").formula)
                target.formula(r,"AA","IF(Z"+r+"=0,\"Complete\",\" Uncomplete\")",quantity.signum()==0?"Complete":" Uncomplete",styleRow);
        }
        target.expandRange(plan.records.get(plan.records.size()-1).destinationRow);
        return copy.export(target);
    }
    private static String canonical(Object o){String s=String.valueOf(o).strip().replaceAll("\\s+"," ");try{return new BigDecimal(s).stripTrailingZeros().toPlainString();}catch(Exception e){return s;}}
    private static String itemKey(Item item){return key(List.of("D","E","F","K","L").stream().map(c->canonical(item.values.get(c))).toList());}
    private static String existingKey(Workbook.Sheet s,int row){return key(List.of("D","E","F","K","L").stream().map(c->canonical(s.text(row,c))).toList());}
    private static String key(List<String> values){StringBuilder s=new StringBuilder();for(String v:values)s.append(v.length()).append(':').append(v);return s.toString();}
    static String cleanHeader(String s){return s.strip().replaceFirst("^:\\s*","");}
    static String shipment(String name){
        Matcher m=Pattern.compile("(?i)^SHIPMENT\\s+(.+?)(?:-\\d{3})?$").matcher(name.strip());
        return m.matches()?"Shipment "+m.group(1):name;
    }
}
