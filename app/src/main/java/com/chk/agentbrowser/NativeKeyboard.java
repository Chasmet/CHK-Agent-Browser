package com.chk.agentbrowser;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.InputFilter;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.AdapterView;
import android.widget.Spinner;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.UUID;
import java.util.WeakHashMap;

/** Main-thread editing of CHK view trees. Paused screens remain usable until Android destroys them. */
final class NativeKeyboard {
    private static WeakReference<Activity> last=new WeakReference<>(null);
    private static boolean foreground;
    private static final WeakHashMap<Activity,ArrayList<WeakReference<Dialog>>> dialogs=new WeakHashMap<>();
    private static final WeakHashMap<View,String> identities=new WeakHashMap<>();
    private static final WeakHashMap<EditText,History> histories=new WeakHashMap<>();
    private NativeKeyboard(){}
    static void resumed(Activity a){last=new WeakReference<>(a);foreground=true;}
    static void paused(Activity a){if(last.get()==a)foreground=false;}
    static void destroyed(Activity a){dialogs.remove(a);if(last.get()==a){last.clear();foreground=false;}}
    static void watch(Activity a,Dialog d){
        ArrayList<WeakReference<Dialog>> list=dialogs.get(a);
        if(list==null){list=new ArrayList<>();dialogs.put(a,list);}
        for(int i=list.size()-1;i>=0;i--)if(list.get(i).get()==null)list.remove(i);
        list.add(new WeakReference<>(d));
    }
    private static Activity activity()throws Exception{
        Activity a=last.get();if(a==null||a.isFinishing()||a.isDestroyed())throw new Exception("Ouvre l’écran CHK à remplir ; aucun écran natif conservé.");return a;
    }
    private static View root()throws Exception{
        Activity a=activity();ArrayList<WeakReference<Dialog>> list=dialogs.get(a);
        if(list!=null)for(int i=list.size()-1;i>=0;i--){Dialog d=list.get(i).get();if(d!=null&&d.isShowing()&&d.getWindow()!=null)return d.getWindow().getDecorView();}
        return a.findViewById(android.R.id.content);
    }
    private static String identity(View v){String s=identities.get(v);if(s==null){s=UUID.randomUUID().toString().substring(0,12);identities.put(v,s);}return s;}
    private static String context(View r){return "native:"+identity(r);}
    private static String selector(View r,View v){return context(r)+":"+identity(v);}
    private static boolean protectedField(EditText e){
        int type=e.getInputType(),kind=type&InputType.TYPE_MASK_CLASS,variant=type&InputType.TYPE_MASK_VARIATION;
        return e.getTransformationMethod() instanceof PasswordTransformationMethod||
            (kind==InputType.TYPE_CLASS_TEXT&&(variant==InputType.TYPE_TEXT_VARIATION_PASSWORD||variant==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD||variant==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))||
            (kind==InputType.TYPE_CLASS_NUMBER&&variant==InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }
    private static void collect(View v,ArrayList<View> out){
        if(v==null||v.getVisibility()!=View.VISIBLE)return;
        if(v instanceof EditText){if(!protectedField((EditText)v))out.add(v);}
        else if(v instanceof CompoundButton||v.isClickable()||listItem(v))out.add(v);
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),out);
    }
    private static ArrayList<View> controls(View r){ArrayList<View> out=new ArrayList<>();collect(r,out);return out;}
    private static boolean listItem(View v){return v.getParent() instanceof AdapterView&&((AdapterView<?>)v.getParent()).getOnItemClickListener()!=null;}
    private static String label(View v){
        CharSequence s=v.getContentDescription();
        if((s==null||s.length()==0)&&v instanceof EditText)s=((EditText)v).getHint();
        if((s==null||s.length()==0)&&v instanceof TextView&&!(v instanceof EditText))s=((TextView)v).getText();
        if((s==null||s.length()==0)&&v.getParent() instanceof ViewGroup){ViewGroup p=(ViewGroup)v.getParent();int at=p.indexOfChild(v);if(at>0&&p.getChildAt(at-1) instanceof TextView&&!(p.getChildAt(at-1) instanceof EditText))s=((TextView)p.getChildAt(at-1)).getText();}
        if(s==null||s.length()==0)try{s=v.getResources().getResourceEntryName(v.getId());}catch(Exception ignored){s=v.getClass().getSimpleName();}
        return clip(s.toString(),100);
    }
    static int cpLength(String s){return s.codePointCount(0,s.length());}
    private static int cpOffset(String s,int index)throws Exception{int count=cpLength(s);if(index<0||index>count)throw new Exception("Position de curseur hors du champ.");return s.offsetByCodePoints(0,index);}
    private static int cpPosition(String s,int utf16){return s.codePointCount(0,Math.min(s.length(),Math.max(0,utf16)));}
    private static String clip(String s,int max){return cpLength(s)>max?s.substring(0,s.offsetByCodePoints(0,max)):s;}
    private static JSONObject describe(View r,View v)throws Exception{
        JSONObject o=new JSONObject().put("selector",selector(r,v)).put("label",label(v)).put("enabled",v.isEnabled()).put("focused",v.hasFocus());
        if(v instanceof EditText){EditText e=(EditText)v;String text=e.getText().toString();int t=e.getInputType();
            o.put("kind","field").put("readonly",e.getKeyListener()==null).put("numeric",(t&InputType.TYPE_MASK_CLASS)==InputType.TYPE_CLASS_NUMBER).put("multiline",(t&InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0)
                .put("value",clip(text,256)).put("value_truncated",cpLength(text)>256).put("length",cpLength(text)).put("selection_start",cpPosition(text,e.getSelectionStart())).put("selection_end",cpPosition(text,e.getSelectionEnd()));
            History h=histories.get(e);o.put("can_undo",h!=null&&!h.undo.isEmpty()).put("can_redo",h!=null&&!h.redo.isEmpty());
        }else{
            o.put("kind",v instanceof CompoundButton?"check":v instanceof Spinner?"select":listItem(v)?"choice":"action");
            if(v instanceof CompoundButton)o.put("checked",((CompoundButton)v).isChecked());
            if(v instanceof Spinner){Spinner spinner=(Spinner)v;JSONArray options=new JSONArray();for(int i=0;spinner.getAdapter()!=null&&i<Math.min(30,spinner.getAdapter().getCount());i++)options.put(new JSONObject().put("value",String.valueOf(i)).put("label",clip(String.valueOf(spinner.getAdapter().getItem(i)),100)));o.put("options",options).put("selected",spinner.getSelectedItemPosition());}
        }
        return o;
    }
    static JSONObject inspect()throws Exception{
        return inspect(0);
    }
    private static JSONObject inspect(int offset)throws Exception{
        if(offset<0||offset>5000)throw new Exception("Pagination de champs invalide.");
        View r=root();JSONArray rows=new JSONArray();ArrayList<View> all=controls(r);boolean truncated=false;
        for(int i=offset;i<all.size();i++){JSONObject row=describe(r,all.get(i));rows.put(row);if(rows.length()>60||rows.toString().getBytes("UTF-8").length>23000){rows.remove(rows.length()-1);truncated=true;break;}}
        return new JSONObject().put("ok",true).put("target","native").put("context",context(r)).put("activity",activity().getClass().getSimpleName())
            .put("foreground",foreground).put("controls",rows).put("truncated",truncated).put("next_offset",truncated?offset+rows.length():-1).put("total",all.size()).put("position_unit","unicode_code_points");
    }
    static boolean prefersNative(){try{View r=root();if(r!=activity().findViewById(android.R.id.content))return true;for(View v:controls(r))if(v instanceof EditText&&v.hasFocus())return true;return !(activity() instanceof MainActivity);}catch(Exception ignored){return false;}}
    static AgentClient.CommandHandler handler(android.content.Context app){return new AgentClient.CommandHandler(){
        public String pageUrl(){return "chk://native";}
        public String pageTitle(){return "Saisie dans CHK";}
        public String sessionSource(){return "native";}
        public void onCommand(JSONObject command,AgentClient.ResultCallback cb){
            if(!cb.isActive())return;
            AgentClient agent=AgentClient.get(app);
            Runnable run=()->{if(cb.isActive())KeyboardCommands.run(app,null,command.optString("action"),command.optJSONObject("args"),cb);};
            if(agent.isAutonomous()){run.run();return;}
            try{Activity a=activity();if(!foreground){cb.finish(false,"Ouvre CHK pour autoriser la commande en mode manuel.");return;}
                new AgentDialog.Builder(a).setTitle("Saisie par l’agent").setMessage("Autoriser la commande clavier dans cet écran CHK ?")
                    .setNegativeButton("Refuser",(d,w)->cb.finish(false,"Commande refusée."))
                    .setPositiveButton("Autoriser",(d,w)->new Handler(Looper.getMainLooper()).post(run))
                    .setOnCancelListener(d->cb.finish(false,"Commande annulée.")).show();
            }catch(Exception e){cb.finish(false,e.getMessage());}
        }
    };}
    private static View find(View r,String id)throws Exception{
        ArrayList<View> all=controls(r);
        if(id.isEmpty()){for(View v:all)if(v instanceof EditText&&v.hasFocus())return v;throw new Exception("Aucun champ CHK ciblé.");}
        for(View v:all)if(selector(r,v).equals(id))return v;
        throw new Exception("Champ ou écran remplacé : inspecte à nouveau l’écran CHK.");
    }
    private static void validateContext(View r,JSONObject p)throws Exception{
        String expected=p.optString("expected_context","");String sel=p.optString("selector","");
        if(expected.isEmpty()&&sel.startsWith(context(r)+":"))expected=context(r);
        if(!context(r).equals(expected))throw new Exception("Contexte CHK absent ou périmé : inspecte l’écran avant la saisie.");
    }
    static void execute(JSONObject p,AgentClient.ResultCallback cb){
        if(!cb.isActive())return;
        try{
            String operation=p.optString("operation","inspect");
            if(operation.equals("inspect")){cb.finish(true,inspect(p.optInt("offset",0)).toString());return;}
            View r=root();validateContext(r,p);
            if(operation.equals("fill")){
                JSONArray fields=p.optJSONArray("fields");if(fields==null||fields.length()<1||fields.length()>25)throw new Exception("Un lot doit contenir entre 1 et 25 champs.");
                JSONArray results=new JSONArray();int completed=0;
                for(int i=0;i<fields.length();i++){
                    if(!cb.isActive())return;
                    JSONObject f=fields.getJSONObject(i);JSONObject one=new JSONObject(f.toString());String op=one.optString("operation","replace");
                    JSONObject result=new JSONObject().put("selector",one.optString("selector"));
                    try{if(root()!=r)throw new Exception("L’écran a changé pendant le lot.");if(!op.equals("replace")&&!op.equals("insert")&&!op.equals("clear")&&!op.equals("check")&&!op.equals("select_option"))throw new Exception("Un lot remplit des champs sans cliquer ni envoyer.");
                        View v=find(r,one.optString("selector"));apply(r,v,op,one);result.put("ok",true).put("field",describe(r,v));completed++;
                    }catch(Exception e){result.put("ok",false).put("error",e.getMessage());}results.put(result);
                }
                JSONObject out=new JSONObject().put("ok",completed==fields.length()).put("context",context(r)).put("succeeded",completed).put("failed",fields.length()-completed).put("results",results);
                // Partial outcomes remain structured, including every success; never retry the entire lot blindly.
                cb.finish(true,out.toString());return;
            }
            View v=find(r,p.optString("selector"));apply(r,v,operation,p);
            JSONObject out=new JSONObject().put("ok",true).put("target","native").put("field",describe(r,v)).put("context",context(root()));
            cb.finish(true,out.toString());
        }catch(Exception e){cb.finish(false,e.getMessage()==null?"Saisie CHK impossible.":e.getMessage());}
    }
    private static final class EditState{final String text;final int start,end;EditState(EditText e){text=e.getText().toString();start=Math.max(0,e.getSelectionStart());end=Math.max(0,e.getSelectionEnd());}}
    private static final class History{final ArrayDeque<EditState> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();String expected="";}
    private static History history(EditText e){History h=histories.get(e);if(h==null){h=new History();h.expected=e.getText().toString();histories.put(e,h);}if(!h.expected.equals(e.getText().toString())){h.undo.clear();h.redo.clear();h.expected=e.getText().toString();}return h;}
    private static void remember(EditText e,History h){h.undo.addLast(new EditState(e));while(h.undo.size()>8)h.undo.removeFirst();h.redo.clear();}
    private static String normalized(EditText e,String value)throws Exception{
        int t=e.getInputType();if((t&InputType.TYPE_MASK_CLASS)==InputType.TYPE_CLASS_NUMBER&&!value.isEmpty()){
            boolean decimal=(t&InputType.TYPE_NUMBER_FLAG_DECIMAL)!=0,signed=(t&InputType.TYPE_NUMBER_FLAG_SIGNED)!=0;
            if(decimal)value=value.replace(',','.');String pattern=(signed?"[-+]?":"")+(decimal?"(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)":"[0-9]+");
            if(!value.matches(pattern))throw new Exception("Valeur incompatible avec ce champ numérique.");
        }
        for(InputFilter filter:e.getFilters())if(filter instanceof InputFilter.LengthFilter&&value.length()>((InputFilter.LengthFilter)filter).getMax())throw new Exception("Le texte dépasse la longueur autorisée du champ.");
        if(value.length()>12000)throw new Exception("Champ trop long (12 000 caractères maximum).");return value;
    }
    private static void replace(EditText e,String expected,int cursor,History h)throws Exception{
        remember(e,h);InputConnection ic=e.onCreateInputConnection(new EditorInfo());
        if(ic==null)throw new Exception("Champ non modifiable.");ic.beginBatchEdit();
        try{ic.finishComposingText();ic.setSelection(0,e.length());ic.commitText(expected,1);ic.setSelection(Math.min(cursor,e.length()),Math.min(cursor,e.length()));}finally{ic.endBatchEdit();}
        h.expected=e.getText().toString();if(!expected.equals(h.expected))throw new Exception("Le champ a filtré la saisie ; inspecte sa valeur réelle.");
    }
    private static void apply(View r,View v,String op,JSONObject p)throws Exception{
        if(!v.isEnabled())throw new Exception("Contrôle désactivé.");
        if(op.equals("click")){
            if(v instanceof EditText){v.requestFocus();return;}
            if(listItem(v)){AdapterView<?> parent=(AdapterView<?>)v.getParent();int position=parent.getPositionForView(v);if(position<0||!parent.performItemClick(v,position,parent.getAdapter().getItemId(position)))throw new Exception("Choix non confirmé.");return;}
            if(!v.isClickable()||!v.performClick())throw new Exception("Action non cliquable.");return;
        }
        if(op.equals("check")){if(!(v instanceof CompoundButton))throw new Exception("Contrôle non cochable.");CompoundButton b=(CompoundButton)v;boolean expected=p.optBoolean("checked",true);if(b.isChecked()!=expected)b.performClick();if(b.isChecked()!=expected)throw new Exception("État non confirmé.");return;}
        if(op.equals("select_option")){
            if(!(v instanceof Spinner))throw new Exception("Contrôle non sélectionnable.");Spinner spinner=(Spinner)v;String wanted=p.optString("text");int index=-1;
            for(int i=0;spinner.getAdapter()!=null&&i<spinner.getAdapter().getCount();i++)if(String.valueOf(i).equals(wanted)||String.valueOf(spinner.getAdapter().getItem(i)).equals(wanted)){if(index>=0)throw new Exception("Option ambiguë : utilise son index.");index=i;}
            if(index<0)throw new Exception("Option introuvable.");spinner.setSelection(index);if(spinner.getSelectedItemPosition()!=index)throw new Exception("Option non confirmée.");return;
        }
        if(!(v instanceof EditText)||protectedField((EditText)v))throw new Exception("Champ non modifiable par le clavier agent.");
        EditText e=(EditText)v;if(e.getKeyListener()==null)throw new Exception("Champ en lecture seule.");e.requestFocus();
        String current=e.getText().toString();int from=Math.max(0,e.getSelectionStart()),to=Math.max(0,e.getSelectionEnd());if(from>to){int swap=from;from=to;to=swap;}
        History h=history(e);
        if(op.equals("select")){int a=cpOffset(current,p.optInt("start",0)),b=cpOffset(current,p.optInt("end",p.optInt("start",0)));e.setSelection(a,b);return;}
        if(op.equals("key")){
            String key=p.optString("key").toUpperCase(java.util.Locale.ROOT);
            if(key.equals("SELECT_ALL")){e.selectAll();return;}
            if(key.equals("UNDO")||key.equals("REDO")){
                ArrayDeque<EditState> source=key.equals("UNDO")?h.undo:h.redo,dest=key.equals("UNDO")?h.redo:h.undo;
                if(source.isEmpty())throw new Exception("Aucune saisie agent à "+(key.equals("UNDO")?"annuler":"rétablir")+".");EditState s=source.removeLast();dest.addLast(new EditState(e));e.setText(s.text);e.setSelection(Math.min(s.start,e.length()),Math.min(s.end,e.length()));h.expected=e.getText().toString();if(!s.text.equals(h.expected))throw new Exception("Restauration filtrée par le champ.");return;
            }
            if(key.equals("TAB")||key.equals("SHIFT_TAB")){ArrayList<View> fields=new ArrayList<>();for(View candidate:controls(r))if(candidate instanceof EditText&&candidate.isEnabled())fields.add(candidate);int index=fields.indexOf(e)+(key.equals("TAB")?1:-1);if(index<0||index>=fields.size())throw new Exception("Aucun autre champ dans ce sens.");fields.get(index).requestFocus();return;}
            if(key.equals("HOME")||key.equals("END")||key.equals("LEFT")||key.equals("RIGHT")){
                int at=key.equals("HOME")?0:key.equals("END")?current.length():key.equals("LEFT")?(from!=to?from:current.offsetByCodePoints(from,from>0?-1:0)):(from!=to?to:current.offsetByCodePoints(to,to<current.length()?1:0));e.setSelection(at);return;
            }
            if(key.equals("ENTER")){
                if((e.getInputType()&InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0){op="insert";p=new JSONObject().put("text","\n");}
                else {int action=e.getImeOptions()&EditorInfo.IME_MASK_ACTION;if(action==EditorInfo.IME_ACTION_NONE||action==EditorInfo.IME_ACTION_UNSPECIFIED)throw new Exception("Pas d’action Entrée déclarée : utilise le bouton visible du formulaire.");InputConnection ic=e.onCreateInputConnection(new EditorInfo());if(ic==null||!ic.performEditorAction(action))throw new Exception("Action Entrée non prise en charge.");return;}
            }else if(key.equals("BACKSPACE")||key.equals("DELETE")){
                if(from==to){if(key.equals("BACKSPACE")&&from>0)from=current.offsetByCodePoints(from,-1);if(key.equals("DELETE")&&to<current.length())to=current.offsetByCodePoints(to,1);}
                String next=normalized(e,current.substring(0,from)+current.substring(to));replace(e,next,from,h);return;
            }else throw new Exception("Touche clavier non prise en charge.");
        }
        String text=p.optString("text","");if(text.length()>8000)throw new Exception("Saisie trop longue (8 000 caractères maximum).");
        String expected;int caret;
        if(op.equals("replace")||op.equals("clear")){expected=normalized(e,op.equals("clear")?"":text);caret=expected.length();}
        else if(op.equals("insert")){String raw=current.substring(0,from)+text+current.substring(to);expected=normalized(e,raw);caret=from+text.length();}
        else throw new Exception("Opération clavier inconnue.");
        replace(e,expected,caret,h);
    }
}
