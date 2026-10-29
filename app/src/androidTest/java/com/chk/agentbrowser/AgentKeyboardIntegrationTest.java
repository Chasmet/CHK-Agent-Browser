package com.chk.agentbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Android view/input connections and real WebView DOM; no owner account or document is used. */
@RunWith(AndroidJUnit4.class)
public final class AgentKeyboardIntegrationTest {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private WebView web;
    private SharedPreferences agentPrefs;
    private boolean enabled,autonomous;
    private final ArrayList<AlertDialog> dialogs=new ArrayList<>();
    private interface Work{void run()throws Exception;}
    private static final class Reply{boolean ok;String text;JSONObject json()throws Exception{return new JSONObject(text);}}
    @Before public void prepare()throws Exception{
        instrumentation=InstrumentationRegistry.getInstrumentation();context=instrumentation.getTargetContext();
        context.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();
        agentPrefs=context.getSharedPreferences("browser_mcp_v1",0);enabled=agentPrefs.getBoolean("enabled",false);autonomous=agentPrefs.getBoolean("autonomous_mode",false);
        agentPrefs.edit().putBoolean("enabled",false).commit();
        activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Field current=MainActivity.class.getDeclaredField("current");current.setAccessible(true);web=(WebView)current.get(activity);
    }
    @After public void cleanup(){
        instrumentation.runOnMainSync(()->{for(AlertDialog d:dialogs)if(d.isShowing())d.dismiss();if(activity!=null)activity.finish();});
        agentPrefs.edit().putBoolean("enabled",enabled).putBoolean("autonomous_mode",autonomous).commit();
    }
    private void main(Work work)throws Exception{
        AtomicReference<Throwable> error=new AtomicReference<>();instrumentation.runOnMainSync(()->{try{work.run();}catch(Throwable t){error.set(t);}});
        if(error.get()!=null)throw new AssertionError(error.get());
    }
    private EditText edit(LinearLayout box,String label,String initial,int type){EditText e=new EditText(activity);e.setHint(label);e.setInputType(type);e.setText(initial);box.addView(e);return e;}
    private LinearLayout form(){LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);return box;}
    private void show(LinearLayout box){AlertDialog dialog=new AgentDialog.Builder(activity).setTitle("Test de saisie CHK").setView(box).setNegativeButton("Fermer",null).create();dialogs.add(dialog);dialog.show();}
    private Reply nativeCall(JSONObject request)throws Exception{
        Reply r=new Reply();main(()->KeyboardCommands.run(context,null,"keyboard",new JSONObject(request.toString()).put("target","native"),(ok,text)->{r.ok=ok;r.text=text;}));return r;
    }
    private JSONObject nativeState()throws Exception{Reply r=nativeCall(new JSONObject().put("operation","inspect"));assertTrue(r.text,r.ok);return r.json();}
    private JSONObject control(JSONObject state,String label)throws Exception{
        JSONArray controls=state.getJSONArray("controls");for(int i=0;i<controls.length();i++){JSONObject row=controls.getJSONObject(i);if(row.optString("label").equals(label))return row;}
        throw new AssertionError("Control missing: "+label+" in "+state);
    }
    private JSONObject command(String operation,JSONObject state,JSONObject field)throws Exception{return new JSONObject().put("operation",operation).put("expected_context",state.getString("context")).put("selector",field.getString("selector"));}
    private String js(String script)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();main(()->web.evaluateJavascript(script,value->{result.set(value);done.countDown();}));assertTrue("WebView response",done.await(8,TimeUnit.SECONDS));return result.get();
    }
    private void load(String html)throws Exception{
        main(()->{Method show=MainActivity.class.getDeclaredMethod("showSpace",String.class);show.setAccessible(true);show.invoke(activity,"browser");
            Field homeField=MainActivity.class.getDeclaredField("home");homeField.setAccessible(true);((BrowserHome)homeField.get(activity)).revealWeb();
        });
        WebFixture.load(instrumentation,web,"https://keyboard-test.example/",html,"!!document.getElementById('ready')");
    }
    private Reply webCall(JSONObject request)throws Exception{
        Reply r=new Reply();CountDownLatch done=new CountDownLatch(1);main(()->WebKeyboard.execute(context,web,request,(ok,text)->{r.ok=ok;r.text=text;done.countDown();}));assertTrue("Keyboard callback",done.await(8,TimeUnit.SECONDS));return r;
    }
    private JSONObject webState()throws Exception{Reply r=webCall(new JSONObject().put("operation","inspect"));assertTrue(r.text,r.ok);return r.json();}

    @Test public void nativeDialogUsesRealInputAndPositiveAction()throws Exception{
        AtomicReference<EditText> name=new AtomicReference<>();AtomicReference<String> saved=new AtomicReference<>();
        main(()->{LinearLayout box=form();name.set(edit(box,"Nom du dossier","",InputType.TYPE_CLASS_TEXT));AlertDialog d=new AgentDialog.Builder(activity).setTitle("Créer un dossier").setView(box).setPositiveButton("Créer",(dialog,w)->saved.set(name.get().getText().toString())).create();dialogs.add(d);d.show();});
        JSONObject state=nativeState();Reply typed=nativeCall(command("replace",state,control(state,"Nom du dossier")).put("text","Dossier été 📝"));assertTrue(typed.text,typed.ok);
        Reply click=nativeCall(command("click",nativeState(),control(nativeState(),"Créer")));assertTrue(click.text,click.ok);instrumentation.waitForIdleSync();assertEquals("Dossier été 📝",saved.get());
    }
    @Test public void nativeUnicodeSelectionUndoRedoAndPausedScreen()throws Exception{
        AtomicReference<EditText> field=new AtomicReference<>();main(()->{LinearLayout box=form();field.set(edit(box,"Texte","A📝B",InputType.TYPE_CLASS_TEXT));show(box);NativeKeyboard.paused(activity);});
        JSONObject state=nativeState(),f=control(state,"Texte");assertFalse(state.getBoolean("foreground"));
        assertTrue(nativeCall(command("select",state,f).put("start",1).put("end",2)).ok);
        assertTrue(nativeCall(command("insert",state,f).put("text","💡")).ok);main(()->assertEquals("A💡B",field.get().getText().toString()));
        assertTrue(nativeCall(command("key",state,f).put("key","UNDO")).ok);main(()->assertEquals("A📝B",field.get().getText().toString()));
        assertTrue(nativeCall(command("key",state,f).put("key","REDO")).ok);assertTrue(nativeCall(command("key",state,f).put("key","BACKSPACE")).ok);main(()->assertEquals("AB",field.get().getText().toString()));
    }
    @Test public void nativeNumbersNormalizeAndFiltersRefuseWithoutChangingValue()throws Exception{
        AtomicReference<EditText> number=new AtomicReference<>();main(()->{LinearLayout box=form();number.set(edit(box,"Montant","3",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED));number.get().setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});show(box);});
        JSONObject state=nativeState(),f=control(state,"Montant");assertTrue(nativeCall(command("replace",state,f).put("text","12,5")).ok);
        assertFalse(nativeCall(command("replace",state,f).put("text","abc")).ok);assertFalse(nativeCall(command("replace",state,f).put("text","123456")).ok);main(()->assertEquals("12.5",number.get().getText().toString()));
    }
    @Test public void nativeBatchContinuesAfterDisabledFieldAndCanFillChoices()throws Exception{
        AtomicReference<EditText> first=new AtomicReference<>(),last=new AtomicReference<>();AtomicReference<Spinner> choice=new AtomicReference<>(),numberChoice=new AtomicReference<>();AtomicReference<CheckBox> check=new AtomicReference<>();
        main(()->{LinearLayout box=form();first.set(edit(box,"Premier","",InputType.TYPE_CLASS_TEXT));EditText blocked=edit(box,"Bloqué","inchangé",InputType.TYPE_CLASS_TEXT);blocked.setEnabled(false);last.set(edit(box,"Dernier","",InputType.TYPE_CLASS_TEXT));
            Spinner spinner=new Spinner(activity);spinner.setContentDescription("Réseau");spinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,new String[]{"YouTube","X"}));box.addView(spinner);choice.set(spinner);
            Spinner numbers=new Spinner(activity);numbers.setContentDescription("Numéro de liste");numbers.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,new String[]{"1","2"}));box.addView(numbers);numberChoice.set(numbers);
            CheckBox c=new CheckBox(activity);c.setText("Épingler");box.addView(c);check.set(c);show(box);});
        JSONObject state=nativeState();JSONArray fields=new JSONArray();for(String label:new String[]{"Premier","Bloqué","Dernier"})fields.put(new JSONObject().put("selector",control(state,label).getString("selector")).put("text",label));
        fields.put(new JSONObject().put("selector",control(state,"Réseau").getString("selector")).put("operation","select_option").put("text","X"));fields.put(new JSONObject().put("selector",control(state,"Épingler").getString("selector")).put("operation","check").put("checked",true));
        fields.put(new JSONObject().put("selector",control(state,"Numéro de liste").getString("selector")).put("operation","select_option").put("text","1"));
        Reply result=nativeCall(new JSONObject().put("operation","fill").put("fields",fields).put("expected_context",state.getString("context")));assertTrue(result.text,result.ok);assertEquals(5,result.json().getInt("succeeded"));assertEquals(1,result.json().getInt("failed"));
        main(()->{assertEquals("Premier",first.get().getText().toString());assertEquals("Dernier",last.get().getText().toString());assertEquals(1,choice.get().getSelectedItemPosition());assertEquals(1,numberChoice.get().getSelectedItemPosition());assertEquals("2",numberChoice.get().getSelectedItem());assertTrue(check.get().isChecked());});
    }
    @Test public void nativeStaleAndProtectedFieldsNeverReceiveText()throws Exception{
        main(()->{LinearLayout box=form();edit(box,"Ordinaire","ancien",InputType.TYPE_CLASS_TEXT);edit(box,"Secret","do-not-expose",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);show(box);});
        JSONObject old=nativeState();assertFalse(old.toString().contains("do-not-expose"));assertFalse(old.toString().contains("Secret"));JSONObject field=control(old,"Ordinaire");
        AtomicReference<EditText> replacement=new AtomicReference<>();main(()->{dialogs.get(0).dismiss();LinearLayout box=form();replacement.set(edit(box,"Ordinaire","nouveau",InputType.TYPE_CLASS_TEXT));show(box);});
        Reply stale=nativeCall(command("replace",old,field).put("text","erreur"));assertFalse(stale.ok);main(()->assertEquals("nouveau",replacement.get().getText().toString()));
    }
    @Test public void nativeListChoiceOpensTheNextDialog()throws Exception{
        main(()->{AlertDialog d=new AgentDialog.Builder(activity).setTitle("Créer").setItems(new String[]{"Nouveau dossier","Document texte"},(dialog,index)->{LinearLayout box=form();edit(box,"Nom du nouveau dossier","",InputType.TYPE_CLASS_TEXT);show(box);}).create();dialogs.add(d);d.show();});
        instrumentation.waitForIdleSync();JSONObject state=nativeState();JSONObject item=control(state,"Nouveau dossier");assertEquals("choice",item.getString("kind"));assertTrue(nativeCall(command("click",state,item)).ok);instrumentation.waitForIdleSync();
        assertNotEquals(state.getString("context"),nativeState().getString("context"));assertNotNull(control(nativeState(),"Nom du nouveau dossier"));
    }
    @Test public void existingCarrierKeepsTheFilesScreenAndCancellationDoesNothing()throws Exception{
        main(()->{Method showSpace=MainActivity.class.getDeclaredMethod("showSpace",String.class);showSpace.setAccessible(true);showSpace.invoke(activity,"files");LinearLayout box=form();edit(box,"Dossier","avant",InputType.TYPE_CLASS_TEXT);show(box);});
        JSONObject state=nativeState(),field=control(state,"Dossier");JSONObject payload=command("replace",state,field).put("target","native").put("text","après");AtomicBoolean success=new AtomicBoolean();agentPrefs.edit().putBoolean("enabled",true).commit();
        main(()->{Method run=MainActivity.class.getDeclaredMethod("runAgentCommand",String.class,JSONObject.class,AgentClient.ResultCallback.class);run.setAccessible(true);run.invoke(activity,"type",new JSONObject().put("selector",KeyboardCommands.CARRIER).put("text",payload.toString()),(AgentClient.ResultCallback)(ok,text)->success.set(ok));});
        assertTrue(success.get());Field space=MainActivity.class.getDeclaredField("activeSpace");space.setAccessible(true);assertEquals("files",space.get(activity));
        main(()->NativeKeyboard.execute(payload.put("text","annulé"),new AgentClient.ResultCallback(){public boolean isActive(){return false;}public void finish(boolean ok,String text){fail("Cancelled keyboard must not reply twice");}}));
        assertEquals("après",control(nativeState(),"Dossier").getString("value"));
    }
    @Test public void nativeEnterUsesTheDeclaredEditorActionOnce()throws Exception{
        AtomicInteger hits=new AtomicInteger();main(()->{LinearLayout box=form();EditText field=edit(box,"Recherche","",InputType.TYPE_CLASS_TEXT);field.setSingleLine(true);field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);field.setOnEditorActionListener((v,id,event)->{hits.incrementAndGet();return true;});show(box);});
        JSONObject state=nativeState();assertTrue(nativeCall(command("key",state,control(state,"Recherche")).put("key","ENTER")).ok);assertEquals(1,hits.get());
    }
    @Test public void nativeNoteFieldsKeepTheActualAutosave()throws Exception{
        String marker="Keyboard-note-"+System.nanoTime();
        NoteEditorActivity editor=(NoteEditorActivity)instrumentation.startActivitySync(new Intent(context,NoteEditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        WorkspaceStore store=new WorkspaceStore(context);String id=null;
        try{
            JSONObject state=nativeState();JSONArray fields=new JSONArray()
                .put(new JSONObject().put("selector",control(state,"Titre de la note").getString("selector")).put("text",marker))
                .put(new JSONObject().put("selector",control(state,"Écris une idée, une liste ou ton prochain projet…").getString("selector")).put("text","Dossier rempli 📝\nMontant : 12,5"));
            Reply filled=nativeCall(new JSONObject().put("operation","fill").put("fields",fields).put("expected_context",state.getString("context")));assertTrue(filled.text,filled.ok);assertEquals(2,filled.json().getInt("succeeded"));
            for(int i=0;i<40;i++){JSONArray rows=store.notes(marker,false,0);if(rows.length()>0){id=rows.getJSONObject(0).getString("id");break;}Thread.sleep(100);}
            assertNotNull("Actual note must autosave",id);assertEquals("Dossier rempli 📝\nMontant : 12,5",store.note(id).getString("body"));
        }finally{
            main(()->editor.finish());instrumentation.waitForIdleSync();
            if(id!=null){JSONObject saved=store.note(id);store.deleteNote(id,true,saved.getInt("revision"));}
        }
    }
    @Test public void webBatchUsesRealEventsAndReportsDeferredPageRejection()throws Exception{
        load("<html><body id='ready'><input id='name' aria-label='Nom'><input id='amount' aria-label='Montant' type='number'><input id='masked' aria-label='Refus' value='ancien' oninput='setTimeout(()=>this.value=\"ancien\",20)'><input id='readonly' aria-label='Lecture seule' readonly value='intact'><input type='password' aria-label='Secret' value='do-not-expose'><script>window.inputs=0;document.getElementById('name').addEventListener('input',()=>window.inputs++);</script></body></html>");
        JSONObject state=webState();assertFalse(state.toString().contains("do-not-expose"));JSONArray fields=new JSONArray().put(new JSONObject().put("selector",control(state,"Nom").getString("selector")).put("text","Idée 📝"))
            .put(new JSONObject().put("selector",control(state,"Montant").getString("selector")).put("text","12,5"))
            .put(new JSONObject().put("selector",control(state,"Refus").getString("selector")).put("text","remplacé"))
            .put(new JSONObject().put("selector","#readonly").put("text","interdit"));
        Reply result=webCall(new JSONObject().put("operation","fill").put("expected_context",state.getString("context")).put("fields",fields));assertFalse(result.text,result.ok);assertFalse(result.json().getBoolean("ok"));assertEquals(2,result.json().getInt("succeeded"));assertEquals(2,result.json().getInt("failed"));
        assertEquals("\"Idée 📝\"",js("document.getElementById('name').value"));assertEquals("\"12.5\"",js("document.getElementById('amount').value"));assertEquals("1",js("window.inputs"));assertEquals("\"intact\"",js("document.getElementById('readonly').value"));
    }
    @Test public void webCursorUnicodeUndoAndNextField()throws Exception{
        load("<body id='ready'><input id='text' aria-label='Texte' value='A📝B'><textarea aria-label='Suite'></textarea></body>");JSONObject state=webState(),field=control(state,"Texte");
        assertTrue(webCall(command("select",state,field).put("start",1).put("end",2)).ok);assertTrue(webCall(command("insert",state,field).put("text","💡")).ok);assertEquals("\"A💡B\"",js("document.getElementById('text').value"));
        assertTrue(webCall(command("key",state,field).put("key","UNDO")).ok);assertEquals("\"A📝B\"",js("document.getElementById('text').value"));assertTrue(webCall(command("key",state,field).put("key","REDO")).ok);
        assertTrue(webCall(command("key",state,field).put("key","BACKSPACE")).ok);assertEquals("\"AB\"",js("document.getElementById('text').value"));assertTrue(webCall(command("key",state,field).put("key","TAB")).ok);assertTrue(control(webState(),"Suite").getBoolean("focused"));
    }
    @Test public void webSameOriginFramesAndShadowRootsHaveUniqueTargets()throws Exception{
        load("<body id='ready'><div id='host'></div><iframe id='frame' srcdoc=\"<input id='shared' aria-label='Cadre'>\"></iframe><script>document.getElementById('host').attachShadow({mode:'open'}).innerHTML=\"<input id='shared' aria-label='Ombre'>\";</script></body>");
        for(int i=0;i<30&&!"true".equals(js("!!document.getElementById('frame').contentDocument.getElementById('shared')"));i++)Thread.sleep(100);
        JSONObject state=webState();String shadow=control(state,"Ombre").getString("selector"),frame=control(state,"Cadre").getString("selector");assertNotEquals(shadow,frame);
        Reply ambiguous=webCall(new JSONObject().put("operation","replace").put("expected_context",state.getString("context")).put("selector","#shared").put("text","erreur"));assertFalse(ambiguous.json().getBoolean("ok"));
        JSONArray fields=new JSONArray().put(new JSONObject().put("selector",shadow).put("text","ombre")).put(new JSONObject().put("selector",frame).put("text","cadre"));Reply filled=webCall(new JSONObject().put("operation","fill").put("expected_context",state.getString("context")).put("fields",fields));assertEquals(2,filled.json().getInt("succeeded"));
    }
    @Test public void webStaleControlsAndWrongHostDoNotReceiveText()throws Exception{
        load("<body id='ready'><input id='name' aria-label='Nom' value='ancien'></body>");JSONObject state=webState(),field=control(state,"Nom");
        Reply validHost=webCall(command("replace",state,field).put("text","valide").put("expected_host","keyboard-test.example"));assertTrue(validHost.text,validHost.ok);assertTrue(validHost.json().getBoolean("ok"));
        Reply host=webCall(command("replace",state,field).put("text","erreur").put("expected_host","another.example"));assertFalse(host.ok);assertEquals("\"valide\"",js("document.getElementById('name').value"));
        js("document.getElementById('name').outerHTML='<input id=\"name\" aria-label=\"Nom\" value=\"nouveau\">'");Reply stale=webCall(command("replace",state,field).put("text","erreur"));assertFalse(stale.json().getBoolean("ok"));assertEquals("\"nouveau\"",js("document.getElementById('name').value"));
    }
    @Test public void webRichTextInsertPreservesMarkupAndEnterIsARealKey()throws Exception{
        load("<body id='ready'><div contenteditable='true' aria-label='Riche'><b>bonjour</b></div><input id='key' aria-label='Entrée'><script>window.enters=0;document.getElementById('key').addEventListener('keydown',e=>{if(e.key==='Enter'){window.enters++;e.preventDefault();}});</script></body>");JSONObject state=webState();JSONObject rich=control(state,"Riche");
        assertTrue(webCall(command("key",state,rich).put("key","END")).ok);assertTrue(webCall(command("insert",state,rich).put("text"," ami")).ok);assertEquals("\"bonjour ami\"",js("document.querySelector('[contenteditable]').textContent"));assertEquals("true",js("!!document.querySelector('[contenteditable] b')"));
        Reply enter=webCall(command("key",state,control(state,"Entrée")).put("key","ENTER"));assertTrue(enter.text,enter.ok);assertFalse(enter.json().getBoolean("submission_confirmed"));for(int i=0;i<25&&!"1".equals(js("window.enters"));i++)Thread.sleep(100);assertEquals("1",js("window.enters"));
    }
    @Test public void richEditorReceivesOneTransactionAndClearsEntityNodes()throws Exception{
        load("<body id='ready'><div id='caption' contenteditable='true' aria-label='Légende'>ancien <span contenteditable='false'>#tag</span></div><script>window.syntheticBefore=0;window.editorInputs=0;window.editorChanges=0;var e=document.getElementById('caption');e.addEventListener('beforeinput',event=>{if(!event.isTrusted){window.syntheticBefore++;e.appendChild(document.createTextNode(event.data||''));}});e.addEventListener('input',()=>window.editorInputs++);e.addEventListener('change',()=>window.editorChanges++);</script></body>");
        JSONObject state=webState(),field=control(state,"Légende");
        Reply typed=webCall(command("replace",state,field).put("text","CHK #Brume"));assertTrue(typed.text,typed.ok);
        assertEquals("\"CHK #Brume\"",js("document.getElementById('caption').textContent"));
        assertEquals("0",js("window.syntheticBefore"));assertEquals("1",js("window.editorInputs"));assertEquals("0",js("window.editorChanges"));
        Reply cleared=webCall(command("clear",state,field));assertTrue(cleared.text,cleared.ok);
        assertEquals("\"\"",js("document.getElementById('caption').textContent"));assertEquals("0",js("document.querySelectorAll('#caption [contenteditable=false]').length"));
        assertEquals("2",js("window.editorInputs"));
    }
    @Test public void unsupportedRichCommandNeverMutatesFrameworkDom()throws Exception{
        load("<body id='ready'><div contenteditable='true' aria-label='Légende'><b>intact</b></div><script>document.execCommand=function(){return false;};</script></body>");
        JSONObject state=webState();Reply result=webCall(command("replace",state,control(state,"Légende")).put("text","nouveau"));
        assertFalse(result.text,result.ok);assertEquals("\"<b>intact</b>\"",js("document.querySelector('[contenteditable]').innerHTML"));
    }

    @Test public void dialogAfterLargeLibraryOwnsTextFieldsAndShortSelectors()throws Exception{
        load("<body id='ready'><main id='library'></main><div role='dialog' aria-modal='true' style='position:fixed;inset:0;background:white'><h1>Création active</h1><input aria-label='Titre du morceau' value='Brume'><div contenteditable='true' aria-label='Lyrics editor'>Paroles</div></div><script>document.getElementById('library').innerHTML='<button>Ancienne bibliothèque</button>'.repeat(6000);</script></body>");
        JSONObject page=new JSONObject(new JSONArray("["+js(BrowserScripts.readPage())+"]").getString(0));
        assertTrue(page.getString("text").contains("Création active"));assertFalse(page.getString("text").contains("Ancienne bibliothèque"));
        assertEquals("Titre du morceau",page.getJSONArray("elements").getJSONObject(0).getString("label"));
        assertTrue(page.getJSONArray("elements").getJSONObject(0).getString("selector").length()<100);
        JSONObject form=new JSONObject(new JSONArray("["+js(BrowserScripts.getForm())+"]").getString(0));
        JSONArray controls=form.getJSONArray("dynamic_controls");assertEquals(2,controls.length());assertFalse(form.getBoolean("dynamic_truncated"));
    }
    @Test public void hiddenDialogCannotCaptureInspectionAndHorizontalScrollIsMeasured()throws Exception{
        load("<body id='ready'><h1>Page actuelle</h1><div role='dialog' style='display:none'>ancien dialogue</div><div id='horizontal' style='width:180px;height:80px;overflow-x:auto'><div style='width:1200px;height:60px'>large</div></div></body>");
        JSONObject page=new JSONObject(new JSONArray("["+js(BrowserScripts.readPage())+"]").getString(0));assertTrue(page.getString("text").contains("Page actuelle"));
        JSONObject moved=new JSONObject(new JSONArray("["+js(BrowserScripts.scrollContainer("right",200,"#horizontal",""))+"]").getString(0));
        assertTrue(moved.toString(),moved.getBoolean("moved"));assertEquals("horizontal",moved.getString("axis"));assertEquals(200,moved.getInt("scroll_after"));
        JSONObject restored=new JSONObject(new JSONArray("["+js(BrowserScripts.scrollContainer("left",200,"#horizontal",""))+"]").getString(0));assertEquals(0,restored.getInt("scroll_after"));
    }

    @Test public void navigationDeadlineSurvivesMissingJavascriptCallback()throws Exception{
        AtomicReference<WebView> stalled=new AtomicReference<>();AtomicReference<android.webkit.ValueCallback<String>> late=new AtomicReference<>();AtomicInteger finished=new AtomicInteger();CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();
        Field current=MainActivity.class.getDeclaredField("current");current.setAccessible(true);
        try{
            main(()->{
                WebView fake=new WebView(context){@Override public String getUrl(){return "https://fixture.example/";}@Override public void evaluateJavascript(String script,android.webkit.ValueCallback<String> cb){late.set(cb);}};
                stalled.set(fake);current.set(activity,fake);BrowserWebState.remember(fake,fake.getUrl());BrowserWebState.committed(fake,fake.getUrl());
                Method verify=MainActivity.class.getDeclaredMethod("verifyNavigation",WebView.class,String.class,long.class,int.class,AgentClient.ResultCallback.class);verify.setAccessible(true);
                verify.invoke(activity,fake,fake.getUrl(),0L,1000,new AgentClient.ResultCallback(){public void finish(boolean ok,String text){finished.incrementAndGet();result.set(text);done.countDown();}});
            });
            assertTrue("Navigation deadline without JS reply",done.await(3,TimeUnit.SECONDS));assertTrue(result.get(),result.get().contains("résultat inconnu"));
            main(()->{if(late.get()!=null)late.get().onReceiveValue("\"late\"");});assertEquals(1,finished.get());
        }finally{main(()->{current.set(activity,web);if(stalled.get()!=null)stalled.get().destroy();});}
    }

}
