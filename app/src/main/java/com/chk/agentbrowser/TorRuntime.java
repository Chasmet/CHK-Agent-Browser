package com.chk.agentbrowser;

import android.content.*;
import android.os.*;
import android.util.AtomicFile;
import android.webkit.*;
import androidx.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.torproject.jni.TorService;

/** Runs exclusively in :tor. A circuit and an HTTPS IsTor response are both required. */
@androidx.annotation.RequiresApi(29)
final class TorRuntime {
    interface Result {void done(boolean ok,JSONObject value);}
    private static TorRuntime instance;
    static synchronized TorRuntime get(Context c){if(instance==null)instance=new TorRuntime(c.getApplicationContext());if(c instanceof TorRuntimeService)instance.owner=(TorRuntimeService)c;return instance;}
    final TorSessionPolicy policy=new TorSessionPolicy();
    final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    // Reader requests (translation) must never block the navigation queue.
    private final ExecutorService readerIo=Executors.newSingleThreadExecutor();
    private final ExecutorService verifier=Executors.newSingleThreadExecutor();
    private final Context app;
    private final String sessionId=UUID.randomUUID().toString();
    private final JSONObject receipts=new JSONObject();
    private final ArrayList<Result> connecting=new ArrayList<>();
    private volatile TorService engine;
    private boolean bound,proxyReady,watching;
    private volatile int socksPort,httpPort,bootstrap;
    private volatile String nativeVersion="not_started";
    private JSONObject diagnostic;
    TorRuntimeService owner;
    TorWebActivity screen;
    WebView web;
    private JSONObject navigation;
    private Result navigationReply;
    private long navigationStart;
    private boolean pageLoadStarted;
    private String lastUrl="",lastTitle="",lastPageError="";
    private final ServiceConnection nativeConnection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){engine=((TorService.LocalBinder)binder).getService();}
        public void onServiceDisconnected(ComponentName name){engine=null;block("TOR_ENGINE_DISCONNECTED");}
    };
    private TorRuntime(Context c){
        app=c;
        if(!ApplicationProcess.isTor())throw new IllegalStateException("TOR_PROCESS_REQUIRED");
        try{
            if(!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))throw new Exception("TOR_WEBVIEW_PROXY_UNSUPPORTED");
            // Even while disconnected, the process has a dead proxy, never a direct fallback.
            ProxyController.getInstance().setProxyOverride(proxy(1),main::post,()->{});
        }catch(Exception e){policy.fail(policy.generation(),e.getMessage());}
    }
    private static ProxyConfig proxy(int port){return new ProxyConfig.Builder().addProxyRule("socks://127.0.0.1:"+port).removeImplicitRules().build();}
    JSONObject status()throws Exception{
        long now=SystemClock.elapsedRealtime();if(policy.state().equals("connected")&&!policy.usable(now))block("TOR_PROOF_EXPIRED");
        return new JSONObject().put("state","completed").put("session_id",sessionId).put("generation",policy.generation())
            .put("connection_state",policy.state()).put("tor_connected",policy.usable(now)).put("tor_verified",policy.usable(now))
            .put("proof_age_ms",policy.proofAge(now)).put("error",policy.error()).put("bootstrap_percent",bootstrap)
            .put("engine","guardianproject_tor_android").put("engine_bundle_version","0.4.9.14").put("native_version",nativeVersion)
            .put("engine_process_id",android.os.Process.myPid()).put("process_name",android.app.Application.getProcessName())
            .put("http_tunnel_port",httpPort).put("socks_port",socksPort).put("proxy_scope","dedicated_tor_process_only")
            .put("web_storage_suffix","chk_tor").put("javascript_enabled",false).put("vpn_used",false)
            .put("ui_open",screen!=null&&screen.resumed).put("webview_attached",hasLiveWebView()).put("background_navigation_ready",hasLiveWebView()&&proxyReady&&policy.usable(now)).put("url",web==null?lastUrl:java.util.Objects.toString(web.getUrl(),"")).put("title",web==null?lastTitle:shortTitle(web.getTitle())).put("last_successful_url",lastUrl)
            .put("page_error",lastPageError).put("navigation",navigation==null?JSONObject.NULL:new JSONObject(navigation.toString()))
            .put("diagnostic",diagnostic==null?JSONObject.NULL:new JSONObject(diagnostic.toString()));
    }
    void command(JSONObject args,Result cb){
        try{
            String op=args.optString("operation","status");
            if(op.equals("status")){cb.done(true,status());return;}
            if(op.equals("connect")){connect(cb);return;}
            if(op.equals("open_browser")){openBrowser(cb,args.optBoolean("home_on_open",false));return;}
            if(op.equals("diagnostic")){refresh(cb);return;}
            if(op.equals("read")){read(args,cb);return;}
            if(op.equals("screenshot")){if(!args.optBoolean("preview_allowed"))throw new Exception("PREVIEW_SHARING_DISABLED");if(!hasLiveWebView())throw new Exception("TOR_WEBVIEW_UNAVAILABLE_OPEN_ONCE");screen.capture(cb);return;}
            if(op.equals("close_browser")){if(screen!=null)screen.finish();waitClosed(cb,SystemClock.elapsedRealtime()+5000);return;}
            if(op.equals("cancel_navigation")){if(navigationReply==null){reply(cb,true);return;}if(web!=null)web.stopLoading();finishNavigation(false,"TOR_NAVIGATION_CANCELLED_BY_USER");reply(cb,true);return;}
            if(!Arrays.asList("navigate","back","forward","reload").contains(op))throw new Exception("TOR_OPERATION_UNSUPPORTED");
            navigate(args,cb);
        }catch(Exception|LinkageError e){
            String reason=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            if(args.optString("operation").equals("connect")&&connecting.contains(cb)){block(reason);finishConnect(false);}else cb.done(false,TorBridge.error(reason));
        }
    }
    private void waitClosed(Result cb,long deadline){if(screen==null){reply(cb,true);return;}if(SystemClock.elapsedRealtime()>deadline){cb.done(false,TorBridge.error("TOR_UI_CLOSE_UNCONFIRMED"));return;}main.postDelayed(()->waitClosed(cb,deadline),100);}
    private void connect(Result cb)throws Exception{
        if(policy.usable(SystemClock.elapsedRealtime())){reply(cb,true);return;}
        if(policy.state().equals("connecting")){connecting.add(cb);return;}
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))throw new Exception("TOR_WEBVIEW_PROXY_UNSUPPORTED");
        if(owner==null)throw new Exception("TOR_SERVICE_NOT_BOUND");
        owner.activate();long ticket=policy.begin();proxyReady=false;diagnostic=null;connecting.add(cb);bootstrap=0;
        if(!bound){
            File config=TorService.getTorrc(app);try(FileOutputStream out=new FileOutputStream(config)){
                out.write(("ClientOnly 1\nSocksPort auto\nHTTPTunnelPort auto\nSocksPolicy accept 127.0.0.1\nSocksPolicy reject *\nDNSPort 0\nTransPort 0\nControlPort 0\nSafeSocks 1\nAvoidDiskWrites 1\n").getBytes(StandardCharsets.UTF_8));
            }
            bound=app.bindService(new Intent(app,TorService.class),nativeConnection,Context.BIND_AUTO_CREATE);
            if(!bound)throw new Exception("TOR_NATIVE_SERVICE_UNAVAILABLE");
        }
        io.execute(()->{try{
            long deadline=SystemClock.elapsedRealtime()+240000;
            while(ticket==policy.generation()){
                TorService service=engine;
                if(service!=null&&service.getTorControlConnection()!=null){
                    String phase=service.getInfo("status/bootstrap-phase");if(phase!=null){java.util.regex.Matcher m=java.util.regex.Pattern.compile("PROGRESS=([0-9]+)").matcher(phase);if(m.find())bootstrap=Integer.parseInt(m.group(1));}
                    if("1".equals(service.getInfo("status/circuit-established"))){
                        httpPort=service.getHttpTunnelPort();socksPort=service.getSocksPort();verifyListeners(service);nativeVersion=service.getInfo("version");
                        JSONObject proof=TorHttp.diagnostic(httpPort);
                        main.post(()->{
                            if(ticket!=policy.generation())return;
                            try{ProxyController.getInstance().setProxyOverride(proxy(socksPort),main::post,()->{
                                if(ticket!=policy.generation())return;proxyReady=true;diagnostic=proof;
                                if(!policy.confirm(ticket,true,true,SystemClock.elapsedRealtime())){finishConnect(false);return;}
                                bootstrap=100;finishConnect(true);startWatch();
                            });}catch(Exception e){block(e.getMessage());finishConnect(false);}
                        });return;
                    }
                }
                if(SystemClock.elapsedRealtime()>deadline)throw new Exception("TOR_BOOTSTRAP_TIMEOUT");Thread.sleep(250);
            }
        }catch(Exception|LinkageError e){main.post(()->{policy.fail(ticket,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());finishConnect(false);});}});
    }
    private void verifyListeners(TorService service)throws Exception{
        for(String key:new String[]{"net/listeners/socks","net/listeners/httptunnel"}){
            String value=service.getInfo(key);if(value==null||!value.matches("\"127\\.0\\.0\\.1:[0-9]+\""))throw new Exception("TOR_LISTENER_NOT_EXCLUSIVELY_LOOPBACK");
        }
        if(httpPort<1||socksPort<1)throw new Exception("TOR_PROXY_PORT_UNAVAILABLE");
    }
    private void finishConnect(boolean ok){ArrayList<Result> callbacks=new ArrayList<>(connecting);connecting.clear();for(Result cb:callbacks)reply(cb,ok);}
    private void startWatch(){if(watching)return;watching=true;main.postDelayed(watch,5000);}
    private final Runnable watch=new Runnable(){public void run(){
        if(!policy.state().equals("connected")){watching=false;return;}long ticket=policy.generation();
        verifier.execute(()->{try{requireCircuit();if(policy.proofAge(SystemClock.elapsedRealtime())>=60000){JSONObject proof=TorHttp.diagnostic(httpPort);main.post(()->{if(policy.refresh(ticket,true,true,SystemClock.elapsedRealtime()))diagnostic=proof;});}}
            catch(Exception e){main.post(()->{if(ticket==policy.generation())block(e.getMessage());});}
            finally{main.postDelayed(this,5000);}});
    }};
    private void requireCircuit()throws Exception{TorService service=engine;if(service==null||service.getTorControlConnection()==null||!"1".equals(service.getInfo("status/circuit-established")))throw new Exception("TOR_CIRCUIT_NOT_ESTABLISHED");}
    private void refresh(Result cb)throws Exception{
        if(!policy.usable(SystemClock.elapsedRealtime()))throw new Exception("TOR_NOT_CONNECTED");long ticket=policy.generation();
        io.execute(()->{try{requireCircuit();JSONObject proof=TorHttp.diagnostic(httpPort);main.post(()->{if(policy.refresh(ticket,true,true,SystemClock.elapsedRealtime())){diagnostic=proof;reply(cb,true);}else cb.done(false,TorBridge.error("TOR_SESSION_CHANGED"));});}
            catch(Exception e){main.post(()->{block(e.getMessage());reply(cb,false);});}});
    }
    /** Keep control in :tor when ChatGPT moves the activity into the background.
     * Never create a new WebView outside the isolated, proxy-guarded Tor activity. */
    private boolean hasLiveWebView(){return web!=null&&screen!=null&&!screen.isFinishing()&&!screen.isDestroyed();}
    void attach(TorWebActivity a,WebView view){screen=a;web=view;}
    void detach(TorWebActivity a){if(screen==a){screen=null;web=null;if(navigationReply!=null)finishNavigation(false,"TOR_UI_CLOSED");}}
    private void openBrowser(Result cb,boolean frenchHome)throws Exception{
        if(!policy.usable(SystemClock.elapsedRealtime())||!proxyReady)throw new Exception("TOR_NOT_CONNECTED");
        Intent launch=new Intent(app,TorWebActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if(frenchHome)launch.putExtra("start_french_home",true);
        app.startActivity(launch);waitOpen(cb,SystemClock.elapsedRealtime()+10000);
    }
    private void waitOpen(Result cb,long deadline){if(screen!=null&&screen.resumed&&web!=null){reply(cb,true);return;}if(SystemClock.elapsedRealtime()>deadline){cb.done(false,TorBridge.error("TOR_UI_OPEN_UNCONFIRMED"));return;}main.postDelayed(()->waitOpen(cb,deadline),100);}
    JSONObject uiArgs(String op)throws Exception{return new JSONObject().put("operation",op).put("session_id",sessionId).put("request_id",UUID.randomUUID().toString());}
    private void navigate(JSONObject args,Result cb)throws Exception{
        for(Iterator<String> keys=args.keys();keys.hasNext();){String key=keys.next();if(!Arrays.asList("operation","session_id","request_id","url").contains(key))throw new Exception("TOR_PARAMETER_UNSUPPORTED: "+key);}
        if(!sessionId.equals(args.optString("session_id")))throw new Exception("TOR_SESSION_MISMATCH");
        if(!policy.usable(SystemClock.elapsedRealtime())||!proxyReady)throw new Exception("TOR_NOT_CONNECTED");
        if(!hasLiveWebView())throw new Exception("TOR_WEBVIEW_UNAVAILABLE_OPEN_ONCE");
        String request=args.optString("request_id");if(!request.matches("[A-Za-z0-9_-]{1,80}"))throw new Exception("TOR_REQUEST_ID_REQUIRED");
        String fingerprint=canonical(args);JSONObject old=receipts.optJSONObject(request);
        if(old!=null){if(!fingerprint.equals(old.optString("fingerprint")))throw new Exception("TOR_REQUEST_ID_REUSED_WITH_DIFFERENT_PARAMETERS");JSONObject value=new JSONObject(old.getJSONObject("result").toString()).put("replayed",true).put("current_url",lastUrl);cb.done(value.optString("state").equals("completed"),value);return;}
        if(receipts.length()>=128)throw new Exception("TOR_SESSION_RECEIPT_LIMIT: déconnecter puis créer une nouvelle session");
        String op=args.getString("operation"),url=args.optString("url",web==null?lastUrl:java.util.Objects.toString(web.getUrl(),""));
        if(op.equals("back")||op.equals("forward")){WebBackForwardList history=web.copyBackForwardList();int index=history.getCurrentIndex()+(op.equals("back")?-1:1);if(index<0||index>=history.getSize())throw new Exception("TOR_HISTORY_BOUNDARY");url=history.getItemAtIndex(index).getUrl();}
        final String target=TorUrlPolicy.checked(url);
        // A new deliberate navigation takes priority over an old stalled .onion page.
        // Each request still has an independent receipt and never replays a gesture.
        if(navigationReply!=null){
            finishNavigation(false,"TOR_NAVIGATION_REPLACED");
            if(web!=null)web.stopLoading();
        }
        long ticket=policy.generation();navigationStart=SystemClock.elapsedRealtime();pageLoadStarted=false;
        navigation=new JSONObject().put("state","running").put("request_id",request).put("requested_url",target).put("session_id",sessionId);navigationReply=cb;lastPageError="";
        receipts.put(request,new JSONObject().put("fingerprint",fingerprint).put("result",new JSONObject(navigation.toString())));persistReceipts();
        // Reuse a recent Tor proof to avoid a full network probe before every tap.
        // Fail closed: expired proof is revalidated through Tor before WebView can load.
        io.execute(()->{try{
            requireCircuit();
            final JSONObject proof=policy.proofAge(SystemClock.elapsedRealtime())<55000?null:TorHttp.diagnostic(httpPort);
            main.post(()->{
                if(ticket!=policy.generation()||navigationReply==null||navigation==null||!request.equals(navigation.optString("request_id")))return;
                if(proof!=null){
                    if(!policy.refresh(ticket,true,true,SystemClock.elapsedRealtime())){finishNavigation(false,"TOR_SESSION_CHANGED");return;}
                    diagnostic=proof;
                }
                if(!policy.usable(SystemClock.elapsedRealtime())||!proxyReady){finishNavigation(false,"TOR_PROOF_EXPIRED");return;}
                // Navigation MCP must reveal the actual Tor WebView, not leave the home dashboard on screen.
                // This only changes views inside the already-open isolated Tor Activity.
                if(screen!=null)screen.showBrowserForNavigation();
                web.getSettings().setBlockNetworkLoads(false);
                if(op.equals("back"))web.goBack();else if(op.equals("forward"))web.goForward();else if(op.equals("reload"))web.reload();else web.loadUrl(target);
            });
        }catch(Exception e){main.post(()->{
            if(navigationReply==null||navigation==null||!request.equals(navigation.optString("request_id")))return;
            // A failed diagnostic must not permit clear-network fallback.
            block(e.getMessage());finishNavigation(false,e.getMessage());
        });}});
        final long navigationTimeout=target.contains(".onion")?90000:45000;
        main.postDelayed(()->{if(navigationReply!=null&&navigation!=null&&request.equals(navigation.optString("request_id"))){if(web!=null)web.stopLoading();finishNavigation(false,"TOR_PAGE_LOAD_TIMEOUT");}},navigationTimeout);
    }
    void pageStarted(String url){if(navigationReply==null)return;try{if(pageLoadStarted||TorUrlPolicy.checked(url).equals(navigation.optString("requested_url")))pageLoadStarted=true;}catch(Exception e){pageFailed(e.getMessage());}}
    void pageFinished(String url,String title){
        if(navigationReply==null||!pageLoadStarted||"about:blank".equals(url))return;
        try{if(!policy.usable(SystemClock.elapsedRealtime())||!TorUrlPolicy.checked(url).equals(web.getUrl()))throw new Exception("TOR_PAGE_READBACK_UNCONFIRMED");lastUrl=url;lastTitle=shortTitle(title);finishNavigation(true,"");}catch(Exception e){finishNavigation(false,e.getMessage());}
    }
    void pageFailed(String reason){lastPageError=reason;finishNavigation(false,reason);}
    private void finishNavigation(boolean ok,String reason){
        if(navigationReply==null)return;Result cb=navigationReply;navigationReply=null;
        try{navigation.put("state",ok?"completed":"failed").put("url",lastUrl).put("title",lastTitle).put("error",reason).put("elapsed_ms",SystemClock.elapsedRealtime()-navigationStart).put("verified",ok?"webview_page_finished_and_url_readback":"unconfirmed");
            JSONObject record=receipts.getJSONObject(navigation.getString("request_id"));record.put("result",new JSONObject(navigation.toString()));persistReceipts();
            cb.done(ok,new JSONObject(navigation.toString()).put("tor_verified",policy.usable(SystemClock.elapsedRealtime())));
        }catch(Exception e){cb.done(false,TorBridge.error("TOR_NAVIGATION_RECEIPT_UNCONFIRMED"));}
    }
    private void persistReceipts()throws Exception{
        AtomicFile file=new AtomicFile(new File(app.getFilesDir(),"tor_isolated_receipts.json"));FileOutputStream out=null;
        try{out=file.startWrite();out.write(new JSONObject().put("session_id",sessionId).put("receipts",receipts).toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    private static String canonical(Object value)throws Exception{if(value instanceof JSONObject){JSONObject o=(JSONObject)value;ArrayList<String> keys=new ArrayList<>();for(Iterator<String> it=o.keys();it.hasNext();)keys.add(it.next());Collections.sort(keys);StringBuilder b=new StringBuilder("{");for(String k:keys)b.append(JSONObject.quote(k)).append(':').append(canonical(o.get(k))).append(',');return b.append('}').toString();}if(value instanceof JSONArray){StringBuilder b=new StringBuilder("[");JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)b.append(canonical(a.get(i))).append(',');return b.append(']').toString();}return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);}
    private static String shortTitle(String title){return title==null?"":title.substring(0,Math.min(512,title.length()));}
    private void read(JSONObject args,Result cb)throws Exception{
        if(!policy.usable(SystemClock.elapsedRealtime()))throw new Exception("TOR_NOT_CONNECTED");String url=TorUrlPolicy.checked(args.optString("url",web==null?lastUrl:java.util.Objects.toString(web.getUrl(),"")));long ticket=policy.generation();
        readerIo.execute(()->{try{requireCircuit();TorHttp.Reply r=TorHttp.get(url,httpPort,524288);if(ticket!=policy.generation()||!policy.usable(SystemClock.elapsedRealtime()))throw new Exception("TOR_SESSION_CHANGED_OR_PROOF_EXPIRED");String text=android.text.Html.fromHtml(r.body,android.text.Html.FROM_HTML_MODE_LEGACY).toString();
            JSONObject value=new JSONObject().put("state",r.code>=200&&r.code<400?"completed":"failed").put("url",r.url).put("http_status",r.code).put("response_sha256",r.sha256).put("redirects",r.redirects).put("text",text.substring(0,Math.min(16000,text.length()))).put("read_scope","fresh_http_response_through_tor_not_authenticated_webview_dom").put("session_id",sessionId).put("tor_verified",true);
            main.post(()->cb.done(r.code>=200&&r.code<400,value));
        }catch(Exception e){main.post(()->cb.done(false,TorBridge.error(e.getMessage())));}});
    }
    void block(String reason){policy.fail(policy.generation(),reason==null?"TOR_FAILED":reason);proxyReady=false;if(web!=null){web.stopLoading();web.getSettings().setBlockNetworkLoads(true);}try{if(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))ProxyController.getInstance().setProxyOverride(proxy(1),main::post,()->{});}catch(Exception ignored){}}
    private void reply(Result cb,boolean ok){try{JSONObject value=status();if(!ok)value.put("state","failed");cb.done(ok,value);}catch(Exception e){cb.done(false,TorBridge.error(e.getMessage()));}}
    private static final class ApplicationProcess {static boolean isTor(){return android.app.Application.getProcessName().endsWith(":tor");}}
}
