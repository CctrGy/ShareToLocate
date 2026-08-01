package com.zoffcc.applications.trifa;

import android.util.Log;
import java.nio.charset.StandardCharsets;

/** Utility callbacks required by the native toxcore logging and UTF-8 boundary. */
public final class TrifaToxService {
    private TrifaToxService() {}
    public static void logger(int level, String message) {
        if (message != null) Log.d("ShareToLocate/Tox", message);
    }
    public static String safe_string(byte[] value) {
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }
}
