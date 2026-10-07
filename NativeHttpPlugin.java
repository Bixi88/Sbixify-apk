package it.sbixify.app;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
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
 */
@CapacitorPlugin(name = "NativeHttp")
public class NativeHttpPlugin extends Plugin {

    private static final long CHUNK = 1024L * 1024L;

    private ServerSocket serverSocket;
    private int proxyPort = 0;
    private String proxyToken = "";
    private volatile int lastStatus = 0;
    private volatile String lastError = "";

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
                    w.addFlags(WindowManager.LayoutParams.FLAG_DRAW_SYSTEM_BAR_BACKGROUNDS);
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
