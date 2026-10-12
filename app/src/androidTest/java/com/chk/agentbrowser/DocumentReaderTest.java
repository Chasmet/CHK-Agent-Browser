package com.chk.agentbrowser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

/** Real file readers tested on Android without sending or modifying user documents. */
@RunWith(AndroidJUnit4.class)
public class DocumentReaderTest {
    @Test public void readsLocalPdfText()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceStore store=new WorkspaceStore(app);
        String name="reader-test-"+UUID.randomUUID()+".pdf";
        File file=store.file(name);
        PdfDocument document=new PdfDocument();
        try{
            PdfDocument.Page page=document.startPage(new PdfDocument.PageInfo.Builder(612,792,1).create());
            Canvas canvas=page.getCanvas();
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(18f);
            canvas.drawText("CONTROLE PDF TEST DE LECTURE DOCUMENT FISCAL ANNEE 2026",30,80,paint);
            document.finishPage(page);
            try(FileOutputStream out=new FileOutputStream(file)){document.writeTo(out);}
        }finally{document.close();}
        try{
            JSONObject result=DocumentReader.readFile(app,name,1,1,0);
            assertEquals("pdf",result.getString("format"));
            assertEquals(1,result.getInt("page_count"));
            assertTrue("texte PDF manquant: "+result.optString("text"),result.getString("text").contains("CONTROLE"));
        }finally{file.delete();}
    }

    @Test public void readsLocalDocx()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceStore store=new WorkspaceStore(app);
        String name="reader-test-"+UUID.randomUUID()+".docx";
        File file=store.file(name);
        try{
            try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(file))){
                out.putNextEntry(new ZipEntry("word/document.xml"));
                String xml="<?xml version=\"1.0\" encoding=\"UTF-8\"?>"+
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"+
                    "<w:body><w:p><w:r><w:t>Document personnel teste sans modification</w:t></w:r></w:p></w:body></w:document>";
                out.write(xml.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            JSONObject result=DocumentReader.readFile(app,name,1,1,0);
            assertEquals("docx",result.getString("format"));
            assertTrue(result.getString("text").contains("Document personnel teste"));
        }finally{file.delete();}
    }
}
