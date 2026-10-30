package com.chk.agentbrowser;

import android.content.Context;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.*;
import java.util.ArrayList;

/**
 * Translate in :tor using ONLY already installed ML Kit models.
 * No model download or external translation endpoint is permitted in :tor.
 * Missing public language models are installed separately by CHK's main process.
 */
final class TorFrenchTranslator {
    interface Result { void done(boolean success, String translated); }
    interface Availability { void done(boolean installed, String detail); }
    interface Progress { void update(String partial, int completed, int total); }
    private TorFrenchTranslator(){}

    static void isReady(Context context, Availability cb){
        RemoteModelManager models=RemoteModelManager.getInstance();
        TranslateRemoteModel en=new TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build();
        TranslateRemoteModel fr=new TranslateRemoteModel.Builder(TranslateLanguage.FRENCH).build();
        models.isModelDownloaded(en).addOnSuccessListener(english->
            models.isModelDownloaded(fr).addOnSuccessListener(french->
                cb.done(Boolean.TRUE.equals(english)&&Boolean.TRUE.equals(french),
                        "Le dictionnaire EN → FR est nécessaire. Il s'installe une seule fois depuis CHK, puis traduit localement."))
            .addOnFailureListener(error->cb.done(false,"Modèle français indisponible : "+error.getMessage()))
        ).addOnFailureListener(error->cb.done(false,"Modèle anglais indisponible : "+error.getMessage()));
    }

    static void translate(Context context,String source,Progress progress,Result result){
        if(source==null||source.trim().isEmpty()){result.done(false,"Aucun texte à traduire sur cette page.");return;}
        isReady(context,(installed,detail)->{
            if(!installed){result.done(false,detail);return;}
            try{
                TranslatorOptions options=new TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(TranslateLanguage.FRENCH).build();
                final Translator client=Translation.getClient(options);
                String compact=source.replaceAll("[\\t ]+"," ").trim();
                if(compact.length()>12000)compact=compact.substring(0,12000)+"\n[Fin de l'extrait — 12 000 caractères]";
                ArrayList<String> pieces=new ArrayList<>();
                // Short text segments keep the first visible result responsive.
                for(int pos=0;pos<compact.length();pos+=500)
                    pieces.add(compact.substring(pos,Math.min(pos+500,compact.length())));
                translateNext(client,pieces,0,new StringBuilder(),progress,result);
            }catch(Exception error){result.done(false,"Traduction locale : "+error.getMessage());}
        });
    }

    static void translate(Context context,String source,Result result){
        translate(context,source,(partial,n,total)->{},result);
    }

    private static void translateNext(Translator translator,ArrayList<String> parts,int index,
                                      StringBuilder output,Progress progress,Result cb){
        if(index==parts.size()){translator.close();cb.done(true,output.toString());return;}
        translator.translate(parts.get(index)).addOnSuccessListener(value->{
            if(output.length()>0)output.append("\n\n");
            output.append(value);
            progress.update(output.toString(),index+1,parts.size());
            translateNext(translator,parts,index+1,output,progress,cb);
        }).addOnFailureListener(error->{
            translator.close();
            cb.done(false,"Échec de traduction locale : "+error.getMessage());
        });
    }
}
