package it.sbixify.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean inBackground = false;
    private boolean keepAliveRunning = false;

    /**
     * Mentre l'app e' in background e sta suonando, tiene il WebView "vivo": Android lo dichiara non
     * visibile (schermo spento / app in secondo piano) e la pagina rischia di essere sospesa, con l'audio
     * che si ferma o che non passa al brano successivo. Lo ripetiamo ogni secondo e mezzo solo se il
     * servizio di riproduzione e' attivo.
     */
    private final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            if (!inBackground) {
                keepAliveRunning = false;
                return;
            }
            try {
                WebView wv = getBridge() != null ? getBridge().getWebView() : null;
                if (wv != null && NativeHttpPlugin.MediaService.instance != null) {
                    wv.dispatchWindowVisibilityChanged(View.VISIBLE);
                    wv.onResume();
                    wv.resumeTimers();
                }
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativeHttpPlugin.class);
        super.onCreate(savedInstanceState);

        // Il processo che disegna la pagina (e riproduce l'audio) resta ad alta priorita' anche quando
        // l'app non e' visibile: meno rischio che Android lo fermi o lo "congeli" a schermo spento.
        try {
            WebView wv = getBridge() != null ? getBridge().getWebView() : null;
            if (wv != null && Build.VERSION.SDK_INT >= 26) {
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            }
        } catch (Exception ignored) {
        }

        // Tasto / gesto "indietro": la logica (chiudere testi, player, liste... fino alla home, poi il
        // popup "Tocca di nuovo per uscire" e alla seconda volta l'uscita) vive nella pagina, che la
        // espone come window.__sbxBackPressed. Qui la richiamiamo direttamente: niente dipendenza dalla
        // cronologia del WebView. Se la pagina non e' ancora pronta, si esce.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView wv = getBridge() != null ? getBridge().getWebView() : null;
                if (wv == null) {
                    finishAndRemoveTask();
                    return;
                }
                wv.evaluateJavascript(
                        "(function(){return window.__sbxBackPressed ? window.__sbxBackPressed() : 'none';})()",
                        new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                if (value != null && value.contains("none")) {
                                    finishAndRemoveTask();
                                }
                            }
                        });
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        inBackground = true;
        if (!keepAliveRunning) {
            keepAliveRunning = true;
            handler.postDelayed(keepAlive, 300);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        inBackground = false;
    }

    @Override
    public void onDestroy() {
        inBackground = false;
        handler.removeCallbacks(keepAlive);
        try {
            // Se l'attivita' viene distrutta l'audio si ferma: la notifica non deve restare
            stopService(new Intent(this, NativeHttpPlugin.MediaService.class));
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
