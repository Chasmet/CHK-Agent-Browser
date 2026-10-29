package com.chk.agentbrowser;

/** Deny onion main frames and subresources before WebView networking. No proxy changes. */
final class TorNetworkGuard {
    private TorNetworkGuard(){}
    static android.webkit.WebResourceResponse blockedOnion(String value){
        if(!BrowserPolicy.onionUrl(value))return null;
        return new android.webkit.WebResourceResponse("text/plain","UTF-8",403,"Tor required",java.util.Collections.emptyMap(),new java.io.ByteArrayInputStream("Domaine .onion bloqué dans CHK Web. Utiliser Tor Browser et vérifier sa connexion.".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
