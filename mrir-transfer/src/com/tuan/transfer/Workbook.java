package com.tuan.transfer;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Reads OOXML without executing macros; writes only explicitly replaced ZIP parts. */
public final class Workbook {
    static final String NS="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String REL="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    final LinkedHashMap<String,byte[]> parts=new LinkedHashMap<>();
    final LinkedHashMap<String,String> sheets=new LinkedHashMap<>();
    final List<String> strings=new ArrayList<>();
    final Map<String,Sheet> cache=new HashMap<>();
    final byte[] original;
    final String filename;

    public Workbook(byte[] bytes,String filename) throws Exception {
        this.original=bytes; this.filename=filename;
        int total=0, entries=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if(++entries>10000) throw new IllegalArgumentException("Workbook contains too many ZIP entries.");
                byte[] data=zip.readNBytes(64*1024*1024+1);
                total+=data.length;
                if(data.length>64*1024*1024 || total>256*1024*1024) throw new IllegalArgumentException("Workbook expands beyond the supported size.");
                if(parts.put(entry.getName(),data)!=null) throw new IllegalArgumentException("Duplicate workbook ZIP entry.");
            }
        }
        if(!parts.containsKey("xl/workbook.xml")) throw new IllegalArgumentException("Not a valid .xlsx/.xlsm workbook. Encrypted and .xls files are unsupported.");
        if(parts.keySet().stream().anyMatch(s->s.startsWith("_xmlsignatures/"))) throw new IllegalArgumentException("Digitally signed workbook packages are unsupported.");
        Document book=parse(parts.get("xl/workbook.xml"));
        Document rels=parse(parts.get("xl/_rels/workbook.xml.rels"));
        Map<String,String> paths=new HashMap<>();
        for(Element e:descendants(rels.getDocumentElement(),"Relationship")) {
            if("External".equals(e.getAttribute("TargetMode"))) continue;
            String target=e.getAttribute("Target");
            String path=target.startsWith("/")?target.substring(1):Path.of("xl").resolve(target).normalize().toString().replace('\\','/');
            paths.put(e.getAttribute("Id"),path);
        }
        for(Element e:descendants(book.getDocumentElement(),"sheet")) {
            String path=paths.get(e.getAttributeNS(REL,"id"));
            if(path!=null && path.startsWith("xl/worksheets/")) sheets.put(e.getAttribute("name"),path);
        }
        if(parts.containsKey("xl/sharedStrings.xml")) {
            for(Element si:descendants(parse(parts.get("xl/sharedStrings.xml")).getDocumentElement(),"si")) strings.add(richText(si));
        }
    }

    public Sheet sheet(String name) throws Exception {
        if(!sheets.containsKey(name)) throw new IllegalArgumentException("Worksheet is missing: "+name);
        if(!cache.containsKey(name)) cache.put(name,new Sheet(name,sheets.get(name),parse(parts.get(sheets.get(name)))));
        return cache.get(name);
    }

    byte[] export(Sheet changed) throws Exception {
        Map<String,byte[]> replacements=new HashMap<>();
        replacements.put(changed.path,serialize(changed.document));
        Document book=parse(parts.get("xl/workbook.xml"));
        Element calc=child(book.getDocumentElement(),"calcPr");
        if(calc==null) {calc=book.createElementNS(NS,"calcPr");book.getDocumentElement().appendChild(calc);}
        calc.setAttribute("fullCalcOnLoad","1");calc.setAttribute("forceFullCalc","1");calc.setAttribute("calcMode","auto");
        replacements.put("xl/workbook.xml",serialize(book));
        var output=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output)) {
            for(var entry:parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(replacements.getOrDefault(entry.getKey(),entry.getValue()));zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    public final class Sheet {
        final String name,path;
        final Document document;
        final TreeMap<Integer,LinkedHashMap<String,Cell>> rows=new TreeMap<>();
        final Map<Integer,Element> rowElements=new HashMap<>();
        final List<int[]> merges=new ArrayList<>();
        Sheet(String name,String path,Document doc) {
            this.name=name;this.path=path;this.document=doc;
            Element sheetData=child(doc.getDocumentElement(),"sheetData");
            if(sheetData==null) throw new IllegalArgumentException("Worksheet has no sheetData: "+name);
            for(Element row:children(sheetData,"row")) {
                int n=Integer.parseInt(row.getAttribute("r"));
                var cells=new LinkedHashMap<String,Cell>();
                for(Element cell:children(row,"c")) cells.put(column(cell.getAttribute("r")),new Cell(cell));
                rows.put(n,cells);rowElements.put(n,row);
            }
            Element merged=child(doc.getDocumentElement(),"mergeCells");
            if(merged!=null) for(Element m:children(merged,"mergeCell")) {
                String[] ends=m.getAttribute("ref").split(":");
                if(ends.length==2) merges.add(new int[]{columnNumber(column(ends[0])),rowNumber(ends[0]),columnNumber(column(ends[1])),rowNumber(ends[1])});
            }
        }
        String text(int row,String col) {Cell c=cell(row,col);return c==null?"":c.value;}
        Cell cell(int row,String col) {return rows.getOrDefault(row,new LinkedHashMap<>()).get(col);}
        boolean mergedRow(int row) {return merges.stream().anyMatch(m->row>=m[1]&&row<=m[3]&&m[0]<=28);}
        int lastRow() {return rows.isEmpty()?1:rows.lastKey();}

        Element ensureCell(int row,String col,int styleRow) {
            Element rowElement=rowElements.get(row);
            if(rowElement==null) {
                rowElement=document.createElementNS(NS,"row");rowElement.setAttribute("r",Integer.toString(row));
                Element data=child(document.getDocumentElement(),"sheetData");
                Element before=null;
                for(Element candidate:children(data,"row")) if(Integer.parseInt(candidate.getAttribute("r"))>row){before=candidate;break;}
                data.insertBefore(rowElement,before);rowElements.put(row,rowElement);rows.put(row,new LinkedHashMap<>());
            }
            Cell old=cell(row,col);
            if(old!=null) return old.element;
            Element cell=document.createElementNS(NS,"c");cell.setAttribute("r",col+row);
            Cell prototype=cell(styleRow,col);
            if(prototype!=null && prototype.element.hasAttribute("s")) cell.setAttribute("s",prototype.element.getAttribute("s"));
            Element before=null;
            for(Element existing:children(rowElement,"c")) if(columnNumber(column(existing.getAttribute("r")))>columnNumber(col)){before=existing;break;}
            rowElement.insertBefore(cell,before);return cell;
        }

        void put(int row,String col,Object value,int styleRow) {
            Element e=ensureCell(row,col,styleRow);
            for(String tag:List.of("f","v","is")) {Element old;while((old=child(e,tag))!=null)e.removeChild(old);}
            e.removeAttribute("t");
            if(value instanceof Number) {
                Element v=document.createElementNS(NS,"v");v.setTextContent(value.toString());e.appendChild(v);
            } else {
                e.setAttribute("t","inlineStr");
                Element is=document.createElementNS(NS,"is"),t=document.createElementNS(NS,"t");
                t.setAttributeNS(XMLConstants.XML_NS_URI,"xml:space","preserve");t.setTextContent(String.valueOf(value));is.appendChild(t);e.appendChild(is);
            }
            rows.computeIfAbsent(row,k->new LinkedHashMap<>()).put(col,new Cell(e));
        }

        void formula(int row,String col,String expression,String result,int styleRow) {
            Element e=ensureCell(row,col,styleRow);
            Element f=child(e,"f");
            if(f==null) {
                for(String tag:List.of("v","is")){Element old;while((old=child(e,tag))!=null)e.removeChild(old);}
                f=document.createElementNS(NS,"f");f.setTextContent(expression);e.appendChild(f);
            }
            Element v=child(e,"v");if(v==null){v=document.createElementNS(NS,"v");e.appendChild(v);}
            if("AA".equals(col))e.setAttribute("t","str");else e.removeAttribute("t");
            v.setTextContent(result);
            rows.computeIfAbsent(row,k->new LinkedHashMap<>()).put(col,new Cell(e));
        }

        void expandRange(int end) {
            Element dimension=child(document.getDocumentElement(),"dimension");
            if(dimension!=null) {
                String[] ends=dimension.getAttribute("ref").split(":");
                String last=ends[ends.length-1];
                dimension.setAttribute("ref",ends[0]+":"+column(last)+Math.max(rowNumber(last),end));
            }
            Element filter=child(document.getDocumentElement(),"autoFilter");
            if(filter!=null) {
                String[] ends=filter.getAttribute("ref").split(":");
                if(ends.length==2)filter.setAttribute("ref",ends[0]+":"+column(ends[1])+Math.max(rowNumber(ends[1]),end));
            }
        }
    }

    final class Cell {
        final Element element;final String value,type;final boolean formula;
        Cell(Element e) {
            element=e;type=e.getAttribute("t");formula=child(e,"f")!=null;
            Element v=child(e,"v");String raw=v==null?"":v.getTextContent();
            value=switch(type) {
                case "s" -> raw.isEmpty()?"":strings.get(Integer.parseInt(raw));
                case "inlineStr" -> child(e,"is")==null?"":richText(child(e,"is"));
                default -> raw;
            };
        }
    }

    static String column(String address){return address.replaceAll("[0-9$]","");}
    static int rowNumber(String address){return Integer.parseInt(address.replaceAll("[^0-9]",""));}
    static int columnNumber(String col){int n=0;for(char c:col.toCharArray())n=n*26+c-'A'+1;return n;}
    static boolean zero(String s){try{return new BigDecimal(s).signum()==0;}catch(Exception e){return false;}}
    static String richText(Element element) {
        StringBuilder s=new StringBuilder();
        for(Element child:children(element,null)) {
            if("t".equals(child.getLocalName()))s.append(child.getTextContent());
            else if("r".equals(child.getLocalName())) {Element t=child(child,"t");if(t!=null)s.append(t.getTextContent());}
        }
        return s.toString();
    }
    static List<Element> children(Element parent,String tag) {
        var out=new ArrayList<Element>();
        for(Node node=parent.getFirstChild();node!=null;node=node.getNextSibling())
            if(node instanceof Element e && (tag==null||tag.equals(e.getLocalName())))out.add(e);
        return out;
    }
    static Element child(Element parent,String tag){return children(parent,tag).stream().findFirst().orElse(null);}
    static List<Element> descendants(Element parent,String tag){
        var out=new ArrayList<Element>();NodeList nodes=parent.getElementsByTagNameNS("*",tag);
        for(int i=0;i<nodes.getLength();i++)out.add((Element)nodes.item(i));return out;
    }
    static Document parse(byte[] data) throws Exception {
        if(data==null)throw new IllegalArgumentException("Required workbook XML part is missing.");
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
    }
    static byte[] serialize(Document doc) throws Exception {
        var factory=TransformerFactory.newInstance();factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
        var t=factory.newTransformer();t.setOutputProperty(OutputKeys.ENCODING,"UTF-8");t.setOutputProperty(OutputKeys.INDENT,"no");
        var out=new ByteArrayOutputStream();t.transform(new DOMSource(doc),new StreamResult(out));return out.toByteArray();
    }
}
