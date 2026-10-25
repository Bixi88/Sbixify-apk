package it.sbixify.app;

import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;


import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

/**
 * Sessione Spotify senza API ufficiale (che per le app nuove e' chiusa/limitata).
 *
 *  - getSession():  carica open.spotify.com in un WebView NASCOSTO e "ascolta"
 *                   le richieste che il web player fa da solo: ne ricava access token, client-token e
 *                   gli hash delle query GraphQL (libraryV3, fetchPlaylist, ...). Cosi' non serve
 *                   replicare a mano il calcolo TOTP, che Spotify cambia di continuo.
 */
@CapacitorPlugin(name = "SpotifySession")
public class SpotifySessionPlugin extends Plugin {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final String HOOK = "(function(){if(window.__sbxH)return;window.__sbxH=1;"
            + "var B=window.SbxBridge;if(!B)return;var F=window.fetch;"
            + "function hv(h,k){try{if(!h)return '';if(typeof h.get==='function')return h.get(k)||'';"
            + "for(var n in h){if(n.toLowerCase()===k)return h[n];}}catch(e){}return '';}"
            + "window.fetch=function(input,init){var url=(typeof input==='string')?input:((input&&input.url)||'');"
            + "var p=F.apply(this,arguments);"
            + "try{if(url.indexOf('/api/token')>-1){"
            + "p.then(function(r){return r.clone().text();}).then(function(t){B.token(t);}).catch(function(){});}"
            + "else if(url.indexOf('pathfinder')>-1){"
            + "var h=(init&&init.headers)||(input&&input.headers);var ct=hv(h,'client-token');"
            + "var op='',hash='',vars={};var body=init&&init.body;"
            + "if(typeof body==='string'){var j=JSON.parse(body);op=j.operationName;vars=j.variables||{};"
            + "hash=j.extensions&&j.extensions.persistedQuery&&j.extensions.persistedQuery.sha256Hash;}"
            + "else{var u=new URL(url,location.href);op=u.searchParams.get('operationName');"
            + "var vs=u.searchParams.get('variables');if(vs){vars=JSON.parse(vs);}"
            + "var e=u.searchParams.get('extensions');if(e){hash=JSON.parse(e).persistedQuery.sha256Hash;}}"
            + "if(op&&hash){B.gql(op,hash,ct||'',url.split('?')[0],JSON.stringify(vars));}}}catch(e){}"
            + "return p;};})();";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Stato condiviso (il WebView nascosto vive finche' vive il processo)
    private static WebView hidden;
    private static volatile String accessToken = "";
    private static volatile long tokenExp = 0;
    private static volatile boolean anonymous = true;
    private static volatile String clientToken = "";
    private static final JSONObject hashes = new JSONObject();

    public static class Bridge {
        @JavascriptInterface
        public void token(String json) {
            try {
                JSONObject o = new JSONObject(json);
                String t = o.optString("accessToken", "");
                if (t.length() > 0) {
                    accessToken = t;
                    tokenExp = o.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 50 * 60 * 1000L);
                    anonymous = o.optBoolean("isAnonymous", false);
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void gql(String op, String hash, String ct, String endpoint, String vars) {
            try {
                synchronized (hashes) {
                    JSONObject e = new JSONObject();
                    e.put("hash", hash);
                    e.put("endpoint", endpoint);
                    try {
                        e.put("vars", new JSONObject(vars));
                    } catch (Exception ignored2) {
                    }
                    hashes.put(op, e);
                }
                if (ct != null && ct.length() > 0) clientToken = ct;
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ sessione (token + hash)

    /** Parametri: url (pagina del web player da caricare), need (nome query di cui serve l'hash). */
    @PluginMethod
    public void getSession(final PluginCall call) {
        final boolean anon = true; // nessun login: token anonimo del web player
        final String url = call.getString("url", "https://open.spotify.com/collection/playlists");
        final String need = call.getString("need", "");
        final boolean force = Boolean.TRUE.equals(call.getBoolean("force", false));

        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (hidden == null) {
                        hidden = new WebView(getContext().getApplicationContext());
                        WebSettings s = hidden.getSettings();
                        s.setJavaScriptEnabled(true);
                        s.setDomStorageEnabled(true);
                        s.setUserAgentString(UA);
                        s.setUseWideViewPort(true);
                        s.setLoadWithOverviewMode(true);
                        CookieManager.getInstance().setAcceptThirdPartyCookies(hidden, true);
                        hidden.addJavascriptInterface(new Bridge(), "SbxBridge");
                        hidden.setWebViewClient(new WebViewClient() {
                            @Override
                            public void onPageStarted(WebView v, String u, android.graphics.Bitmap f) {
                                v.evaluateJavascript(HOOK, null);
                            }

                            @Override
                            public void onPageFinished(WebView v, String u) {
                                v.evaluateJavascript(HOOK, null);
                            }
                        });
                    }
                    boolean tokenOk = (anon || !anonymous) && accessToken.length() > 0 && tokenExp - System.currentTimeMillis() > 120000L;
                    boolean needOk = need.length() == 0 || hasHash(need);
                    if (!force && tokenOk && needOk) {
                        call.resolve(snapshot());
                        return;
                    }
                    if (force) {
                        accessToken = "";
                        anonymous = true;
                    }
                    hidden.loadUrl(url);
                    waitFor(call, need, anon, System.currentTimeMillis() + 25000L);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    private static boolean hasHash(String op) {
        synchronized (hashes) {
            return hashes.has(op);
        }
    }

    private void waitFor(final PluginCall call, final String need, final boolean anon, final long deadline) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean tokenOk = (anon || !anonymous) && accessToken.length() > 0;
                boolean needOk = need.length() == 0 || hasHash(need);
                if (tokenOk && needOk) {
                    call.resolve(snapshot());
                } else if (System.currentTimeMillis() > deadline) {
                    if (tokenOk) {
                        JSObject o = snapshot();
                        o.put("missing", need);
                        call.resolve(o);
                    } else {
                        call.reject("token_timeout");
                    }
                } else {
                    waitFor(call, need, anon, deadline);
                }
            }
        }, 400);
    }

    private static JSObject snapshot() {
        JSObject o = new JSObject();
        o.put("accessToken", accessToken);
        o.put("exp", tokenExp);
        o.put("clientToken", clientToken);
        try {
            synchronized (hashes) {
                o.put("hashes", new JSObject(hashes.toString()));
            }
        } catch (Exception ignored) {
        }
        return o;
    }
}
