// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge.clip;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The clipboard's crypto desk.
 *
 * <p>The hardware keystore takes a moment to reach, and a Java program on
 * this phone takes a couple of seconds just to start, so doing the work in
 * a program that is started for every copy would put the copy itself
 * behind a wait of several seconds. This program starts once, keeps the
 * keystore open, and answers over a socket.
 *
 * <p>The socket is on the loopback address, so nothing outside the phone
 * can reach it, and every request has to carry the secret from a file
 * that only root can read. A program on the device that guessed the port
 * would still have nothing to say.
 *
 * <p>The talk is simple. The client sends the secret, then a line saying
 * what it wants, then the payload. The answer comes back and the
 * connection closes.
 */
public class ClipStore {

	/** Where the port and the secret are kept, unless told otherwise. */
	private static final String DEFAULT_DIR = "/data/adb/tailscale";

	/** The ports it will try, in order. */
	private static final int[] PORTS = {39621, 39622, 39623, 39624, 39625};

	public static void main(String[] args) {
		String dir = args.length > 0 ? args[0] : DEFAULT_DIR;
		try {
			File tokenFile = new File(dir, "clip.token");
			File portFile = new File(dir, "clip.port");
			String token = token(tokenFile);

			ServerSocket server = null;
			int port = 0;
			for (int p : PORTS) {
				try {
					server = new ServerSocket(p, 16, InetAddress.getByName("127.0.0.1"));
					port = p;
					break;
				} catch (Throwable ignored) {
					// That one is taken. Try the next.
				}
			}
			if (server == null) {
				throw new IllegalStateException("no free port for the clipboard desk");
			}
			// The port is in a file that only root can read, so nothing
			// else knows where to look.
			write(portFile, Integer.toString(port));

			// Say that it is up, so whoever started it can tell.
			System.out.println("ready " + port);
			System.out.flush();

			while (true) {
				Socket client = server.accept();
				try {
					answer(client, token);
				} catch (Throwable t) {
					// One bad request must not take the desk down.
					Throwable c = t;
					while (c.getCause() != null) {
						c = c.getCause();
					}
					try {
						OutputStream out = client.getOutputStream();
						out.write(("err:" + c).getBytes("UTF-8"));
						out.flush();
					} catch (Throwable ignored) {
						// The client is gone.
					}
				} finally {
					try {
						client.close();
					} catch (Throwable ignored) {
						// Nothing to do about it.
					}
				}
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

	/** The secret every request has to carry, made once and kept. */
	private static String token(File file) throws Exception {
		if (file.exists()) {
			String t = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8").trim();
			if (!t.isEmpty()) {
				return t;
			}
		}
		byte[] raw = new byte[32];
		new SecureRandom().nextBytes(raw);
		String t = Base64.getEncoder().encodeToString(raw);
		write(file, t);
		return t;
	}

	/** Writes a file that only root can read. */
	private static void write(File file, String text) throws Exception {
		java.nio.file.Files.write(file.toPath(), text.getBytes("UTF-8"));
		try {
			android.system.Os.chmod(file.getAbsolutePath(), 0600);
		} catch (Throwable ignored) {
			// The directory is already root only.
		}
	}

	/** Read the request, do what it says, write the answer. */
	private static void answer(Socket client, String token) throws Exception {
		InputStream in = client.getInputStream();
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int n;
		while ((n = in.read(chunk)) > 0) {
			buf.write(chunk, 0, n);
		}
		String all = buf.toString("UTF-8");

		// The secret, then the command, then the payload.
		int first = all.indexOf('\n');
		if (first < 0) {
			throw new IllegalArgumentException("no secret");
		}
		String given = all.substring(0, first).trim();
		if (!token.equals(given)) {
			throw new SecurityException("wrong secret");
		}
		int second = all.indexOf('\n', first + 1);
		String command = second < 0 ? all.substring(first + 1).trim()
				: all.substring(first + 1, second).trim();
		String payload = second < 0 ? "" : all.substring(second + 1);

		String[] parts = command.split("\\s+");
		String mode = parts.length > 0 ? parts[0] : "";
		String answer;
		switch (mode) {
			case "probe":
				ClipCrypto.probe();
				answer = "ok";
				break;
			case "seal":
				answer = ClipCrypto.sealEntry(payload.trim(), parts.length > 1 ? parts[1] : "",
						parts.length > 2 ? parts[2] : "0");
				break;
			case "encrypt-store":
				answer = ClipCrypto.encryptStore(payload);
				break;
			case "decrypt-store":
				answer = ClipCrypto.decryptStore(payload);
				break;
			default:
				answer = "err:unknown command " + mode;
				break;
		}
		OutputStream out = client.getOutputStream();
		out.write(answer.getBytes("UTF-8"));
		out.flush();
	}
}
