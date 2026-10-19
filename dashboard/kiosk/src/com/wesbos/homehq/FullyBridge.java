package com.wesbos.homehq;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.HashMap;
import java.util.Map;

/**
 * Optional `window.fully` stand-in, so Home Assistant's Browser Mod can switch the frame's screen
 * and set its brightness as it does for Fully Kiosk Browser. Off unless the owner opts in:
 *   adb shell am start -n com.wesbos.homehq/.MainActivity --ez fully true
 * Only the calls Browser Mod makes are provided. Device details (MAC, serial, IMEI and so on) are
 * left out on purpose; Browser Mod reads them inside a try/catch.
 *
 * Screen off uses DevicePolicyManager.lockNow(), which needs this app as a device admin:
 *   adb shell am start -n com.wesbos.homehq/.MainActivity --ez screen_admin true   (tap Activate)
 * Without it, "off" is a black cover at minimum brightness and the backlight may stay lit.
 *
 * Every call is ignored unless the page comes from the same origin as the saved dashboard URL.
 */
class FullyBridge {
  static final String TAG = "HomeHQ";
  static final String NAME = "fully";

  private final Activity activity;
  private final WebView web;
  private final View cover;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final Map<String, String> events = new HashMap<>();
  private volatile String allowedOrigin;
  private volatile String pageOrigin;
  private volatile int brightness = -1; // -1: system default
  private volatile boolean covered;

  private final BroadcastReceiver screen = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      fire(Intent.ACTION_SCREEN_ON.equals(intent.getAction()) ? "screenOn" : "screenOff");
    }
  };

  FullyBridge(Activity activity, WebView web, View cover) {
    this.activity = activity;
    this.web = web;
    this.cover = cover;
    cover.setBackgroundColor(Color.BLACK);
    cover.setClickable(true);
    cover.setVisibility(View.GONE);
    IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
    f.addAction(Intent.ACTION_SCREEN_OFF);
    activity.registerReceiver(screen, f);
  }

  void release() {
    activity.unregisterReceiver(screen);
  }

  /** The dashboard URL decides which origin may use the bridge. */
  void setAllowedUrl(String url) {
    allowedOrigin = origin(url);
  }

  /** Called from onPageStarted, on the UI thread. */
  void setPageUrl(String url) {
    pageOrigin = origin(url);
    synchronized (events) {
      events.clear(); // a new page binds its own handlers
    }
  }

  static String origin(String url) {
    if (url == null) return null;
    Uri u = Uri.parse(url);
    if (u.getScheme() == null || u.getHost() == null) return null;
    return u.getScheme().toLowerCase() + "://" + u.getHost().toLowerCase() + ":" + u.getPort();
  }

  private boolean allowed() {
    boolean ok = allowedOrigin != null && allowedOrigin.equals(pageOrigin);
    if (!ok) Log.w(TAG, "window.fully call from " + pageOrigin + " ignored");
    return ok;
  }

  static ComponentName admin(Context c) {
    return new ComponentName(c, ScreenAdmin.class);
  }

  private boolean isAdmin() {
    DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
    return dpm != null && dpm.isAdminActive(admin(activity));
  }

  private void fire(String event) {
    String js;
    synchronized (events) {
      js = events.get(event);
    }
    if (js != null) web.evaluateJavascript(js, null);
  }

  @JavascriptInterface
  public void bind(String event, String js) {
    if (!allowed()) return;
    synchronized (events) {
      events.put(event, js);
    }
  }

  @JavascriptInterface
  public boolean getScreenOn() {
    PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
    return pm != null && pm.isInteractive() && !covered;
  }

  @JavascriptInterface
  @SuppressWarnings("deprecation")
  public void turnScreenOn() {
    if (!allowed()) return;
    ui.post(() -> {
      covered = false;
      cover.setVisibility(View.GONE);
      applyBrightness();
      PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
      if (pm != null && !pm.isInteractive()) {
        PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
            | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "HomeHQ:screenOn");
        wake.acquire(2000); // the window's FLAG_KEEP_SCREEN_ON keeps it on from here
      }
      Log.i(TAG, "Screen on (Browser Mod)");
      fire("screenOn");
    });
  }

  @JavascriptInterface
  public void turnScreenOff() {
    if (!allowed()) return;
    ui.post(() -> {
      if (isAdmin()) {
        DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        Log.i(TAG, "Screen off (Browser Mod): lockNow");
        dpm.lockNow(); // screenOff fires from the broadcast
      } else {
        Log.i(TAG, "Screen off (Browser Mod): no device admin, black cover");
        covered = true;
        cover.setVisibility(View.VISIBLE);
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF);
        fire("screenOff");
      }
    });
  }

  @JavascriptInterface
  public void stopScreensaver() {
  }

  /** 0-255, as in Fully. */
  @JavascriptInterface
  public int getScreenBrightness() {
    if (brightness >= 0) return brightness;
    try {
      return android.provider.Settings.System.getInt(activity.getContentResolver(),
          android.provider.Settings.System.SCREEN_BRIGHTNESS);
    } catch (android.provider.Settings.SettingNotFoundException e) {
      return 255;
    }
  }

  @JavascriptInterface
  public void setScreenBrightness(float value) {
    if (!allowed()) return;
    brightness = Math.max(0, Math.min(255, Math.round(value)));
    ui.post(() -> {
      if (!covered) applyBrightness();
    });
  }

  private void applyBrightness() {
    setWindowBrightness(brightness < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        : Math.max(1, brightness) / 255f);
  }

  private void setWindowBrightness(float v) {
    WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
    lp.screenBrightness = v;
    activity.getWindow().setAttributes(lp);
  }

  // Placeholders Browser Mod may call. Empty values make it fall back to its own behaviour.
  @JavascriptInterface
  public String getDeviceId() {
    return "";
  }

  @JavascriptInterface
  public String getCamshotJpgBase64() {
    return "";
  }

  @JavascriptInterface
  public boolean isPlugged() {
    return true;
  }
}
