package com.chk.agentbrowser;

import android.webkit.WebView;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Traduction locale de texte de page EN/FR. Les modèles ML Kit sont téléchargés
 * lors du premier usage ; ensuite le traitement peut fonctionner hors ligne.
 * Aucun HTML, cookie ou champ de formulaire n'est envoyé à un service distant.
 */
public final class BrowserTranslator {
    private BrowserTranslator() {}
    public interface Callback { void done(boolean ok, String message); }

    private static final String PREPARE =
        "(function(){if(!document.body)return JSON.stringify({error:'Page non chargée'});"
        +"var s=window.__chkTranslation;"
        +"if(!s){s={nodes:[],original:[]};"
        +"var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);var n,total=0;"
        +"while((n=w.nextNode())&&s.nodes.length<100&&total<10000){"
        +"var p=n.parentElement;if(!p||!n.nodeValue||!n.nodeValue.trim())continue;"
        +"if(p.closest('script,style,noscript,textarea,input,select,option,code,pre,[contenteditable], [translate=no], [class*=password]'))continue;"
        +"var t=n.nodeValue;if(t.length>1100||!/[a-zA-ZÀ-ÿ]{2}/.test(t))continue;"
        +"s.nodes.push(n);s.original.push(t);total+=t.length;}"
        +"window.__chkTranslation=s;}return JSON.stringify({texts:s.original});})()";

    private static final String RESTORE =
        "(function(){var s=window.__chkTranslation;if(!s)return false;"
        +"s.nodes.forEach(function(n,i){if(n&&n.isConnected)n.nodeValue=s.original[i]});"
        +"delete window.__chkTranslation;return true;})()";

    public static void translate(WebView web, String direction, Callback callback) {
        if(web==null||!BrowserWebState.alive(web)){callback.done(false,"Onglet indisponible.");return;}
        if("original".equals(direction)) {
            web.evaluateJavascript(RESTORE, raw->callback.done(true,"Texte original restauré."));
            return;
        }
        final String source,target;
        if("en-fr".equals(direction)){source=TranslateLanguage.ENGLISH;target=TranslateLanguage.FRENCH;}
        else if("fr-en".equals(direction)){source=TranslateLanguage.FRENCH;target=TranslateLanguage.ENGLISH;}
        else {callback.done(false,"Langues acceptées : en-fr, fr-en, original.");return;}
        final String page=web.getUrl();
        web.evaluateJavascript(PREPARE,raw->{
            JSONArray texts;
            try {
                String serialized=new JSONArray("["+raw+"]").getString(0);
                JSONObject payload=new JSONObject(serialized);
                if(payload.has("error")){callback.done(false,payload.optString("error"));return;}
                texts=payload.getJSONArray("texts");
            } catch(Exception error){callback.done(false,"Impossible de lire le texte de la page.");return;}
            if(texts.length()==0){callback.done(false,"Aucun texte traduisible sur la page.");return;}
            TranslatorOptions options=new TranslatorOptions.Builder()
                .setSourceLanguage(source).setTargetLanguage(target).build();
            final Translator translator=Translation.getClient(options);
            translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused->{
                    List<Task<String>> jobs=new ArrayList<>();
                    for(int i=0;i<texts.length();i++)jobs.add(translator.translate(texts.optString(i)));
                    Tasks.whenAllSuccess(jobs).addOnSuccessListener(results->{
                        translator.close();
                        if(!BrowserWebState.alive(web)||page==null||!page.equals(web.getUrl())){
                            callback.done(false,"L'onglet a changé pendant la traduction.");return;
                        }
                        JSONArray translated=new JSONArray();
                        for(Object item:results)translated.put(String.valueOf(item));
                        String script="(function(){var s=window.__chkTranslation;if(!s)return false;"
                            +"var t="+translated.toString()+";s.nodes.forEach(function(n,i){"
                            +"if(n&&n.isConnected)n.nodeValue=t[i]});return true;})()";
                        web.evaluateJavascript(script,result->callback.done("true".equals(result),
                            "true".equals(result)?"Page traduite ("+direction+").":"La page a changé."));
                    }).addOnFailureListener(error->{
                        translator.close();callback.done(false,"Traduction impossible : "+error.getMessage());
                    });
                }).addOnFailureListener(error->{
                    translator.close();callback.done(false,
                        "Modèle de langue indisponible. Connecte Internet pour le premier téléchargement.");
                });
        });
    }
}
