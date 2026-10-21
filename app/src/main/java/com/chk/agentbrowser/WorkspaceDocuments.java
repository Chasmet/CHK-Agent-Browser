package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Small local document generator. Office packages contain editable text and values, no macros. */
final class WorkspaceDocuments {
    private static final String REL="http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    private WorkspaceDocuments(){}
    static JSONObject create(Context c,JSONObject p,AgentClient.ResultCallback active)throws Exception{
        if(p.toString().getBytes(StandardCharsets.UTF_8).length>65536)throw new IOException("Création limitée à 64 Ko de contenu par document.");
        WorkspaceStore store=new WorkspaceStore(c);String path=p.getString("path"),format=p.getString("format");File target=store.file(path);
        if(!Arrays.asList("pdf","docx","xlsx","pptx","json","csv","txt").contains(format)||!path.toLowerCase(Locale.ROOT).endsWith("."+format))throw new IOException("Format et extension doivent correspondre : pdf, docx, xlsx, pptx, json, csv ou txt.");
        if(target.exists()||!target.getParentFile().isDirectory())throw new IOException("Destination existante ou parent absent. Créer une nouvelle version du document.");
        File stage=new File(target.getParentFile(),".document-"+UUID.randomUUID());int units=1;
        try{
            switch(format){
                case "pdf":units=pdf(stage,p);break;
                case "docx":docx(stage,p);break;
                case "xlsx":xlsx(stage,p);break;
                case "pptx":units=pptx(c,stage,p);break;
                case "json":Object data=p.get("data");if(!(data instanceof JSONObject)&&!(data instanceof JSONArray))throw new IOException("JSON : data doit être un objet ou un tableau.");write(stage,data instanceof JSONObject?((JSONObject)data).toString(2):((JSONArray)data).toString(2));break;
                case "csv":write(stage,csv(p.getJSONArray("rows")));break;
                default:write(stage,p.getString("text"));
            }
            if(!stage.isFile()||stage.length()==0)throw new IOException("Document vide : création non confirmée.");
            if(Arrays.asList("docx","xlsx","pptx").contains(format))try(ZipFile zip=new ZipFile(stage)){
                String main=format.equals("docx")?"word/document.xml":format.equals("xlsx")?"xl/workbook.xml":"ppt/presentation.xml";
                if(zip.getEntry(main)==null||zip.getEntry("[Content_Types].xml")==null)throw new IOException("Paquet Office incomplet.");
            }
            try(RandomAccessFile sync=new RandomAccessFile(stage,"rw")){sync.getFD().sync();}
            String hash=WorkspaceTransfer.hash(stage);if(!active.isActive())throw new IOException("Création arrêtée avant publication.");
            if(target.exists()||!stage.renameTo(target))throw new IOException("Publication du document impossible.");
            return new JSONObject().put("path",path).put("format",format).put("size",target.length()).put("sha256",hash).put("exists",true).put("verified",true).put("pages_or_slides",units).put("storage","private_phone");
        }finally{stage.delete();}
    }
    private static void write(File f,String text)throws Exception{try(FileOutputStream out=new FileOutputStream(f)){out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}
    private static String xml(String s)throws IOException{
        for(int i=0;i<s.length();){int cp=s.codePointAt(i);if(!(cp==9||cp==10||cp==13||cp>=32&&cp<=0xD7FF||cp>=0xE000&&cp<=0xFFFD||cp>=0x10000&&cp<=0x10FFFF))throw new IOException("Caractère XML interdit.");i+=Character.charCount(cp);}
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
    }
    private static void entry(ZipOutputStream zip,String name,String text)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    private static String types(String overrides){return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"+overrides+"</Types>";}
    private static String override(String path,String type){return "<Override PartName=\"/"+path+"\" ContentType=\"application/vnd.openxmlformats-officedocument."+type+"+xml\"/>";}
    private static String relationship(String id,String type,String target){return "<Relationship Id=\""+id+"\" Type=\""+REL+type+"\" Target=\""+target+"\"/>";}
    private static String rels(String body){return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+body+"</Relationships>";}
    private static void docx(File f,JSONObject p)throws Exception{
        StringBuilder body=new StringBuilder();String text=p.optString("title","")+"\n"+p.getString("text");
        for(String line:text.split("\n",-1))body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(xml(line)).append("</w:t></w:r></w:p>");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(f))){
            entry(zip,"[Content_Types].xml",types(override("word/document.xml","wordprocessingml.document.main")));
            entry(zip,"_rels/.rels",rels(relationship("rId1","officeDocument","word/document.xml")));
            entry(zip,"word/document.xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"+body+"<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr></w:body></w:document>");
        }
    }
    private static JSONArray rows(JSONObject p)throws Exception{JSONArray rows=p.getJSONArray("rows");if(rows.length()>2000)throw new IOException("Tableau limité à 2 000 lignes.");for(int i=0;i<rows.length();i++)if(rows.getJSONArray(i).length()>100)throw new IOException("Tableau limité à 100 colonnes.");return rows;}
    private static void xlsx(File f,JSONObject p)throws Exception{
        JSONArray rows=rows(p);StringBuilder data=new StringBuilder();
        for(int i=0;i<rows.length();i++){data.append("<row r=\"").append(i+1).append("\">");JSONArray row=rows.getJSONArray(i);
            for(int j=0;j<row.length();j++){Object value=row.get(j);if(value==JSONObject.NULL)continue;String cell=column(j)+(i+1);data.append("<c r=\"").append(cell).append("\"");
                if(value instanceof Number){double n=((Number)value).doubleValue();if(Double.isInfinite(n)||Double.isNaN(n))throw new IOException("Nombre non fini.");data.append("><v>").append(value).append("</v></c>");}
                else if(value instanceof Boolean)data.append(" t=\"b\"><v>").append((Boolean)value?1:0).append("</v></c>");
                else if(value instanceof String)data.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml((String)value)).append("</t></is></c>");
                else throw new IOException("Cellule : texte, nombre, booléen ou null requis.");
            }data.append("</row>");
        }
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(f))){
            entry(zip,"[Content_Types].xml",types(override("xl/workbook.xml","spreadsheetml.sheet.main")+override("xl/worksheets/sheet1.xml","spreadsheetml.worksheet")));
            entry(zip,"_rels/.rels",rels(relationship("rId1","officeDocument","xl/workbook.xml")));
            entry(zip,"xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\""+REL.substring(0,REL.length()-1)+"\"><sheets><sheet name=\"Feuille 1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip,"xl/_rels/workbook.xml.rels",rels(relationship("rId1","worksheet","worksheets/sheet1.xml")));
            entry(zip,"xl/worksheets/sheet1.xml","<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"+data+"</sheetData></worksheet>");
        }
    }
    private static String column(int n){StringBuilder s=new StringBuilder();do{s.insert(0,(char)('A'+n%26));n=n/26-1;}while(n>=0);return s.toString();}
    private static String csv(JSONArray rows)throws Exception{
        if(rows.length()>2000)throw new IOException("CSV limité à 2 000 lignes.");StringBuilder text=new StringBuilder();
        for(int i=0;i<rows.length();i++){JSONArray row=rows.getJSONArray(i);if(row.length()>100)throw new IOException("CSV limité à 100 colonnes.");for(int j=0;j<row.length();j++){if(j>0)text.append(',');String value=row.isNull(j)?"":String.valueOf(row.get(j));text.append('"').append(value.replace("\"","\"\"")).append('"');}text.append("\r\n");}return text.toString();
    }
    private static int pdf(File f,JSONObject p)throws Exception{
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(android.graphics.Color.BLACK);paint.setTextSize(12);
        List<String> lines=new ArrayList<>();String text=p.optString("title","")+"\n\n"+p.getString("text");
        for(String paragraph:text.split("\n",-1)){
            if(paragraph.isEmpty())lines.add("");else for(int offset=0;offset<paragraph.length();){int count=paint.breakText(paragraph,offset,paragraph.length(),true,499,null);if(count<=0)count=Character.charCount(paragraph.codePointAt(offset));if(offset+count<paragraph.length()&&Character.isHighSurrogate(paragraph.charAt(offset+count-1)))count--;if(count==0)count=2;lines.add(paragraph.substring(offset,offset+count));offset+=count;}
        }
        int pages=Math.max(1,(lines.size()+43)/44);if(pages>100)throw new IOException("PDF limité à 100 pages.");
        try(PdfDocument doc=new PdfDocument()){
            for(int page=0;page<pages;page++){PdfDocument.Page sheet=doc.startPage(new PdfDocument.PageInfo.Builder(595,842,page+1).create());Canvas canvas=sheet.getCanvas();for(int i=page*44;i<Math.min(lines.size(),(page+1)*44);i++)canvas.drawText(lines.get(i),48,60+(i-page*44)*16,paint);doc.finishPage(sheet);}
            try(FileOutputStream out=new FileOutputStream(f)){doc.writeTo(out);out.getFD().sync();}
        }return pages;
    }
    private static int pptx(Context c,File f,JSONObject p)throws Exception{
        JSONArray slides=p.getJSONArray("slides");if(slides.length()<1||slides.length()>50)throw new IOException("Présentation : 1 à 50 diapositives.");
        StringBuilder ids=new StringBuilder("<p:sldIdLst>"),links=new StringBuilder(),types=new StringBuilder();
        for(int i=1;i<=slides.length();i++){ids.append("<p:sldId id=\"").append(255+i).append("\" r:id=\"rIdCHK").append(i).append("\"/>");links.append(relationship("rIdCHK"+i,"slide","slides/slide"+i+".xml"));types.append(override("ppt/slides/slide"+i+".xml","presentationml.slide"));}ids.append("</p:sldIdLst>");
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();try(InputStream in=c.getAssets().open("document_templates/presentation.b64")){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)encoded.write(b,0,n);}
        try(ZipInputStream template=new ZipInputStream(new ByteArrayInputStream(Base64.decode(encoded.toByteArray(),Base64.DEFAULT)));ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(f))){
            ZipEntry part;while((part=template.getNextEntry())!=null){ByteArrayOutputStream content=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=template.read(b))!=-1)content.write(b,0,n);String xml=new String(content.toByteArray(),StandardCharsets.UTF_8);
                if(part.getName().equals("ppt/presentation.xml"))xml=xml.replace("<!--CHK_SLIDES-->",ids);
                else if(part.getName().equals("ppt/_rels/presentation.xml.rels"))xml=xml.replace("</Relationships>",links+"</Relationships>");
                else if(part.getName().equals("[Content_Types].xml"))xml=xml.replace("</Types>",types+"</Types>");
                // Preserve non-XML template parts as bytes (the package includes a JPEG thumbnail).
                zip.putNextEntry(new ZipEntry(part.getName()));zip.write(part.getName().endsWith(".xml")||part.getName().endsWith(".rels")?xml.getBytes(StandardCharsets.UTF_8):content.toByteArray());zip.closeEntry();
            }
            for(int i=1;i<=slides.length();i++){JSONObject slide=slides.getJSONObject(i-1);String shapes=shape(2,slide.optString("title",""),400000,250000,8300000,1000000,3000)+shape(3,slide.getString("text"),400000,1400000,8300000,4800000,2000);
                entry(zip,"ppt/slides/slide"+i+".xml","<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\""+REL.substring(0,REL.length()-1)+"\"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"+shapes+"</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>");
                entry(zip,"ppt/slides/_rels/slide"+i+".xml.rels",rels(relationship("rId1","slideLayout","../slideLayouts/slideLayout7.xml")));
            }
        }return slides.length();
    }
    private static String shape(int id,String text,int x,int y,int w,int h,int size)throws Exception{
        StringBuilder paragraphs=new StringBuilder();for(String line:text.split("\n",-1))paragraphs.append("<a:p><a:r><a:rPr lang=\"fr-FR\" sz=\"").append(size).append("\"/><a:t>").append(xml(line)).append("</a:t></a:r></a:p>");
        return "<p:sp><p:nvSpPr><p:cNvPr id=\""+id+"\" name=\"Texte "+id+"\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\""+x+"\" y=\""+y+"\"/><a:ext cx=\""+w+"\" cy=\""+h+"\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr wrap=\"square\"><a:spAutoFit/></a:bodyPr><a:lstStyle/>"+paragraphs+"</p:txBody></p:sp>";
    }
}
