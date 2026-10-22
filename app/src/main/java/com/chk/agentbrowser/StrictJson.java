package com.chk.agentbrowser;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.*;

/** Android JSONObject is lenient; validation must reject JavaScript-style object syntax. */
final class StrictJson {
    static void validate(String text)throws IOException{
        try(JsonReader reader=new JsonReader(new StringReader(text))){reader.setLenient(false);value(reader,0);if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("Texte supplémentaire après le JSON");}
    }
    private static void value(JsonReader reader,int depth)throws IOException{
        if(depth>64)throw new IOException("JSON trop imbriqué");
        switch(reader.peek()){
            case BEGIN_OBJECT:reader.beginObject();while(reader.hasNext()){reader.nextName();value(reader,depth+1);}reader.endObject();break;
            case BEGIN_ARRAY:reader.beginArray();while(reader.hasNext())value(reader,depth+1);reader.endArray();break;
            case STRING:reader.nextString();break;
            case NUMBER:String number=reader.nextString();if(!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IOException("Nombre JSON invalide");break;
            case BOOLEAN:reader.nextBoolean();break;
            case NULL:reader.nextNull();break;
            default:throw new IOException("Valeur JSON invalide");
        }
    }
}
