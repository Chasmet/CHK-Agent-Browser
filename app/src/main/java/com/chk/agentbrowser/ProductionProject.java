package com.chk.agentbrowser;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** One durable workspace for an agent production; all manifests use atomic writes. */
final class ProductionProject {
    private ProductionProject(){}
    static String id(JSONObject args)throws Exception {
        String id=args.getString("request_id");
        if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("request_id requis, 1 à 80 lettres, chiffres, tirets ou soulignés");
        return id;
    }
    static String root(JSONObject args)throws Exception {
        String path=args.getString("project_path");
        if(!path.matches("Projects/[A-Za-z0-9_-]{1,100}"))throw new IOException("project_path fourni par project_create requis");
        return path;
    }
    static synchronized JSONObject create(Context app,JSONObject args)throws Exception {
        String id=id(args),name=args.getString("name").trim();
        if(name.isEmpty()||name.length()>100)throw new IOException("Nom de projet requis, 100 caractères maximum");
        WorkspaceStore store=new WorkspaceStore(app);
        String path="Projects/"+id;
        File manifest=store.file(path+"/production.json");
        if(manifest.isFile()){
            JSONObject previous=read(app,path);
            if(!name.equals(previous.getString("name")))throw new IOException("Cet identifiant appartient à un autre projet");
            return previous;
        }
        mkdir(store,"Projects");mkdir(store,path);
        for(String folder:new String[]{"Captures","Documents","Medias","Narrations","Montage","Exports","Publications"})mkdir(store,path+"/"+folder);
        JSONObject project=new JSONObject().put("schema",1).put("id",UUID.randomUUID().toString())
            .put("project_path",path).put("name",name).put("created_at",System.currentTimeMillis())
            .put("assets",new JSONArray()).put("revision",1);
        write(app,path,project);return project;
    }
    static void mkdir(WorkspaceStore store,String path)throws Exception {
        File folder=store.file(path);
        if(!folder.isDirectory())store.mkdir(path);
    }
    static synchronized JSONObject read(Context app,String path)throws Exception {
        root(new JSONObject().put("project_path",path));
        JSONObject project=AgentFiles.read(new WorkspaceStore(app).file(path+"/production.json"));
        if(!path.equals(project.optString("project_path")))throw new IOException("Projet incohérent");
        return project;
    }
    static void write(Context app,String path,JSONObject project)throws Exception {
        project.put("updated_at",System.currentTimeMillis());
        StudioAutomation.write(new WorkspaceStore(app).file(path+"/production.json"),project);
    }
    static synchronized void update(Context app,String path,String key,Object value)throws Exception {
        JSONObject project=read(app,path);
        project.put(key,value).put("revision",project.optInt("revision")+1);write(app,path,project);
    }
    static synchronized JSONObject asset(Context app,String projectPath,String path,String kind,JSONObject details)throws Exception {
        if(!path.startsWith(projectPath+"/"))throw new IOException("Le média doit appartenir au projet");
        WorkspaceStore store=new WorkspaceStore(app);File file=store.file(path);
        if(!file.isFile()||file.length()==0)throw new IOException("Média absent ou vide");
        JSONObject project=read(app,projectPath);JSONArray assets=project.getJSONArray("assets");
        for(int i=0;i<assets.length();i++)if(path.equals(assets.getJSONObject(i).optString("path")))return assets.getJSONObject(i);
        JSONObject entry=new JSONObject().put("path",path).put("kind",kind).put("size",file.length())
            .put("sha256",WorkspaceTransfer.hash(file)).put("created_at",System.currentTimeMillis());
        if(details!=null)entry.put("details",details);
        assets.put(entry);project.put("revision",project.optInt("revision")+1);write(app,projectPath,project);return entry;
    }
    static JSONObject status(Context app,JSONObject args)throws Exception {
        String path=root(args);JSONObject project=read(app,path);
        JSONArray assets=project.getJSONArray("assets");int missing=0;
        WorkspaceStore store=new WorkspaceStore(app);
        for(int i=0;i<assets.length();i++)if(!store.file(assets.getJSONObject(i).getString("path")).isFile())missing++;
        return project.put("missing_assets",missing).put("installed_version",
            app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName);
    }
}
