package com.chk.agentbrowser;

import android.content.Context;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.*;
import java.util.ArrayList;

/** Local EN -> FR Tor reading, no remote translation request or model download in :tor. */
final class TorFrenchTranslator {
    interface Result { void done(boolean success, String translated); }
    interface Ready { void done(boolean ready, String reason); }
    private TorFrenchTranslator(){}

    static void ready(Ready callback){
        RemoteModelManager manager=RemoteModelManager.getInstance();
        TranslateRemoteModel english=new TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build();
        TranslateRemoteModel french=new TranslateRemoteModel.Builder(TranslateLanguage.FRENCH).build();
        manager.isModelDownloaded(english).addOnSuccessListener(en->
            manager.isModelDownloaded(french).addOnSuccessListener(fr->{
                boolean installed=Boolean.TRUE.equals(en)&&Boolean.TRUE.equals(fr);
                callback.done(installed,installed?"Modèles anglais et français installés.":
                    "Les modèles EN → FR ne sont pas encore installés.");
            }).addOnFailureListener(error->callback.done(false,
                "Impossible de vérifier le modèle français : "+error.getMessage()))
        ).addOnFailureListener(error->callback.done(false,
            "Impossible de vérifier le modèle anglais : "+error.getMessage()));
    }

    static void translate(Context context, String source, Result callback){
        if(source==null||source.trim().isEmpty()){
            callback.done(false,"Page sans texte lisible.");return;
        }
        // Re-check to fail closed; do not accidentally fetch a language model in Tor.
        ready((installed,message)->{
            if(!installed){callback.done(false,message);return;}
            TranslatorOptions options=new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.FRENCH)
                .build();
            Translator client=Translation.getClient(options);
            String compact=source.replaceAll("[\\t ]+"," ").trim();
            if(compact.length()>12000)
                compact=compact.substring(0,12000)+"\n[Lecture limitée aux premiers 12 000 caractères]";
            ArrayList<String> chunks=new ArrayList<>();
            for(int i=0;i<compact.length();i+=800)
                chunks.add(compact.substring(i,Math.min(i+800,compact.length())));
            translateNext(client,chunks,0,new StringBuilder(),callback);
        });
    }
    private static void translateNext(Translator client,ArrayList<String> chunks,int index,
                                      StringBuilder out,Result callback){
        if(index>=chunks.size()){
            client.close();callback.done(true,out.toString());return;
        }
        client.translate(chunks.get(index)).addOnSuccessListener(translated->{
            if(out.length()>0)out.append("\n\n");
            out.append(translated);
            translateNext(client,chunks,index+1,out,callback);
        }).addOnFailureListener(error->{
            client.close();callback.done(false,"Traduction locale indisponible : "+error.getMessage());
        });
    }
}
