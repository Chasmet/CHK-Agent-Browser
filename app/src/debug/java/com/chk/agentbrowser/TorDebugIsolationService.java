package com.chk.agentbrowser;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.webkit.CookieManager;
/** Debug-only, fixed .invalid test origin; cannot read a real user's website cookies. */
@androidx.annotation.RequiresApi(29)
public final class TorDebugIsolationService extends Service {
    private final Messenger messenger=new Messenger(new Handler(Looper.getMainLooper(),message->{
        try{Bundle input=message.getData();if(input.getBoolean("set_test_cookie"))CookieManager.getInstance().setCookie("https://chk-tor-isolation.invalid/","tor_only=tor-value",null);
            Bundle out=new Bundle();out.putString("cookie",CookieManager.getInstance().getCookie("https://chk-tor-isolation.invalid/"));out.putString("process",android.app.Application.getProcessName());out.putInt("pid",android.os.Process.myPid());Message answer=Message.obtain();answer.setData(out);message.replyTo.send(answer);
        }catch(Exception ignored){}return true;
    }));
    @Override public IBinder onBind(Intent intent){return messenger.getBinder();}
}
