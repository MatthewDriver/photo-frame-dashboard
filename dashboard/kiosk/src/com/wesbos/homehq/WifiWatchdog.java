package com.wesbos.homehq;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Rejoins Wi-Fi while the screen is off. Started by FullyBridge, because a screen that Browser Mod
 * switched off is what strands the frame: on the Allwinner A33 (XR819, Android 6), once the access
 * point drops the frame with the screen off, Android does no scan or reconnect until the screen comes
 * back on, and Home Assistant can't turn it on without a connection.
 *
 * Every CHECK_MS it looks at Wi-Fi. After one missed check (Android's own retry gets that long) it
 * asks for a scan and a reconnect; if that hasn't worked after TOGGLE_AFTER checks, it switches
 * Wi-Fi off and on. Does nothing while Wi-Fi is switched off, so the owner can still turn it off.
 */
class WifiWatchdog {
  static final String TAG = "HomeHQ";
  static final long CHECK_MS = 60000;
  static final int TOGGLE_AFTER = 5;
  static final long TOGGLE_GAP_MS = 3000;

  private final WifiManager wifi;
  private final ConnectivityManager net;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private int misses;

  private final Runnable check = new Runnable() {
    @Override
    public void run() {
      tick();
      handler.postDelayed(this, CHECK_MS);
    }
  };

  WifiWatchdog(Context context) {
    Context app = context.getApplicationContext();
    wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
    net = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
  }

  void start() {
    handler.removeCallbacks(check);
    handler.postDelayed(check, CHECK_MS);
  }

  void stop() {
    handler.removeCallbacksAndMessages(null);
  }

  @SuppressWarnings("deprecation")
  private void tick() {
    if (wifi == null || net == null || !wifi.isWifiEnabled()) {
      misses = 0;
      return;
    }
    NetworkInfo info = net.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
    if (info != null && info.isConnected()) {
      if (misses > 1) Log.i(TAG, "Wi-Fi watchdog: back on Wi-Fi");
      misses = 0;
      return;
    }
    misses++;
    if (misses == 1) return;
    if (misses % TOGGLE_AFTER == 0) {
      Log.w(TAG, "Wi-Fi watchdog: still offline, switching Wi-Fi off and on");
      wifi.setWifiEnabled(false);
      handler.postDelayed(() -> wifi.setWifiEnabled(true), TOGGLE_GAP_MS);
    } else {
      Log.w(TAG, "Wi-Fi watchdog: offline, asking for a scan and reconnect");
      wifi.startScan();
      wifi.reconnect();
    }
  }
}
