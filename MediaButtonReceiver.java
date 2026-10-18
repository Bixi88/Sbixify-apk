package it.sbixify.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.KeyEvent;

/**
 * Riceve i tasti multimediali (anche dalle routine Samsung "Riproduci musica").
 * Se il servizio di riproduzione e' attivo gli inoltra il comando; altrimenti apre l'app.
 */
public class MediaButtonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }
        KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (ev == null || ev.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        String act = null;
        switch (ev.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                act = NativeHttpPlugin.MediaService.ACT_PLAY;
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                act = NativeHttpPlugin.MediaService.ACT_PAUSE;
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                act = NativeHttpPlugin.MediaService.ACT_NEXT;
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                act = NativeHttpPlugin.MediaService.ACT_PREV;
                break;
            default:
                return;
        }
        try {
            if (NativeHttpPlugin.MediaService.instance != null) {
                Intent i = new Intent(ctx, NativeHttpPlugin.MediaService.class);
                i.setAction(act);
                ctx.startService(i);
            } else if (NativeHttpPlugin.MediaService.ACT_PLAY.equals(act)) {
                // App chiusa: il servizio in primo piano (consentito dal tasto multimediale) riapre l'app e fa partire la musica
                Intent i = new Intent(ctx, NativeHttpPlugin.MediaService.class);
                i.setAction(NativeHttpPlugin.MediaService.ACT_COLD);
                if (Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(i);
                } else {
                    ctx.startService(i);
                }
            }
        } catch (Exception ignored) {
            // app in background: Android puo' rifiutare l'avvio
        }
    }
}
