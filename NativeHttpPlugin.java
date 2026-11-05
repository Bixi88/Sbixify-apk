package it.sbixify.app;

import android.Manifest;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
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
import java.io.File;
import java.io.FileOutputStream;
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
 *  - downloadUpdate(): scarica l'APK nuovo con il gestore download di sistema (solo su richiesta); a fine
 *                     download apre direttamente l'installazione (o manda una notifica che la apre).
 *  - getCutoutInsets(): ingombro del foro fotocamera a sinistra/destra (per la schermata testi).
 *  - setScreenMode(): orientamento orizzontale forzato + schermo sempre acceso (schermata testi).
 *  - takeSharedText(): testo ricevuto dal menu Condividi di Android (es. link di una playlist Spotify).
 *  - requestBatteryExemption(): esclude l'app dal risparmio batteria (musica in background).
 *  - exitApp():       chiusura completa (ferma notifica e servizio).
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
        // Riaperta dal tasto play dell'auto: dice alla pagina di far partire il brano
        long cold = MediaService.coldPlayAt;
        if (cold != 0 && android.os.SystemClock.elapsedRealtime() - cold < 60000L) {
            MediaService.coldPlayAt = 0;
            emit("coldplay", 0);
        }
        try {
            // Avvio da "Condividi" con l'app chiusa: l'intent iniziale contiene il testo condiviso.
            // Se l'app e' stata riaperta dalla lista delle app recenti, quell'intent e' vecchio: si ignora.
            Intent first = getActivity().getIntent();
            if (first != null && (first.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
                captureShare(first);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ condivisione da altre app

    private static volatile String sharedText = null;

    private static void captureShare(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            String t = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null && t.length() > 0) {
                sharedText = t;
            }
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getDataString() != null) {
            sharedText = intent.getDataString();
        }
    }

    /** App gia' aperta e riportata in primo piano da "Condividi" (launchMode singleTask). */
    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        captureShare(intent);
    }

    @PluginMethod
    public void takeSharedText(PluginCall call) {
        String t = sharedText;
        sharedText = null;
        JSObject res = new JSObject();
        res.put("text", t == null ? "" : t);
        call.resolve(res);
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

    /** Richiesta a YouTube di un blocco di byte (con gli header che servono a googlevideo). */
    private HttpURLConnection openUp(URL u, String ua, long from, long to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Range", "bytes=" + from + "-" + to);
        c.setRequestProperty("User-Agent", ua != null && ua.length() > 0 ? ua : "Mozilla/5.0");
        c.setRequestProperty("Origin", "https://music.youtube.com");
        c.setRequestProperty("Referer", "https://music.youtube.com/");
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
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
            long clientEnd = end;                       // fine richiesta dal client (-1 = fino alla fine del file)
            long firstEnd = (end < 0 || end > cap) ? cap : end;

            up = openUp(u, ua, start, firstEnd);

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

            // Dimensione totale del file (da "Content-Range: bytes a-b/TOTALE")
            long total = -1;
            if (status == 206 && contentRange != null) {
                int sl = contentRange.lastIndexOf('/');
                if (sl >= 0) {
                    try {
                        total = Long.parseLong(contentRange.substring(sl + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            // YouTube accetta solo blocchi da 1 MB, ma i player nativi (MediaPlayer, ExoPlayer) si aspettano che la
            // risposta copra TUTTO l'intervallo richiesto: se la chiudiamo dopo 1 MB credono che il brano sia finito
            // (di qui il salto al brano dopo quando si sposta la barra). Allora promettiamo l'intervallo intero e
            // scarichiamo i blocchi successivi da YouTube uno dopo l'altro, dentro la stessa risposta.
            long fullEnd = -1;
            if (total > 0) {
                fullEnd = total - 1;
                if (clientEnd >= 0 && clientEnd < fullEnd) {
                    fullEnd = clientEnd;
                }
            }
            boolean chain = status == 206 && total > 0 && fullEnd > firstEnd;

            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(status == 206 ? "206 Partial Content" : "200 OK").append("\r\n");
            h.append("Content-Type: ").append(contentType).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            if (chain) {
                h.append("Content-Range: bytes ").append(start).append("-").append(fullEnd).append("/").append(total).append("\r\n");
                h.append("Content-Length: ").append(fullEnd - start + 1).append("\r\n");
            } else {
                if (contentRange != null) {
                    h.append("Content-Range: ").append(contentRange).append("\r\n");
                }
                if (length >= 0) {
                    h.append("Content-Length: ").append(length).append("\r\n");
                }
            }
            h.append("Access-Control-Allow-Origin: *\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));

            InputStream in = up.getInputStream();
            byte[] buf = new byte[16384];
            int n;
            long sent = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                sent += n;
            }
            if (chain) {
                long next = start + sent;
                while (next <= fullEnd) {
                    try {
                        up.disconnect();
                    } catch (Exception ignored) {
                    }
                    long e2 = Math.min(fullEnd, next + CHUNK - 1);
                    up = openUp(u, ua, next, e2);
                    int st2 = up.getResponseCode();
                    lastStatus = st2;
                    if (st2 != 206) {
                        lastError = "HTTP " + st2;
                        break;
                    }
                    InputStream in2 = up.getInputStream();
                    long got = 0;
                    while ((n = in2.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        got += n;
                    }
                    if (got <= 0) {
                        break;
                    }
                    next += got;
                }
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

    // App in primo piano? (per decidere se aprire subito l'installazione o mandare una notifica)
    private static volatile boolean appVisible = false;

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        appVisible = true;
    }

    @Override
    protected void handleOnPause() {
        appVisible = false;
        super.handleOnPause();
    }

    @Override
    protected void handleOnDestroy() {
        try {
            if (updateReceiver != null) {
                getContext().unregisterReceiver(updateReceiver);
                updateReceiver = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        try {
            // La pagina (e con lei l'audio) non esiste piu': niente notifica "fantasma" che sembra suonare
            releaseExo();
            getContext().stopService(new Intent(getContext(), MediaService.class));
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

    /** "Appari sopra altre app": serve ad Android per lasciare riaprire Sbixify dal tasto play dell'auto. */
    @PluginMethod
    public void getOverlayStatus(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void requestOverlay(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    // =====================================================================================
    // Player audio NATIVO come in Echo Music: ExoPlayer + MediaSession di Media3.
    // L'audio non esce piu' dal WebView e lo stato che vede l'auto (play/pausa, posizione, durata, titolo)
    // lo genera Media3 direttamente dal player, senza codice nostro in mezzo.
    // La pagina web comanda il player tramite un finto <audio> (NativeAudioElement in index.html) e riceve
    // gli eventi (playing, pause, ended...) dal player: anche i comandi dell'auto passano da qui.
    // =====================================================================================
    static volatile NativeHttpPlugin audioInst;
    static androidx.media3.exoplayer.ExoPlayer exo;
    static androidx.media3.session.MediaSession m3;
    private static final android.os.Handler mpHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static int mpGen = 0;
    private static String mpUrl = "";
    private static String mpMediaId = "sbx";
    private static String mpMetaKey = "";
    private static boolean mpMetaSent = false;
    private static float mpVol = 1f;
    private static boolean mpMuted = false;
    private static final Runnable mpTick = new Runnable() {
        @Override
        public void run() {
            try {
                if (exo != null && exo.isPlaying()) {
                    emitAudio("tick", 0, 0);
                    mpHandler.postDelayed(this, 1000);
                }
            } catch (Exception ignored) {
            }
        }
    };

    /** Player esposto alla MediaSession: aggiunge "brano successivo / precedente" (li decide la pagina web). */
    static final class SbxPlayer extends androidx.media3.common.ForwardingPlayer {
        SbxPlayer(androidx.media3.common.Player p) {
            super(p);
        }

        @Override
        public androidx.media3.common.Player.Commands getAvailableCommands() {
            return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build();
        }

        @Override
        public boolean isCommandAvailable(int command) {
            if (command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                    || command == COMMAND_SEEK_TO_PREVIOUS || command == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
                return true;
            }
            return super.isCommandAvailable(command);
        }

        @Override
        public boolean hasNextMediaItem() {
            return true;
        }

        @Override
        public boolean hasPreviousMediaItem() {
            return true;
        }

        @Override
        public void seekToNext() {
            skipFromSession("next");
        }

        @Override
        public void seekToNextMediaItem() {
            skipFromSession("next");
        }

        @Override
        public void seekToPrevious() {
            skipFromSession("previous");
        }

        @Override
        public void seekToPreviousMediaItem() {
            skipFromSession("previous");
        }
    }

    private static void skipFromSession(String action) {
        NativeHttpPlugin p = instanceRef;
        if (p != null) {
            p.emit(action, 0);
        }
    }

    private static void emitAudio(String ev, int what, int extra) {
        try {
            NativeHttpPlugin p = instanceRef;
            if (p == null) {
                return;
            }
            JSObject o = new JSObject();
            o.put("ev", ev);
            o.put("gen", mpGen);
            double pos = 0;
            double dur = -1;
            boolean playing = false;
            if (exo != null) {
                long ps = exo.getCurrentPosition();
                if (ps >= 0) {
                    pos = ps / 1000.0;
                }
                long d = exo.getDuration();
                if (d > 0) {
                    dur = d / 1000.0;
                }
                playing = exo.isPlaying();
            }
            o.put("pos", pos);
            o.put("dur", dur);
            o.put("playing", playing);
            o.put("what", what);
            o.put("extra", extra);
            p.notifyListeners("nativeAudio", o);
        } catch (Exception ignored) {
        }
    }

    private static androidx.media3.common.MediaItem buildItem(String url, String mediaId, String title,
                                                              String artist, String album, String artwork) {
        androidx.media3.common.MediaMetadata.Builder mb = new androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title)
                .setDisplayTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album);
        if (artwork != null && (artwork.startsWith("https://") || artwork.startsWith("http://"))) {
            mb.setArtworkUri(Uri.parse(artwork));
        }
        return new androidx.media3.common.MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(url)
                .setMediaMetadata(mb.build())
                .build();
    }

    /** Crea ExoPlayer e la sessione Media3 (una volta sola). Solo sul thread principale. */
    private static void ensureExo(Context ctx) {
        if (exo != null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        androidx.media3.common.AudioAttributes aa = new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
        exo = new androidx.media3.exoplayer.ExoPlayer.Builder(app)
                .setAudioAttributes(aa, true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        exo.setVolume(mpMuted ? 0f : mpVol);
        exo.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    if (!mpMetaSent) {
                        mpMetaSent = true;
                        emitAudio("loadedmetadata", 0, 0);
                    }
                } else if (state == androidx.media3.common.Player.STATE_BUFFERING) {
                    if (exo != null && exo.getPlayWhenReady() && mpMetaSent) {
                        emitAudio("waiting", 0, 0);
                    }
                } else if (state == androidx.media3.common.Player.STATE_ENDED) {
                    mpHandler.removeCallbacks(mpTick);
                    emitAudio("ended", 0, 0);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                mpHandler.removeCallbacks(mpTick);
                if (isPlaying) {
                    emitAudio("playing", 0, 0);
                    mpHandler.postDelayed(mpTick, 1000);
                }
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (!playWhenReady) {
                    emitAudio("pause", 0, 0);
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                mpHandler.removeCallbacks(mpTick);
                emitAudio("error", error.errorCode, 0);
            }

            @Override
            public void onPositionDiscontinuity(androidx.media3.common.Player.PositionInfo oldPosition,
                                                androidx.media3.common.Player.PositionInfo newPosition, int reason) {
                if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) {
                    emitAudio("tick", 0, 0);
                }
            }
        });
        PendingIntent open = null;
        try {
            Intent li = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (li != null) {
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                open = PendingIntent.getActivity(app, 0, li, flags);
            }
        } catch (Exception ignored) {
        }
        androidx.media3.session.MediaSession.Builder sb =
                new androidx.media3.session.MediaSession.Builder(app, new SbxPlayer(exo)).setId("sbixify");
        if (open != null) {
            sb.setSessionActivity(open);
        }
        m3 = sb.build();
    }

    /** Libera player e sessione (chiusura app / stop). */
    static void releaseExo() {
        mpHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mpHandler.removeCallbacks(mpTick);
                    if (m3 != null) {
                        m3.release();
                        m3 = null;
                    }
                    if (exo != null) {
                        exo.release();
                        exo = null;
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** Pausa immediata dal tasto "pausa" della notifica (senza aspettare la pagina web). */
    static void pauseFromNotification() {
        mpHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (exo != null) {
                        exo.pause();
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** Titolo/artista/copertina cambiati: aggiorna il brano in corso senza interromperlo (stesso indirizzo audio). */
    private static void applyMeta(final MediaInfo info) {
        final String key = info.title + "|" + info.artist + "|" + info.album + "|" + info.artwork;
        mpHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (exo == null || exo.getMediaItemCount() == 0 || mpUrl.length() == 0) {
                        return;
                    }
                    if (key.equals(mpMetaKey) || info.title.length() == 0) {
                        return;
                    }
                    mpMetaKey = key;
                    exo.replaceMediaItem(0, buildItem(mpUrl, mpMediaId, info.title, info.artist, info.album, info.artwork));
                } catch (Exception ignored) {
                }
            }
        });
    }

    @PluginMethod
    public void audioLoad(final PluginCall call) {
        final String url = call.getString("url", "");
        final int gen = call.getInt("gen", 0);
        final long startMs = Math.round(call.getDouble("startSec", 0.0) * 1000.0);
        final boolean autoplay = Boolean.TRUE.equals(call.getBoolean("autoplay", false));
        final String title = call.getString("title", "");
        final String artist = call.getString("artist", "");
        final String album = call.getString("album", "");
        final String artwork = call.getString("artwork", "");
        final Context ctx = getContext();
        audioInst = this;
        mpHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureExo(ctx);
                    mpGen = gen;
                    mpUrl = url;
                    mpMediaId = "sbx-" + gen;
                    mpMetaKey = title + "|" + artist + "|" + album + "|" + artwork;
                    mpMetaSent = false;
                    mpHandler.removeCallbacks(mpTick);
                    exo.setMediaItem(buildItem(url, mpMediaId, title, artist, album, artwork), startMs);
                    exo.setPlayWhenReady(autoplay);
                    exo.prepare();
                } catch (Exception e) {
                    emitAudio("error", -2, 0);
                }
            }
        });
        call.resolve();
    }

    @PluginMethod
    public void audioPlay(PluginCall call) {
        mpHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (exo == null) {
                        return;
                    }
                    if (exo.getPlaybackState() == androidx.media3.common.Player.STATE_IDLE && exo.getMediaItemCount() > 0) {
                        exo.prepare();
                    }
                    exo.play();
                } catch (Exception ignored) {
                }
            }
        });
        call.resolve();
    }

    @PluginMethod
    public void audioPause(PluginCall call) {
        mpHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (exo != null) {
                        exo.pause();
                    }
                } catch (Exception ignored) {
                }
            }
        });
        call.resolve();
    }

    @PluginMethod
    public void audioSeek(PluginCall call) {
        final long ms = Math.round(call.getDouble("sec", 0.0) * 1000.0);
        mpHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (exo != null && exo.getMediaItemCount() > 0) {
                        exo.seekTo(ms);
                    }
                } catch (Exception ignored) {
                }
            }
        });
        call.resolve();
    }

    @PluginMethod
    public void audioVolume(PluginCall call) {
        final double v = call.getDouble("vol", 1.0);
        final boolean muted = Boolean.TRUE.equals(call.getBoolean("muted", false));
        mpHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mpVol = (float) Math.max(0.0, Math.min(1.0, v));
                    mpMuted = muted;
                    if (exo != null) {
                        exo.setVolume(mpMuted ? 0f : mpVol);
                    }
                } catch (Exception ignored) {
                }
            }
        });
        call.resolve();
    }

    /** "Stop" della pagina tra un brano e l'altro: solo pausa, cosi' la sessione resta col titolo (niente "buco" per l'auto). */
    @PluginMethod
    public void audioStop(PluginCall call) {
        mpHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (exo != null) {
                        exo.pause();
                    }
                } catch (Exception ignored) {
                }
            }
        });
        call.resolve();
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
            applyMeta(info);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void stopMedia(PluginCall call) {
        try {
            releaseExo();
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
        // Tasto play dall'auto / Bluetooth con l'app chiusa: parte il servizio e riapre l'app
        static final String ACT_COLD = "it.sbixify.app.COLD";
        static volatile long coldPlayAt = 0;
        // Con il telefono bloccato l'app riaperta dal play dell'auto deve potersi mostrare sopra il blocco schermo
        static volatile boolean showOverLock = false;

        static volatile MediaService instance;
        static volatile MediaInfo pending;

        // Cuffie/auricolari Bluetooth o cavo che si scollegano: Android manda BECOMING_NOISY e noi mettiamo in pausa
        private BroadcastReceiver noisyReceiver;
        private PowerManager.WakeLock wakeLock;
        private WifiManager.WifiLock wifiLock;
        private Bitmap art;
        private String artUrl = "";
        private boolean fg = false;
        private MediaInfo current = new MediaInfo();
        private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

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
            // Se le cuffie si spengono / si scollegano, la musica non deve passare all'altoparlante del telefono:
            // pausa. Usiamo "pause" (non "ext_pause") cosi' non vale la regola che ignora le pause subito
            // dopo un cambio brano. La pausa e' idempotente: se e' gia' ferma non succede nulla.
            noisyReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent != null && android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                        send("pause", 0);
                    }
                }
            };
            try {
                registerReceiver(noisyReceiver, new IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            } catch (Exception ignored) {
                noisyReceiver = null;
            }
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sbixify:playback");
            wakeLock.setReferenceCounted(false);
            // A schermo spento il Wi-Fi puo' andare in risparmio e far saltare lo streaming: lo teniamo attivo mentre suona
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sbixify:wifi");
                    wifiLock.setReferenceCounted(false);
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            String action = intent != null ? intent.getAction() : null;
            if (ACT_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACT_COLD.equals(action)) {
                // App chiusa e play premuto dall'auto: tiene vivo il processo e riapre l'app, che poi fa partire il brano
                coldPlayAt = android.os.SystemClock.elapsedRealtime();
                showOverLock = true;
                try {
                    startFg(buildNotification(current));
                } catch (Exception ignored) {
                }
                try {
                    Intent open = new Intent(this, MainActivity.class);
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(open);
                } catch (Exception ignored) {
                    // senza il permesso "Appari sopra altre app" Android puo' rifiutare
                }
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (MediaService.this) {
                            if (current != null && !current.playing && fg) {
                                stopForeground(false);
                                fg = false;
                            }
                        }
                    }
                }, 25000);
                return START_NOT_STICKY;
            }
            if (ACT_PLAY.equals(action)) {
                send("play", 0);
            } else if (ACT_PAUSE.equals(action)) {
                NativeHttpPlugin.pauseFromNotification();
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
                if (noisyReceiver != null) {
                    unregisterReceiver(noisyReceiver);
                    noisyReceiver = null;
                }
            } catch (Exception ignored) {
            }
            try {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
                if (wifiLock != null && wifiLock.isHeld()) {
                    wifiLock.release();
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
            ensureArt(info.artwork);
            Notification n = buildNotification(info);
            if (info.playing) {
                startFg(n);
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(6L * 60L * 60L * 1000L);
                }
                if (wifiLock != null && !wifiLock.isHeld()) {
                    wifiLock.acquire();
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
                if (wifiLock != null && wifiLock.isHeld()) {
                    wifiLock.release();
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
            androidx.core.app.NotificationCompat.Builder b =
                    new androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID);
            b.setSmallIcon(android.R.drawable.stat_sys_headset)
                    .setContentTitle(info.title.length() > 0 ? info.title : "Sbixify")
                    .setContentText(info.artist)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setOngoing(info.playing)
                    .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
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
            androidx.media3.session.MediaSession s3 = NativeHttpPlugin.m3;
            if (s3 != null) {
                b.setStyle(new androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(s3)
                        .setShowActionsInCompactView(0, 1, 2));
            } else {
                b.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2));
            }
            return b.build();
        }
    }

    // ------------------------------------------------------------------ versione, aggiornamenti, file

    /** Versione installata: versionCode = ora della compilazione (vedi workflow), usata dal controllo aggiornamenti. */
    @PluginMethod
    public void getAppInfo(PluginCall call) {
        try {
            PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : (long) pi.versionCode;
            JSObject res = new JSObject();
            res.put("versionName", pi.versionName == null ? "" : pi.versionName);
            res.put("versionCode", code);
            call.resolve(res);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /** Apre un indirizzo https nel browser di sistema (es. per scaricare il nuovo APK). */
    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url");
        if (url == null || !url.startsWith("https://")) {
            call.reject("indirizzo non valido");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Scarica il nuovo APK con il gestore download di Android (notifica con avanzamento, riprende da
     * solo se cade la rete). Parte solo quando l'utente preme "Scarica". A fine download Sbixify apre
     * direttamente l'installazione (se l'app e' aperta) oppure manda una sua notifica che la apre: la
     * notifica "di sistema" di fine download, per gli APK, su alcuni telefoni non faceva niente.
     * Serve il permesso REQUEST_INSTALL_PACKAGES (aggiunto da patch-manifest.js): la prima volta Android
     * chiede di consentire "Installa app sconosciute" a Sbixify, poi parte l'installazione.
     */
    @PluginMethod
    public void downloadUpdate(PluginCall call) {
        String url = call.getString("url");
        if (url == null || !url.startsWith("https://")) {
            call.reject("indirizzo non valido");
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
            try {
                // Via l'APK scaricato la volta scorsa (se Android non lo lascia cancellare, pazienza)
                new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "Sbixify-aggiornamento.apk").delete();
            } catch (Exception ignored) {
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("Sbixify - aggiornamento");
            req.setDescription("Download in corso: a fine download parte l'installazione");
            req.setMimeType("application/vnd.android.package-archive");
            // Solo l'avanzamento: a fine download la notifica la manda Sbixify, e il tocco installa davvero.
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Sbixify-aggiornamento.apk");
            long id = dm.enqueue(req);
            watchUpdateDownload(dm, id);
            JSObject res = new JSObject();
            res.put("id", id);
            call.resolve(res);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    private BroadcastReceiver updateReceiver;

    private void watchUpdateDownload(final DownloadManager dm, final long id) {
        final Context ctx = getContext();
        try {
            if (updateReceiver != null) {
                ctx.unregisterReceiver(updateReceiver);
            }
        } catch (Exception ignored) {
        }
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                long got = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (got != id) {
                    return;
                }
                try {
                    c.unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                updateReceiver = null;
                handleUpdateDownloaded(dm, id);
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(updateReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(updateReceiver, f);
        }
    }

    private void handleUpdateDownloaded(DownloadManager dm, long id) {
        Context ctx = getContext();
        boolean ok = false;
        Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c != null && c.moveToFirst()) {
                int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                ok = (st == DownloadManager.STATUS_SUCCESSFUL);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        if (!ok) {
            postUpdateNotification("Aggiornamento non scaricato", "Riprova a scaricarlo dalle impostazioni di Sbixify", null);
            return;
        }
        Uri apk = null;
        try {
            apk = dm.getUriForDownloadedFile(id);
        } catch (Exception ignored) {
        }
        if (apk == null) {
            postUpdateNotification("Aggiornamento scaricato", "Aprilo dalla cartella Download per installarlo", null);
            return;
        }
        Intent inst = new Intent(Intent.ACTION_VIEW);
        inst.setDataAndType(apk, "application/vnd.android.package-archive");
        inst.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (appVisible) {
            try {
                ctx.startActivity(inst);
                return;
            } catch (Exception ignored) {
            }
        }
        // App non in primo piano: Android non permette di aprire una schermata da sola, quindi notifica.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 77, inst, flags);
        postUpdateNotification("Aggiornamento pronto", "Tocca per installare Sbixify", pi);
    }

    private void postUpdateNotification(String title, String text, PendingIntent pi) {
        try {
            Context ctx = getContext();
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("sbixify_update", "Aggiornamenti", NotificationManager.IMPORTANCE_HIGH));
                b = new Notification.Builder(ctx, "sbixify_update");
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true);
            if (pi != null) {
                b.setContentIntent(pi);
            }
            nm.notify(7701, b.build());
        } catch (Exception ignored) {
        }
    }

    /** Salva un file di testo (il backup) nella cartella pubblica Download. */
    @PluginMethod
    public void saveBackupFile(PluginCall call) {
        String name = call.getString("filename", "Sbixify-backup.json");
        String content = call.getString("content", "");
        try {
            name = name.replaceAll("[^A-Za-z0-9._-]", "_");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                ContentResolver cr = getContext().getContentResolver();
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) {
                    throw new IOException("impossibile creare il file");
                }
                OutputStream os = cr.openOutputStream(uri);
                if (os == null) {
                    throw new IOException("impossibile scrivere il file");
                }
                os.write(bytes);
                os.close();
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                FileOutputStream fo = new FileOutputStream(new File(dir, name));
                fo.write(bytes);
                fo.close();
            }
            JSObject res = new JSObject();
            res.put("filename", name);
            call.resolve(res);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ risparmio batteria

    /** Android puo' fermare la musica a schermo spento o in background se l'app e' soggetta al risparmio batteria. */
    @PluginMethod
    public void getBatteryStatus(PluginCall call) {
        try {
            boolean ignoring = false;
            if (Build.VERSION.SDK_INT >= 23) {
                PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
                ignoring = pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
            }
            JSObject res = new JSObject();
            res.put("ignoring", ignoring);
            call.resolve(res);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /** Apre la richiesta di sistema per escludere Sbixify dal risparmio batteria (se non lo e' gia'). */
    @PluginMethod
    public void requestBatteryExemption(PluginCall call) {
        try {
            String pkg = getContext().getPackageName();
            boolean ignoring = false;
            if (Build.VERSION.SDK_INT >= 23) {
                PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
                ignoring = pm.isIgnoringBatteryOptimizations(pkg);
                if (!ignoring) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(i);
                }
            }
            JSObject res = new JSObject();
            res.put("ignoring", ignoring);
            call.resolve(res);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ uscita

    /** Chiude l'app: ferma il servizio (e quindi la notifica) e termina l'attivita'. */
    @PluginMethod
    public void exitApp(PluginCall call) {
        try {
            releaseExo();
            getContext().stopService(new Intent(getContext(), MediaService.class));
            call.resolve();
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    getActivity().finishAndRemoveTask();
                }
            });
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ schermata testi (modalita' auto)

    /**
     * landscape: blocca davvero lo schermo in orizzontale (anche con la rotazione automatica spenta,
     * perche' usa il sensore); false torna al comportamento normale.
     * keepOn: lo schermo non si spegne finche' e' attivo.
     */
    @PluginMethod
    public void setScreenMode(final PluginCall call) {
        final boolean landscape = Boolean.TRUE.equals(call.getBoolean("landscape", false));
        final boolean keepOn = Boolean.TRUE.equals(call.getBoolean("keepOn", false));
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    getActivity().setRequestedOrientation(landscape
                            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    keepAwakeLyrics = keepOn;
                    applyKeepAwake();
                    call.resolve();
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    // Schermo sempre acceso: due motivi indipendenti (schermata testi / interfaccia orizzontale).
    // Il flag resta attivo finche' almeno uno dei due lo richiede.
    private static boolean keepAwakeLyrics = false;
    private static boolean keepAwakeLandscape = false;

    private void applyKeepAwake() {
        Window w = getActivity().getWindow();
        if (keepAwakeLyrics || keepAwakeLandscape) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** Schermo sempre acceso mentre il telefono e' in orizzontale (lo chiama la pagina al cambio di orientamento). */
    @PluginMethod
    public void setKeepAwake(final PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    keepAwakeLandscape = on;
                    applyKeepAwake();
                    call.resolve();
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    /**
     * Di quanti dp il foro della fotocamera (o il notch) invade lo schermo da sinistra e da destra,
     * nell'orientamento attuale. Serve alla schermata testi per non scrivere sotto la fotocamera.
     */
    @PluginMethod
    public void getCutoutInsets(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    float density = getContext().getResources().getDisplayMetrics().density;
                    int left = 0;
                    int right = 0;
                    if (Build.VERSION.SDK_INT >= 28) {
                        WindowInsets wi = getActivity().getWindow().getDecorView().getRootWindowInsets();
                        if (wi != null) {
                            DisplayCutout dc = wi.getDisplayCutout();
                            if (dc != null) {
                                left = dc.getSafeInsetLeft();
                                right = dc.getSafeInsetRight();
                            }
                        }
                    }
                    JSObject res = new JSObject();
                    res.put("left", Math.round(left / density));
                    res.put("right", Math.round(right / density));
                    call.resolve(res);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
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
