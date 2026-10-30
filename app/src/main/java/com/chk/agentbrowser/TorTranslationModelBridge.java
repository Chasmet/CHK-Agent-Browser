package com.chk.agentbrowser;

import android.content.*;
import android.os.*;

/** No navigation: the Tor activity asks a private main-process service to install
 * a public language model, while every Tor page stays in the isolated :tor process.
 * Messages carry no URLs, DOM, web contents or user secrets.
 */
final class TorTranslationModelBridge {
    interface Callback {void done(boolean ok,String detail);}
    private TorTranslationModelBridge(){}
    static void prepare(Context context,Callback cb){new Session(context.getApplicationContext(),cb,TorTranslationModelService.PREPARE_MODEL).start();}
    static void ping(Context context,Callback cb){new Session(context.getApplicationContext(),cb,TorTranslationModelService.PING).start();}

    private static final class Session implements ServiceConnection {
        private final Context app;
        private final Callback callback;
        private final int command;
        private final Handler main=new Handler(Looper.getMainLooper());
        private final Messenger receiver=new Messenger(new Handler(Looper.getMainLooper(),message->{
            Bundle data=message.getData();
            done(data.getBoolean("ok"),data.getString("detail","MODEL_IPC_INVALID"));
            return true;
        }));
        private boolean bound,finished;
        private final Runnable timeout=()->done(false,"MODEL_DOWNLOAD_TIMEOUT");

        Session(Context app,Callback callback,int command){
            this.app=app;this.callback=callback;this.command=command;
        }
        void start(){
            main.post(()->{
                if(finished)return;
                try{
                    // Binding is private: the target service is non-exported and
                    // Android starts it in CHK's ordinary (non-Tor) process.
                    boolean ok=app.bindService(new Intent(app,TorTranslationModelService.class),this,Context.BIND_AUTO_CREATE);
                    if(!ok){done(false,"MODEL_SERVICE_UNAVAILABLE");return;}
                    bound=true;
                    main.postDelayed(timeout,300000);
                }catch(Exception e){done(false,"MODEL_BIND_FAILED: "+e.getMessage());}
            });
        }
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            try{
                Message request=Message.obtain(null,command);
                request.replyTo=receiver;
                // No message data: this IPC cannot carry page content.
                new Messenger(binder).send(request);
            }catch(Exception e){done(false,"MODEL_IPC_SEND_FAILED: "+e.getMessage());}
        }
        @Override public void onServiceDisconnected(ComponentName name){done(false,"MODEL_SERVICE_DISCONNECTED");}
        @Override public void onBindingDied(ComponentName name){done(false,"MODEL_BINDING_DIED");}
        @Override public void onNullBinding(ComponentName name){done(false,"MODEL_NULL_BINDING");}
        private void done(boolean ok,String message){
            if(finished)return;
            finished=true;
            main.removeCallbacks(timeout);
            if(bound)try{app.unbindService(this);}catch(Exception ignored){}
            callback.done(ok,message);
        }
    }
}
