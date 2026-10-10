package com.chk.agentbrowser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Preserve readable names and extensions while respecting Android filesystem limits. */
public final class FileNames {
    private FileNames() {}
    public static String clean(String value) {
        String name=value==null?"":value.replaceAll("[\\p{Cntrl}/\\\\]","_").trim();
        while(name.startsWith("."))name=name.substring(1);
        if(name.isEmpty())name="Fichier";
        int dot=name.lastIndexOf('.');
        String extension=dot>0&&name.length()-dot<=20?name.substring(dot):"";
        String stem=extension.isEmpty()?name:name.substring(0,dot);
        return shorten(stem,100-extension.length(),180-extension.getBytes(StandardCharsets.UTF_8).length)+extension;
    }
    private static String shorten(String text,int characters,int bytes) {
        int end=text.length();
        while(end>0&&(end>characters||text.substring(0,end).getBytes(StandardCharsets.UTF_8).length>bytes))
            end=text.offsetByCodePoints(end,-1);
        return end==0?"Fichier":text.substring(0,end);
    }
    public static String available(File directory,String requested,Set<String> reserved) {
        String base=clean(requested),candidate=base;
        int dot=base.lastIndexOf('.');
        String stem=dot>0?base.substring(0,dot):base,extension=dot>0?base.substring(dot):"";
        for(int index=1;new File(directory,candidate).exists()||(reserved!=null&&reserved.contains(candidate));index++)
            candidate=stem+" ("+index+")"+extension;
        return candidate;
    }
}
