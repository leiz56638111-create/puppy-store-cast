// SPDX-License-Identifier: GPL-3.0-only
package io.github.jqssun.airplay;

import android.content.Context;
import android.content.SharedPreferences;

/** No native or API24 dependency: also used by the API23 DLNA entry point. */
public final class ReceiverMode {
    private ReceiverMode() {}
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("receiver_mode", Context.MODE_PRIVATE); }
    public static boolean isAirPlay(Context context) { return prefs(context).getBoolean("airplay", false); }
    public static boolean wantsAirPlay(Context context) { return isAirPlay(context) && prefs(context).getBoolean("airplay_wanted", false); }
    public static void selectAirPlay(Context context) { prefs(context).edit().putBoolean("airplay", true).putBoolean("airplay_wanted", true).commit(); }
    public static void selectDlna(Context context) { prefs(context).edit().putBoolean("airplay", false).putBoolean("airplay_wanted", false).commit(); }
    public static void stopAirPlay(Context context) { prefs(context).edit().putBoolean("airplay_wanted", false).commit(); }
}
