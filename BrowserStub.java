package it.sbixify.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import android.media.browse.MediaBrowser;

import java.util.ArrayList;
import java.util.List;

/**
 * Servizio "vuoto" che dichiara Sbixify come app musicale (MediaBrowserService):
 * serve solo perche' Android / Samsung la elenchino tra i player (routine, auto, assistenti).
 */
public class BrowserStub extends MediaBrowserService {
    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot("sbixify_root", null);
    }

    /**
     * Con l'app chiusa, Media3 manda il tasto multimediale dell'auto/cuffie a questo servizio (e' l'unico dichiarato
     * come "app musicale"). Android pretende subito una notifica in primo piano; poi il vecchio ricevitore fa
     * quello che faceva prima: riapre l'app e fa partire la musica.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null && Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
                if (Build.VERSION.SDK_INT >= 26) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    NotificationChannel ch = new NotificationChannel("sbixify_playback", "Riproduzione", NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                    Notification n = new Notification.Builder(this, "sbixify_playback")
                            .setSmallIcon(android.R.drawable.stat_sys_headset)
                            .setContentTitle("Sbixify")
                            .build();
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(4712, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                    } else {
                        startForeground(4712, n);
                    }
                }
                new MediaButtonReceiver().onReceive(this, intent);
                stopSelf();
            }
        } catch (Exception ignored) {
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        result.sendResult(new ArrayList<MediaBrowser.MediaItem>());
    }
}
