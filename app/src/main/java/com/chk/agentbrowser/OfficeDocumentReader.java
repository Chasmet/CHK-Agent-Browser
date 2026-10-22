package com.chk.agentbrowser;

import android.util.Xml;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Bounded local extraction of XLSX cell values and PPTX slide text, in declared document order. */
final class OfficeDocumentReader {
    private static final String R="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private OfficeDocumentReader(){}
    static JSONObject read(File file,int page,int count)throws Exception{
        return read(file,page,count,file.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx"));
    }
    static JSONObject read(File file,int page,int count,boolean sheet)throws Exception{
        if(file.length()>20L*1024*1024)throw new IOException("Document Office supérieur à 20 Mo.");
        String base=sheet?"xl/workbook.xml":"ppt/presentation.xml";
        try(ZipFile zip=new ZipFile(file)){
            if(zip.size()>5000)throw new IOException("Paquet Office trop complexe.");
            Map<String,String> links=links(zip,sheet?"xl/_rels/workbook.xml.rels":"ppt/_rels/presentation.xml.rels",base);
            List<String> paths=new ArrayList<>(),names=new ArrayList<>();XmlPullParser p=parser(xml(zip,base));
            while(p.next()!=XmlPullParser.END_DOCUMENT)if(p.getEventType()==XmlPullParser.START_TAG&&p.getName().equals(sheet?"sheet":"sldId")){
                String id=p.getAttributeValue(R,"id");if(!links.containsKey(id))throw new IOException("Relation Office manquante.");paths.add(links.get(id));names.add(sheet?p.getAttributeValue(null,"name"):"Diapositive "+paths.size());
                if(paths.size()>2000)throw new IOException("Document Office trop long.");
            }
            if(page<1||page>paths.size())throw new IOException("Page ou feuille Office hors limites.");
            List<String> shared=new ArrayList<>();
            if(sheet&&zip.getEntry("xl/sharedStrings.xml")!=null){
                p=parser(xml(zip,"xl/sharedStrings.xml"));StringBuilder item=null;
                while(p.next()!=XmlPullParser.END_DOCUMENT){if(p.getEventType()==XmlPullParser.START_TAG&&p.getName().equals("si"))item=new StringBuilder();else if(p.getEventType()==XmlPullParser.START_TAG&&p.getName().equals("t")&&item!=null)item.append(p.nextText());else if(p.getEventType()==XmlPullParser.END_TAG&&p.getName().equals("si")){shared.add(item==null?"":item.toString());item=null;}}
            }
            StringBuilder text=new StringBuilder();int end=Math.min(paths.size(),page+Math.min(8,Math.max(1,count))-1);boolean truncated=false;
            for(int i=page-1;i<end;i++){
                text.append(names.get(i)).append('\n');p=parser(xml(zip,paths.get(i)));String cell="",type="",value="",formula="";
                while(p.next()!=XmlPullParser.END_DOCUMENT){
                    int event=p.getEventType();String tag=p.getName();
                    if(sheet){
                        if(event==XmlPullParser.START_TAG&&"c".equals(tag)){cell=p.getAttributeValue(null,"r");type=p.getAttributeValue(null,"t");value="";formula="";}
                        else if(event==XmlPullParser.START_TAG&&("v".equals(tag)||"t".equals(tag)))value+=p.nextText();
                        else if(event==XmlPullParser.START_TAG&&"f".equals(tag))formula=p.nextText();
                        else if(event==XmlPullParser.END_TAG&&"c".equals(tag)){
                            if("s".equals(type)){int n=Integer.parseInt(value);if(n<0||n>=shared.size())throw new IOException("Index de texte partagé invalide.");value=shared.get(n);}
                            if("b".equals(type))value=value.equals("1")?"true":"false";
                            text.append(cell==null?"cellule":cell).append('=');if(!formula.isEmpty())text.append("[formule : ").append(formula).append(" ; valeur enregistrée : ").append(value).append(']');else text.append(value);text.append('\t');
                        }else if(event==XmlPullParser.END_TAG&&"row".equals(tag))text.append('\n');
                    }else{
                        if(event==XmlPullParser.START_TAG&&"t".equals(tag))text.append(p.nextText());
                        else if(event==XmlPullParser.END_TAG&&"p".equals(tag))text.append('\n');
                    }
                    if(text.length()>500000){text.setLength(500000);truncated=true;break;}
                }
                if(truncated)break;text.append('\n');
            }
            return new JSONObject().put("type",sheet?"xlsx":"pptx").put("text",text.toString()).put("page",page).put("pages_read",end-page+1).put("total_pages",paths.size()).put("truncated",truncated)
                .put("extraction","cell_values_and_formula_cache_or_slide_text").put("formula_evaluation",false);
        }
    }
    private static Map<String,String> links(ZipFile zip,String name,String base)throws Exception{
        Map<String,String> links=new HashMap<>();XmlPullParser p=parser(xml(zip,name));
        while(p.next()!=XmlPullParser.END_DOCUMENT)if(p.getEventType()==XmlPullParser.START_TAG&&p.getName().equals("Relationship")){
            if("External".equals(p.getAttributeValue(null,"TargetMode")))continue;
            String target=p.getAttributeValue(null,"Target"),id=p.getAttributeValue(null,"Id");if(target==null||id==null)throw new IOException("Relation Office invalide.");
            java.net.URI uri=new java.net.URI(target);if(uri.getScheme()!=null||uri.getAuthority()!=null||target.contains("\\"))throw new IOException("Relation Office externe interdite.");
            String path=new java.net.URI("zip:/"+base).resolve(uri).normalize().getPath();if(path==null||!path.startsWith("/")||path.contains("/../"))throw new IOException("Chemin Office invalide.");
            links.put(id,path.substring(1));
        }return links;
    }
    static XmlPullParser parser(String xml)throws Exception{
        if(xml.contains("<!DOCTYPE"))throw new IOException("DTD interdite dans un document Office.");
        XmlPullParser p=Xml.newPullParser();p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);p.setInput(new StringReader(xml));return p;
    }
    static String xml(ZipFile zip,String path)throws Exception{
        ZipEntry entry=zip.getEntry(path);if(entry==null||entry.isDirectory())throw new IOException("Composant Office absent : "+path);
        try(InputStream in=zip.getInputStream(entry);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("Composant Office décompressé supérieur à 2 Mo.");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}
