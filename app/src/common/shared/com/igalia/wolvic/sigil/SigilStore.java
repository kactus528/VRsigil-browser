/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.sigil;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps the sigil image and its trigger settings on the headset.
 *
 * A sigil maker web page hands a new sigil to the browser by navigating to
 * {@code https://sigil.invalid/set?img=<base64url PNG>&mode=hold|toggle&size=<degrees>&back=<url>}.
 * The ".invalid" domain can never resolve, so in any other browser the link simply fails;
 * here {@link #handleUri} catches it before it reaches the network. Nothing leaves the device.
 */
public class SigilStore {
    private static final String LOGTAG = "SigilStore";
    private static final String HANDOFF_PREFIX = "https://sigil.invalid/set";
    /** The sigil maker page ships inside the app; typing "sigil.invalid" opens it. */
    public static final String MAKER_URL = "resource://android/assets/sigil/index.html";
    private static final String PREFS = "sigil_trigger";
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    public static final int MODE_HOLD = 0;
    public static final int MODE_TOGGLE = 1;

    public interface Listener {
        void onSigilChanged(@NonNull String path, int mode, float sizeDegrees);
    }

    private static SigilStore sInstance;
    private final Context mContext;
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();

    public static synchronized SigilStore get(@NonNull Context aContext) {
        if (sInstance == null) {
            sInstance = new SigilStore(aContext.getApplicationContext());
        }
        return sInstance;
    }

    private SigilStore(Context aContext) {
        mContext = aContext;
    }

    public static boolean isHandoffUri(@Nullable String aUri) {
        return aUri != null && aUri.startsWith(HANDOFF_PREFIX);
    }

    public static boolean isMakerShortcut(@Nullable String aUri) {
        return aUri != null && !isHandoffUri(aUri)
                && (aUri.startsWith("https://sigil.invalid") || aUri.startsWith("http://sigil.invalid"));
    }

    public void addListener(@NonNull Listener aListener) { mListeners.addIfAbsent(aListener); }
    public void removeListener(@NonNull Listener aListener) { mListeners.remove(aListener); }

    @NonNull
    public String getImagePath() {
        File dir = mContext.getExternalFilesDir(null);
        if (dir == null) {
            dir = mContext.getFilesDir();
        }
        return new File(dir, "sigil.png").getAbsolutePath();
    }

    public int getMode() { return prefs().getInt("mode", MODE_HOLD); }
    public float getSizeDegrees() { return prefs().getFloat("size", 40f); }

    /**
     * Saves the sigil carried by a hand-off link.
     * @return the page to return to afterwards (with #saved or #failed appended), or null.
     */
    @Nullable
    public String handleUri(@NonNull String aUri) {
        Uri uri = Uri.parse(aUri);
        boolean ok = false;
        try {
            String img = uri.getQueryParameter("img");
            if (img != null) {
                byte[] bytes = Base64.decode(img, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                // Only accept something that really decodes as an image.
                Bitmap check = bytes.length <= MAX_IMAGE_BYTES ? BitmapFactory.decodeByteArray(bytes, 0, bytes.length) : null;
                if (check != null) {
                    check.recycle();
                    File out = new File(getImagePath());
                    File tmp = new File(out.getPath() + ".tmp");
                    try (FileOutputStream stream = new FileOutputStream(tmp)) {
                        stream.write(bytes);
                    }
                    ok = tmp.renameTo(out);
                }
            }
            SharedPreferences.Editor editor = prefs().edit();
            String mode = uri.getQueryParameter("mode");
            if (mode != null) {
                editor.putInt("mode", "toggle".equals(mode) ? MODE_TOGGLE : MODE_HOLD);
            }
            String size = uri.getQueryParameter("size");
            if (size != null) {
                editor.putFloat("size", Math.max(5f, Math.min(100f, Float.parseFloat(size))));
            }
            editor.apply();
        } catch (Exception e) {
            Log.e(LOGTAG, "Could not save sigil: " + e);
            ok = false;
        }
        Log.d(LOGTAG, ok ? "Sigil saved to " + getImagePath() : "Sigil hand-off had no usable image");
        if (ok) {
            notifyChanged();
        }
        String back = uri.getQueryParameter("back");
        if (back == null || !(back.startsWith("https://") || back.startsWith("http://") || back.startsWith(MAKER_URL))) {
            return null;
        }
        int hash = back.indexOf('#');
        return (hash >= 0 ? back.substring(0, hash) : back) + (ok ? "#saved" : "#failed");
    }

    public void notifyChanged() {
        for (Listener listener : mListeners) {
            listener.onSigilChanged(getImagePath(), getMode(), getSizeDegrees());
        }
    }

    private SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
