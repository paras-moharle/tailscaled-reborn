// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The tailnet side of DNS, over Tailscale's own API.
 *
 * <p>Most of what the app controls lives on the phone: the daemon, its
 * preferences, the files it moves. Global nameservers, search domains and
 * MagicDNS do not. They belong to the tailnet, are stored by the control
 * plane, and are pushed to every device from there — which is why the CLI
 * has no command for them and why the admin console was the only place to
 * set them.
 *
 * <p>They are reachable through the public v2 API with an access token the
 * user creates once. That token is deliberately narrow: it is an ordinary
 * API key, it can be revoked, and it is kept in this app's private storage
 * rather than in the module's root-readable directory, because nothing else
 * on the device needs it.
 *
 * <p>No token, no problem: the caller still gets the daemon-owned controls
 * (this device's DNS, the tailnet suffix) and a card explaining the one step
 * that unlocks the rest.
 */
final class CloudApi {

	private static final String BASE = "https://api.tailscale.com/api/v2";
	private static final String PREFS = "tailnet_api";
	private static final String K_TOKEN = "token";
	/** "-" is the API's name for "the tailnet this token belongs to". */
	private static final String TAILNET = "-";

	private static final int CONNECT_TIMEOUT_MS = 12_000;
	private static final int READ_TIMEOUT_MS = 15_000;

	private CloudApi() {}

	/**
	 * A request that did not succeed, carrying enough to say something useful.
	 *
	 * <p>"Network error" is not useful. A 401 means the token was revoked or
	 * mistyped and nothing on the phone can fix it; a 403 means the token's
	 * scope does not include DNS; a 5xx means wait. The UI shows the API's own
	 * message when it sends one.
	 */
	static final class CloudError extends Exception {
		/** HTTP status, or 0 when the request never reached the API. */
		final int status;

		CloudError(int status, String msg) {
			super(msg);
			this.status = status;
		}
	}

	// ------------------------------------------------------------- the token

	private static SharedPreferences prefs(Context c) {
		return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	static String token(Context c) {
		return prefs(c).getString(K_TOKEN, "");
	}

	static boolean connected(Context c) {
		return !token(c).isEmpty();
	}

	static void save(Context c, String token) {
		// A token pasted from a terminal or a password manager can carry a
		// newline or a stray space; those would turn a good key into a 401.
		prefs(c).edit().putString(K_TOKEN, token.replaceAll("\\s+", "")).apply();
	}

	static void forget(Context c) {
		prefs(c).edit().remove(K_TOKEN).apply();
	}

	/** Enough of the token to recognise it, not enough to use it. */
	static String masked(Context c) {
		String t = token(c);
		if (t.length() <= 12) {
			return t.isEmpty() ? "" : "••••";
		}
		return t.substring(0, 9) + "…" + t.substring(t.length() - 4);
	}

	// ------------------------------------------------------------- transport

	private static JSONObject request(String tok, String method, String path,
			JSONObject body) throws CloudError {
		if (tok == null || tok.isEmpty()) {
			throw new CloudError(401, "no API token");
		}
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) new URL(BASE + path).openConnection();
			c.setRequestMethod(method);
			c.setConnectTimeout(CONNECT_TIMEOUT_MS);
			c.setReadTimeout(READ_TIMEOUT_MS);
			c.setRequestProperty("Authorization", "Bearer " + tok);
			c.setRequestProperty("Accept", "application/json");
			c.setRequestProperty("User-Agent", "TailBridge");
			if (body != null) {
				c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/json");
				try (OutputStream os = c.getOutputStream()) {
					os.write(body.toString().getBytes("UTF-8"));
				}
			}
			int code = c.getResponseCode();
			InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
			String text = read(in);
			if (code >= 400) {
				throw new CloudError(code, message(code, text));
			}
			return text.isEmpty() ? new JSONObject() : new JSONObject(text);
		} catch (CloudError e) {
			throw e;
		} catch (Exception e) {
			throw new CloudError(0, "could not reach api.tailscale.com — "
					+ (e.getMessage() == null ? "network error" : e.getMessage()));
		} finally {
			if (c != null) {
				c.disconnect();
			}
		}
	}

	/**
	 * One call, with the token read from this app's private storage at the
	 * moment of the call. Every entry point below goes through these two, so
	 * a token that was just pasted or just removed is the one used.
	 */
	private static JSONObject get(Context c, String path) throws CloudError {
		return request(token(c), "GET", path, null);
	}

	private static JSONObject post(Context c, String path, JSONObject body)
			throws CloudError {
		return request(token(c), "POST", path, body);
	}

	private static String read(InputStream in) throws Exception {
		if (in == null) {
			return "";
		}
		try (InputStream is = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = is.read(buf)) > 0) {
				out.write(buf, 0, n);
			}
			return out.toString("UTF-8");
		}
	}

	private static String message(int code, String text) {
		// The API answers {"message":"..."} for most errors. Show that when it
		// is there; otherwise name the status so the user can tell a revoked
		// token from a DNS-scope problem.
		try {
			JSONObject o = new JSONObject(text);
			String m = o.optString("message", "");
			if (!m.isEmpty()) {
				return m;
			}
		} catch (Exception ignored) {
		}
		switch (code) {
			case 401:
				return "the API token was rejected (401) — create a new one";
			case 403:
				return "this token cannot change DNS (403) — it needs DNS write scope";
			case 404:
				return "not found (404) — does this token belong to a tailnet?";
			case 429:
				return "rate limited by Tailscale (429) — try again in a minute";
			default:
				if (code >= 500) {
					return "Tailscale's API is having trouble (" + code + ")";
				}
				return "request failed (" + code + ")";
		}
	}

	private static List<String> strings(JSONObject o, String key) {
		List<String> out = new ArrayList<>();
		JSONArray a = o.optJSONArray(key);
		if (a == null) {
			return out;
		}
		for (int i = 0; i < a.length(); i++) {
			String s = a.optString(i, "").trim();
			if (!s.isEmpty()) {
				out.add(s);
			}
		}
		return out;
	}

	/** The document shape both nameserver endpoints accept. */
	private static JSONObject listBody(String key, List<String> values) {
		JSONArray a = new JSONArray();
		for (String v : values) {
			a.put(v);
		}
		JSONObject o = new JSONObject();
		try {
			o.put(key, a);
		} catch (Exception ignored) {
		}
		return o;
	}

	// ------------------------------------------------------------------ DNS

	/**
	 * Check that the stored token can read DNS at all.
	 *
	 * <p>Deliberately the cheapest DNS read rather than a token-introspection
	 * endpoint: if this answers, the token is valid and the DNS section will
	 * work, which is exactly the pair of facts the setup card promises.
	 */
	static void verify(Context c) throws CloudError {
		get(c, "/tailnet/" + TAILNET + "/dns/nameservers");
	}

	static List<String> nameservers(Context c) throws CloudError {
		return strings(get(c, "/tailnet/" + TAILNET + "/dns/nameservers"), "dns");
	}

	static void setNameservers(Context c, List<String> values) throws CloudError {
		post(c, "/tailnet/" + TAILNET + "/dns/nameservers",
				listBody("dns", values));
	}

	static List<String> searchPaths(Context c) throws CloudError {
		return strings(get(c, "/tailnet/" + TAILNET + "/dns/searchpaths"), "searchPaths");
	}

	static void setSearchPaths(Context c, List<String> values) throws CloudError {
		post(c, "/tailnet/" + TAILNET + "/dns/searchpaths",
				listBody("searchPaths", values));
	}

	static boolean magicDns(Context c) throws CloudError {
		return get(c, "/tailnet/" + TAILNET + "/dns/preferences")
				.optBoolean("magicDNS", false);
	}

	static void setMagicDns(Context c, boolean on) throws CloudError {
		JSONObject o = new JSONObject();
		try {
			o.put("magicDNS", on);
		} catch (Exception ignored) {
		}
		post(c, "/tailnet/" + TAILNET + "/dns/preferences", o);
	}

	/**
	 * Everything the DNS page shows, in one round trip per endpoint.
	 *
	 * <p>Three small reads rather than one: the API has no combined DNS
	 * document, and running them in sequence still lands well under a second
	 * on a phone that is online.
	 */
	static final class Snapshot {
		final List<String> nameservers;
		final List<String> searchPaths;
		final boolean magicDns;

		Snapshot(List<String> nameservers, List<String> searchPaths, boolean magicDns) {
			this.nameservers = nameservers;
			this.searchPaths = searchPaths;
			this.magicDns = magicDns;
		}
	}

	static Snapshot snapshot(Context c) throws CloudError {
		List<String> ns = nameservers(c);
		List<String> sp = searchPaths(c);
		boolean md = magicDns(c);
		return new Snapshot(ns, sp, md);
	}
}
