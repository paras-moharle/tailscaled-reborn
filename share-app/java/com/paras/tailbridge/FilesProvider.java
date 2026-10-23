// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Serves received files to other apps.
 *
 * <p>This exists because the obvious approach cannot work. A notification
 * action pointing at
 * {@code content://com.android.externalstorage.documents/document/primary%3A...}
 * throws {@code SecurityException}: a document URI may only be used by an app
 * that was handed it through SAF (ACTION_OPEN_DOCUMENT and friends), and
 * FLAG_GRANT_READ_URI_PERMISSION cannot manufacture that grant for a URI we do
 * not own. Which is why "Open folder" looked alive and silently did nothing.
 *
 * <p>A provider we own is the thing that actually works: we wrote the file, we
 * serve the descriptor, and the grant we hand out is real.
 *
 * <p>Deliberately hand-rolled rather than androidx FileProvider. This app has
 * no AndroidX dependency and should not gain one to open a text file.
 *
 * <p>Scoped to the Taildrop directory only. It is not a general file browser:
 * {@link #query} and {@link #openFile} reject anything that is not a direct
 * child of {@link #ROOT}, so a crafted URI cannot reach the rest of the device.
 */
public class FilesProvider extends ContentProvider {

	static final String AUTHORITY = "com.paras.tailbridge.files";
	/** Path segment that opens the directory itself, for the folder action. */
	private static final String DIR = ".";

	private static File root() {
		File d = android.os.Environment.getExternalStoragePublicDirectory(
				android.os.Environment.DIRECTORY_DOWNLOADS);
		return new File(d, "Tailscale");
	}

	/** Reject anything that is not a plain name inside the Taildrop directory. */
	private static File resolve(String name) throws FileNotFoundException {
		if (name == null || name.isEmpty() || name.contains("/") || name.contains("..")) {
			throw new FileNotFoundException("bad name: " + name);
		}
		File dir = root();
		try {
			// Canonicalise before comparing: without this, a symlink inside the
			// directory would pass the name check and then open something else.
			File canonRoot = dir.getCanonicalFile();
			File canon = new File(dir, name).getCanonicalFile();
			if (!canonRoot.equals(canon.getParentFile())) {
				throw new FileNotFoundException("outside the Taildrop directory");
			}
			return canon;
		} catch (java.io.IOException e) {
			throw new FileNotFoundException("cannot resolve " + name);
		}
	}

	private static String mimeOf(String name) {
		int dot = name.lastIndexOf('.');
		String ext = dot > 0 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
		switch (ext) {
			case "txt": case "log": case "md": return "text/plain";
			case "json": return "application/json";
			case "csv": return "text/csv";
			case "png": return "image/png";
			case "jpg": case "jpeg": return "image/jpeg";
			case "gif": return "image/gif";
			case "webp": return "image/webp";
			case "svg": return "image/svg+xml";
			case "pdf": return "application/pdf";
			case "zip": return "application/zip";
			case "gz": case "tgz": return "application/gzip";
			case "mp4": return "video/mp4";
			case "mkv": return "video/x-matroska";
			case "mp3": return "audio/mpeg";
			case "wav": return "audio/wav";
			case "apk": return "application/vnd.android.package-archive";
			default: return "application/octet-stream";
		}
	}

	static Uri uriFor(String name) {
		return new Uri.Builder()
				.scheme("content")
				.authority(AUTHORITY)
				.appendPath(name)
				.build();
	}

	@Override
	public boolean onCreate() {
		return true;
	}

	@Override
	public String getType(Uri uri) {
		List<String> seg = uri.getPathSegments();
		if (seg == null || seg.isEmpty()) {
			return null;
		}
		if (seg.size() == 1 && DIR.equals(seg.get(0))) {
			return "vnd.android.document/directory";
		}
		try {
			File f = resolve(seg.get(seg.size() - 1));
			return mimeOf(f.getName());
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
		if (!"r".equals(mode)) {
			// Read-only. This provider exists to hand files out, not to let a
			// notification tap rewrite something in the Downloads directory.
			throw new FileNotFoundException("read-only");
		}
		List<String> seg = uri.getPathSegments();
		if (seg == null || seg.isEmpty() || DIR.equals(seg.get(0))) {
			throw new FileNotFoundException("not a file");
		}
		File f = resolve(seg.get(seg.size() - 1));
		if (!f.isFile()) {
			throw new FileNotFoundException(f.getName());
		}
		try {
			return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
		} catch (FileNotFoundException refused) {
			// The file is there, but this app may not touch it directly.
			// Received files are staged by the module as root, and the FUSE
			// view can show them owned by another uid with a group the app
			// is not in; the direct open is then refused while the file
			// itself is perfect. The app has root; a pipe from `cat` serves
			// the bytes without turning the provider into a browser.
			return throughRoot(f);
		}
	}

	/**
	 * One file, streamed through root, into a pipe.
	 *
	 * <p>A new process rather than the shared shell: `cat` of a binary
	 * would flood Root's line protocol, and a transfer must not be
	 * seated behind the fifteen-second status poll on the single shell.
	 * Once the bytes are gone the process is destroyed and the pipe is
	 * closed by the other end's reader.
	 */
	private ParcelFileDescriptor throughRoot(File f) throws FileNotFoundException {
		try {
			ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
			final ParcelFileDescriptor writeEnd = pipe[1];
			final Process p = Runtime.getRuntime().exec(
					new String[] { "/system/bin/su", "-c",
							"cat '" + f.getCanonicalPath().replace("'", "'\\''") + "' 2>/dev/null" });
			new Thread(() -> {
				try (java.io.InputStream src = p.getInputStream();
						java.io.OutputStream dst =
								new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
					byte[] buf = new byte[8192];
					int n;
					while ((n = src.read(buf)) >= 0) {
						dst.write(buf, 0, n);
					}
				} catch (Throwable ignored) {
					// The reader hung up, or the file vanished. Either way
					// the descriptor is closing itself now.
				} finally {
					p.destroy();
				}
			}, "tailbridge-serve").start();
			return pipe[0];
		} catch (Exception e) {
			throw new FileNotFoundException("cannot open " + f.getName() + ": " + e);
		}
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection,
			String[] selectionArgs, String sortOrder) {
		String[] cols = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
		MatrixCursor c = new MatrixCursor(cols, 1);
		List<String> seg = uri.getPathSegments();
		if (seg != null && !seg.isEmpty()) {
			try {
				File f = resolve(seg.get(seg.size() - 1));
				c.addRow(new Object[]{f.getName(), f.length()});
			} catch (Exception ignored) {
				// empty cursor
			}
		}
		return c;
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}
}