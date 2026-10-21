// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client for the module's tailscaled.api. Every call runs as root. */
final class Api {

	private static final String PATH = "/data/adb/tailscale/scripts/tailscaled.api";

	private Api() {}

	/** Root works, but the module could not be reached. Distinct from no root. */
	static final class ApiBroken extends Exception {
		final boolean modulePresent;

		ApiBroken(String msg, boolean modulePresent) {
			super(msg);
			this.modulePresent = modulePresent;
		}
	}

	/**
	 * Run one API command and parse its JSON.
	 *
	 * <p>Most commands are a status read that answers in well under a second.
	 * A daemon that is mid-restart, or an unreachable control plane, can hang
	 * the shell instead, and this runs behind the UI, so the common case gets a
	 * short budget and fails visibly instead of leaving a spinner up. login is
	 * the one caller that waits on a person, and says so by passing its own.
	 */
	private static final long DEFAULT_TIMEOUT_MS = 30_000;

	private static JSONObject call(String... args) throws Exception {
		return call(DEFAULT_TIMEOUT_MS, args);
	}

	/**
	 * Run one API command and return its raw output.
	 *
	 * <p>For the commands that answer with a JSON array rather than an
	 * object. The same root handling and the same timeout rules apply; only
	 * the shape of the answer differs.
	 */
	private static String callRaw(long timeoutMs, String... args) throws Exception {
		StringBuilder cmd = new StringBuilder(PATH);
		for (String a : args) {
			cmd.append(" '").append(a.replace("'", "'\\''")).append('\'');
		}
		try {
			return Root.sh(cmd.toString(), timeoutMs).trim();
		} catch (Root.Denied d) {
			throw d;
		} catch (Exception e) {
			throw new ApiBroken("root is available but the module is not installed or "
					+ "is broken: " + e, false);
		}
	}

	private static JSONObject call(long timeoutMs, String... args) throws Exception {
		StringBuilder cmd = new StringBuilder(PATH);
		for (String a : args) {
			// The API re-validates everything; quote anyway so a filename with a
			// space cannot split into two arguments here.
			cmd.append(" '").append(a.replace("'", "'\\''")).append('\'');
		}
		String out;
		try {
			out = Root.sh(cmd.toString(), timeoutMs).trim();
		} catch (Root.Denied d) {
			// Genuinely no root. The caller shows the permission screen.
			throw d;
		} catch (Exception e) {
			throw new ApiBroken("root is available but the module is not installed or "
					+ "is broken: " + e, false);
		}
		int i = out.indexOf('{');
		if (i < 0) {
			// The command ran, so root works. Something else is wrong, and
			// saying "grant root permission" here sends the user to a setting
			// that cannot possibly fix it.
			String why = out.isEmpty() ? "(no output)" : out;
			if (why.length() > 200) {
				why = why.substring(0, 200) + "…";
			}
			throw new ApiBroken("the module is installed but not answering: " + why, true);
		}
		JSONObject o = new JSONObject(out.substring(i));
		if (!o.optBoolean("ok", false)) {
			throw new IllegalStateException(o.optString("error", "unknown error"));
		}
		return o;
	}

	static final class Peer {
		final String name;
		final String ip;
		final String os;
		final boolean exitNode;
		/** Reachability. Carried rather than filtered: the list should show a
		 *  device that is known but down, so it is obvious it is down instead
		 *  of appearing to have been removed. */
		final boolean online;
		/** When this peer was last seen, as the daemon reports it. */
		final String lastSeen;
		/** Offers itself as an exit node. */
		final boolean exitNodeOption;
		/** DERP relay actually carrying traffic, empty when the link is direct. */
		final String relay;
		/** True when peers talk to each other without a relay. */
		final boolean direct;
		/** The endpoint in use, e.g. "192.168.1.5:41641". Empty until connected. */
		final String curAddr;

		Peer(String name, String ip, String os, boolean exitNode, boolean online,
				String lastSeen, boolean exitNodeOption, String relay,
				boolean direct, String curAddr) {
			this.name = name;
			this.ip = ip;
			this.os = os;
			this.exitNode = exitNode;
			this.online = online;
			this.lastSeen = lastSeen;
			this.exitNodeOption = exitNodeOption;
			this.relay = relay;
			this.direct = direct;
			this.curAddr = curAddr;
		}

	}

	/**
	 * One call for the whole dashboard.
	 *
	 * Peers ride along in the same document, so the UI does not need a second
	 * root round-trip, and `status` is purely local (no network), which keeps
	 * the screen instant.
	 */
	static JSONObject status() throws Exception {
		return call("status");
	}

	static List<Peer> peersFrom(JSONObject st) {
		List<Peer> out = new ArrayList<>();
		JSONArray arr = st.optJSONArray("peers");
		if (arr == null) {
			return out;
		}
		for (int i = 0; i < arr.length(); i++) {
			JSONObject p = arr.optJSONObject(i);
			if (p == null) {
				continue;
			}
			JSONArray ips = p.optJSONArray("ips");
			String ip = ips != null && ips.length() > 0 ? ips.optString(0) : "";
			out.add(new Peer(
					p.optString("host", ip.isEmpty() ? "peer" : ip),
					ip,
					p.optString("os", ""),
					p.optBoolean("exitNode", false),
					p.optBoolean("online", false),
					p.optString("lastSeen", ""),
					p.optBoolean("exitNodeOption", false),
					p.optString("relay", ""),
					p.optBoolean("direct", false),
					p.optString("curAddr", "")));
		}
		return out;
	}

	/** Latest published version. Slow: touches the network. */
	static String updateCheck() throws Exception {
		return updateCheckInfo().optString("latest", "");
	}

	/**
	 * The whole answer from the feed: current, latest, and whether they differ.
	 *
	 * <p>Tapping "check" needs all three. Asking only for the latest version
	 * cannot tell "you are up to date" from "we could not reach the feed",
	 * which is how the update row used to end up promising an install that had
	 * nothing to install.
	 */
	static JSONObject updateCheckInfo() throws Exception {
		return call("update-check");
	}

	/**
	 * Wireless debugging: adb over TCP, on the phone's Tailscale address.
	 *
	 * <p>The port is set twice: once as the property that survives a
	 * reboot, and once as the running one. adbd is then restarted, because
	 * it reads the port when it starts and nothing else makes it listen.
	 *
	 * <p>This needs no help from the module. It is a property and a
	 * restart, and the app already has root.
	 */
	static void adbWireless(boolean on) throws Exception {
		String cmd = on
				? "setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555"
				: "setprop persist.adb.tcp.port ''; setprop service.adb.tcp.port ''";
		String out = Root.sh(cmd + "; setprop ctl.restart adbd", 30_000);
		if (!out.isEmpty()) {
			throw new IllegalStateException(out);
		}
		// adbd needs a moment to come back up.
		try {
			Thread.sleep(2000);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Whether adbd is listening for TCP connections.
	 *
	 * <p>It comes out of the module's status, which the app asks for anyway.
	 * Reading it separately meant a second root call on every refresh, and
	 * a root call is a process, a pipe and the root manager.
	 */
	static boolean adbWirelessOn(JSONObject status) {
		if (status == null) {
			return false;
		}
		String p = status.optString("adbPort", "").trim();
		return !p.isEmpty();
	}

	/**
	 * Send one file.
	 *
	 * <p>Budgeted separately from an ordinary read. A send is a byte transfer
	 * to a peer that may be asleep, so it inherits neither the 30 second read
	 * ceiling nor the short one it used to have: a photo is instant, and a
	 * hundred megabytes over a relay is minutes. The shell side sizes its own
	 * budget from the file and caps it, and this must be at least as generous,
	 * because cutting this call short kills the CLI mid-transfer and leaves the
	 * daemon's partial file behind while the app reports success.
	 */
	static boolean send(String peerIp, String path) throws Exception {
		call(600_000, "send", peerIp, path);
		return true;
	}

	// ------------------------------------------------------------- clipboard

	/**
	 * The clipboard history, newest first.
	 *
	 * <p>Each entry carries its text base64 encoded. The store lives in the
	 * module and is written by a shell process, so the text goes in encoded:
	 * a shell variable cannot hold a trailing newline, and clipboard contents
	 * are arbitrary text that ends in one more often than not.
	 */
	static JSONArray clipboardList() throws Exception {
		// The module answers with one JSON object per line, newest first,
		// and only as many as were asked for. Asking for the whole store
		// meant a few hundred kilobytes over a root round trip every
		// time the list was refreshed.
		String out = callRaw(DEFAULT_TIMEOUT_MS, "clipboard", "list", "200");
		JSONArray arr = new JSONArray();
		for (String line : out.split("\n")) {
			line = line.trim();
			if (line.isEmpty()) {
				continue;
			}
			try {
				arr.put(new JSONObject(line));
			} catch (Exception ignored) {
				// A line that is not an object is not worth stopping for.
			}
		}
		return arr;
	}

	/** The clipboard right now, not the last one recorded. */
	static JSONObject clipboardNow() throws Exception {
		return call("clipboard", "get");
	}

	/** Send the current clipboard to a peer. */
	static void clipboardSend(String peerIp) throws Exception {
		call(120_000, "clipboard", "send", peerIp);
	}

	/** Empty the history. */
	static void clipboardClear() throws Exception {
		call("clipboard", "clear");
	}

	/** Star an entry, so a limit never quietly removes it. */
	static void clipboardStar(long timestamp, boolean star) throws Exception {
		call(60_000, "clipboard", star ? "star" : "unstar", String.valueOf(timestamp));
	}

	/** Remove one entry. */
	static void clipboardDelete(long timestamp) throws Exception {
		call(60_000, "clipboard", "delete", String.valueOf(timestamp));
	}

	/** What the watcher is doing: watch, max, ignore. */
	static JSONObject clipboardConfig() throws Exception {
		return call("clipboard", "config");
	}

	/** Change one of the watcher's settings. */
	static void clipboardSet(String key, String value) throws Exception {
		call(60_000, "clipboard", "set", key, value);
	}

	/** Which apps have copied something, and how often. */
	static JSONArray clipboardApps() throws Exception {
		String out = callRaw(DEFAULT_TIMEOUT_MS, "clipboard", "apps");
		int i = out.indexOf('[');
		if (i < 0) {
			return new JSONArray();
		}
		return new JSONArray(out.substring(i).trim());
	}

	/** Apply a preference. Returns the refreshed status document. */
	static JSONObject set(String key, String value) throws Exception {
		return call("set", key, value);
	}


	/** What the Taildrop receiver collected while the app was away. */
	static JSONObject received() throws Exception {
		return call("received");
	}

	/** start | stop | restart | status */
	static JSONObject service(String action) throws Exception {
		return call("service", action);
	}

	/**
	 * Download, install and health-check a new daemon, rolling back on failure.
	 *
	 * <p>Genuinely slow: a binary download, a daemon restart and a settle
	 * window. It gets its own budget so the short read timeout does not kill it
	 * halfway and leave the module in an unknown state.
	 */
	static JSONObject update() throws Exception {
		return call(600_000, "update");
	}

	/** Restore the previous daemon. Also restarts and health-checks. */
	static JSONObject rollback() throws Exception {
		JSONObject o = call(600_000, "rollback");
		return o;
	}

	static String logs(int lines) throws Exception {
		JSONObject o = call("logs", String.valueOf(lines));
		return o.optString("log", "");
	}

	/** @return {url, alreadyLoggedIn} */
	/** Start a browser login. Waits on a person, so it gets the long budget. */
	static JSONObject login() throws Exception {
		return call(240_000, "login");
	}

	static JSONObject logout() throws Exception {
		return call("logout");
	}

	/** Prefs keyed the way the UI needs them. */
	static Map<String, Boolean> flagsFrom(JSONObject st) {
		JSONObject p = st.optJSONObject("prefs");
		Map<String, Boolean> m = new LinkedHashMap<>();
		if (p == null) {
			return m;
		}
		m.put("accept-dns", p.optBoolean("acceptDNS", false));
		m.put("accept-routes", p.optBoolean("routeAll", false));
		m.put("shields-up", p.optBoolean("shieldsUp", false));
		m.put("run-ssh", p.optBoolean("runSSH", false));
		m.put("run-webclient", p.optBoolean("runWebClient", false));
		m.put("advertise-exit-node", p.optBoolean("advertiseExitNode", false));
		m.put("snat-subnet-routes", p.optBoolean("snatSubnetRoutes", false));
		return m;
	}

	/**
	 * Copy a received file into this app's own cache so it can actually be read.
	 *
	 * Files under /sdcard are owned by the media_rw group and are unreadable to
	 * an ordinary app, even though a file manager can open them. Root can copy
	 * them somewhere this app owns, which is what the FileProvider then serves.
	 */

	/**
	 * Pull anything waiting in the Taildrop inbox into Downloads/Tailscale.
	 *
	 * Without this the daemon accepts inbound files and nothing ever shows them:
	 * they sit in a root-only inbox the user cannot reach. @return the names
	 * that were exported.
	 */
}