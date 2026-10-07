# Sbixify - app Android

Stessa app di Sbixify, ma dentro un'app Android vera (Capacitor). La differenza
sta nella riproduzione: le richieste a YouTube partono dal telefono (plugin
nativo `NativeHttp`), come fa Echo Music, e lo stream audio va dritto in un
normale player audio. Se un brano non parte, l'app ripiega sull'IFrame di YouTube
e mostra un avviso con il motivo.

## Come ottenere l'APK (tutto da telefono)

1. Su GitHub crea un repository NUOVO, per esempio `Sbixify-App`
   (non toccare quello della PWA).
2. **Add file -> Upload files** e carica tutti questi file:
   `index.html`, `manifest.json`, `192x192.png`, `512x512.png`,
   `package.json`, `capacitor.config.json`, `debug.keystore`,
   `NativeHttpPlugin.java`, `MainActivity.java`, `README.md`.
3. **Add file -> Create new file**. Nel nome scrivi esattamente
   `.github/workflows/build-apk.yml` (le barre creano le cartelle) e incolla
   il contenuto del file `build-apk.yml` che trovi nello zip. Poi Commit.
4. Apri la scheda **Actions**: parte da sola "Compila APK" (5-10 minuti).
5. A fine lavoro vai in **Releases -> Sbixify - ultima build** e scarica
   `Sbixify.apk`. Aprilo e consenti l'installazione da questa fonte.
   Le build successive si installano sopra la precedente senza disinstallare.

Se la compilazione fallisce: Actions -> clicca il run rosso -> copia le ultime
righe dell'errore e mandale.

## Da provare

- Un brano: dopo quanti secondi parte?
- Se compare l'avviso "Stream diretto non riuscito: ...", leggi il motivo.
- Salto a metà brano, cambio brano, volume.
- Schermo spento e app in background: la musica continua?
- Controlli nella notifica / blocco schermo.
