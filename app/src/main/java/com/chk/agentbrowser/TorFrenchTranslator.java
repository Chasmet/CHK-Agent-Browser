package com.chk.agentbrowser;

import android.content.Context;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.*;
import java.util.ArrayList;

/**
 * Local EN -> FR reader for isolated Tor pages.
 * Never downloads a model or sends page contents to a translation service from :tor.
 */
final class TorFrenchTranslator {
    interface Result { void done(boolean success, String translated); }
    private TorFrenchTranslator(){}

    static void translate(Context context, String source, Result result) {
        if(source==null || source.trim().isEmpty()){result.done(false,"Page vide ou non lisible.");return;}
        TranslateRemoteModel english=new TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build();
        TranslateRemoteModel french=new TranslateRemoteModel.Builder(TranslateLanguage.FRENCH).build();
        RemoteModelManager manager=RemoteModelManager.getInstance();
        manager.isModelDownloaded(english).addOnSuccessListener(en->
            manager.isModelDownloaded(french).addOnSuccessListener(fr->{
                if(!Boolean.TRUE.equals(en)||!Boolean.TRUE.equals(fr)){
                    result.done(false,"Le modèle EN → FR n'est pas encore installé. Ouvrir CHK > Tor > Préparer traduction hors ligne.");
                    return;
                }
                TranslatorOptions options=new TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(TranslateLanguage.FRENCH).build();
                Translator client=Translation.getClient(options);
                String compact=source.replaceAll("[\\t ]+"," ").trim();
                if(compact.length()>6500)compact=compact.substring(0,6500)+"\\n[Extrait limité à 6 500 caractères]";
                ArrayList<String> chunks=new ArrayList<>();
                for(int i=0;i<compact.length();i+=700)chunks.add(compact.substring(i,Math.min(i+700,compact.length())));
                translateNext(client,chunks,0,new StringBuilder(),result);
            }).addOnFailureListener(e->result.done(false,"Vérification du modèle français impossible : "+e.getMessage()))
        ).addOnFailureListener(e->result.done(false,"Vérification du modèle anglais impossible : "+e.getMessage()));
    }

    private static void translateNext(Translator client, ArrayList<String> chunks, int index,
                                      StringBuilder out, Result result) {
        if(index>=chunks.size()){client.close();result.done(true,out.toString());return;}
        client.translate(chunks.get(index)).addOnSuccessListener(translated->{
            if(out.length()>0)out.append("\\n\\n");
            out.append(translated);
            translateNext(client,chunks,index+1,out,result);
        }).addOnFailureListener(error->{client.close();result.done(false,"Traduction locale indisponible : "+error.getMessage());});
    }
}
