package it.sbixify.app;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativeHttpPlugin.class);
        super.onCreate(savedInstanceState);

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
    public void onDestroy() {
        try {
            // Se l'attivita' viene distrutta l'audio si ferma: la notifica non deve restare
            stopService(new Intent(this, NativeHttpPlugin.MediaService.class));
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
