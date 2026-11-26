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
        void onSigilChanged(@NonNull SigilStore store);
        /** The Sigil Maker asked to detect a button combo from the controllers. */
        default void onComboLearnRequested() {}
    }

    private static final String LEARN_PREFIX = "https://sigil.invalid/learn";
    @Nullable private String mPendingLearnBack;

    // Controller button bits, matching ControllerDelegate::Button in the native code.
    private static final int BUTTON_SQUEEZE = 1 << 1;
    private static final int BUTTON_A = 1 << 4;
    private static final int BUTTON_B = 1 << 5;
    private static final int BUTTON_X = 1 << 6;
    private static final int BUTTON_Y = 1 << 7;

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

    public static boolean isLearnUri(@Nullable String aUri) {
        return aUri != null && aUri.startsWith(LEARN_PREFIX);
    }

    /** Starts combo detection; the result goes back to the page given in "back". */
    public void handleLearnUri(@NonNull String aUri) {
        String back = Uri.parse(aUri).getQueryParameter("back");
        if (back == null || !(back.startsWith("https://") || back.startsWith("http://") || back.startsWith(MAKER_URL))) {
            back = MAKER_URL;
        }
        int hash = back.indexOf('#');
        mPendingLearnBack = hash >= 0 ? back.substring(0, hash) : back;
        for (Listener listener : mListeners) {
            listener.onComboLearnRequested();
        }
    }

    /**
     * @return the page to return to with the detected buttons in its hash
     * (e.g. "#combo=A,B" or "#combo=none"), or null if nothing was waiting.
     */
    @Nullable
    public String takeLearnResult(int aLeftMask, int aRightMask) {
        String back = mPendingLearnBack;
        mPendingLearnBack = null;
        if (back == null) {
            return null;
        }
        StringBuilder tokens = new StringBuilder();
        if ((aRightMask & BUTTON_A) != 0) tokens.append("A,");
        if ((aRightMask & BUTTON_B) != 0) tokens.append("B,");
        if ((aRightMask & BUTTON_SQUEEZE) != 0) tokens.append("RG,");
        if ((aLeftMask & BUTTON_X) != 0) tokens.append("X,");
        if ((aLeftMask & BUTTON_Y) != 0) tokens.append("Y,");
        if ((aLeftMask & BUTTON_SQUEEZE) != 0) tokens.append("LG,");
        String list = tokens.length() > 0 ? tokens.substring(0, tokens.length() - 1) : "none";
        return back + "#combo=" + list;
    }

    public static boolean isMakerShortcut(@Nullable String aUri) {
        return aUri != null && !isHandoffUri(aUri) && !isLearnUri(aUri)
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
    /** Trigger buttons per hand. Default: A+B on the right controller. */
    public int getComboLeft() { return prefs().getInt("comboLeft", 0); }
    public int getComboRight() { return prefs().getInt("comboRight", BUTTON_A | BUTTON_B); }
    /** Emergency-dismiss buttons per hand. Default: X on the left controller. */
    public int getDismissLeft() { return prefs().getInt("dismissLeft", BUTTON_X); }
    public int getDismissRight() { return prefs().getInt("dismissRight", 0); }
    public int getSoundMode() { return prefs().getInt("sound", SigilTone.SOUND_OFF); }
    public float getCarrierHz() { return prefs().getFloat("carrier", 200f); }
    public float getBeatHz() { return prefs().getFloat("beat", 6f); }
    public float getVolume() { return prefs().getFloat("volume", 0.4f); }
    public float getFadeInSeconds() { return prefs().getFloat("fade", 3f); }
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
            // combo=A,B  dismiss=X   (A, B, RG = right hand; X, Y, LG = left hand; G = grip)
            String combo = uri.getQueryParameter("combo");
            if (combo != null) {
                int[] masks = parseButtons(combo);
                if ((masks[0] | masks[1]) != 0) {
                    editor.putInt("comboLeft", masks[0]).putInt("comboRight", masks[1]);
                }
            }
            String dismiss = uri.getQueryParameter("dismiss");
            if (dismiss != null) {
                int[] masks = parseButtons(dismiss);
                editor.putInt("dismissLeft", masks[0]).putInt("dismissRight", masks[1]);
            }
            String sound = uri.getQueryParameter("sound");
            if (sound != null) {
                int soundMode = SigilTone.SOUND_OFF;
                switch (sound) {
                    case "binaural": soundMode = SigilTone.SOUND_BINAURAL; break;
                    case "isochronic": soundMode = SigilTone.SOUND_ISOCHRONIC; break;
                    case "both": soundMode = SigilTone.SOUND_BOTH; break;
                }
                editor.putInt("sound", soundMode);
            }
            putFloat(editor, uri, "carrier", 20f, 2000f);
            putFloat(editor, uri, "beat", 0.1f, 100f);
            putFloat(editor, uri, "volume", 0f, 1f);
            putFloat(editor, uri, "fade", 0f, 60f);
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

    /** @return {leftMask, rightMask} */
    private static int[] parseButtons(@NonNull String aList) {
        int left = 0, right = 0;
        for (String token : aList.toUpperCase().split(",")) {
            switch (token.trim()) {
                case "A": right |= BUTTON_A; break;
                case "B": right |= BUTTON_B; break;
                case "RG": right |= BUTTON_SQUEEZE; break;
                case "X": left |= BUTTON_X; break;
                case "Y": left |= BUTTON_Y; break;
                case "LG": left |= BUTTON_SQUEEZE; break;
            }
        }
        return new int[] { left, right };
    }

    public void notifyChanged() {
        for (Listener listener : mListeners) {
            listener.onSigilChanged(this);
        }
    }

    private static void putFloat(SharedPreferences.Editor aEditor, Uri aUri, String aKey, float aMin, float aMax) {
        String value = aUri.getQueryParameter(aKey);
        if (value == null) {
            return;
        }
        try {
            aEditor.putFloat(aKey, Math.max(aMin, Math.min(aMax, Float.parseFloat(value))));
        } catch (NumberFormatException ignored) {
        }
    }

    private SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
