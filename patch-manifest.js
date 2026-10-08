// Eseguito automaticamente da "npx cap sync" (vedi package.json, "capacitor:sync:after").
// Aggiunge ad AndroidManifest.xml quello che serve a Sbixify: traffico verso 127.0.0.1 (proxy audio
// locale), vibrazione, servizio di riproduzione in primo piano e permessi collegati.
const fs = require('fs');

const p = 'android/app/src/main/AndroidManifest.xml';
if (!fs.existsSync(p)) process.exit(0);

let x = fs.readFileSync(p, 'utf8');
let changed = false;

if (!x.includes('usesCleartextTraffic')) {
  x = x.replace('<application', () => '<application android:usesCleartextTraffic="true"');
  changed = true;
}

const perms = ['VIBRATE', 'WAKE_LOCK', 'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_MEDIA_PLAYBACK', 'POST_NOTIFICATIONS'];
for (const perm of perms) {
  if (!x.includes('android.permission.' + perm + '"')) {
    x = x.replace('<application', () => '<uses-permission android:name="android.permission.' + perm + '" />\n    <application');
    changed = true;
  }
}

if (!x.includes('NativeHttpPlugin$MediaService')) {
  x = x.replace('</application>', () =>
    '    <service android:name="it.sbixify.app.NativeHttpPlugin$MediaService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n    </application>');
  changed = true;
}

if (changed) {
  fs.writeFileSync(p, x);
  console.log('AndroidManifest.xml aggiornato');
}
