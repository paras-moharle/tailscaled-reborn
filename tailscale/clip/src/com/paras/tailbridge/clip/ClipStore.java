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
 * <p>Since the desk became the only writer of the history, it owns more
 * than crypto: sealing a copy also records it, the list is answered from
 * the index, and the retention limits are enforced here. The talk stayed
 * what it was -- secret, command, payload -- but with the order fixed so
 * that the protocol no longer trusts before it checks:
 *
 * <ul>
 * <li>The secret line is read first, off a read with a size cap and a
 * deadline. The old desk read the whole request to the end before it
 * looked at the secret, so anything that could reach the port could make
 * it buffer without bound while it waited for a verdict.</li>
 * <li>Every request has a size cap and a deadline. A client that sends
 * without end, or sends and then waits, is closed.</li>
 * <li>Requests are answered one per thread, bounded, so a slow client
 * holds up one conversation and nothing else: the sealing of the next
 * copy does not queue behind it.</li>
 * </ul>
 */
public class ClipStore {

	/** Where the port and the secret are kept, unless told otherwise. */
	private static final String DEFAULT_DIR = "/data/adb/tailscale";

	/** The ports it will try, in order. */
	private static final int[] PORTS = {39621, 39622, 39623, 39624, 39625};

	/** The most a request may weigh, secret line included. A clipboard text
	 *  is base64ed before it travels, so 6 MB of request holds a clip of
	 *  some 4 MB -- far past anything a paste box would carry. */
	private static final int MAX_REQUEST_BYTES = 6 * 1024 * 1024;

	/** The most the first line -- the secret -- may weigh. */
	private static final int MAX_SECRET_BYTES = 512;

	/** The most the second line -- the command -- may weigh. */
	private static final int MAX_COMMAND_BYTES = 1024;

	/** How long the client may take between two words of what it says. */
	private static final int SO_TIMEOUT_MS = 10_000;

	/** How many conversations may be open at once. */
	private static final int MAX_HANDLERS = 16;

	/** The history, which is the only thing the desk writes. */
	private static ClipHistory history;

	public static void main(String[] args) {
		String dir = args.length > 0 ? args[0] : DEFAULT_DIR;
		try {
			File tokenFile = new File(dir, "clip.token");
			File portFile = new File(dir, "clip.port");
			String token = token(tokenFile);

			history = new ClipHistory(new File(ClipHistory.DEFAULT_STORE),
					new File(ClipHistory.DEFAULT_CONF));
			// The index is built -- and the store migrated to v2 if it
			// needs it -- now, once, so nothing later has to parse the
			// whole file to answer about one entry.
			history.load();

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

			// How many conversations are open. Past the cap a connection is
			// refused politely rather than queued for ever: the client
			// asks again, and the queue's length would only hide it.
			final java.util.concurrent.Semaphore seats =
					new java.util.concurrent.Semaphore(MAX_HANDLERS);
			while (true) {
				final Socket client = server.accept();
				if (!seats.tryAcquire()) {
					try {
						client.getOutputStream().write("err:desk is busy".getBytes("UTF-8"));
						client.getOutputStream().flush();
					} catch (Throwable ignored) {
						// The client is gone already.
					}
					try {
						client.close();
					} catch (Throwable ignored) {
						// Nothing to do about it.
					}
					continue;
				}
				Thread t = new Thread(new Runnable() {
					public void run() {
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
							seats.release();
						}
					}
				});
				t.setDaemon(true);
				t.start();
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

	/**
	 * One line, read with a cap. The cap is what keeps a client from
	 * feeding the desk a line without end; the timeout is what keeps a
	 * client from opening a conversation and never speaking.
	 *
	 * @return the line without its newline, or null when the client
	 *         closed without finishing the line.
	 */
	private static String readLine(InputStream in, int cap) throws Exception {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int total = 0;
		while (true) {
			int b = in.read();
			if (b < 0) {
				if (buf.size() == 0) {
					return null;
				}
				break;
			}
			if (b == '\n') {
				break;
			}
			if (++total > cap) {
				throw new IllegalArgumentException("line too long");
			}
			buf.write(b);
		}
		return buf.toString("UTF-8");
	}

	/**
	 * The rest of the request: the payload, capped, within a grace period.
	 *
	 * <p>The payload is not waited to the end of the world: the callers
	 * speak their whole request and then hold the line open waiting for
	 * the answer, so there is no end-of-file to find. What is there is
	 * read -- one read as it comes, then a bounded wait for the rest --
	 * and once the client stops talking for the grace period the request
	 * is whatever arrived. A client that never spoke at all closes the
	 * conversation there instead of holding the thread.
	 */
	private static String readPayload(Socket client, InputStream in, int cap)
			throws Exception {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] chunk = new byte[16384];
		client.setSoTimeout(PAYLOAD_GRACE_MS);
		try {
			while (true) {
				int n = in.read(chunk);
				if (n < 0) {
					break;
				}
				buf.write(chunk, 0, n);
				if (buf.size() > cap) {
					throw new IllegalArgumentException("payload too large");
				}
				// The grace applies again from here: the client has to
				// keep speaking, or the last full chunk stands as the
				// payload.
			}
		} catch (java.net.SocketTimeoutException stalled) {
			if (buf.size() == 0) {
				throw new IllegalArgumentException("no payload");
			}
			// What arrived is what was sent.
		}
		return buf.toString("UTF-8");
	}

	/** How long the desk waits for the rest of a payload after the client
	 *  started sending it. Copies arrive in a chunk or two, or not at all. */
	private static final int PAYLOAD_GRACE_MS = 400;

	/** Read the request, do what it says, write the answer. */
	private static void answer(Socket client, String token) throws Exception {
		client.setSoTimeout(SO_TIMEOUT_MS);
		InputStream in = client.getInputStream();

		// The secret first, and before anything else is read: the order
		// that was backwards. A request that cannot show the secret is
		// closed after one line, not after it has been swallowed whole.
		String given = readLine(in, MAX_SECRET_BYTES);
		if (given == null) {
			throw new SecurityException("no secret");
		}
		if (!token.equals(given.trim())) {
			throw new SecurityException("wrong secret");
		}

		String command = readLine(in, MAX_COMMAND_BYTES);
		if (command == null) {
			throw new IllegalArgumentException("no command");
		}

		String[] parts = command.trim().split("\\s+");
		String mode = parts.length > 0 ? parts[0] : "";
		String answer;

		// Commands that carry a payload read it only now, after both the
		// secret and the command have been found sane.
		if (wantsPayload(mode)) {
			String payload = readPayload(client, in, MAX_REQUEST_BYTES);
			answer = dispatch(mode, parts, payload);
		} else {
			answer = dispatch(mode, parts, "");
		}
		OutputStream out = client.getOutputStream();
		out.write(answer.getBytes("UTF-8"));
		out.flush();
	}

	/** seal and search carry their payload on the connection; so do the
	 *  two whole-store passes, which the fallback path still asks for
	 *  while the desk is down. */
	private static boolean wantsPayload(String mode) {
		return "seal".equals(mode) || "search".equals(mode)
				|| "encrypt-store".equals(mode) || "decrypt-store".equals(mode);
	}

	/** What the command says, done. */
	private static String dispatch(String mode, String[] parts, String payload)
			throws Exception {
		switch (mode) {
			case "probe":
				ClipCrypto.probe();
				return "ok";
			case "seal":
				// The source travels as its own base64, not as bare
				// words: sources carry spaces ("this computer", app
				// names with gaps), and a command line split on
				// whitespace used to eat everything after the first
				// word -- the direction included.
				return history.append(payload.trim(), decodeSource(
						parts.length > 1 ? parts[1] : "-"),
						parsable(parts, 2) ? Integer.parseInt(parts[2]) : 0).toString();
			case "list": {
				int n = parsable(parts, 1) ? Integer.parseInt(parts[1]) : 200;
				int offset = parsable(parts, 2) ? Integer.parseInt(parts[2]) : 0;
				String out = history.list(n, offset);
				return out.isEmpty() ? "[]" : out;
			}
			case "page": {
				// The total first, then the rows. The app needs the real
				// count to say how much history there is without loading
				// all of it.
				int n = parsable(parts, 1) ? Integer.parseInt(parts[1]) : 100;
				int offset = parsable(parts, 2) ? Integer.parseInt(parts[2]) : 0;
				return "{\"total\":" + history.size() + "}\n"
						+ history.list(n, offset);
			}
			case "search":
				return history.search(payload.trim());
			case "apps":
				return history.sources();
			case "star":
				return history.star(handle(parts), 1);
			case "unstar":
				return history.star(handle(parts), 0);
			case "delete":
				return history.delete(handle(parts));
			case "clear":
				return history.clear();
			case "prune":
				return history.prune();
			case "count":
				return history.count();
			case "encrypt-store":
				return ClipCrypto.encryptStore(payload);
			case "decrypt-store":
				return ClipCrypto.decryptStore(payload);
			default:
				return "err:unknown command " + mode;
		}
	}

	/** The seal's source argument: base64, or the bare dash for none. */
	private static String decodeSource(String b64) {
		try {
			if (b64 == null || b64.isEmpty() || "-".equals(b64)) {
				return "";
			}
			return new String(java.util.Base64.getDecoder().decode(b64), "UTF-8");
		} catch (Throwable bad) {
			// Not base64: refuse to invent a source out of it.
			return "";
		}
	}

	private static boolean parsable(String[] parts, int at) {
		return parts.length > at && parts[at].matches("-?[0-9]+");
	}

	private static String handle(String[] parts) {
		return parts.length > 1 ? parts[1] : "";
	}
}

