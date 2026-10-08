package it.sbixify.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin nativo di Sbixify.
 *
 *  - request():       richieste HTTP fatte dal telefono (non dal WebView): niente CORS, header
 *                     liberi (User-Agent, Origin, Referer) e IP del telefono, come Echo Music.
 *  - startProxy():    mini-server su 127.0.0.1 che inoltra lo stream audio di googlevideo a blocchi
 *                     da 1 MB con gli header del client YouTube (il WebView non puo' mandarli).
 *  - proxyInfo():     ultimo stato HTTP del proxy (per la diagnostica).
 *  - setBarColors():  colore di barra di stato e barra di navigazione.
 *  - vibrate():       vibrazione (il WebView non supporta navigator.vibrate).
 *  - setMediaInfo():  notifica multimediale + servizio in primo piano (la musica continua a schermo
 *                     spento) con i pulsanti precedente / play-pausa / successivo.
 */
@CapacitorPlugin(name = "NativeHttp")
public class NativeHttpPlugin extends Plugin {

    private static final long CHUNK = 1024L * 1024L;

    private ServerSocket serverSocket;
    private int proxyPort = 0;
    private String proxyToken = "";
    private volatile int lastStatus = 0;
    private volatile String lastError = "";

    private static NativeHttpPlugin instanceRef;

    @Override
    public void load() {
        instanceRef = this;
    }

    /**
     * Pulsanti della notifica / blocco schermo -> JavaScript. Le azioni restano in coda e la pagina
     * le ritira con pollActions() (piu' semplice e affidabile di un listener nel WebView).
     */
    private final ConcurrentLinkedQueue<String[]> actionQueue = new ConcurrentLinkedQueue<String[]>();

    void emit(String action, double positionSec) {
        actionQueue.add(new String[]{action, String.valueOf(positionSec)});
        while (actionQueue.size() > 20) {
            actionQueue.poll();
        }
    }

    @PluginMethod
    public void pollActions(PluginCall call) {
        JSArray arr = new JSArray();
        String[] a;
        while ((a = actionQueue.poll()) != null) {
            JSObject o = new JSObject();
            o.put("action", a[0]);
            o.put("position", Double.parseDouble(a[1]));
            arr.put(o);
        }
        JSObject res = new JSObject();
        res.put("actions", arr);
        call.resolve(res);
    }

    // ------------------------------------------------------------------ request()

    @PluginMethod
    public void request(final PluginCall call) {
        final String urlStr = call.getString("url");
        if (urlStr == null) {
            call.reject("url mancante");
            return;
        }
        final String method = call.getString("method", "GET");
        final JSObject headers = call.getObject("headers", new JSObject());
        final String body = call.getString("body");
        final int timeout = call.getInt("timeout", 15000);

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setRequestMethod(method);
                    conn.setConnectTimeout(timeout);
                    conn.setReadTimeout(timeout);
                    conn.setInstanceFollowRedirects(true);

                    Iterator<String> keys = headers.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        conn.setRequestProperty(k, headers.getString(k));
                    }

                    if (body != null) {
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        conn.setDoOutput(true);
                        conn.setFixedLengthStreamingMode(bytes.length);
                        OutputStream os = conn.getOutputStream();
                        os.write(bytes);
                        os.close();
                    }

                    int status = conn.getResponseCode();
                    InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    String text = "";
                    if (is != null) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            out.write(buf, 0, n);
                        }
                        is.close();
                        text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                    }

                    JSObject res = new JSObject();
                    res.put("status", status);
                    res.put("data", text);
                    call.resolve(res);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()), e);
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }).start();
    }

    // ------------------------------------------------------------------ proxy audio

    @PluginMethod
    public void startProxy(PluginCall call) {
        try {
            synchronized (this) {
                if (serverSocket == null || serverSocket.isClosed()) {
                    serverSocket = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
                    proxyPort = serverSocket.getLocalPort();
                    proxyToken = UUID.randomUUID().toString().replace("-", "");
                    startAcceptLoop();
                }
            }
            JSObject res = new JSObject();
            res.put("port", proxyPort);
            res.put("token", proxyToken);
            call.resolve(res);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()), e);
        }
    }

    @PluginMethod
    public void proxyInfo(PluginCall call) {
        JSObject res = new JSObject();
        res.put("status", lastStatus);
        res.put("error", lastError);
        call.resolve(res);
    }

    private void startAcceptLoop() {
        final ServerSocket server = serverSocket;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!server.isClosed()) {
                    try {
                        final Socket client = server.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(client);
                            }
                        }).start();
                    } catch (IOException e) {
                        break;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void writeSimple(OutputStream out, int code, String text) throws IOException {
        String h = "HTTP/1.1 " + code + " " + text + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void handleClient(Socket client) {
        HttpURLConnection up = null;
        try {
            client.setSoTimeout(30000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = new BufferedOutputStream(client.getOutputStream());

            String reqLine = reader.readLine();
            if (reqLine == null) {
                return;
            }
            String rangeHeader = null;
            String line;
            while ((line = reader.readLine()) != null && line.length() > 0) {
                if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                    rangeHeader = line.substring(6).trim();
                }
            }

            String[] parts = reqLine.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0])) {
                writeSimple(out, 405, "Method Not Allowed");
                return;
            }

            String path = parts[1];
            int qi = path.indexOf('?');
            String query = qi >= 0 ? path.substring(qi + 1) : "";
            String token = null;
            String target = null;
            String ua = null;
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String k = kv.substring(0, eq);
                String v = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                if (k.equals("t")) {
                    token = v;
                } else if (k.equals("u")) {
                    target = v;
                } else if (k.equals("ua")) {
                    ua = v;
                }
            }

            // Solo con il token dell'app e solo verso googlevideo: nessun altro puo' usarlo come proxy
            if (token == null || !token.equals(proxyToken) || target == null) {
                writeSimple(out, 403, "Forbidden");
                return;
            }
            URL u = new URL(target);
            String host = u.getHost();
            if (!"https".equals(u.getProtocol()) || host == null || !host.endsWith(".googlevideo.com")) {
                writeSimple(out, 403, "Forbidden");
                return;
            }

            long start = 0;
            long end = -1;
            if (rangeHeader != null) {
                Matcher m = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(rangeHeader);
                if (m.find()) {
                    if (m.group(1).length() > 0) {
                        start = Long.parseLong(m.group(1));
                    }
                    if (m.group(2).length() > 0) {
                        end = Long.parseLong(m.group(2));
                    }
                }
            }
            long cap = start + CHUNK - 1;
            if (end < 0 || end > cap) {
                end = cap;
            }

            up = (HttpURLConnection) u.openConnection();
            up.setConnectTimeout(15000);
            up.setReadTimeout(20000);
            up.setRequestProperty("Range", "bytes=" + start + "-" + end);
            up.setRequestProperty("User-Agent", ua != null && ua.length() > 0 ? ua : "Mozilla/5.0");
            up.setRequestProperty("Origin", "https://music.youtube.com");
            up.setRequestProperty("Referer", "https://music.youtube.com/");
            up.setRequestProperty("Accept-Encoding", "identity");

            int status = up.getResponseCode();
            lastStatus = status;
            lastError = status >= 400 ? "HTTP " + status : "";
            if (status >= 400) {
                writeSimple(out, status, "Upstream Error");
                return;
            }

            String contentType = up.getContentType();
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            String contentRange = up.getHeaderField("Content-Range");
            int length = up.getContentLength();

            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(status == 206 ? "206 Partial Content" : "200 OK").append("\r\n");
            h.append("Content-Type: ").append(contentType).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            if (contentRange != null) {
                h.append("Content-Range: ").append(contentRange).append("\r\n");
            }
            if (length >= 0) {
                h.append("Content-Length: ").append(length).append("\r\n");
            }
            h.append("Access-Control-Allow-Origin: *\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));

            InputStream in = up.getInputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (Exception e) {
            // Il browser chiude spesso la connessione (salti, cambio brano): normale
            if (lastStatus == 0 || lastStatus >= 400) {
                lastError = String.valueOf(e.getMessage());
            }
        } finally {
            try {
                if (up != null) {
                    up.disconnect();
                }
            } catch (Exception ignored) {
            }
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void handleOnDestroy() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        super.handleOnDestroy();
    }

    // ------------------------------------------------------------------ vibrazione

    @PluginMethod
    public void vibrate(PluginCall call) {
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) {
                call.resolve();
                return;
            }

            JSArray pattern = call.getArray("pattern");
            if (pattern != null && pattern.length() > 0) {
                // Come navigator.vibrate([vibra, pausa, vibra...]); Android vuole una pausa iniziale
                long[] timings = new long[pattern.length() + 1];
                timings[0] = 0;
                for (int i = 0; i < pattern.length(); i++) {
                    timings[i + 1] = pattern.getLong(i);
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createWaveform(timings, -1));
                } else {
                    v.vibrate(timings, -1);
                }
            } else {
                long ms = call.getInt("ms", 20);
                if (ms < 1) {
                    ms = 1;
                }
                if (ms > 2000) {
                    ms = 2000;
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(ms);
                }
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ notifica multimediale

    static class MediaInfo {
        String title = "";
        String artist = "";
        String album = "";
        String artwork = "";
        boolean playing = false;
        long positionMs = 0;
        long durationMs = 0;
    }

    @PluginMethod
    public void setMediaInfo(PluginCall call) {
        try {
            MediaInfo info = new MediaInfo();
            info.title = call.getString("title", "");
            info.artist = call.getString("artist", "");
            info.album = call.getString("album", "");
            info.artwork = call.getString("artwork", "");
            info.playing = Boolean.TRUE.equals(call.getBoolean("playing", false));
            Double pos = call.getDouble("position", 0.0);
            Double dur = call.getDouble("duration", 0.0);
            info.positionMs = (long) (pos.doubleValue() * 1000.0);
            info.durationMs = (long) (dur.doubleValue() * 1000.0);
            MediaService.submit(getContext(), info);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void stopMedia(PluginCall call) {
        try {
            getContext().stopService(new Intent(getContext(), MediaService.class));
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && getContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                getActivity().requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4711);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Servizio in primo piano di tipo "mediaPlayback": tiene vivo il processo (e quindi l'audio del
     * WebView) a schermo spento e disegna la notifica multimediale con i controlli.
     */
    public static class MediaService extends Service {
        static final String CHANNEL_ID = "sbixify_playback";
        static final int NOTIF_ID = 4711;
        static final String ACT_PLAY = "it.sbixify.app.PLAY";
        static final String ACT_PAUSE = "it.sbixify.app.PAUSE";
        static final String ACT_NEXT = "it.sbixify.app.NEXT";
        static final String ACT_PREV = "it.sbixify.app.PREV";
        static final String ACT_STOP = "it.sbixify.app.STOP";

        static volatile MediaService instance;
        static volatile MediaInfo pending;

        private MediaSession session;
        private PowerManager.WakeLock wakeLock;
        private Bitmap art;
        private String artUrl = "";
        private boolean fg = false;
        private MediaInfo current = new MediaInfo();

        static void submit(Context ctx, MediaInfo info) {
            pending = info;
            MediaService s = instance;
            if (s != null) {
                s.apply(info);
                return;
            }
            if (!info.playing) {
                return; // niente notifica finche' la musica non parte
            }
            try {
                Intent i = new Intent(ctx, MediaService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(i);
                } else {
                    ctx.startService(i);
                }
            } catch (Exception e) {
                // app in background: Android non permette di avviare il servizio
            }
        }

        private static void send(String action, double pos) {
            NativeHttpPlugin p = NativeHttpPlugin.instanceRef;
            if (p != null) {
                p.emit(action, pos);
            }
        }

        @Override
        public void onCreate() {
            super.onCreate();
            instance = this;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Riproduzione", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                notificationManager().createNotificationChannel(ch);
            }
            session = new MediaSession(this, "Sbixify");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    send("play", 0);
                }

                @Override
                public void onPause() {
                    send("pause", 0);
                }

                @Override
                public void onSkipToNext() {
                    send("next", 0);
                }

                @Override
                public void onSkipToPrevious() {
                    send("previous", 0);
                }

                @Override
                public void onSeekTo(long pos) {
                    send("seek", pos / 1000.0);
                }
            });
            session.setActive(true);
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sbixify:playback");
            wakeLock.setReferenceCounted(false);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            String action = intent != null ? intent.getAction() : null;
            if (ACT_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACT_PLAY.equals(action)) {
                send("play", 0);
            } else if (ACT_PAUSE.equals(action)) {
                send("pause", 0);
            } else if (ACT_NEXT.equals(action)) {
                send("next", 0);
            } else if (ACT_PREV.equals(action)) {
                send("previous", 0);
            }
            MediaInfo p = pending;
            apply(p != null ? p : current);
            return START_NOT_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            stopSelf();
            super.onTaskRemoved(rootIntent);
        }

        @Override
        public void onDestroy() {
            instance = null;
            try {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
                if (session != null) {
                    session.release();
                }
            } catch (Exception ignored) {
            }
            super.onDestroy();
        }

        private NotificationManager notificationManager() {
            return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }

        synchronized void apply(MediaInfo info) {
            current = info;
            updateSession(info);
            ensureArt(info.artwork);
            Notification n = buildNotification(info);
            if (info.playing) {
                startFg(n);
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(6L * 60L * 60L * 1000L);
                }
            } else {
                if (!fg) {
                    startFg(n); // dopo startForegroundService Android pretende comunque startForeground
                }
                stopForeground(false);
                fg = false;
                notificationManager().notify(NOTIF_ID, n);
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        }

        private void startFg(Notification n) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
            fg = true;
        }

        private void updateSession(MediaInfo info) {
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, info.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, info.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, info.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, info.durationMs);
            if (art != null) {
                mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            }
            session.setMetadata(mb.build());

            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_SEEK_TO;
            PlaybackState ps = new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(info.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            info.positionMs, info.playing ? 1.0f : 0.0f)
                    .build();
            session.setPlaybackState(ps);
        }

        private void ensureArt(final String url) {
            if (url == null || url.length() == 0) {
                art = null;
                artUrl = "";
                return;
            }
            if (url.equals(artUrl)) {
                return;
            }
            artUrl = url;
            art = null;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(10000);
                        InputStream is = c.getInputStream();
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        is.close();
                        if (bmp != null && url.equals(artUrl)) {
                            art = bmp;
                            apply(current); // ridisegna la notifica con la copertina
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (c != null) {
                            c.disconnect();
                        }
                    }
                }
            }).start();
        }

        private PendingIntent servicePi(String action, int code) {
            Intent i = new Intent(this, MediaService.class);
            i.setAction(action);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            return PendingIntent.getService(this, code, i, flags);
        }

        private PendingIntent openAppPi() {
            Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            return PendingIntent.getActivity(this, 0, li, flags);
        }

        private Notification buildNotification(MediaInfo info) {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(this, CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
            }
            b.setSmallIcon(android.R.drawable.stat_sys_headset)
                    .setContentTitle(info.title.length() > 0 ? info.title : "Sbixify")
                    .setContentText(info.artist)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setOngoing(info.playing)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setContentIntent(openAppPi())
                    .setDeleteIntent(servicePi(ACT_STOP, 5));
            if (art != null) {
                b.setLargeIcon(art);
            }
            b.addAction(android.R.drawable.ic_media_previous, "Precedente", servicePi(ACT_PREV, 1));
            if (info.playing) {
                b.addAction(android.R.drawable.ic_media_pause, "Pausa", servicePi(ACT_PAUSE, 2));
            } else {
                b.addAction(android.R.drawable.ic_media_play, "Riproduci", servicePi(ACT_PLAY, 2));
            }
            b.addAction(android.R.drawable.ic_media_next, "Successivo", servicePi(ACT_NEXT, 3));
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
            return b.build();
        }
    }

    // ------------------------------------------------------------------ barre di sistema

    @PluginMethod
    public void setBarColors(final PluginCall call) {
        final String colorStr = call.getString("color");
        if (colorStr == null) {
            call.reject("color mancante");
            return;
        }
        final int color;
        try {
            color = Color.parseColor(colorStr);
        } catch (Exception e) {
            call.reject("colore non valido");
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Window w = getActivity().getWindow();
                    w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                    w.setStatusBarColor(color);
                    w.setNavigationBarColor(color);

                    double lum = (0.299 * Color.red(color) + 0.587 * Color.green(color)
                            + 0.114 * Color.blue(color)) / 255.0;
                    boolean light = lum > 0.6;
                    View decor = w.getDecorView();
                    int flags = decor.getSystemUiVisibility();
                    if (Build.VERSION.SDK_INT >= 23) {
                        flags = light ? (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                                : (flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                    }
                    if (Build.VERSION.SDK_INT >= 26) {
                        flags = light ? (flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                                : (flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                    }
                    decor.setSystemUiVisibility(flags);
                    call.resolve();
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }
}
