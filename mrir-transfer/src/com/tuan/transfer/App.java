package com.tuan.transfer;

import com.sun.net.httpserver.*;
import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class App {
    private static final int MAX_UPLOAD=20*1024*1024;
    private static final ConcurrentHashMap<String,Upload> uploads=new ConcurrentHashMap<>();
    private static final TransferEngine engine=new TransferEngine();
    private record Upload(Workbook workbook,String role,Instant created) {}
    private static int port;
    public static void main(String[] args) throws Exception {
        port=args.length>0?Integer.parseInt(args[0]):8080;
        HttpServer server;
        try {server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),0);}
        catch(BindException e){System.err.println("Port "+port+" is busy. Stop the other app, or run: java -jar mrir-transfer.jar 8081");System.exit(1);return;}
        server.createContext("/",App::handle);
        server.setExecutor(Executors.newFixedThreadPool(2));server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(0)));
        System.out.println("MRIR Transfer is running at http://127.0.0.1:"+port);
        System.out.println("Keep this window open. Press Ctrl+C to stop.");
        if(Desktop.isDesktopSupported())try{Desktop.getDesktop().browse(URI.create("http://127.0.0.1:"+port));}catch(Exception ignored){}
    }
    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String path=exchange.getRequestURI().getPath();
            var query=query(exchange.getRequestURI().getRawQuery());
            if(!Set.of("GET","POST").contains(exchange.getRequestMethod()))throw new IllegalArgumentException("Unsupported request method.");
            String origin=exchange.getRequestHeaders().getFirst("Origin");
            if(origin!=null&&!origin.equals("http://127.0.0.1:"+port))throw new IllegalArgumentException("Open the app using http://127.0.0.1:"+port);
            if(path.equals("/")||path.equals("/app.js")||path.equals("/style.css")) {
                if(!exchange.getRequestMethod().equals("GET"))throw new IllegalArgumentException("Use GET for app pages.");
                String resource=path.equals("/")?"index.html":path.substring(1);
                try(var stream=App.class.getResourceAsStream("/web/"+resource)) {
                    if(stream==null)throw new IllegalStateException("UI resource is missing.");
                    String type=resource.endsWith("js")?"text/javascript":resource.endsWith("css")?"text/css":"text/html";
                    send(exchange,200,type+"; charset=utf-8",stream.readAllBytes());
                }
                return;
            }
            if(path.equals("/api/mapping")) {
                json(exchange,200,Map.of("mapping",TransferEngine.DEFAULT_MAPPING,"target",TransferEngine.TARGET));return;
            }
            if(!exchange.getRequestMethod().equals("POST"))throw new IllegalArgumentException("Use POST for this operation.");
            if(path.equals("/api/upload")) {
                synchronized(uploads) {
                    uploads.entrySet().removeIf(e->e.getValue().created.isBefore(Instant.now().minus(Duration.ofMinutes(30))));
                    if(uploads.size()>=8)throw new IllegalArgumentException("Eight workbooks are already loaded. Clear uploads or restart the app.");
                }
                String role=one(query,"role"),filename=one(query,"name");
                if(!Set.of("source","destination").contains(role))throw new IllegalArgumentException("Invalid workbook role.");
                if(!filename.toLowerCase(Locale.ROOT).matches(".*\\.xls[mx]$"))throw new IllegalArgumentException("Upload .xlsm or .xlsx files only.");
                byte[] bytes=exchange.getRequestBody().readNBytes(MAX_UPLOAD+1);
                if(bytes.length>MAX_UPLOAD)throw new IllegalArgumentException("Workbook is larger than 20 MB.");
                Workbook workbook=new Workbook(bytes,filename);
                List<Map<String,Object>> sheets=List.of();
                if(role.equals("source"))sheets=engine.sourceSheets(workbook);else engine.validateTarget(workbook);
                String id=UUID.randomUUID().toString();uploads.put(id,new Upload(workbook,role,Instant.now()));
                json(exchange,200,Map.of("id",id,"filename",filename,"sheets",sheets,"target",TransferEngine.TARGET));return;
            }
            if(path.equals("/api/release")) {uploads.remove(one(query,"id"));json(exchange,200,Map.of("ok",true));return;}
            if(path.equals("/api/preview")||path.equals("/api/transfer")) {
                Workbook source=uploaded(one(query,"source"),"source"),destination=uploaded(one(query,"destination"),"destination");
                var selected=query.getOrDefault("sheet",List.of());
                var mapping=new LinkedHashMap<>(TransferEngine.DEFAULT_MAPPING);
                for(String col:mapping.keySet()) {
                    if(query.containsKey("map_"+col)) {
                        String value=one(query,"map_"+col);
                        if(!value.matches("row:[A-S]|header:(E4|O3|O1)|shipment|blank"))throw new IllegalArgumentException("Invalid source mapping for destination "+col);
                        if(Set.of("D","E","F","K","L").contains(col)&&value.equals("blank"))throw new IllegalArgumentException("Required destination field cannot be blank: "+col);
                        mapping.put(col,value);
                    }
                }
                boolean skip=!"false".equals(query.getOrDefault("skip",List.of("true")).get(0));
                if(path.endsWith("preview")) {
                    var plan=engine.plan(source,destination,selected,mapping,skip);
                    var rows=new ArrayList<Map<String,Object>>();
                    for(var p:plan.records().stream().limit(100).toList())rows.add(Map.of("sheet",p.item().sheet(),"sourceRow",p.item().sourceRow(),"destinationRow",p.destinationRow(),"values",p.item().values()));
                    json(exchange,200,Map.of("count",plan.records().size(),"duplicates",plan.duplicateCount(),"rows",rows,"warnings",plan.warnings(),"mapping",mapping));
                } else {
                    byte[] result=engine.transfer(source,destination,selected,mapping,skip);
                    boolean macro=destination.parts.containsKey("xl/vbaProject.bin");
                    exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=\"Free-Issue-transferred."+(macro?"xlsm":"xlsx")+"\"");
                    send(exchange,200,macro?"application/vnd.ms-excel.sheet.macroEnabled.12":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",result);
                }
                return;
            }
            json(exchange,404,Map.of("error","Page not found."));
        } catch(Exception error) {
            error.printStackTrace(System.err);
            json(exchange,400,Map.of("error",error.getMessage()==null?"Could not process the workbook.":error.getMessage()));
        } finally {exchange.close();}
    }
    private static Workbook uploaded(String id,String role) {
        Upload u=uploads.get(id);
        if(u==null||!u.role.equals(role))throw new IllegalArgumentException("Upload the "+role+" workbook again; its session is unavailable.");return u.workbook;
    }
    private static Map<String,List<String>> query(String raw) {
        var result=new LinkedHashMap<String,List<String>>();if(raw==null)return result;
        for(String part:raw.split("&")) {
            String[] pair=part.split("=",2);
            String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8),value=pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"";
            result.computeIfAbsent(key,k->new ArrayList<>()).add(value);
        }
        return result;
    }
    private static String one(Map<String,List<String>> q,String key) {
        if(!q.containsKey(key)||q.get(key).isEmpty()||q.get(key).get(0).isBlank())throw new IllegalArgumentException("Missing field: "+key);return q.get(key).get(0);
    }
    private static void json(HttpExchange e,int code,Object object) throws IOException {send(e,code,"application/json; charset=utf-8",jsonString(object).getBytes(StandardCharsets.UTF_8));}
    private static void send(HttpExchange e,int code,String type,byte[] bytes) throws IOException {
        e.getResponseHeaders().set("Content-Type",type);
        e.getResponseHeaders().set("Cache-Control","no-store");
        e.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        e.sendResponseHeaders(code,bytes.length);e.getResponseBody().write(bytes);
    }
    static String jsonString(Object object) {
        if(object==null)return "null";
        if(object instanceof Number||object instanceof Boolean)return object.toString();
        if(object instanceof Map<?,?> map) {
            var values=new ArrayList<String>();for(var entry:map.entrySet())values.add(jsonString(entry.getKey().toString())+":"+jsonString(entry.getValue()));return "{"+String.join(",",values)+"}";
        }
        if(object instanceof Iterable<?> list) {var values=new ArrayList<String>();for(Object value:list)values.add(jsonString(value));return "["+String.join(",",values)+"]";}
        StringBuilder s=new StringBuilder("\"");
        for(char c:object.toString().toCharArray())switch(c) {
            case '"' -> s.append("\\\"");case '\\' -> s.append("\\\\");case '\n' -> s.append("\\n");case '\r' -> s.append("\\r");case '\t' -> s.append("\\t");
            default -> {if(c<32)s.append(String.format("\\u%04x",(int)c));else s.append(c);}
        }
        return s.append('"').toString();
    }
}
