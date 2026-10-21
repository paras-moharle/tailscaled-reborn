// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

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

	// One root shell, kept open.
	//
	// Every call used to start a fresh su: a process, a pipe, and a trip
	// through the root manager. The app asks the module something every
	// fifteen seconds, and asked for two things at once on a refresh, and
	// all of it was paying for a new shell to run one command and die.
	//
	// The root manager keeps the session alive after a command, so one shell
	// can serve every call. The end of one command's output is known by a
	// marker line it prints afterwards.
	private static final Object LOCK = new Object();
	private static final String MARK = "__tailbridge_root_done__";

	private static Process proc;
	private static OutputStreamWriter in;
	private static BufferedReader out;

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

	private static void open() throws Exception {
		proc = start();
		in = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8);
		out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
	}

	private static void close() {
		try {
			if (proc != null) {
				proc.destroy();
			}
		} catch (Throwable ignored) {
			// Nothing to do about it.
		}
		proc = null;
		in = null;
		out = null;
	}

	/**
	 * Run one command as root and return what it printed.
	 *
	 * <p>The command's own error output is folded into its output, so that
	 * one marker ends both and there is nothing left over for the next
	 * command to pick up. The exit code comes back on the marker, which is
	 * how an empty answer is told apart from a refused root shell: a
	 * command that says nothing and worked is not a failure.
	 */
	static String sh(String cmd, long timeoutMs) throws Exception {
		synchronized (LOCK) {
			Exception last = null;
			for (int attempt = 0; attempt < 2; attempt++) {
				try {
					return run(cmd, timeoutMs);
				} catch (Denied d) {
					throw d;
				} catch (Exception e) {
					last = e;
					// The shell died, or the command hung. Start a new one
					// and try once more.
					close();
				}
			}
			if (last instanceof Denied) {
				throw (Denied) last;
			}
			throw new Denied("root permission denied");
		}
	}

	private static String run(String cmd, long timeoutMs) throws Exception {
		if (proc == null) {
			open();
		}
		StringBuilder sb = new StringBuilder();
		// The command's errors are folded into its output, so that one
		// marker ends both. The group keeps the shell alive: `exec` would
		// replace it, and there would be no shell left for the next call.
		in.write("{ " + cmd + " < /dev/null ; } 2>&1\n");
		in.write("printf '%s %s\\n' '" + MARK + "' \"$?\"\n");
		in.flush();

		// A command that hangs must not hang the app. The watchdog closes
		// the shell, and the read below then fails.
		final Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(timeoutMs);
			} catch (InterruptedException ignored) {
				return;
			}
			close();
		});
		watchdog.setDaemon(true);
		watchdog.start();
		int code = 0;
		try {
			while (true) {
				String line = out.readLine();
				if (line == null) {
					throw new IOException("the root shell ended");
				}
				if (line.startsWith(MARK)) {
					String rest = line.substring(MARK.length()).trim();
					try {
						code = Integer.parseInt(rest);
					} catch (NumberFormatException ignored) {
						code = 0;
					}
					break;
				}
				sb.append(line).append('\n');
			}
		} finally {
			watchdog.interrupt();
		}

		String text = sb.toString().trim();
		if (text.isEmpty()) {
			if (code == 0) {
				// It worked, and it had nothing to say. That is not a
				// refusal of root.
				return "";
			}
			throw new Denied("root permission denied");
		}
		if (text.contains("Permission denied") || text.contains("not allowed")) {
			throw new Denied(text);
		}
		return text;
	}

	static String sh(String cmd) throws Exception {
		return sh(cmd, 120_000);
	}
}
