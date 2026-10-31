package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.UUID;

/** Real SQLite regression tests: notes, folders, revisions and recoverable trash. */
@RunWith(AndroidJUnit4.class)
public class NotesFoldersIntegrationTest {
    private WorkspaceStore store() throws Exception {
        return new WorkspaceStore(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @Test public void oldNotesRemainReadableAndStartUnfiled() throws Exception {
        WorkspaceStore s=store();
        String marker="old-note-"+UUID.randomUUID();
        JSONObject note=s.saveNote("",marker,"Ancien texte intact\nDeuxième ligne","",true,0);
        assertEquals("",s.note(note.getString("id")).getString("folder_id"));
        assertEquals("Ancien texte intact\nDeuxième ligne",s.note(note.getString("id")).getString("body"));
        assertEquals(1,s.notes(marker,false,0).length());
        assertEquals(1,s.notesFiltered(marker,false,"",false,0).length());
        assertTrue(s.notes(marker,false,0).getJSONObject(0).getString("preview").contains("Ancien"));
    }

    @Test public void movingAndDeletingFolderNeverDeletesNote() throws Exception {
        WorkspaceStore s=store();
        String marker="folder-note-"+UUID.randomUUID();
        JSONObject note=s.saveNote("",marker,"Contenu sauvegardé","https://example.org",false,0);
        JSONObject folder=s.saveNoteFolder("","Musique "+marker,"🎤","#9B6DE3");
        JSONObject moved=s.moveNote(note.getString("id"),folder.getString("id"),note.getInt("revision"));
        assertEquals(folder.getString("id"),moved.getString("folder_id"));
        assertEquals(1,s.notesFiltered(marker,false,folder.getString("id"),false,0).length());
        assertEquals(0,s.notesFiltered(marker,false,"",false,0).length());
        s.deleteNoteFolder(folder.getString("id"));
        JSONObject preserved=s.note(note.getString("id"));
        assertEquals("",preserved.getString("folder_id"));
        assertEquals("Contenu sauvegardé",preserved.getString("body"));
        assertEquals("https://example.org",preserved.getString("source_url"));
        assertEquals(moved.getInt("revision")+1,preserved.getInt("revision"));
        assertEquals(1,s.notesFiltered(marker,false,"",false,0).length());
    }

    @Test public void concurrentMoveRejectedAndTrashStillRestorable() throws Exception {
        WorkspaceStore s=store();
        String marker="guard-note-"+UUID.randomUUID();
        JSONObject note=s.saveNote("",marker,"Texte v1","",false,0);
        JSONObject folder=s.saveNoteFolder("","Dossier "+marker,"📂","#3B82F6");
        JSONObject moved=s.moveNote(note.getString("id"),folder.getString("id"),note.getInt("revision"));
        try {
            s.moveNote(note.getString("id"),"",note.getInt("revision"));
            fail("Old revision must be rejected");
        }catch(IOException expected){}
        assertEquals("Texte v1",s.note(note.getString("id")).getString("body"));
        s.deleteNote(note.getString("id"),true,moved.getInt("revision"));
        assertEquals(0,s.notes(marker,false,0).length());
        s.deleteNote(note.getString("id"),false,moved.getInt("revision")+1);
        assertEquals(1,s.notes(marker,false,0).length());
        assertEquals(folder.getString("id"),s.note(note.getString("id")).getString("folder_id"));
    }
    @Test public void nestedFoldersCanBeCreatedAndNeverFormCycles() throws Exception {
        WorkspaceStore s=store();
        String prefix="Nested-"+UUID.randomUUID();
        JSONObject root=s.saveNoteFolder("",prefix,"🗂️","#C97821","");
        JSONObject child=s.saveNoteFolder("",prefix+"-Clips","🎬","#AF5734",root.getString("id"));
        JSONObject grandChild=s.saveNoteFolder("",prefix+"-Shorts","📹","#B54239",child.getString("id"));
        assertEquals("",root.getString("parent_id"));
        assertEquals(root.getString("id"),child.getString("parent_id"));
        assertEquals(child.getString("id"),grandChild.getString("parent_id"));
        try {
            s.saveNoteFolder(root.getString("id"),prefix,"🗂️","#C97821",grandChild.getString("id"));
            fail("A folder cannot be moved under a descendant");
        }catch(IOException expected){}
        try {
            s.saveNoteFolder(child.getString("id"),prefix+"-Clips","🎬","#AF5734",child.getString("id"));
            fail("A folder cannot be its own parent");
        }catch(IOException expected){}
        JSONArray all=s.noteFolders();
        assertTrue(NotesFolderDialogs.path(all,grandChild.getString("id")).contains("Shorts"));
        JSONObject note=s.saveNote("",prefix+"-note","La scène est conservée","",true,0,grandChild.getString("id"));
        assertEquals(grandChild.getString("id"),note.getString("folder_id"));
        s.deleteNoteFolder(child.getString("id"));
        all=s.noteFolders();
        JSONObject reparented=null;
        for(int j=0;j<all.length();j++)
            if(grandChild.getString("id").equals(all.getJSONObject(j).getString("id")))
                reparented=all.getJSONObject(j);
        assertNotNull(reparented);
        assertEquals(root.getString("id"),reparented.getString("parent_id"));
        assertEquals("La scène est conservée",s.note(note.getString("id")).getString("body"));
        s.deleteNoteFolder(grandChild.getString("id"));
        JSONObject relocated=s.note(note.getString("id"));
        assertEquals(root.getString("id"),relocated.getString("folder_id"));
        assertTrue(relocated.getInt("revision")>note.getInt("revision"));
        s.deleteNoteFolder(root.getString("id"));
        assertEquals("",s.note(note.getString("id")).getString("folder_id"));
    }

    @Test public void upgradingMcpListDoesNotChangeOriginalNoteContract() throws Exception {
        WorkspaceStore s=store();
        String marker="MCP-backcompat-"+UUID.randomUUID();
        JSONObject original=s.saveNote("",marker,"Ancien corps","",false,0);
        JSONObject directory=s.saveNoteFolder("",marker+" dir","📁","#C97821","");
        JSONObject moved=s.moveNote(original.getString("id"),directory.getString("id"),original.getInt("revision"));
        assertEquals(1,s.notes(marker,false,0).length());
        assertEquals(1,s.notesFiltered(marker,false,directory.getString("id"),false,0).length());
        JSONObject revised=s.saveNote(original.getString("id"),marker,"Corps modifié","",false,moved.getInt("revision"));
        assertEquals(directory.getString("id"),revised.getString("folder_id"));
    }

}
