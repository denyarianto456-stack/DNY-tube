package com.dnytube.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;

public class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences prefs;
    private MediaPlayer player;
    private Equalizer eq;
    private BassBoost bass;
    private Virtualizer virt;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private final Handler handler = new Handler();
    private static final int PICK_AUDIO = 44, SAVE_PRESET = 45, LOAD_PRESET = 46;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (web != null && player != null) {
                try {
                    web.evaluateJavascript("window.nativePlayerTick&&window.nativePlayerTick(" +
                            player.getCurrentPosition() + "," + player.getDuration() + "," +
                            (player.isPlaying() ? "true" : "false") + ")", null);
                } catch (Exception ignored) {}
            }
            handler.postDelayed(this, 250);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("dnytube_state", MODE_PRIVATE);
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        setupWeb();
        handler.post(ticker);
    }

    private void setupWeb() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/dnytube_ui.html");
    }

    private void requestAudioFocus() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                AudioAttributes aa = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(aa).setOnAudioFocusChangeListener(f -> {
                            if (f <= 0 && player != null && player.isPlaying()) player.pause();
                        }).build();
                audioManager.requestAudioFocus(focusRequest);
            } else {
                audioManager.requestAudioFocus(f -> {
                    if (f <= 0 && player != null && player.isPlaying()) player.pause();
                }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Exception ignored) {}
    }

    private void abandonAudioFocus() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
            else audioManager.abandonAudioFocus(null);
        } catch (Exception ignored) {}
    }

    private void initFx() {
        if (player == null) return;
        try {
            releaseFx();
            int session = player.getAudioSessionId();
            eq = new Equalizer(0, session);
            bass = new BassBoost(0, session);
            virt = new Virtualizer(0, session);
            eq.setEnabled(true); bass.setEnabled(true); virt.setEnabled(true);
            applyFx();
        } catch (Exception ignored) {}
    }

    private int nearestNativeBand(int requested31Band) {
        if (eq == null) return 0;
        int count = eq.getNumberOfBands();
        if (count <= 1) return 0;
        float[] centers = new float[count];
        for (short i = 0; i < count; i++) centers[i] = eq.getCenterFreq(i);
        double[] hz = {20,25,31.5,40,50,63,80,100,125,160,200,250,315,400,500,630,800,1000,1250,1600,2000,2500,3100,4000,5000,6300,8000,10000,12000,16000,20000};
        double f = hz[Math.max(0, Math.min(30, requested31Band))];
        int best = 0; double err = Double.MAX_VALUE;
        for (int i=0;i<count;i++) { double e=Math.abs(Math.log(Math.max(1,centers[i])/f)); if(e<err){err=e;best=i;} }
        return best;
    }

    private void applyFx() {
        try {
            if (eq != null) {
                short[] range = eq.getBandLevelRange();
                int count = eq.getNumberOfBands();
                for (short nativeBand = 0; nativeBand < count; nativeBand++) {
                    int value = prefs.getInt("native_eq_" + nativeBand, 0);
                    value = Math.max(range[0], Math.min(range[1], value));
                    eq.setBandLevel(nativeBand, (short)value);
                }
            }
            if (bass != null) bass.setStrength((short)Math.max(0, Math.min(100, prefs.getInt("bass", 0))));
            if (virt != null) virt.setStrength((short)Math.max(0, Math.min(100, prefs.getInt("stereo", 0))));
        } catch (Exception ignored) {}
    }

    private void releaseFx() {
        try { if (eq != null) eq.release(); } catch (Exception ignored) {}
        try { if (bass != null) bass.release(); } catch (Exception ignored) {}
        try { if (virt != null) virt.release(); } catch (Exception ignored) {}
        eq = null; bass = null; virt = null;
    }

    private void toast(String message) { runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show()); }

    private void chooseAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("audio/*");
        startActivityForResult(i, PICK_AUDIO);
    }

    private void choosePreset() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json");
        startActivityForResult(i, LOAD_PRESET);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (request == PICK_AUDIO) play(uri);
            else if (request == SAVE_PRESET) write(uri, presetJson());
            else if (request == LOAD_PRESET) loadPreset(read(uri));
        } catch (Exception e) { toast("Operasi file gagal"); }
    }

    private void play(Uri uri) {
        try {
            if (player != null) { try { player.stop(); } catch (Exception ignored) {} player.release(); }
            releaseFx(); requestAudioFocus();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(this, uri);
            player.setOnPreparedListener(p -> { initFx(); p.start(); notifyPlayer(true); });
            player.setOnCompletionListener(p -> notifyPlayer(false));
            player.setOnErrorListener((p, what, extra) -> { toast("Format audio tidak dapat diputar"); return false; });
            player.prepareAsync();
        } catch (Exception e) { toast("Gagal memutar audio"); }
    }

    private void notifyPlayer(boolean playing) {
        if (web != null) web.evaluateJavascript("window.playerState&&window.playerState(" + playing + ")", null);
    }

    private String read(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void write(Uri uri, String text) throws Exception {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            out.write(text.getBytes("UTF-8")); out.flush();
        }
        toast("Preset tersimpan");
    }

    private void loadPreset(String text) throws Exception {
        JSONObject root = new JSONObject(text);
        JSONObject state = root.optJSONObject("state");
        if (state == null) throw new IllegalArgumentException();
        SharedPreferences.Editor e = prefs.edit(); Iterator<String> keys = state.keys();
        while (keys.hasNext()) {
            String k = keys.next(); Object v = state.get(k);
            if (v instanceof Integer) e.putInt(k, (Integer)v);
            else if (v instanceof Long) e.putLong(k, (Long)v);
            else if (v instanceof Boolean) e.putBoolean(k, (Boolean)v);
            else if (v instanceof Double) e.putString(k, String.valueOf(v));
            else e.putString(k, String.valueOf(v));
        }
        e.apply(); applyFx();
        if (web != null) web.evaluateJavascript("window.onPresetLoaded&&window.onPresetLoaded()", null);
        toast("Preset dimuat");
    }

    private String presetJson() {
        try {
            JSONObject root = new JSONObject(); root.put("name", "DNYTUBE preset"); root.put("version", 4); root.put("activation", false); root.put("state", new JSONObject(prefs.getAll())); return root.toString(2);
        } catch (Exception e) { return "{\"app\":\"DNYTUBE\",\"version\":4}"; }
    }

    private class Bridge {
        @JavascriptInterface public void updateEq(String channel, int band, int value) {
            prefs.edit().putInt(channel + "_eq_" + band, value).apply();
            if (eq != null && "chA".equals(channel)) {
                try {
                    int nativeBand = nearestNativeBand(band); short[] r = eq.getBandLevelRange();
                    short v = (short)Math.max(r[0], Math.min(r[1], value));
                    eq.setBandLevel((short)nativeBand, v); prefs.edit().putInt("native_eq_"+nativeBand, v).apply();
                } catch (Exception ignored) {}
            }
        }
        @JavascriptInterface public void updateParam(String key, double value) { prefs.edit().putString(key, Double.toString(value)).apply(); }
        @JavascriptInterface public void updateStringParam(String key, String value) { prefs.edit().putString(key, value).apply(); }
        @JavascriptInterface public void chooseLocalFile() { chooseAudio(); }
        @JavascriptInterface public void playLocal(String uri) { play(Uri.parse(uri)); }
        @JavascriptInterface public void pauseLocal() { if (player != null && player.isPlaying()) player.pause(); }
        @JavascriptInterface public void resumeLocal() { if (player != null && !player.isPlaying()) { requestAudioFocus(); player.start(); } }
        @JavascriptInterface public void stopLocal() { if (player != null) { try { player.stop(); } catch (Exception ignored) {} player.release(); player=null; } releaseFx(); abandonAudioFocus(); notifyPlayer(false); }
        @JavascriptInterface public void seekTo(int ms) { if (player != null) try { player.seekTo(Math.max(0, ms)); } catch (Exception ignored) {} }
        @JavascriptInterface public int duration() { try { return player == null ? 0 : player.getDuration(); } catch(Exception e){return 0;} }
        @JavascriptInterface public int position() { try { return player == null ? 0 : player.getCurrentPosition(); } catch(Exception e){return 0;} }
        @JavascriptInterface public void setBass(int value) { value=Math.max(0,Math.min(100,value)); prefs.edit().putInt("bass",value).apply(); try{if(bass!=null)bass.setStrength((short)value);}catch(Exception ignored){} }
        @JavascriptInterface public void setStereo(int value) { value=Math.max(0,Math.min(100,value)); prefs.edit().putInt("stereo",value).apply(); try{if(virt!=null)virt.setStrength((short)value);}catch(Exception ignored){} }
        @JavascriptInterface public void savePreset() { MainActivity.this.savePreset(); }
        @JavascriptInterface public void showSavePresetDialog() { MainActivity.this.savePreset(); }
        @JavascriptInterface public void showLoadPresetDialog() { choosePreset(); }
        @JavascriptInterface public void exportPreset() { MainActivity.this.savePreset(); }
        @JavascriptInterface public void loadPreset() { choosePreset(); }
        @JavascriptInterface public String presetJson() { return MainActivity.this.presetJson(); }
        @JavascriptInterface public void openYouTube() { web.loadUrl("https://m.youtube.com/"); }
        @JavascriptInterface public String getState() { try { JSONObject o=new JSONObject();o.put("app","DNYTUBE");o.put("activation",false);o.put("nativeAudioEffects",eq!=null);return o.toString(); }catch(Exception e){return "{}";} }
    }

    private void savePreset() {
        try {
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json"); i.putExtra(Intent.EXTRA_TITLE,"DNYTUBE-preset.json"); startActivityForResult(i,SAVE_PRESET);
        } catch(Exception e) { toast("Penyimpanan tidak tersedia"); }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker); if(player!=null){try{player.release();}catch(Exception ignored){}} releaseFx(); abandonAudioFocus(); if(web!=null)web.destroy(); super.onDestroy();
    }
    @Override public void onBackPressed() { if(web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
