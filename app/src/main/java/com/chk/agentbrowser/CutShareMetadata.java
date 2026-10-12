package com.chk.agentbrowser;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;

/**
 * Share metadata stored beside a CutVideo folder.
 * It is owner-editable UTF-8 JSON, never an instruction to publish or send a video.
 */
final class CutShareMetadata {
    private static final String NAME="metadonnees.json";
    private CutShareMetadata(){}

    static JSONObject forVideo(WorkspaceStore workspace,String videoPath,String platform) throws Exception {
        if(workspace==null||videoPath==null||!videoPath.startsWith("CutVideo/"))
            throw new IllegalArgumentException("Chemin Cut Video invalide");
        if(!("youtube".equals(platform)||"tiktok".equals(platform)||
            "instagram".equals(platform)||"x".equals(platform)))
            throw new IllegalArgumentException("Réseau inconnu");
        File video=workspace.file(videoPath);
        if(!video.isFile()||!video.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"))
            throw new IOException("Fichier MP4 introuvable");
        String parent=videoPath.substring(0,videoPath.lastIndexOf('/'));
        File manifest=workspace.file(parent+"/"+NAME);
        if(!manifest.isFile())throw new IOException("Aucune fiche de métadonnées pour ce dossier");
        if(manifest.length()>65536)throw new IOException("Fiche trop volumineuse");
        JSONObject payload=new JSONObject(workspace.readText(parent+"/"+NAME,0).getString("text"));
        JSONArray items=payload.optJSONArray("items");
        if(items==null)throw new IOException("Fiches de métadonnées mal formées");
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);
            if(item==null||!video.getName().equals(item.optString("file")))continue;
            JSONObject perNetwork=item.optJSONObject("platforms");
            JSONObject result=perNetwork==null?null:perNetwork.optJSONObject(platform);
            if(result==null)throw new IOException("Aucune fiche pour "+platform);
            String title=result.optString("title","").trim();
            String description=result.optString("description","").trim();
            String hashtags=result.optString("hashtags","").trim();
            String text=format(title,description,hashtags);
            if(title.isEmpty()||title.length()>70||description.length()>90||
                    text.length()>100||(!hashtags.isEmpty()&&hashtags.split("\\s+").length>5))
                throw new IOException("Fiche invalide ou dépassant les limites");
            return new JSONObject()
                .put("title",title).put("description",description)
                .put("hashtags",hashtags).put("text",text);
        }
        throw new IOException("Aucune métadonnée liée au MP4 "+video.getName());
    }

    static String format(String title,String description,String hashtags){
        StringBuilder result=new StringBuilder();
        for(String value:new String[]{title,description,hashtags}){
            if(value==null||value.trim().isEmpty())continue;
            if(result.length()>0)result.append("\n");
            result.append(value.trim());
        }
        return result.toString();
    }
}
