package com.chk.agentbrowser;

import android.content.Context;
import android.webkit.WebView;
import org.json.JSONObject;

/** A compatibility carrier keeps the installed browser_type tool usable without re-pairing. */
final class KeyboardCommands {
    static final String CARRIER="chk://keyboard";
    private KeyboardCommands(){}
    static boolean handles(String action,JSONObject args){
        return "keyboard".equals(action)||("type".equals(action)&&args!=null&&CARRIER.equals(args.optString("selector")));
    }
    static JSONObject parameters(String action,JSONObject args)throws Exception{
        JSONObject p="type".equals(action)?new JSONObject(args.optString("text","")):(args==null?new JSONObject():args);
        validate(p);return p;
    }
    static void validate(JSONObject p)throws Exception{
        if(p.toString().getBytes("UTF-8").length>30000)throw new Exception("Commande trop volumineuse : utilise plusieurs lots.");
        for(String key:new String[]{"selector","expected_context","expected_host","text"}){
            if(p.has(key)&&!(p.get(key) instanceof String))throw new Exception("Paramètre clavier invalide : "+key);
            int max=key.equals("text")?8000:key.equals("expected_context")?160:key.equals("expected_host")?250:350;
            if(p.optString(key).length()>max)throw new Exception("Paramètre clavier trop long : "+key);
        }
        if(p.optString("operation","inspect").equals("fill")){
            org.json.JSONArray fields=p.optJSONArray("fields");if(fields==null||fields.length()<1||fields.length()>25)throw new Exception("Un lot doit contenir entre 1 et 25 champs.");
            java.util.HashSet<String> seen=new java.util.HashSet<>();
            for(int i=0;i<fields.length();i++){
                JSONObject f=fields.optJSONObject(i);if(f==null)throw new Exception("Chaque champ doit être un objet.");
                String sel=f.optString("selector"),op=f.optString("operation","replace");
                if(sel.isEmpty()||!seen.add(sel))throw new Exception("Chaque champ du lot doit avoir un sélecteur unique.");
                if(!op.equals("replace")&&!op.equals("insert")&&!op.equals("clear")&&!op.equals("check")&&!op.equals("select_option"))throw new Exception("Un lot remplit des champs sans cliquer ni envoyer.");
                validate(f);
            }
        }
    }
    static boolean nativeTarget(JSONObject command){
        String action=command.optString("action");JSONObject args=command.optJSONObject("args");
        if(!handles(action,args))return false;
        try{String target=parameters(action,args).optString("target","web");
            return "native".equals(target)||("auto".equals(target)&&NativeKeyboard.prefersNative());
        }catch(Exception ignored){return false;}
    }
    static void run(Context context,WebView web,String action,JSONObject args,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;
        try{
            JSONObject p=parameters(action,args);
            String target=p.optString("target","web");
            if(!target.equals("web")&&!target.equals("native")&&!target.equals("auto"))throw new Exception("Cible attendue : web, native ou auto.");
            if(target.equals("native")||(target.equals("auto")&&NativeKeyboard.prefersNative()))NativeKeyboard.execute(p,cb);
            else if(web!=null)WebKeyboard.execute(context,web,p,cb);
            else cb.finish(false,"Aucun onglet web disponible.");
        }catch(Exception e){cb.finish(false,e.getMessage()==null?"Commande clavier invalide.":e.getMessage());}
    }
}
