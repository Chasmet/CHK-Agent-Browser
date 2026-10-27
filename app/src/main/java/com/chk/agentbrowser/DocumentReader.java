package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Xml;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lecture locale des documents, sans upload vers un service d'IA externe.
 * Le texte PDF est extrait nativement; les scans passent par OCR ML Kit local.
 * Les noms/chemins sont confinés par WorkspaceStore.
 */
public final class DocumentReader {
    private static final int MAX_PDF_BYTES=20*1024*1024;
    private static final int MAX_PAGES_PER_CALL=8;
    private static final int MAX_TEXT=12000;
    private DocumentReader(){}

    static boolean supported(String name){
        String s=name.toLowerCase(Locale.ROOT);
        return s.endsWith(".pdf")||s.endsWith(".docx")||s.endsWith(".xlsx")||s.endsWith(".pptx")||s.endsWith(".png")||s.endsWith(".jpg")||
            s.endsWith(".jpeg")||s.endsWith(".webp")||s.endsWith(".bmp");
    }
    static boolean webPdfCandidate(String url){
        if(url==null)return false;
        try{
            android.net.Uri u=android.net.Uri.parse(url);
            if(!BrowserPolicy.webUrl(url)||u.getHost()==null)return false;
            String p=u.getPath()==null?"":u.getPath().toLowerCase(Locale.ROOT);
            String query=u.getQuery()==null?"":u.getQuery().toLowerCase(Locale.ROOT);
            return p.endsWith(".pdf")||p.contains("affichage_document_pdf")||query.contains("format=pdf")||query.contains("application/pdf");
        }catch(Exception e){return false;}
    }
    public static JSONObject readFile(Context app,String path,int page,int count,int offset)throws Exception{
        File f=new WorkspaceStore(app).file(path);
        if(!f.isFile())throw new IOException("Document inexistant.");
        String s=path.toLowerCase(Locale.ROOT);
        JSONObject result;
        if(s.endsWith(".pdf")) result=readPdf(app,f,page,count);
        else if(s.endsWith(".docx")) result=readDocx(f);
        else if(s.endsWith(".xlsx")||s.endsWith(".pptx"))result=OfficeDocumentReader.read(f,page,count);
        else if(s.endsWith(".png")||s.endsWith(".jpg")||s.endsWith(".jpeg")||s.endsWith(".webp")||s.endsWith(".bmp"))
            result=readImage(f);
        else throw new IOException("Type de document non pris en charge; texte brut: files_read_text.");
        result.put("path",path);
        return pageText(result,offset);
    }
    static JSONObject pageText(JSONObject result,int offset)throws Exception{
        String content=result.optString("text","");
        int start=Math.min(content.length(),Math.max(0,offset));
        int end=Math.min(content.length(),start+MAX_TEXT);
        result.put("text",content.substring(start,end));
        result.put("next_offset",end<content.length()?end:-1);
        if(content.isEmpty())result.put("warning","Aucun texte identifié: image possiblement illisible, chiffrée ou résolution insuffisante.");
        return result;
    }
    static JSONObject readPdf(Context app,File f,int page,int count)throws Exception{
        if(f.length()>MAX_PDF_BYTES)throw new IOException("PDF supérieur à 20 Mo: utilisez un fichier plus petit.");
        PDFBoxResourceLoader.init(app.getApplicationContext());
        try(PDDocument doc=PDDocument.load(f)){
            if(doc.isEncrypted())throw new IOException("PDF protégé par mot de passe.");
            int total=doc.getNumberOfPages();
            if(total<1)throw new IOException("PDF sans pages.");
            int start=Math.max(1,page);
            if(start>total)throw new IOException("Page au-delà du PDF.");
            int end=Math.min(total,start+Math.min(MAX_PAGES_PER_CALL,Math.max(1,count))-1);
            StringBuilder content=new StringBuilder();
            int ocr=0;
            for(int n=start;n<=end;n++){
                PDFTextStripper stripper=new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(n);
                stripper.setEndPage(n);
                String text=stripper.getText(doc).trim();
                if(text.length()<30){
                    String scanned=ocrPdfPage(app,f,n-1);
                    if(scanned.trim().length()>text.length()){text=scanned;ocr++;}
                }
                content.append("\n--- Page ").append(n).append(" ---\n").append(text).append('\n');
                if(content.length()>80000){end=n;break;}
            }
            return new JSONObject().put("format","pdf").put("text",content.toString())
                .put("page_count",total).put("first_page",start).put("last_page",end)
                .put("next_page",end<total?end+1:-1).put("ocr_pages",ocr);
        }
    }
    public static JSONObject readWebPdf(Context app,String address,String cookies)throws Exception{
        if(!BrowserPolicy.publicUrl(address))throw new IOException("Adresse PDF HTTP/HTTPS publique requise.");
        HttpURLConnection c=pdfConnection(address,cookies);
        File temp=File.createTempFile("chk-private-pdf-", ".pdf", app.getCacheDir());
        try{
            if(c.getResponseCode()!=200)throw new IOException("Accès au PDF refusé (HTTP "+c.getResponseCode()+").");
            long length=c.getContentLength();
            if(length>MAX_PDF_BYTES)throw new IOException("PDF trop volumineux.");
            int total=0;byte[] buffer=new byte[16384];
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(temp)){
                int n;
                while((n=in.read(buffer))!=-1){
                    total+=n;
                    if(total>MAX_PDF_BYTES)throw new IOException("PDF trop volumineux.");
                    out.write(buffer,0,n);
                }
            }
            try(FileInputStream in=new FileInputStream(temp)){
                byte[] signature=new byte[5];
                if(in.read(signature)!=5||!("%PDF-".equals(new String(signature,StandardCharsets.US_ASCII))))
                    throw new IOException("La réponse reçue n'est pas un fichier PDF.");
            }
            JSONObject result=pageText(readPdf(app,temp,1,MAX_PAGES_PER_CALL),0);
            result.put("document_source","authenticated_browser_readonly");
            result.put("temporary_file_deleted",true);
            return result;
        }finally{
            c.disconnect();
            if(!temp.delete()&&temp.exists())temp.deleteOnExit();
        }
    }
    private static HttpURLConnection pdfConnection(String address,String cookies)throws Exception{
        URL original=new URL(address),url=original;
        for(int hop=0;hop<6;hop++){
            if(!BrowserPolicy.publicUrl(url.toString()))throw new IOException("Redirection PDF non autorisée.");
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(12000);connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept","application/pdf");
            if(cookies!=null&&!cookies.isEmpty()&&original.getProtocol().equalsIgnoreCase(url.getProtocol())
                &&original.getHost().equalsIgnoreCase(url.getHost())&&original.getPort()==url.getPort())connection.setRequestProperty("Cookie",cookies);
            int code=connection.getResponseCode();
            if(code==301||code==302||code==303||code==307||code==308){
                String location=connection.getHeaderField("Location");connection.disconnect();
                if(location==null)throw new IOException("Redirection PDF sans destination.");url=new URL(url,location);continue;
            }
            return connection;
        }
        throw new IOException("Trop de redirections PDF.");
    }
    private static String ocrPdfPage(Context app,File pdf,int page)throws Exception{
        try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer renderer=new PdfRenderer(descriptor);
            PdfRenderer.Page p=renderer.openPage(page)){
            float scale=Math.min(2.5f,1450f/Math.max(p.getWidth(),p.getHeight()));
            int w=Math.max(1,Math.round(p.getWidth()*scale));
            int h=Math.max(1,Math.round(p.getHeight()*scale));
            Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            try{
                bitmap.eraseColor(Color.WHITE);
                p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return ocr(bitmap);
            }finally{bitmap.recycle();}
        }
    }
    static JSONObject readImage(File f)throws Exception{
        if(f.length()>MAX_PDF_BYTES)throw new IOException("Image trop volumineuse.");
        BitmapFactory.Options opts=new BitmapFactory.Options();
        opts.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(f.getAbsolutePath(),opts);
        if(opts.outWidth<=0||opts.outHeight<=0)throw new IOException("Format image non décodable.");
        opts.inJustDecodeBounds=false;
        opts.inSampleSize=1;
        while((long)opts.outWidth/opts.inSampleSize*(opts.outHeight/opts.inSampleSize)>3600000)opts.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeFile(f.getAbsolutePath(),opts);
        if(bitmap==null)throw new IOException("Impossible de lire l'image.");
        try{
            return new JSONObject().put("format","image_ocr").put("text",ocr(bitmap)).put("width",opts.outWidth).put("height",opts.outHeight);
        }finally{bitmap.recycle();}
    }
    private static String ocr(Bitmap bitmap)throws Exception{
        TextRecognizer recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try{
            return Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),22,TimeUnit.SECONDS).getText();
        }finally{recognizer.close();}
    }
    static JSONObject readDocx(File file)throws Exception{
        if(file.length()>MAX_PDF_BYTES)throw new IOException("DOCX trop volumineux.");
        StringBuilder out=new StringBuilder();
        try(ZipFile zip=new ZipFile(file)){
            if(zip.size()>5000)throw new IOException("DOCX trop complexe");
            ZipEntry document=zip.getEntry("word/document.xml");
            if(document==null)throw new IOException("DOCX endommagé.");
            try(InputStream in=zip.getInputStream(document)){
                XmlPullParser parser=OfficeDocumentReader.parser(OfficeDocumentReader.xml(zip,"word/document.xml"));
                int event;
                while((event=parser.next())!=XmlPullParser.END_DOCUMENT&&out.length()<80000){
                    if(event==XmlPullParser.TEXT&&"t".equals(parser.getName())){ /* text is handled in START_TAG below */ }
                    if(event==XmlPullParser.START_TAG){
                        String tag=parser.getName();
                        if("t".equals(tag))out.append(parser.nextText());
                        else if("tab".equals(tag))out.append(' ').append(' ');
                        else if("br".equals(tag))out.append('\n');
                    }else if(event==XmlPullParser.END_TAG&&"p".equals(parser.getName()))out.append('\n');
                }
            }
        }
        return new JSONObject().put("format","docx").put("text",out.toString());
    }
    public static JSONObject pdfPreview(Context app,File pdf,int page)throws Exception{
        if(!AgentClient.get(app).isPreviewAllowed())throw new IOException("Active le partage d'aperçus dans Réglages.");
        if(pdf.length()>MAX_PDF_BYTES)throw new IOException("PDF trop volumineux.");
        try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer renderer=new PdfRenderer(descriptor)){
            if(page<0||page>=renderer.getPageCount())throw new IOException("Page inexistante.");
            try(PdfRenderer.Page p=renderer.openPage(page)){
                float scale=Math.min(1.5f,900f/Math.max(p.getWidth(),p.getHeight()));
                Bitmap bmp=Bitmap.createBitmap(Math.max(1,Math.round(p.getWidth()*scale)),
                    Math.max(1,Math.round(p.getHeight()*scale)),Bitmap.Config.ARGB_8888);
                try{
                    bmp.eraseColor(Color.WHITE);
                    p.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    ByteArrayOutputStream jpeg=new ByteArrayOutputStream();
                    for(int quality=75;quality>=15;quality-=15){
                        jpeg.reset();bmp.compress(Bitmap.CompressFormat.JPEG,quality,jpeg);
                        if(jpeg.size()<=150000)break;
                    }
                    if(jpeg.size()>150000)throw new IOException("Aperçu PDF trop volumineux.");
                    return new JSONObject().put("format","pdf_page").put("page",page+1).put("page_count",renderer.getPageCount())
                        .put("width",bmp.getWidth()).put("height",bmp.getHeight())
                        .put("jpeg_base64",android.util.Base64.encodeToString(jpeg.toByteArray(),android.util.Base64.NO_WRAP));
                }finally{bmp.recycle();}
            }
        }
    }
}
