package com.chk.agentbrowser;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Owner-authorized and rate-limited collection of publicly visible replies.
 * It does not call private APIs, evade logins, identify commentators or infer votes.
 * One collection has at most eight public publication URLs, bounded scrolling,
 * and writes anonymous, deduplicated text to the private CHK workspace.
 */
final class PublicCommentCollector {
    private static final String FOLDER="Presidentielle_2027";
    private static final String DESKTOP_UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String EXTRACT_SCRIPT="(function(){\ntry {\n const host=(location.hostname||'').toLowerCase(),path=location.pathname;\n const x=host==='x.com'||host==='www.x.com'||host==='twitter.com'||host==='www.twitter.com';\n const tik=host==='tiktok.com'||host==='www.tiktok.com'||host==='m.tiktok.com';\n if(!((x&&/\\/status\\/\\d+/.test(path))||(tik&&/\\/video\\/\\d+/.test(path))))\n   return JSON.stringify({ok:false,error:'Ouvre une publication X ou une video TikTok publique.'});\n const result=[],seen=new Set();\n function add(text){\n   text=(text||'').replace(/\\s+/g,' ').trim();\n   if(text.length<3||text.length>650||seen.has(text))return;\n   seen.add(text);result.push(text);\n }\n if(x){\n   const root=document.querySelector('[data-testid=\"primaryColumn\"]')||document;\n   Array.from(root.querySelectorAll('article[data-testid=\"tweet\"],article')).forEach(item=>{\n     const t=item.querySelector('[data-testid=\"tweetText\"]');\n     if(!t)return;\n     const a=item.querySelector('a[href*=\"/status/\"]');\n     if(a&&a.href&&a.href.split('?')[0]===location.href.split('?')[0])return;\n     add(t.innerText||t.textContent||'');\n   });\n } else {\n   const sel='[data-e2e=\"comment-item\"],[class*=\"DivCommentItemContainer\"],[class*=\"CommentItemContainer\"]';\n   Array.from(document.querySelectorAll(sel)).forEach(item=>{\n     const text=item.querySelector('[data-e2e=\"comment-level-1\"],[data-e2e=\"comment-content\"],p[class*=\"Comment\"],span[class*=\"Comment\"]');\n     if(text)add(text.innerText||text.textContent||'');\n   });\n }\n return JSON.stringify({ok:true,platform:x?'X':'TikTok',url:location.href,comments:result.slice(0,90)});\n}catch(e){return JSON.stringify({ok:false,error:'Lecture DOM impossible.'});}\n})()";
    private PublicCommentCollector(){}

    static JSONObject validate(JSONObject args)throws Exception {
        String id=args.optString("id","");
        if(!id.matches("[A-Za-z0-9_-]{4,48}"))throw new Exception("ID de collecte requis (4 a 48 caracteres).");
        JSONArray arr=args.optJSONArray("urls");
        if(arr==null||arr.length()<1||arr.length()>8)throw new Exception("Indique 1 a 8 URL de publications publiques.");
        int maxScrolls=args.optInt("max_scrolls",8);
        if(maxScrolls<1||maxScrolls>15||maxScrolls*arr.length()>90)throw new Exception("Defilements limites a 90 par collecte.");
        int maxComments=args.optInt("max_comments",500);
        if(maxComments<1||maxComments>500)throw new Exception("Maximum 500 commentaires par collecte.");
        for(int i=0;i<arr.length();i++){
            String url=arr.getString(i);
            if(url.length()>1500||!allowed(Uri.parse(url)))throw new Exception("Seules les publications publiques X/TikTok HTTPS sont acceptees.");
        }
        return new JSONObject().put("id",id).put("urls",arr).put("max_scrolls",maxScrolls).put("max_comments",maxComments);
    }
    static boolean allowed(Uri uri){
        if(!BackgroundBrowser.isPublicHttps(uri))return false;
        String h=uri.getHost().toLowerCase(Locale.ROOT),p=uri.getPath();
        boolean x=(h.equals("x.com")||h.equals("www.x.com")||h.equals("twitter.com")||h.equals("www.twitter.com"))&&p!=null&&p.matches("/[^/]+/status/[0-9]+/?");
        boolean t=(h.equals("tiktok.com")||h.equals("www.tiktok.com")||h.equals("m.tiktok.com"))&&p!=null&&p.matches("/@[^/]+/video/[0-9]+/?");
        return x||t;
    }
    private static boolean tik(String url){
        String h=Uri.parse(url).getHost();return h!=null&&(h.equals("tiktok.com")||h.equals("www.tiktok.com")||h.equals("m.tiktok.com"));
    }
    private static String decode(String raw){
        try{return new JSONArray("["+raw+"]").getString(0);}catch(Exception e){return "";}
    }
    private static String digest(String value)throws Exception{
        byte[] b=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
        StringBuilder out=new StringBuilder();for(byte v:b)out.append(String.format(Locale.ROOT,"%02x",v&255));return out.toString();
    }
    static String sanitize(String body){
        if(body==null)return "";
        String s=body.replaceAll("https?://\\S+","[lien]").replaceAll("@[A-Za-z0-9_]{2,32}","[mention]");
        return s.replaceAll("\\s+"," ").trim();
    }
    static void start(Context app,WebView web,JSONObject args,Handler handler,AgentClient.ResultCallback cb){
        try{
            JSONObject options=validate(args);
            WorkspaceStore store=new WorkspaceStore(app);
            File folder=store.file(FOLDER);
            if(!folder.exists())store.mkdir(FOLDER);
            String name="commentaires_"+options.getString("id");
            if(store.file(FOLDER+"/"+name+"_rapport.json").exists()
                ||store.file(FOLDER+"/"+name+"_001.jsonl").exists()){
                cb.finish(false,"Identifiant de collecte deja utilise : choisis un nouvel ID.");return;
            }
            new Run(web,handler,cb,store,options,name).nextUrl();
        }catch(Exception e){cb.finish(false,"Collecte non demarree : "+e.getMessage());}
    }
    private static final class Run{
        final WebView web;final Handler handler;final AgentClient.ResultCallback callback;
        final WorkspaceStore store;final JSONObject options;final String name;
        final List<String> urls=new ArrayList<>();final Set<String> dedup=new HashSet<>();
        final JSONArray sources=new JSONArray(),files=new JSONArray();
        final StringBuilder chunk=new StringBuilder();
        final int maxScrolls,maxComments;
        String originalUA;int urlIndex,step,count,part=1,scanned;
        Run(WebView w,Handler h,AgentClient.ResultCallback cb,WorkspaceStore st,JSONObject opt,String id)throws Exception{
            web=w;handler=h;callback=cb;store=st;options=opt;name=id;
            maxScrolls=options.getInt("max_scrolls");maxComments=options.getInt("max_comments");
            JSONArray a=options.getJSONArray("urls");for(int i=0;i<a.length();i++)urls.add(a.getString(i));
            originalUA=w.getSettings().getUserAgentString();
        }
        boolean active(){return callback.isActive()&&AgentClient.get(web.getContext()).isEnabled();}
        void nextUrl(){
            if(!active())return;
            if(urlIndex>=urls.size()||count>=maxComments){finish();return;}
            step=0;String url=urls.get(urlIndex);
            if(tik(url))web.getSettings().setUserAgentString(DESKTOP_UA);
            else web.getSettings().setUserAgentString(originalUA);
            web.loadUrl(url);
            handler.postDelayed(()->awaitUrl(0),2000L);
        }
        void awaitUrl(int tries){
            if(!active())return;
            String target=urls.get(urlIndex),actual=web.getUrl();
            if(actual!=null&&actual.split("\\?")[0].equals(target.split("\\?")[0])){
                web.evaluateJavascript("(function(){return document.readyState})()",raw->{
                    if(!active())return;
                    if("complete".equals(decode(raw))||tries>=8)capture();
                    else handler.postDelayed(()->awaitUrl(tries+1),700L);
                });return;
            }
            if(tries>=8){sourceResult("Page inaccessible ou redirection externe.",0);urlIndex++;nextUrl();}
            else handler.postDelayed(()->awaitUrl(tries+1),800L);
        }
        void capture(){
            if(!active())return;
            if(step>=maxScrolls||count>=maxComments){
                sourceResult("Fin du quota ou du defilement. Verifier le nombre de commentaires visibles.",scanned);
                urlIndex++;nextUrl();return;
            }
            final String expected=urls.get(urlIndex),actual=web.getUrl();
            if(actual==null||!actual.split("\\?")[0].equals(expected.split("\\?")[0])){
                sourceResult("La page a change pendant la collecte.",scanned);urlIndex++;nextUrl();return;
            }
            step++;
            web.scrollBy(0,Math.max(200,(int)(web.getHeight()*0.72f)));
            handler.postDelayed(()->web.evaluateJavascript(EXTRACT_SCRIPT,raw->{
                if(!active())return;
                try{
                    JSONObject data=new JSONObject(decode(raw));
                    if(!data.optBoolean("ok",false))throw new Exception(data.optString("error","Section des commentaires indisponible."));
                    JSONArray comments=data.optJSONArray("comments");if(comments==null)comments=new JSONArray();
                    for(int i=0;i<comments.length()&&count<maxComments;i++){
                        String clean=sanitize(comments.optString(i));
                        if(clean.length()<3||clean.length()>650)continue;
                        String fingerprint=digest(clean.toLowerCase(Locale.ROOT));
                        if(!dedup.add(fingerprint))continue;
                        JSONObject record=new JSONObject().put("source",expected).put("commentaire",clean);
                        String line=record.toString()+"\n";
                        if(chunk.length()+line.length()>16000)flush();
                        chunk.append(line);count++;scanned++;
                    }
                    handler.postDelayed(this::capture,250L);
                }catch(Exception e){sourceResult(e.getMessage(),scanned);urlIndex++;nextUrl();}
            }),950L);
        }
        void sourceResult(String message,int observed){
            try{sources.put(new JSONObject().put("url",urls.get(urlIndex)).put("scrolls",step)
               .put("total_cumule",count).put("etat",message));}catch(Exception ignored){}
            scanned=0;
        }
        void flush()throws Exception{
            if(chunk.length()==0)return;
            String path=FOLDER+"/"+name+"_"+String.format(Locale.ROOT,"%03d",part++)+".jsonl";
            store.writeText(path,chunk.toString(),false);files.put(path);chunk.setLength(0);
        }
        void finish(){
            try{
                flush();
                JSONObject summary=new JSONObject().put("ok",true).put("commentaires_distincts",count)
                    .put("pages_demandees",urls.size()).put("sources",sources).put("fichiers",files)
                    .put("limites","Commentaires accessibles dans la WebView seulement ; dedoublonnage par texte, pas par compte. Pas un sondage electoral.")
                    .put("date",System.currentTimeMillis());
                String report=FOLDER+"/"+name+"_rapport.json";
                store.writeText(report,summary.toString(2),false);
                callback.finish(true,summary.put("rapport",report).toString());
            }catch(Exception e){callback.finish(false,"Collecte partielle : "+count+" commentaires ; erreur de fichier : "+e.getMessage());}
            finally{web.getSettings().setUserAgentString(originalUA);}
        }
    }
}
