package it.sbixify.app;

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

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        result.sendResult(new ArrayList<MediaBrowser.MediaItem>());
    }
}
