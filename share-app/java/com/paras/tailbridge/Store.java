// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.content.Context;
import android.os.Environment;

import java.io.File;

/**
 * The small amount of state shared by the app, its providers and the module.
 *
 * <p>Only the receive folder lives here for now. It has to be readable from
 * classes the activity does not own -- the document providers run in the same
 * process but are created by the framework before any screen exists, and the
 * notification receiver may start the process on its own -- so a plain
 * SharedPreferences file is the one place all of them can meet.
 *
 * <p>The receiver script gets the same path through
 * {@code /data/adb/tailscale/taildrop.dir}, written when the folder changes.
 * The default stays in settings.sh, so a phone that never opens this screen
 * behaves exactly as before.
 */
final class Store {

	private static final String PREFS = "tailbridge";
	private static final String KEY_DIR = "receive_dir";
	private static final String KEY_URI = "receive_uri";

	private Store() {}

	static File receiveDir(Context c) {
		String path = prefs(c).getString(KEY_DIR, "");
		if (path != null && path.startsWith("/")) {
			return new File(path);
		}
		return defaultDir();
	}

	static File defaultDir() {
		File downloads = Environment.getExternalStoragePublicDirectory(
				Environment.DIRECTORY_DOWNLOADS);
		return new File(downloads, "Tailscale");
	}

	static String receivePath(Context c) {
		return receiveDir(c).getAbsolutePath();
	}

	/** "Download/Tailscale" or "SD card/Photos", not the full storage path. */
	static String receiveLabel(Context c) {
		String path = receivePath(c);
		String primary = Environment.getExternalStorageDirectory().getAbsolutePath();
		if (path.startsWith(primary + "/")) {
			return path.substring(primary.length() + 1);
		}
		if (path.startsWith("/storage/")) {
			String rest = path.substring("/storage/".length());
			int slash = rest.indexOf('/');
			if (slash > 0) {
				return "SD card/" + rest.substring(slash + 1);
			}
		}
		return path;
	}

	static void setReceive(Context c, String path, String uri) {
		prefs(c).edit()
				.putString(KEY_DIR, path)
				.putString(KEY_URI, uri == null ? "" : uri)
				.apply();
	}

	static void clearReceive(Context c) {
		prefs(c).edit().remove(KEY_DIR).remove(KEY_URI).apply();
	}

	static String receiveUri(Context c) {
		String u = prefs(c).getString(KEY_URI, "");
		return u == null || u.isEmpty() ? null : u;
	}

	private static android.content.SharedPreferences prefs(Context c) {
		return c.getApplicationContext()
				.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}
}
