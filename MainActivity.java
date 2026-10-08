package it.sbixify.app;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativeHttpPlugin.class);
        super.onCreate(savedInstanceState);

        // Tasto / gesto "indietro": la pagina gestisce da sola le sue schermate con la cronologia
        // (chiude testi, player, liste... fino alla home, poi mostra "Tocca di nuovo per uscire" e
        // alla seconda volta chiama exitApp). Qui ci limitiamo a girarle ogni "indietro".
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView wv = getBridge() != null ? getBridge().getWebView() : null;
                if (wv != null && wv.canGoBack()) {
                    wv.goBack();
                } else {
                    finishAndRemoveTask();
                }
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
