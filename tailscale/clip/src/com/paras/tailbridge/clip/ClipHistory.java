// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge.clip;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.json.JSONObject;

/**
 * The clipboard history, and the only thing that is allowed to write it.
 *
 * <p>Before this class there were four writers: the watcher appended, the
 * API's star and delete rewrote the whole file with jq, prune did whatever
 * was left, and every one of them raced with the others. A history written
 * by whoever gets there first is a history that can lose a password or
 * duplicate a code. So every write goes through here now: the desk is the
 * single writer, and the shell asks the desk.
 *
 * <p>The store is still append-only JSONL, one entry per line, because that
 * has proved itself: an append is O(1), a crash loses at most the line that
 * was being written, and the file can be read a line at a time. What changed
 * is who touches it and how much of it has to move:
 *
 * <ul>
 * <li>An append writes one line at the end of the file. Nothing else moves.</li>
 * <li>A repeated copy collapses into the last line in place. The old code
 * ran sed over the whole file for every repeated copy -- at five thousand
 * entries that is a megabyte moved per repeated copy. Now the file is
 * truncated by one line's worth and the replacement is appended: it touches
 * the size of one line, not one store.</li>
 * <li>A list is answered from an index built once when the desk starts, so
 * a screen refresh does not scan the file for anything.</li>
 * <li>Retention -- the entry cap and the age limit -- is enforced here,
 * because a limit without a caller is a limit that does nothing. That is
 * what every release before this one shipped with.</li>
 * </ul>
 *
 * <p>The on-disk entry (v2) keeps only what has to be readable without the
 * hardware:
 *
 * <pre>
 * {"v":2,"id":"...","t":123,"d":0,"p":0,"h":"...","c":"..."}
 * </pre>
 *
 * <p>"c" seals the text and its source inside the hardware key. The rest is
 * bound to the seal as authenticated data (AAD): readable without opening
 * the entry, and refused -- not quietly accepted -- if it is edited in
 * place. That is what stops a hand-edited timestamp, a swapped ciphertext,
 * or an un-starred line from being served as history.
 */
final class ClipHistory {

	/** Where the store lives, unless told otherwise. */
	static final String DEFAULT_STORE = "/data/adb/tailscale/clipboard.jsonl";

	/** Where the settings live: the cap and the age limit come from there. */
	static final String DEFAULT_CONF = "/data/adb/tailscale/clipboard.conf";

	/** The store is not allowed to grow without bound even if the entry cap
	 *  leaves room: one enormous copy would sit there for ever. */
	private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

	/** One entry in the index. */
	private static final class Entry {
		String id;
		long t;
		int d;
		int p;
		String h;
		String c;
		long offset;
		int len;

		JSONObject line() throws Exception {
			JSONObject o = new JSONObject();
			o.put("v", 2);
			o.put("id", id);
			o.put("t", t);
			o.put("d", d);
			o.put("p", p);
			o.put("h", h);
			o.put("c", c);
			return o;
		}
	}

	private final File storeFile;
	private final File confFile;
	private final List<Entry> entries = new ArrayList<Entry>();

	/** Keeps the file's writes to one thread at a time. */
	private final Object lock = new Object();

	ClipHistory(File storeFile, File confFile) {
		this.storeFile = storeFile;
		this.confFile = confFile;
	}

	// ---------------------------------------------------------------- id --

	/** A stable handle for one entry: the time and the wall clock's tail. */
	static String newId(long t) {
		return Long.toString(t, 36) + "-" + Long.toString(
				System.nanoTime() & 0xffffL, 36);
	}

	/** The AAD that binds one entry together. */
	static String aad(String id, long t, int d, int p, String h) {
		return "v2|" + id + "|" + t + "|" + d + "|" + p + "|" + h;
	}

	// -------------------------------------------------------------- load --

	/**
	 * Reads the store into the index, offsets and all. After this the desk
	 * can answer about any entry without touching the file.
	 *
	 * <p>If any line is not yet v2, the whole file is migrated to v2 in one
	 * pass. It happens once, when the upgraded module first starts the
	 * desk: the old entries are opened with their own format and written
	 * back sealed. A five-thousand entry store pays for that once -- asking
	 * the hardware about every entry it has kept all along -- and from then
	 * on the fast path never opens more than it was asked to.
	 *
	 * <p>An entry that cannot be opened at all is dropped, with a note:
	 * carrying a broken line forward meant every later reader tripped over
	 * it, and an err: marked line in the middle of the history used to be
	 * enough to confuse whoever tried to read the whole file.
	 */
	void load() throws Exception {
		synchronized (lock) {
			entries.clear();
			if (!storeFile.exists()) {
				return;
			}
			byte[] blob = java.nio.file.Files.readAllBytes(storeFile.toPath());
			String text = new String(blob, "UTF-8");
			String[] lines = text.split("\n", -1);
			int offset = 0;
			boolean migrate = false;
			for (String raw : lines) {
				String line = raw.trim();
				int len = raw.length();
				if (!line.isEmpty()) {
					try {
						JSONObject o = new JSONObject(line);
						int version = o.optInt("v", 1);
						Entry e = new Entry();
						e.id = o.optString("id", "");
						e.t = o.optLong("t", 0);
						e.d = o.optInt("d", 0);
						e.p = o.optInt("p", 0);
						e.h = o.optString("h", "");
						e.c = o.optString("c", "");
						e.offset = offset;
						e.len = len;
						if (version >= 2 && !e.id.isEmpty()
								&& !e.h.isEmpty() && !e.c.isEmpty()) {
							entries.add(e);
						} else {
							migrate = true;
						}
					} catch (Throwable bad) {
						// Not JSON, or not ours: the file gets rewritten
						// without it.
						migrate = true;
					}
				}
				offset += len + 1;
			}
			if (migrate) {
				migrate(text);
			}
		}
	}

	/** The store, rewritten in v2. */
	private void migrate(String text) throws Exception {
		String[] lines = text.split("\n", -1);
		StringBuilder keep = new StringBuilder();
		int kept = 0;
		int dropped = 0;
		for (String line : lines) {
			line = line.trim();
			if (line.isEmpty()) {
				continue;
			}
			try {
				JSONObject o = new JSONObject(line);
				if (o.optInt("v", 1) >= 2 && !o.optString("h", "").isEmpty()
						&& !o.optString("c", "").isEmpty()) {
					// Already in the new shape.
					keep.append(o.toString()).append('\n');
					kept++;
					continue;
				}
				// v1: the text may still be unsealed, or sealed the old way.
				String c = o.optString("c", "");
				String hash = o.optString("h", "");
				String txt = null;
				try {
					if (hash.isEmpty() || hash.startsWith("plain:")) {
						txt = new String(Base64.getDecoder().decode(c), "UTF-8");
					} else {
						byte[] plain = ClipCrypto.OPENED.get(c);
						if (plain == null) {
							plain = ClipCrypto.openV1(c);
							ClipCrypto.OPENED.put(c, plain);
						}
						txt = new String(plain, "UTF-8");
					}
				} catch (Throwable unreadable) {
					txt = null;
				}
				if (txt == null || txt.isEmpty()) {
					dropped++;
					continue;
				}
				JSONObject inner = new JSONObject();
				inner.put("text", txt);
				inner.put("s", o.optString("s", ""));
				String newHash = ClipCrypto.historyHash(txt);
				String id = newId(o.optLong("t", System.currentTimeMillis()));
				String aad = aad(id, o.optLong("t", System.currentTimeMillis()),
						o.optInt("d", 0), o.optInt("p", 0), newHash);
				JSONObject out = new JSONObject();
				out.put("v", 2);
				out.put("id", id);
				out.put("t", o.optLong("t", System.currentTimeMillis()));
				out.put("d", o.optInt("d", 0));
				out.put("p", o.optInt("p", 0));
				out.put("h", newHash);
				out.put("c", ClipCrypto.sealBlob(inner.toString(), aad));
				keep.append(out.toString()).append('\n');
				kept++;
			} catch (Throwable bad) {
				dropped++;
			}
		}
		java.nio.file.Files.write(storeFile.toPath(),
				keep.toString().getBytes("UTF-8"));
		chmod();
		reloadIndex();
		if (dropped > 0) {
			System.out.println("note:migration dropped " + dropped
					+ " unreadable entr" + (dropped == 1 ? "y" : "ies"));
		}
	}

	/** Re-reads the index from the file after a rewrite. */
	private void reloadIndex() throws Exception {
		entries.clear();
		if (!storeFile.exists()) {
			return;
		}
		byte[] blob = java.nio.file.Files.readAllBytes(storeFile.toPath());
		int offset = 0;
		for (String raw : new String(blob, "UTF-8").split("\n", -1)) {
			String line = raw.trim();
			int len = raw.length();
			if (!line.isEmpty()) {
				try {
					JSONObject o = new JSONObject(line);
					if (o.optInt("v", 1) >= 2 && !o.optString("id", "").isEmpty()
							&& !o.optString("h", "").isEmpty()
							&& !o.optString("c", "").isEmpty()) {
						Entry e = new Entry();
						e.id = o.optString("id", "");
						e.t = o.optLong("t", 0);
						e.d = o.optInt("d", 0);
						e.p = o.optInt("p", 0);
						e.h = o.optString("h", "");
						e.c = o.optString("c", "");
						e.offset = offset;
						e.len = len;
						entries.add(e);
					}
				} catch (Throwable ignored) {
					// It did not survive the rewrite; nothing depended on it.
				}
			}
			offset += len + 1;
		}
	}

	// ------------------------------------------------------------ append --

	/**
	 * Seals one copy and writes it down, which is what the watcher used to
	 * do in four steps with three race windows. The answer is the entry as
	 * it now stands on disk.
	 *
	 * <p>The same text again is not a new entry. It collapses into the
	 * last line in place: the file is truncated by one line and the
	 * replacement is appended. What the last line knew that the new one
	 * does not, the replacement keeps: a copy that arrived from a peer
	 * keeps the peer's identity, so the loop guard keeps working, and a
	 * starred entry keeps its star -- the collapse used to quietly un-star
	 * whatever was repeated.
	 *
	 * <p>Retention follows the write: the cap and the age limit come from
	 * the settings, and the store is brought back under them before the
	 * answer goes out. The starred entries survive the trim.
	 */
	JSONObject append(String b64, String source, int d) throws Exception {
		byte[] plain = Base64.getDecoder().decode(b64 == null ? "" : b64.trim());
		if (plain.length == 0) {
			throw new IllegalArgumentException("empty clip");
		}
		String text = new String(plain, "UTF-8");
		synchronized (lock) {
			String hash = ClipCrypto.historyHash(text);
			if (!entries.isEmpty()) {
				Entry last = entries.get(entries.size() - 1);
				if (hash.equals(last.h)) {
					return collapse(last, text, source, d);
				}
			}
			JSONObject built = build(System.currentTimeMillis(), source, d, 0, text);
			byte[] line = (built.toString() + "\n").getBytes("UTF-8");
			RandomAccessFile raf = new RandomAccessFile(storeFile, "rw");
			try {
				raf.seek(raf.length());
				raf.write(line);
				raf.getFD().sync();
			} finally {
				try {
					raf.close();
				} catch (Throwable ignored) {
					// Nothing to do about it.
				}
			}
			Entry e = new Entry();
			e.id = built.optString("id", "");
			e.t = built.optLong("t", 0);
			e.d = built.optInt("d", 0);
			e.p = 0;
			e.h = built.optString("h", "");
			e.c = built.optString("c", "");
			e.offset = lastFileOffset() - line.length;
			e.len = line.length - 1;
			entries.add(e);
			chmod();
			enforce();
			return built;
		}
	}

	/**
	 * The collapse: the last line is replaced by a body that carries the
	 * old line's knowledge -- the peer identity, the star -- and the new
	 * line's truth, which is the time the text was actually copied again.
	 */
	private JSONObject collapse(Entry last, String text, String source, int d)
			throws Exception {
		JSONObject opened = openEntry(last);
		String oldSource = opened.optString("s", "");
		// The rule the history has always had: a copy that arrived from a
		// peer keeps the peer's identity -- the direction and the source
		// -- because losing it would break the loop guard. Any other
		// collapse keeps the new copy's own direction and source, which is
		// what makes a peer-delivered clip marked, whichever order the
		// receiver and the watcher's echo happen to write in.
		int nd = (last.d == 1) ? 1 : d;
		String src = (last.d == 1) ? oldSource : source;
		JSONObject built = buildWith(last.id, System.currentTimeMillis(), nd,
				last.p, src, text);
		byte[] line = (built.toString() + "\n").getBytes("UTF-8");
		RandomAccessFile raf = new RandomAccessFile(storeFile, "rw");
		try {
			raf.setLength(last.offset);
			raf.seek(last.offset);
			raf.write(line);
			raf.getFD().sync();
		} finally {
			try {
				raf.close();
			} catch (Throwable ignored) {
				// Nothing to do about it.
			}
		}
		Entry e = new Entry();
		e.id = last.id;
		e.t = built.optLong("t", 0);
		e.d = nd;
		e.p = last.p;
		e.h = built.optString("h", "");
		e.c = built.optString("c", "");
		e.offset = last.offset;
		e.len = line.length - 1;
		entries.set(entries.size() - 1, e);
		chmod();
		return built;
	}

	/** The byte offset the next append goes to. */
	private long lastFileOffset() throws Exception {
		RandomAccessFile raf = new RandomAccessFile(storeFile, "r");
		try {
			return raf.length();
		} finally {
			try {
				raf.close();
			} catch (Throwable ignored) {
				// Nothing to do about it.
			}
		}
	}

	/**
	 * Builds one v2 entry: the blob seals the text and its source, the
	 * outside carries the identity, and the hash lets the next copy find
	 * its twin without opening anything.
	 */
	private JSONObject build(long t, String source, int d, int p, String text)
			throws Exception {
		return buildWith(newId(t), t, d, p, source, text);
	}

	/** build(), with the identity already decided. */
	private JSONObject buildWith(String id, long t, int d, int p, String source,
			String text) throws Exception {
		JSONObject inner = new JSONObject();
		inner.put("text", text);
		inner.put("s", source == null ? "" : source);
		String hash = ClipCrypto.historyHash(text);
		String aad = aad(id, t, d, p, hash);
		String c = ClipCrypto.sealBlob(inner.toString(), aad);
		JSONObject o = new JSONObject();
		o.put("v", 2);
		o.put("id", id);
		o.put("t", t);
		o.put("d", d);
		o.put("p", p);
		o.put("h", hash);
		o.put("c", c);
		return o;
	}

	/**
	 * Opens one entry: the blob with its source, and the text as the
	 * caller expects it -- base64, the same shape the history has always
	 * been handed over in.
	 */
	private JSONObject openEntry(Entry e) throws Exception {
		JSONObject inner;
		// The hardware takes a few milliseconds for every open it is
		// asked to do, and a list of the recent history is asked for over
		// and over. The blob of an entry does not change once it is
		// written -- a star rewrites the whole blob, and that is a new
		// seal with a new "c" -- so the answer is kept, least-recently
		// used, in the same cache the fallback path digs for.
		byte[] hit = ClipCrypto.OPENED.get(e.c);
		if (hit != null) {
			inner = new JSONObject(new String(hit, "UTF-8"));
		} else {
			String aad = aad(e.id, e.t, e.d, e.p, e.h);
			inner = new JSONObject(ClipCrypto.openBlob(e.c, aad));
			ClipCrypto.OPENED.put(e.c, inner.toString().getBytes("UTF-8"));
		}
		JSONObject o = new JSONObject();
		o.put("t", e.t);
		o.put("c", Base64.getEncoder().encodeToString(
				inner.optString("text", "").getBytes("UTF-8")));
		o.put("s", inner.optString("s", ""));
		o.put("d", e.d);
		o.put("p", e.p);
		o.put("id", e.id);
		return o;
	}

	// ------------------------------------------------------------- reads --

	/** The newest n entries, newest first. n of zero means everything. */
	String list(int n) throws Exception {
		synchronized (lock) {
			StringBuilder out = new StringBuilder();
			int want = (n <= 0) ? entries.size() : n;
			for (int i = entries.size() - 1; i >= 0 && want > 0; i--, want--) {
				out.append(openEntry(entries.get(i)).toString()).append('\n');
			}
			return out.toString();
		}
	}

	/** Everything the query matches, newest first. One array, the shape
	 *  the API's search has always answered with. */
	String search(String query) throws Exception {
		synchronized (lock) {
			StringBuilder out = new StringBuilder("[");
			boolean first = true;
			for (int i = entries.size() - 1; i >= 0; i--) {
				JSONObject o = openEntry(entries.get(i));
				String text = "";
				try {
					text = new String(
							Base64.getDecoder().decode(o.optString("c", "")),
							"UTF-8");
				} catch (Throwable ignored) {
					// Not text, and text is the only thing that matches.
				}
				if (text.contains(query)) {
					if (!first) {
						out.append(',');
					}
					first = false;
					out.append(o.toString());
				}
			}
			return out.append(']').toString();
		}
	}

	/** What the settings screen shows: who copied what, how often. */
	String sources() throws Exception {
		synchronized (lock) {
			java.util.LinkedHashMap<String, Integer> counts =
					new java.util.LinkedHashMap<String, Integer>();
			for (int i = entries.size() - 1; i >= 0; i--) {
				String s = sourceOf(entries.get(i));
				if (s == null || s.isEmpty()) {
					continue;
				}
				Integer have = counts.get(s);
				counts.put(s, (have == null ? 0 : have) + 1);
			}
			StringBuilder out = new StringBuilder("[");
			boolean first = true;
			for (java.util.Map.Entry<String, Integer> k : counts.entrySet()) {
				JSONObject o = new JSONObject();
				o.put("source", k.getKey());
				o.put("count", k.getValue());
				if (!first) {
					out.append(',');
				}
				first = false;
				out.append(o.toString());
			}
			return out.append(']').toString();
		}
	}

	private String sourceOf(Entry e) throws Exception {
		JSONObject plain = openEntry(e);
		return plain.optString("s", "");
	}

	// --------------------------------------------------------- mutations --

	/**
	 * Star, or unstar. The handle is the id, and the timestamp is taken
	 * as well, for callers that have not moved over yet.
	 */
	String star(String handle, int p) throws Exception {
		synchronized (lock) {
			int at = find(handle);
			JSONObject o = new JSONObject();
			o.put("ok", true);
			if (at < 0) {
				o.put("found", 0);
				return o.toString();
			}
			Entry e = entries.get(at);
			JSONObject inner = new JSONObject(ClipCrypto.openBlob(e.c,
					aad(e.id, e.t, e.d, e.p, e.h)));
			String again = buildWith(e.id, e.t, e.d, p,
					inner.optString("s", ""), inner.optString("text", ""))
					.toString();
			rewrite(again, at);
			o.put("found", 1);
			return o.toString();
		}
	}

	/** Remove one entry by id or timestamp. */
	String delete(String handle) throws Exception {
		synchronized (lock) {
			int at = find(handle);
			JSONObject o = new JSONObject();
			o.put("ok", true);
			if (at < 0) {
				o.put("found", 0);
				return o.toString();
			}
			entries.remove(at);
			rewriteAll();
			o.put("found", 1);
			return o.toString();
		}
	}

	/** Drop everything. What was starred is not coming back either. */
	String clear() throws Exception {
		synchronized (lock) {
			RandomAccessFile raf = new RandomAccessFile(storeFile, "rw");
			try {
				raf.setLength(0);
				raf.getFD().sync();
			} finally {
				try {
					raf.close();
				} catch (Throwable ignored) {
					// Nothing to do about it.
				}
			}
			entries.clear();
			chmod();
			JSONObject o = new JSONObject();
			o.put("ok", true);
			return o.toString();
		}
	}

	/**
	 * Bring the store under its limits, and say how many entries went.
	 *
	 * <p>The entry cap first, then the age limit, then the size cap. A
	 * starred entry survives all of them: it was starred on purpose, and a
	 * limit that quietly took starred entries away would be the limit
	 * overruling the person who starred it.
	 */
	String prune() throws Exception {
		synchronized (lock) {
			int before = entries.size();
			enforce();
			JSONObject o = new JSONObject();
			o.put("ok", true);
			o.put("removed", before - entries.size());
			return o.toString();
		}
	}

	/** The cap and the age limit, enforced. */
	private void enforce() throws Exception {
		int max = confInt("max", 5000);
		int days = confInt("days", 0);

		// By age.
		if (days > 0) {
			long cutoff = System.currentTimeMillis() - days * 86400000L;
			List<Integer> drop = new ArrayList<Integer>();
			for (int i = 0; i < entries.size(); i++) {
				Entry e = entries.get(i);
				if (e.t < cutoff && e.p != 1) {
					drop.add(i);
				}
			}
			if (!drop.isEmpty()) {
				dropAndRewrite(drop);
			}
		}

		// By count: oldest first.
		int overCount = entries.size() - max;
		if (overCount > 0) {
			List<Integer> drop = new ArrayList<Integer>();
			for (int i = 0; i < entries.size() && drop.size() < overCount; i++) {
				if (entries.get(i).p != 1) {
					drop.add(i);
				}
			}
			if (!drop.isEmpty()) {
				dropAndRewrite(drop);
			}
		}

		// By size: the same rule, until the file fits. The bytes are counted
		// from the index, since the rewrite that answers them happens once.
		long over = storeFile.length() - MAX_FILE_BYTES;
		if (over > 0) {
			List<Integer> drop = new ArrayList<Integer>();
			long freed = 0;
			for (int i = 0; i < entries.size() && freed < over; i++) {
				Entry e = entries.get(i);
				if (e.p != 1 && e.len > 0) {
					drop.add(i);
					freed += e.len + 1;
				}
			}
			if (!drop.isEmpty()) {
				dropAndRewrite(drop);
			}
		}
	}

	private int find(String handle) {
		if (handle == null || handle.isEmpty()) {
			return -1;
		}
		for (int i = entries.size() - 1; i >= 0; i--) {
			if (handle.equals(entries.get(i).id)) {
				return i;
			}
		}
		// A legacy caller names the entry by its timestamp.
		if (handle.matches("[0-9]{10,}")) {
			try {
				long t = Long.parseLong(handle);
				for (int i = entries.size() - 1; i >= 0; i--) {
					if (entries.get(i).t == t) {
						return i;
					}
				}
			} catch (Throwable ignored) {
				// Not a number after all.
			}
		}
		return -1;
	}

	/** How many entries the history holds. */
	String count() throws Exception {
		synchronized (lock) {
			JSONObject o = new JSONObject();
			o.put("ok", true);
			o.put("count", entries.size());
			return o.toString();
		}
	}

	// ----------------------------------------------------------- helpers --

	/**
	 * The store, rewritten with one line replaced by new content. Star is a
	 * user action, rare by any measure, so a whole-file rewrite is the
	 * price it pays; the hot paths -- append and dedup -- touch one line.
	 */
	private void rewrite(String replacement, int at) throws Exception {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < entries.size(); i++) {
			if (i == at) {
				JSONObject o = new JSONObject(replacement.trim());
				JSONObject line = new JSONObject();
				line.put("v", 2);
				line.put("id", o.optString("id"));
				line.put("t", o.optLong("t"));
				line.put("d", o.optInt("d"));
				line.put("p", o.optInt("p"));
				line.put("h", o.optString("h"));
				line.put("c", o.optString("c"));
				out.append(line.toString()).append('\n');
			} else {
				out.append(entries.get(i).line().toString()).append('\n');
			}
		}
		java.nio.file.Files.write(storeFile.toPath(),
				out.toString().getBytes("UTF-8"));
		chmod();
		reloadIndex();
	}

	private void rewriteAll() throws Exception {
		StringBuilder out = new StringBuilder();
		for (Entry e : entries) {
			out.append(e.line().toString()).append('\n');
		}
		java.nio.file.Files.write(storeFile.toPath(),
				out.toString().getBytes("UTF-8"));
		chmod();
		reloadIndex();
	}

	/**
	 * The rewrite that retention asks for: the file without the entries
	 * named, and the index rebuilt from what is left.
	 */
	private void dropAndRewrite(List<Integer> drop) throws Exception {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < entries.size(); i++) {
			if (!drop.contains(i)) {
				out.append(entries.get(i).line().toString()).append('\n');
			}
		}
		java.nio.file.Files.write(storeFile.toPath(),
				out.toString().getBytes("UTF-8"));
		chmod();
		reloadIndex();
	}

	private void chmod() {
		try {
			android.system.Os.chmod(storeFile.getAbsolutePath(), 0600);
		} catch (Throwable ignored) {
			// The file was already private, or that is as far as we get.
		}
	}

	private int confInt(String key, int fallback) {
		try {
			if (confFile.exists()) {
				for (String line : java.nio.file.Files.readAllLines(confFile.toPath())) {
					if (line.startsWith(key + "=")) {
						String v = line.substring(key.length() + 1).trim();
						if (!v.isEmpty() && v.matches("[0-9]+")) {
							return Integer.parseInt(v);
						}
					}
				}
			}
		} catch (Throwable ignored) {
			// The settings are unreadable; the default stands.
		}
		return fallback;
	}
}
