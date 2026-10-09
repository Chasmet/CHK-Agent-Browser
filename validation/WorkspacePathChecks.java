import com.chk.agentbrowser.WorkspacePaths;
import java.io.File;
public final class WorkspacePathChecks{
    public static void main(String[] args)throws Exception{
        File root=new File(args[0]);root.mkdirs();
        if(!WorkspacePaths.resolve(root,"Projets/Idée.md",false).getPath().endsWith("Idée.md"))throw new AssertionError("Unicode path");
        for(String path:new String[]{"../secret","/sdcard/x","a/../../b","a/.hidden","a//b","a\\b","", "a/"}){
            try{WorkspacePaths.resolve(root,path,false);throw new AssertionError("Accepted "+path);}catch(java.io.IOException expected){}
        }
        if(!WorkspacePaths.resolve(root,"",true).equals(root.getCanonicalFile()))throw new AssertionError("Root listing");
        try{WorkspacePaths.child("docs","../secret");throw new AssertionError("Child escape");}catch(java.io.IOException expected){}
        System.out.println("PASS: workspace containment, hidden paths and Unicode names");
    }
}
