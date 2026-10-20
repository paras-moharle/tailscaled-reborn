// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * Runs commands as root.
 *
 * We deliberately do NOT hunt for su binaries across the filesystem. Magisk,
 * KernelSU and APatch all expose su at /system/bin/su, so we ask for that one
 * path: invoking it is what makes the root manager show its permission prompt.
 * If root has not been granted yet, the user gets a normal "allow?" dialog —
 * that is the intended flow, not an error to work around.
 */
final class Root {

	private Root() {}

	/** Thrown when the root manager refused us, so the UI can offer a retry. */
	static final class Denied extends SecurityException {
		Denied(String m) {
			super(m);
		}
	}

	private static Process start() throws Exception {
		// /system/bin/su is provided by every supported root solution and is
		// what triggers their permission prompt.
		try {
			return Runtime.getRuntime().exec(new String[] { "/system/bin/su" });
		} catch (Exception first) {
			try {
				// Very unusual (root not installed at all): fall back to PATH.
				return Runtime.getRuntime().exec(new String[] { "su" });
			} catch (Exception ignored) {
				// The binary is there but could not be run.
				//
				// That is what a root manager that has not granted this app
				// permission looks like: Android reports a denied exec as
				// "no such file or directory" rather than saying the policy
				// stopped it, which is deliberate -- it does not want to tell
				// an app that it is talking to a root manager at all.
				//
				// So this is a permission problem, not a broken module, and
				// it has to be reported as one. Left as a plain exception it
				// became "the module is not installed or is broken", which
				// sent the user looking for a module that was working.
				throw new Denied("root permission denied");
			}
		}
	}

	static String sh(String cmd, long timeoutMs) throws Exception {
		final Process p = start();

		// Do NOT block on the shell exiting: root managers keep the session
		// alive after the command, so waitFor() would sit there until the
		// timeout and the UI would look hung. Write the command, close stdin,
		// read stdout to EOF, then tear the process down.
		final StringBuilder out = new StringBuilder();
		final StringBuilder err = new StringBuilder();

		Thread outReader = pump(p.getInputStream(), out);
		Thread errReader = pump(p.getErrorStream(), err);
		outReader.setDaemon(true);
		errReader.setDaemon(true);
		outReader.start();
		errReader.start();

		try (OutputStreamWriter w = new OutputStreamWriter(p.getOutputStream())) {
			w.write(cmd);
			w.write("\nexit\n");
			w.flush();
		} catch (Exception ignored) {
			// the shell may already be gone
		}

		outReader.join(timeoutMs);
		if (outReader.isAlive()) {
			p.destroy();
			throw new Denied("root command timed out");
		}
		errReader.join(500);
		p.destroy();

		// Snapshot under the same monitor the pumps append under: errReader may
		// still be running here, and StringBuilder is not thread-safe.
		String text = snapshot(out).trim();
		String errors = snapshot(err).trim();

		if (text.isEmpty() && errors.isEmpty()) {
			throw new Denied("root permission denied");
		}
		if (errors.contains("Permission denied") || errors.contains("not allowed")
				|| text.contains("Permission denied")) {
			throw new Denied(errors.isEmpty() ? text : errors);
		}
		return text;
	}

	/** Read a buffer the pump threads may still be appending to. */
	private static String snapshot(StringBuilder sb) {
		synchronized (sb) {
			return sb.toString();
		}
	}

	private static Thread pump(final java.io.InputStream in, final StringBuilder sink) {
		return new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
				String line;
				while ((line = r.readLine()) != null) {
					synchronized (sink) {
						sink.append(line).append('\n');
					}
				}
			} catch (Exception ignored) {
				// stream closed when the process is torn down
			}
		});
	}

	static String sh(String cmd) throws Exception {
		return sh(cmd, 120_000);
	}
}