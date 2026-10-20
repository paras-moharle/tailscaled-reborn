// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge.clip;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.IOnPrimaryClipChangedListener;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Reads, writes and watches the Android clipboard from a plain process.
 *
 * <p>Android will not let an ordinary app read the clipboard while it is in the
 * background, and since Android 10 it will not let it write at all unless the
 * app is focused. What it <em>does</em> allow is the system uid and the shell
 * uid. This program gets that access the way ClipShare does: it turns the
 * process into a system process with {@code ActivityThread.systemMain()}, takes
 * the system context from it, and reaches the clipboard service through the
 * binder proxy that {@link ClipboardManager} keeps in a private field.
 *
 * <p>Two things about that are load-bearing, and both were established by
 * testing on a real device rather than by reading documentation:
 *
 * <ol>
 * <li><b>The caller must be the shell uid, not root.</b> The clipboard service
 * checks that the calling process owns the package it claims to be. Root does
 * not own {@code com.android.shell}, so the service tries to grant a
 * permission it cannot grant, throws, and <em>clears the user's clipboard</em>.
 * The shell uid owns the package, the grant succeeds, and nothing is cleared.
 * So this program must be run as uid 2000. Running it as root destroys the
 * user's clipboard on every call.</li>
 * <li><b>The first read after a change can come back empty.</b> The service
 * notifies listeners while it is still committing the new clip, so a read
 * issued straight away can see the old, empty value. The read is retried.</li>
 * </ol>
 *
 * <p>Output is one item per line, so the calling shell can read it with
 * {@code read}. Text is base64 encoded, because clipboard contents are
 * arbitrary bytes and may contain newlines.
 *
 * <ul>
 * <li>{@code changed:<base64>|<source>} — a change was seen (listen mode)</li>
 * <li>{@code text:<base64>|<source>} — the current clipboard (get mode)</li>
 * <li>{@code uri:<uri>} — the clipboard holds a URI rather than text</li>
 * <li>{@code ok} — the write succeeded</li>
 * <li>{@code err:<message>} — something failed</li>
 * </ul>
 */
public class ClipBoard {

	/**
	 * The package the clipboard service is told we are.
	 *
	 * <p>It has to be a package the calling uid actually owns, because the
	 * service verifies that and refuses the read otherwise. It is passed in
	 * so the caller can name a package it owns: the app's own, or the shell's
	 * when it is running as the shell uid.
	 */
	private static String PKG = "com.android.shell";

	/** How many times to re-read before giving up on a change. */
	private static final int READ_ATTEMPTS = 5;

	/** How long to wait between those reads. */
	private static final long READ_BACKOFF_MS = 150;

	public static void main(String[] args) {
		String mode = args.length > 0 ? args[0] : "get";
		if (args.length > 1 && !args[1].isEmpty()) {
			PKG = args[1];
		}
		try {
			if ("listen".equals(mode)) {
				listen();
			} else if ("get".equals(mode)) {
				get();
			} else if ("set".equals(mode)) {
				set();
			} else {
				System.out.println("err:unknown mode " + mode);
				System.exit(2);
			}
		} catch (Throwable t) {
			Throwable c = t;
			while (c.getCause() != null) {
				c = c.getCause();
			}
			System.out.println("err:" + c);
			System.exit(1);
		}
	}

	// ------------------------------------------------------------------ modes --

	/** Watch the clipboard and report every change until killed. */
	private static void listen() throws Exception {
		ClipboardManager cm = clipboardManager();
		final Object svc = serviceOf(cm);
		Method add = findMethod(svc.getClass(), "addPrimaryClipChangedListener");
		if (add == null) {
			System.out.println("err:no addPrimaryClipChangedListener");
			System.exit(3);
		}
		// The signature of this method has changed over the years: it has
		// taken between one and five arguments. Matching on how many it takes
		// is what lets one binary work across Android versions, and is how
		// ClipShare does it.
		int arity = add.getParameterCount();

		IOnPrimaryClipChangedListener.Stub listener = new IOnPrimaryClipChangedListener.Stub() {
			@Override
			public void dispatchPrimaryClipChanged() {
				// Read the clipboard on another thread.
				//
				// The callback arrives on a binder thread, and the
				// clipboard service holds its lock while it dispatches
				// the notification. Reading from the callback thread
				// asks for the same lock, so the read waits until the
				// service has finished telling everybody -- which
				// measured at two and a half seconds here.
				//
				// Moving the read off the callback thread lets the
				// service finish its notification at once, and the read
				// then finds the new clip straight away.
				final Object service = svc;
				Thread t = new Thread(() -> report(service));
				t.setDaemon(true);
				t.start();
			}
		};

		switch (arity) {
			case 1:
			case 2:
				add.invoke(svc, listener, PKG);
				break;
			case 3:
				add.invoke(svc, listener, PKG, 0);
				break;
			case 4:
				add.invoke(svc, listener, PKG, null, 0);
				break;
			case 5:
				add.invoke(svc, listener, PKG, null, 0, 0);
				break;
			default:
				System.out.println("err:unsupported listener arity " + arity);
				System.exit(3);
		}

		System.out.println("ready:" + arity);
		System.out.flush();

		// The clipboard service calls back on a binder thread, so the process
		// needs a looper to stay alive and the callback needs somewhere to
		// run. Looper.prepare() before the looper is taken over.
		Looper.loop();
	}

	/** Print the current clipboard. */
	private static void get() throws Exception {
		ClipboardManager cm = clipboardManager();
		Object svc = serviceOf(cm);
		String source = sourceOf(svc);
		ClipData clip = readClip(svc);
		if (clip == null) {
			System.out.println("text:");
			if (source != null) {
				System.out.println("source:" + source);
			}
			return;
		}
		StringBuilder text = new StringBuilder();
		String uri = null;
		for (int i = 0; i < clip.getItemCount(); i++) {
			ClipData.Item item = clip.getItemAt(i);
			if (item.getText() != null) {
				if (text.length() > 0) {
					text.append('\n');
				}
				text.append(item.getText());
			} else if (item.getUri() != null && uri == null) {
				uri = item.getUri().toString();
			}
		}
		System.out.println("text:" + b64(text.toString()) + "|" + (source == null ? "" : source));
		if (uri != null) {
			System.out.println("uri:" + b64(uri));
		}
	}

	/** Replace the clipboard with the base64 text on stdin. */
	private static void set() throws Exception {
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
		StringBuilder b = new StringBuilder();
		String line;
		while ((line = in.readLine()) != null) {
			b.append(line);
		}
		String text = b.length() == 0 ? "" : new String(Base64.getDecoder().decode(b.toString()),
				StandardCharsets.UTF_8);

		ClipboardManager cm = clipboardManager();
		Object svc = serviceOf(cm);
		Method set = findMethod(svc.getClass(), "setPrimaryClip");
		if (set == null) {
			System.out.println("err:no setPrimaryClip");
			System.exit(3);
		}
		ClipData cd = new ClipData("tailbridge", new String[] { "text/plain" },
				new ClipData.Item(text));
		// The signature has changed over the years. The arity is the number of
		// parameters the method takes, the clip included.
		int arity = set.getParameterCount();
		switch (arity) {
			case 2:
				set.invoke(svc, cd, PKG);
				break;
			case 3:
				set.invoke(svc, cd, PKG, null);
				break;
			case 4:
				set.invoke(svc, cd, PKG, null, 0);
				break;
			case 5:
				set.invoke(svc, cd, PKG, null, 0, 0);
				break;
			case 6:
				set.invoke(svc, cd, PKG, null, 0, 0, 0);
				break;
			default:
				System.out.println("err:unsupported setPrimaryClip arity " + arity);
				System.exit(3);
		}
		System.out.println("ok");
	}

	// ----------------------------------------------------------------- helpers --

	/** Report one change, with the app that made it. */
	private static void report(Object svc) {
		try {
			String source = sourceOf(svc);
			ClipData clip = readClip(svc);
			StringBuilder text = new StringBuilder();
			String uri = null;
			if (clip != null) {
				for (int i = 0; i < clip.getItemCount(); i++) {
					ClipData.Item item = clip.getItemAt(i);
					if (item.getText() != null) {
						if (text.length() > 0) {
							text.append('\n');
						}
						text.append(item.getText());
					} else if (item.getUri() != null && uri == null) {
						uri = item.getUri().toString();
					}
				}
			}
			System.out.println("changed:" + b64(text.toString()) + "|"
					+ (source == null ? "" : source));
			if (uri != null) {
				System.out.println("uri:" + b64(uri));
			}
			System.out.flush();
		} catch (Throwable t) {
			Throwable c = t;
			while (c.getCause() != null) {
				c = c.getCause();
			}
			System.out.println("err:" + c);
			System.out.flush();
		}
	}

	/**
	 * Read the clip, retrying.
	 *
	 * <p>The service notifies listeners while the new clip is still being
	 * committed, so a read issued immediately can see the previous, empty
	 * value. Waiting and reading again gets the real one.
	 */
	private static ClipData readClip(Object svc) {
		Method get = findMethod(svc.getClass(), "getPrimaryClip");
		if (get == null) {
			return null;
		}
		ClipData clip = null;
		for (int i = 0; i < READ_ATTEMPTS && clip == null; i++) {
			try {
				clip = (ClipData) get.invoke(svc, PKG, null, 0, 0);
			} catch (Throwable ignored) {
				// A refused read is not fatal; try again.
			}
			if (clip == null) {
				try {
					Thread.sleep(READ_BACKOFF_MS);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		return clip;
	}

	/** Which app last wrote the clipboard, if the service will say. */
	private static String sourceOf(Object svc) {
		Method m = findMethod(svc.getClass(), "getPrimaryClipSource");
		if (m == null) {
			return null;
		}
		try {
			Object s = m.invoke(svc, PKG, null, 0, 0);
			if (s instanceof String && !((String) s).isEmpty()) {
				return (String) s;
			}
		} catch (Throwable ignored) {
			// The source is a nicety. Losing it is not worth an error.
		}
		return null;
	}

	/** The system context, which is what a system process can get a service from. */
	private static Context systemContext() throws Exception {
		Class<?> at = Class.forName("android.app.ActivityThread");
		// systemMain() builds a Handler, so the thread needs a looper first.
		Looper.prepare();
		Object sm = at.getMethod("systemMain").invoke(null);
		return (Context) at.getMethod("getSystemContext").invoke(sm);
	}

	private static ClipboardManager clipboardManager() throws Exception {
		Context ctx = systemContext();
		return (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
	}

	/**
	 * The clipboard service, reached through the private field
	 * {@link ClipboardManager} keeps it in.
	 *
	 * <p>That field is hidden, but a raw process has no application info and
	 * so no hidden API restrictions are applied to it.
	 */
	private static Object serviceOf(ClipboardManager cm) throws Exception {
		Field f = ClipboardManager.class.getDeclaredField("mService");
		f.setAccessible(true);
		Object svc = f.get(cm);
		if (svc == null) {
			throw new IllegalStateException("no clipboard service");
		}
		return svc;
	}

	private static Method findMethod(Class<?> c, String name) {
		for (Method m : c.getMethods()) {
			if (m.getName().equals(name)) {
				return m;
			}
		}
		return null;
	}

	private static String b64(String s) {
		return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}
}
