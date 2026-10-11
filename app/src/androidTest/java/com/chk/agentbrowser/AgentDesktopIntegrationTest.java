package com.chk.agentbrowser;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Real MotionEvent routing, pixels, on-device files, durable tasks and script execution. */
@RunWith(AndroidJUnit4.class)
public final class AgentDesktopIntegrationTest {
    Instrumentation instrumentation;Context context;MainActivity activity;WebView web;SharedPreferences prefs;
    boolean enabled,autonomous,preview;String folder;AlertDialog dialog;
    interface Work{void run()throws Exception;}
    static final class Reply {boolean ok;String text;JSONObject json()throws Exception{return new JSONObject(text);}}
    @Before public void setup()throws Exception{
        instrumentation=InstrumentationRegistry.getInstrumentation();context=instrumentation.getTargetContext();
        context.getSharedPreferences("update_monitor",0).edit().putBoolean("enabled",false).commit();prefs=context.getSharedPreferences("browser_mcp_v1",0);
        enabled=prefs.getBoolean("enabled",false);autonomous=prefs.getBoolean("autonomous_mode",false);preview=prefs.getBoolean("preview_allowed",false);prefs.edit().putBoolean("enabled",false).commit();
        activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Field field=MainActivity.class.getDeclaredField("current");field.setAccessible(true);web=(WebView)field.get(activity);
        folder="desktop-test-"+UUID.randomUUID().toString().substring(0,8);new WorkspaceStore(context).mkdir(folder);instrumentation.waitForIdleSync();
    }
    @After public void cleanup()throws Exception{
        prefs.edit().putBoolean("enabled",false).commit();main(()->{if(dialog!=null)dialog.dismiss();if(activity!=null)activity.finish();});
        try{new WorkspaceStore(context).trash(folder);}catch(Exception ignored){}
        prefs.edit().putBoolean("enabled",enabled).putBoolean("autonomous_mode",autonomous).putBoolean("preview_allowed",preview).commit();
    }
    void main(Work work)throws Exception{AtomicReference<Throwable> error=new AtomicReference<>();instrumentation.runOnMainSync(()->{try{work.run();}catch(Throwable t){error.set(t);}});if(error.get()!=null)throw new AssertionError(error.get());}
    Reply call(JSONObject p)throws Exception{Reply reply=new Reply();CountDownLatch done=new CountDownLatch(1);main(()->DesktopCommands.execute(context,web,"desktop",p,(ok,text)->{reply.ok=ok;reply.text=text;done.countDown();}));assertTrue("Desktop response",done.await(10,TimeUnit.SECONDS));return reply;}
    JSONObject state(String target)throws Exception{Reply r=call(new JSONObject().put("tool","vision").put("target",target));assertTrue(r.text,r.ok);return r.json();}
    JSONObject pointer(JSONObject state,String gesture,float x,float y)throws Exception{return new JSONObject().put("tool","pointer").put("target",state.getString("target")).put("gesture",gesture).put("x",x).put("y",y).put("expected_context",state.getString("context"));}
    void measured()throws Exception{for(int i=0;i<40;i++){AtomicBoolean ready=new AtomicBoolean();main(()->ready.set(NativeKeyboard.window().getDecorView().getWidth()>0));if(ready.get()){Thread.sleep(120);return;}Thread.sleep(50);}fail("Window not measured");}
    JSONObject bounds(JSONObject state,String label)throws Exception{JSONArray controls=state.getJSONArray("controls");for(int i=0;i<controls.length();i++){JSONObject row=controls.getJSONObject(i);if(row.optString("label").equals(label)||row.optString("text").equals(label))return row.getJSONObject("bounds");}throw new AssertionError("Missing "+label+" in "+state);}
    JSONObject clickAt(JSONObject state,String label,String gesture)throws Exception{JSONObject b=bounds(state,label);return pointer(state,gesture,b.getInt("x")+b.getInt("width")/2f,b.getInt("y")+b.getInt("height")/2f);}
    String js(String script)throws Exception{AtomicReference<String> value=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);main(()->web.evaluateJavascript(script,v->{value.set(v);done.countDown();}));assertTrue(done.await(6,TimeUnit.SECONDS));return value.get();}
    JSONObject files(String operation,String id)throws Exception{return new JSONObject().put("tool","files").put("operation",operation).put("id",id);}
    void taskMode(){prefs.edit().putBoolean("enabled",true).putBoolean("autonomous_mode",true).commit();}
    JSONObject awaitTask(String id)throws Exception{for(int i=0;i<200;i++){Reply r=call(new JSONObject().put("tool","tasks").put("operation","status").put("id",id));assertTrue(r.text,r.ok);JSONObject state=r.json();if(!state.getString("state").equals("running"))return state;Thread.sleep(50);}throw new AssertionError("Task did not finish");}

    @Test public void nativeTapDoubleTapAndLongPressUseActualTouchListeners()throws Exception{
        AtomicInteger clicks=new AtomicInteger(),longs=new AtomicInteger();
        main(()->{LinearLayout box=new LinearLayout(activity);box.setOrientation(1);Button button=new Button(activity);button.setText("Cible tactile");button.setOnClickListener(v->clicks.incrementAndGet());button.setOnLongClickListener(v->{longs.incrementAndGet();return true;});box.addView(button);dialog=new AgentDialog.Builder(activity).setTitle("Souris native").setView(box).create();dialog.show();});measured();
        JSONObject before=state("native");assertTrue(call(clickAt(before,"Cible tactile","tap")).json().getBoolean("gesture_sent"));assertEquals(1,clicks.get());
        assertTrue(call(clickAt(before,"Cible tactile","double_tap")).ok);assertEquals(3,clicks.get());
        assertTrue(call(clickAt(before,"Cible tactile","long_press")).ok);assertEquals(1,longs.get());assertEquals(3,clicks.get());
        assertTrue(call(new JSONObject().put("tool","pointer").put("target","native").put("gesture","back").put("expected_context",state("native").getString("context"))).ok);main(()->assertFalse(dialog.isShowing()));
    }
    @Test public void dragDeliversDownMovesAndUpToNativeCanvas()throws Exception{
        AtomicInteger down=new AtomicInteger(),moves=new AtomicInteger(),up=new AtomicInteger();AtomicReference<Float> end=new AtomicReference<>(0f);
        main(()->{View canvas=new View(activity);canvas.setContentDescription("Surface native");canvas.setBackgroundColor(0xff205080);canvas.setOnTouchListener((v,e)->{if(e.getAction()==0)down.incrementAndGet();if(e.getAction()==2)moves.incrementAndGet();if(e.getAction()==1){up.incrementAndGet();end.set(e.getX());}return true;});LinearLayout box=new LinearLayout(activity);box.addView(canvas,new LinearLayout.LayoutParams(-1,400));dialog=new AgentDialog.Builder(activity).setTitle("Surface tactile").setView(box).create();dialog.show();});measured();
        JSONObject before=state("native"),b=bounds(before,"Surface native");float x=b.getInt("x")+20,y=b.getInt("y")+30,ex=b.getInt("x")+b.getInt("width")-30;
        Reply r=call(pointer(before,"drag",x,y).put("end_x",ex).put("end_y",y+100).put("duration_ms",350));assertTrue(r.text,r.ok);assertEquals(1,down.get());assertTrue(moves.get()>5);assertEquals(1,up.get());assertTrue(end.get()>100);
    }
    @Test public void visionFindsDialogTextAndRejectsStaleRevisionAndProtectedField()throws Exception{
        AtomicInteger clicks=new AtomicInteger();AtomicReference<Button> button=new AtomicReference<>();
        main(()->{LinearLayout box=new LinearLayout(activity);box.setOrientation(1);Button b=new Button(activity);b.setText("Avant");b.setOnClickListener(v->clicks.incrementAndGet());button.set(b);box.addView(b);EditText secret=new EditText(activity);secret.setInputType(129);secret.setText("secret-interdit");box.addView(secret);dialog=new AgentDialog.Builder(activity).setTitle("Fenêtre observable").setMessage("Message de test").setView(box).create();dialog.show();});measured();
        JSONObject before=state("native");assertTrue(before.toString().contains("Message de test"));assertFalse(before.toString().contains("secret-interdit"));JSONObject p=clickAt(before,"Avant","tap").put("expected_revision",before.getString("revision"));
        main(()->button.get().setText("Après"));assertFalse(call(p).ok);assertEquals(0,clicks.get());assertFalse(call(pointer(state("native"),"tap",-1,5)).ok);
    }
    @Test public void screenCaptureHasRealJpegAndRespectsPreviewConsent()throws Exception{
        measured();prefs.edit().putBoolean("preview_allowed",false).commit();assertFalse(call(new JSONObject().put("tool","vision").put("capture",true)).ok);
        prefs.edit().putBoolean("preview_allowed",true).commit();Reply r=call(new JSONObject().put("tool","vision").put("capture",true));assertTrue(r.text,r.ok);JSONObject shot=r.json();byte[] image=android.util.Base64.decode(shot.getString("jpeg_base64"),0);BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(image,0,image.length,options);assertEquals(shot.getInt("image_width"),options.outWidth);assertEquals(shot.getInt("image_height"),options.outHeight);assertTrue(image.length<150000);
    }
    @Test public void webCanvasReceivesTrustedAndroidTouchWithoutCssSelector()throws Exception{
        WebFixture.load(instrumentation,web,"https://desktop.test/canvas","<meta name='viewport' content='width=device-width,initial-scale=1'><style>body{margin:0}canvas{width:100vw;height:90vh;background:skyblue}</style><canvas></canvas><script>window.taps=0;window.moves=0;document.querySelector('canvas').addEventListener('pointerdown',e=>{if(e.isTrusted)taps++});document.querySelector('canvas').addEventListener('pointermove',e=>moves++);</script>","!!document.querySelector('canvas')");
        JSONObject before=state("web");Reply tap=call(pointer(before,"tap",50,50).put("expected_host","desktop.test"));assertTrue(tap.text,tap.ok);assertEquals("1",js("taps"));
        JSONObject observed=state("web");assertTrue(observed.getJSONArray("controls").length()>0);js("document.querySelector('canvas').setAttribute('aria-label','changed canvas');true");
        assertFalse(call(pointer(observed,"tap",50,50).put("expected_revision",observed.getString("revision"))).ok);assertEquals("1",js("taps"));
        assertTrue(call(pointer(state("web"),"drag",50,50).put("end_x",180).put("end_y",120).put("duration_ms",350)).ok);assertTrue(Integer.parseInt(js("moves"))>0);
        JSONObject stale=state("web");WebFixture.load(instrumentation,web,"https://desktop.test/canvas","<script>window.taps=0;</script>","typeof taps==='number'");assertFalse(call(pointer(stale,"tap",30,30)).ok);assertEquals("0",js("taps"));
    }
    @Test public void batchRenamesTwoHundredFilesAndReplayCreatesNoDuplicates()throws Exception{
        WorkspaceStore store=new WorkspaceStore(context);JSONArray paths=new JSONArray();for(int i=0;i<200;i++){String path=folder+"/source-"+i+".txt";store.writeText(path,"file-"+i,false);paths.put(path);}String id="rename-"+folder;
        Reply plan=call(files("plan",id).put("mode","rename").put("paths",paths).put("prefix","document-"));assertTrue(plan.text,plan.ok);assertTrue(store.file(paths.getString(0)).exists());
        Reply result=call(files("run",id));assertTrue(result.text,result.ok);assertEquals(200,result.json().getInt("succeeded"));assertEquals("file-0",store.readText(folder+"/document-0001.txt",0).getString("text"));
        Reply replay=call(files("run",id));assertEquals(200,replay.json().getInt("succeeded"));assertEquals(200,store.list(folder,"",0).getInt("total"));
    }
    @Test public void batchDetectsChangedSourceAndCollisionAndKeepsPartialResults()throws Exception{
        WorkspaceStore store=new WorkspaceStore(context);store.writeText(folder+"/a.txt","a",false);store.writeText(folder+"/b.txt","b",false);store.writeText(folder+"/c.txt","c",false);store.writeText(folder+"/r-0002.txt","existing",false);String id="partial-"+folder;
        assertTrue(call(files("plan",id).put("paths",new JSONArray().put(folder+"/a.txt").put(folder+"/b.txt").put(folder+"/c.txt")).put("prefix","r-")).ok);
        store.writeText(folder+"/a.txt","changed content",true);JSONObject result=call(files("run",id)).json();assertEquals(1,result.getInt("succeeded"));assertEquals(2,result.getInt("failed"));assertEquals("existing",store.readText(folder+"/r-0002.txt",0).getString("text"));assertEquals("c",store.readText(folder+"/r-0003.txt",0).getString("text"));
    }
    @Test public void automaticOrganizationUsesMimeCategoriesAndNeverOverwrites()throws Exception{
        WorkspaceStore store=new WorkspaceStore(context);store.writeText(folder+"/photo.png","fixture",false);store.writeText(folder+"/note.md","fixture",false);String id="organize-"+folder;
        assertTrue(call(files("plan",id).put("mode","organize").put("folder",folder).put("destination",folder+"/Sorted")).ok);JSONObject result=call(files("run",id)).json();assertEquals(2,result.getInt("succeeded"));assertTrue(store.file(folder+"/Sorted/Images/photo.png").isFile());assertTrue(store.file(folder+"/Sorted/Documents/note.md").isFile());
    }
    @Test public void fiftyActionWorkflowVerifiesFilesAndDoesNotReplayOnSubmit()throws Exception{
        taskMode();String id="task-"+folder;JSONArray steps=new JSONArray();for(int i=0;i<50;i++){String path=folder+"/step-"+i+".txt";steps.put(new JSONObject().put("id","step_"+i).put("action","files_write_text").put("args",new JSONObject().put("path",path).put("text","confirmed-"+i)).put("verify",new JSONObject().put("action","files_read_text").put("args",new JSONObject().put("path",path)).put("expect",new JSONObject().put("text","confirmed-"+i))));}
        JSONObject request=new JSONObject().put("tool","tasks").put("operation","submit").put("id",id).put("steps",steps);assertTrue(call(request).ok);JSONObject done=awaitTask(id);assertEquals(done.toString(),"completed",done.getString("state"));assertEquals(50,done.getInt("completed"));
        assertEquals("completed",call(request).json().getString("state"));assertEquals(50,new WorkspaceStore(context).list(folder,"",0).getInt("total"));
    }
    @Test public void resumeRetriesVerificationWithoutCreatingDuplicateNote()throws Exception{
        taskMode();WorkspaceStore store=new WorkspaceStore(context);String id="resume-"+folder;
        JSONObject create=new JSONObject().put("id","create").put("action","notes_save").put("args",new JSONObject().put("title",folder).put("body","initial"));
        JSONObject verify=new JSONObject().put("action","notes_read").put("args",new JSONObject().put("id","$create.id")).put("expect",new JSONObject().put("body","verified"));
        // The next read intentionally fails its assertion after creation has been checkpointed.
        JSONObject check=new JSONObject().put("id","check").put("action","notes_list").put("args",new JSONObject().put("query",folder)).put("verify",verify);
        assertTrue(call(new JSONObject().put("tool","tasks").put("operation","submit").put("id",id).put("steps",new JSONArray().put(create).put(check))).ok);assertEquals("paused",awaitTask(id).getString("state"));
        JSONArray notes=store.notes(folder,false,0);assertEquals(1,notes.length());JSONObject note=store.note(notes.getJSONObject(0).getString("id"));store.saveNote(note.getString("id"),folder,"verified","",false,note.getInt("revision"));
        assertTrue(call(new JSONObject().put("tool","tasks").put("operation","resume").put("id",id)).ok);assertEquals("completed",awaitTask(id).getString("state"));assertEquals(1,store.notes(folder,false,0).length());JSONObject saved=store.note(note.getString("id"));store.deleteNote(saved.getString("id"),true,saved.getInt("revision"));
    }
    @Test public void terminalPreviewsThenExecutesAuthorizedRenameAndJsonTransformation()throws Exception{
        taskMode();WorkspaceStore store=new WorkspaceStore(context);store.writeText(folder+"/image.jpg","image",false);store.writeText(folder+"/data.json","{\"name\":\"idée\",\"count\":2}",false);String id="script-"+folder;
        JSONObject script=new JSONObject().put("version",1).put("commands",new JSONArray().put(new JSONObject().put("command","rename").put("folder",folder).put("suffix",".jpg").put("prefix","photo-")).put(new JSONObject().put("command","transform").put("paths",new JSONArray().put(folder+"/data.json")).put("transform","format_json").put("output_suffix",".pretty.json")));
        assertTrue(call(new JSONObject().put("tool","terminal").put("operation","validate").put("id",id).put("script",script)).json().getBoolean("dry_run"));assertTrue(store.file(folder+"/image.jpg").exists());
        assertTrue(call(new JSONObject().put("tool","terminal").put("operation","run").put("id",id).put("script",script)).ok);assertEquals("completed",awaitTask(id).getString("state"));assertTrue(store.file(folder+"/photo-0001.jpg").exists());String json=store.readText(folder+"/data.json.pretty.json",0).getString("text");assertTrue(json.contains("\n"));assertEquals("idée",new JSONObject(json).getString("name"));
        JSONObject forbidden=new JSONObject().put("version",1).put("commands",new JSONArray().put(new JSONObject().put("command","shell").put("text","id")));assertFalse(call(new JSONObject().put("tool","terminal").put("id","blocked").put("script",forbidden)).ok);
    }
    @Test public void interruptedFileEffectIsNotRepeatedAndOtherItemsContinue()throws Exception{
        WorkspaceStore store=new WorkspaceStore(context);store.writeText(folder+"/a.txt","first",false);store.writeText(folder+"/b.txt","second",false);String id="interrupted-"+folder;
        assertTrue(call(files("plan",id).put("paths",new JSONArray().put(folder+"/a.txt").put(folder+"/b.txt")).put("prefix","safe-")).ok);
        java.io.File journal=AgentFiles.journal(context,"files",id);JSONObject saved=AgentFiles.read(journal);saved.getJSONArray("items").getJSONObject(0).put("state","running");AgentFiles.save(journal,saved);
        store.move(folder+"/a.txt",folder+"/safe-0001.txt");JSONObject result=call(files("run",id)).json();assertEquals(1,result.getInt("uncertain"));assertEquals(1,result.getInt("succeeded"));
        assertEquals("first",store.readText(folder+"/safe-0001.txt",0).getString("text"));assertEquals("second",store.readText(folder+"/safe-0002.txt",0).getString("text"));assertEquals(1,call(files("run",id)).json().getInt("uncertain"));
    }
    @Test public void javascriptConfirmationIsVisibleAndClickableAsChkDialog()throws Exception{
        WebFixture.load(instrumentation,web,"https://desktop.test/dialog","<script>window.confirmed=false;</script>","typeof confirmed==='boolean'");
        js("setTimeout(()=>{window.confirmed=confirm('Confirmation native de test')},40);true");Thread.sleep(180);JSONObject before=state("native");assertTrue(before.toString().contains("Confirmation native de test"));
        assertTrue(call(clickAt(before,"Confirmer","tap")).ok);assertEquals("true",js("confirmed"));
    }
    @Test public void externalAccessRequiresAnActualAndroidGrantAndPathContainment()throws Exception{
        assertFalse(call(new JSONObject().put("tool","files").put("operation","external_list").put("root_id","0000000000000000")).ok);
        assertFalse(call(files("plan","escape").put("paths",new JSONArray().put("../secret"))).ok);
        assertFalse(call(new JSONObject().put("tool","tasks").put("operation","submit").put("id","nested").put("steps",new JSONArray().put(new JSONObject().put("id","nested").put("action","desktop").put("args",new JSONObject().put("tool","tasks"))))).ok);
    }
}
