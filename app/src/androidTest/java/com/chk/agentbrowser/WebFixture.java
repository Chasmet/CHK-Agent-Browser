package com.chk.agentbrowser;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** A real HTTPS navigation with local HTML, retaining the application's page lifecycle callbacks. */
final class WebFixture {
    private WebFixture(){}
    private static final class Client extends WebViewClient {
        final WebViewClient delegate;
        final String url;
        final byte[] html;
        Client(WebViewClient delegate,String url,String html){this.delegate=delegate;this.url=url;this.html=html.getBytes(StandardCharsets.UTF_8);}
        @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
            if(request.isForMainFrame()&&url.equals(request.getUrl().toString()))
                return new WebResourceResponse("text/html","UTF-8",200,"OK",
                    Collections.singletonMap("Cache-Control","no-store"),new ByteArrayInputStream(html));
            return delegate.shouldInterceptRequest(view,request);
        }
        @Override public void onPageStarted(WebView view,String url,Bitmap icon){delegate.onPageStarted(view,url,icon);}
        @Override public void onPageFinished(WebView view,String url){delegate.onPageFinished(view,url);}
        @Override public void onPageCommitVisible(WebView view,String url){delegate.onPageCommitVisible(view,url);}
        @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){delegate.onReceivedHttpError(view,request,response);}
        @Override public void onReceivedError(WebView view,WebResourceRequest request,android.webkit.WebResourceError error){delegate.onReceivedError(view,request,error);}
        @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return delegate.shouldOverrideUrlLoading(view,request);}
    }
    static void load(Instrumentation instrumentation,WebView web,String url,String html,String ready)throws Exception{
        String token=UUID.randomUUID().toString();
        String page=html+"<script>window.__chkFixturePage='"+token+"';</script>";
        instrumentation.runOnMainSync(()->{
            // The fixture initiates a new request directly, outside the address
            // bar/agent path. Reset the previous request's error in the same way.
            android.content.Context base=web.getContext();
            if(base instanceof android.content.MutableContextWrapper)base=((android.content.MutableContextWrapper)base).getBaseContext();
            if(base instanceof MainActivity)((MainActivity)base).clearPageError(web);
            WebViewClient original=web.getWebViewClient();
            if(original instanceof Client)original=((Client)original).delegate;
            web.stopLoading();web.setWebViewClient(new Client(original,url,page));web.loadUrl(url);
        });
        String last="";
        for(int attempt=0;attempt<80;attempt++){
            CountDownLatch done=new CountDownLatch(1);
            AtomicReference<String> address=new AtomicReference<>(""),state=new AtomicReference<>("");
            instrumentation.runOnMainSync(()->{
                address.set(web.getUrl());
                web.evaluateJavascript("document.readyState==='complete'&&window.__chkFixturePage==='"+token+"'&&("+ready+")",
                    value->{state.set(value);done.countDown();});
            });
            assertTrue("Fixture WebView evaluation timed out",done.await(5,TimeUnit.SECONDS));
            if(url.equals(address.get())&&"true".equals(state.get()))return;
            last="address="+address.get()+", ready="+state.get();Thread.sleep(100);
        }
        fail("HTTPS fixture did not commit: "+last);
    }
}
