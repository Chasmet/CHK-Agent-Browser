package com.chk.agentbrowser;

import android.content.*;
import android.os.*;

/**
 * A private local Messenger bridge for the Tor-only French reader.
 * URLs, form fields, DOM, cookies and Tor proxy configuration never cross IPC.
 * Only up to 12k characters of plain readable page text can reach the
 * in-app offline translation worker; no remote translation endpoint is used.
 */
final class TorOfflineTranslationClient {
    interface Availability {void done(boolean installed,String detail);}
    interface Progress {void update(String partial,int completed,int total);}
    interface Result {void done(boolean success,String translation);}
    private TorOfflineTranslationClient(){}

    static void isReady(Context context,Availability cb){
        new Session(context.getApplicationContext(),TorOfflineTranslationService.CHECK,null,
            cb,null,null).start();
    }
    static void translate(Context context,String text,Progress progress,Result cb){
        new Session(context.getApplicationContext(),TorOfflineTranslationService.TRANSLATE,
            text, null,progress,cb).start();
    }
    static void ping(Context context,Availability cb){
        new Session(context.getApplicationContext(),TorOfflineTranslationService.PING,
            null,cb,null,null).start();
    }

    private static final class Session implements ServiceConnection {
        private final Context app;
        private final int operation;
        private final String text;
        private final Availability availability;
        private final Progress progress;
        private final Result result;
        private final Handler main=new Handler(Looper.getMainLooper());
        private boolean bound,finished;
        private final Runnable timeout=()->done(false,"TRANSLATION_WORKER_TIMEOUT");
        private final Messenger receiver=new Messenger(new Handler(Looper.getMainLooper(),message->{
            if(finished)return true;
            Bundle data=message.getData();
            if(message.what==2){
                if(progress!=null)progress.update(data.getString("partial",""),
                    data.getInt("done"),data.getInt("total"));
            }else if(message.what==1){
                done(data.getBoolean("ok"),data.getString("detail","TRANSLATION_EMPTY_RESPONSE"));
            }
            return true;
        }));

        Session(Context app,int operation,String text,Availability availability,Progress progress,Result result){
            this.app=app;this.operation=operation;this.text=text;
            this.availability=availability;this.progress=progress;this.result=result;
        }
        void start(){
            main.post(()->{
                if(finished)return;
                try{
                    boolean ok=app.bindService(new Intent(app,TorOfflineTranslationService.class),
                        this,Context.BIND_AUTO_CREATE);
                    if(!ok){done(false,"TRANSLATION_SERVICE_UNAVAILABLE");return;}
                    bound=true;
                    main.postDelayed(timeout,180000);
                }catch(Exception e){done(false,"TRANSLATION_BIND_FAILED: "+e.getMessage());}
            });
        }
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            if(finished)return;
            try{
                Message request=Message.obtain(null,operation);
                request.replyTo=receiver;
                if(text!=null){
                    if(text.length()>TorOfflineTranslationService.MAX_TEXT){
                        done(false,"TRANSLATION_TEXT_TOO_LONG");return;
                    }
                    Bundle data=new Bundle();data.putString("text",text);request.setData(data);
                }
                new Messenger(binder).send(request);
            }catch(Exception e){done(false,"TRANSLATION_IPC_SEND_FAILED");}
        }
        @Override public void onServiceDisconnected(ComponentName name){
            done(false,"TRANSLATION_SERVICE_DISCONNECTED");
        }
        @Override public void onBindingDied(ComponentName name){
            done(false,"TRANSLATION_BINDING_DIED");
        }
        @Override public void onNullBinding(ComponentName name){
            done(false,"TRANSLATION_NULL_BINDING");
        }
        private void done(boolean ok,String detail){
            if(finished)return;
            finished=true;
            main.removeCallbacks(timeout);
            if(bound)try{app.unbindService(this);}catch(Exception ignored){}
            if(availability!=null)availability.done(ok,detail);
            if(result!=null)result.done(ok,detail);
        }
    }
}
