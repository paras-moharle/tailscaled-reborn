// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Exposes the Taildrop folder to the system file browser.
 *
 * <p>This is what makes "Open folder" actually work.
 *
 * <p>A storage document URI cannot be used at all: only an app handed one
 * through SAF may use one. That is why the first attempt at this button threw
 * SecurityException and looked like a dead button. Building the provider
 * ourselves is the only way to hand out a URI we are allowed to grant.
 *
 * <p>Scope is one directory -- {@code Download/Tailscale} by default, or
 * whatever the user picked on the System tab -- exposed as a single root. It
 * is not a general file browser: every id is resolved to a real path inside
 * that one directory, and anything else is rejected.
 */
public class TailscaleDocuments extends DocumentsProvider {

	private static final String ROOT_ID = "taildrop-root";

	private File root() {
		return Store.receiveDir(getContext());
	}

	/** Map a document id to a path, refusing anything outside the root. */
	private File fileFor(String docId) throws FileNotFoundException {
		if (docId == null || docId.isEmpty()) {
			return root();
		}
		if (ROOT_ID.equals(docId)) {
			return root();
		}
		String name = docId;
		int slash = name.lastIndexOf('/');
		if (slash >= 0) {
			name = name.substring(slash + 1);
		}
		if (name.contains("/") || name.contains("..")) {
			throw new FileNotFoundException("bad id");
		}
		File dir = root();
		File f;
		try {
			File canonRoot = dir.getCanonicalFile();
			f = new File(dir, name).getCanonicalFile();
			if (!canonRoot.equals(f.getParentFile())) {
				throw new FileNotFoundException("outside the Taildrop directory");
			}
		} catch (java.io.IOException e) {
			throw new FileNotFoundException("cannot resolve " + docId);
		}
		return f;
	}

	private static String[] cols() {
		return new String[]{
				DocumentsContract.Document.COLUMN_DOCUMENT_ID,
				DocumentsContract.Document.COLUMN_DISPLAY_NAME,
				DocumentsContract.Document.COLUMN_MIME_TYPE,
				DocumentsContract.Document.COLUMN_SIZE,
				DocumentsContract.Document.COLUMN_LAST_MODIFIED,
				DocumentsContract.Document.COLUMN_FLAGS,
		};
	}

	private static String[] rootCols() {
		return new String[]{
				DocumentsContract.Root.COLUMN_ROOT_ID,
				DocumentsContract.Root.COLUMN_DOCUMENT_ID,
				DocumentsContract.Root.COLUMN_TITLE,
				DocumentsContract.Root.COLUMN_SUMMARY,
				DocumentsContract.Root.COLUMN_ICON,
				DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
				DocumentsContract.Root.COLUMN_FLAGS,
		};
	}

	/**
	 * A cursor whose columns are exactly what the caller asked for.
	 *
	 * <p>The caller chooses the projection -- a file browser routinely asks
	 * for columns this provider has no data for -- so rows must be built by
	 * name. Filling a MatrixCursor positionally with a fixed set of values
	 * throws as soon as the two disagree, which is every real file browser.
	 */
	private static MatrixCursor cursor(String[] projection, String[] defaults) {
		return new MatrixCursor(projection != null ? projection : defaults, 1);
	}

	private static void row(MatrixCursor c, java.util.Map<String, Object> values) {
		String[] names = c.getColumnNames();
		Object[] out = new Object[names.length];
		for (int i = 0; i < names.length; i++) {
			out[i] = values.get(names[i]);
		}
		c.addRow(out);
	}

	private java.util.Map<String, Object> docValues(File f, String id) {
		java.util.Map<String, Object> v = new java.util.HashMap<>();
		boolean dir = f.isDirectory();
		v.put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
		v.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, f.getName());
		v.put(DocumentsContract.Document.COLUMN_MIME_TYPE,
				dir ? DocumentsContract.Document.MIME_TYPE_DIR : mimeOf(f.getName()));
		v.put(DocumentsContract.Document.COLUMN_SIZE, dir ? null : f.length());
		v.put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, f.lastModified());
		v.put(DocumentsContract.Document.COLUMN_FLAGS, 0);
		return v;
	}

	static String mimeOf(String name) {
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

	/**
	 * The framework's entry point is queryRoots, not queryRootDocuments: the
	 * older name is deprecated but still called on some OEM builds, so both
	 * answer the same question.
	 */
	@Override
	public boolean onCreate() {
		return true;
	}

	@Override
	public Cursor queryRoots(String[] projection) {
		MatrixCursor c = cursor(projection, rootCols());
		File dir = root();
		java.util.Map<String, Object> v = new java.util.HashMap<>();
		v.put(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
		v.put(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID);
		v.put(DocumentsContract.Root.COLUMN_TITLE, dir.getName());
		v.put(DocumentsContract.Root.COLUMN_SUMMARY, dir.getAbsolutePath());
		v.put(DocumentsContract.Root.COLUMN_ICON, null);
		v.put(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, dir.getUsableSpace());
		v.put(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY);
		row(c, v);
		return c;
	}

	public Cursor queryRootDocuments(String[] projection, String sortOrder) {
		MatrixCursor c = cursor(projection, cols());
		File dir = root();
		java.util.Map<String, Object> v = new java.util.HashMap<>();
		v.put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_ID);
		v.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, dir.getName());
		v.put(DocumentsContract.Document.COLUMN_MIME_TYPE,
				DocumentsContract.Document.MIME_TYPE_DIR);
		v.put(DocumentsContract.Document.COLUMN_SIZE, null);
		v.put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, dir.lastModified());
		v.put(DocumentsContract.Document.COLUMN_FLAGS, 0);
		row(c, v);
		return c;
	}

	@Override
	public Cursor queryDocument(String documentId, String[] projection) {
		MatrixCursor c = cursor(projection, cols());
		try {
			File f = fileFor(documentId);
			row(c, docValues(f, ROOT_ID.equals(documentId) ? ROOT_ID : f.getName()));
		} catch (FileNotFoundException e) {
			// an unknown id yields an empty cursor rather than crashing
		}
		return c;
	}

	@Override
	public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
			String sortOrder) {
		MatrixCursor c = cursor(projection, cols());
		try {
			File dir = fileFor(parentDocumentId);
			if (!dir.isDirectory()) {
				return c;
			}
			File[] kids = dir.listFiles();
			if (kids == null) {
				return c;
			}
			java.util.Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
			for (File f : kids) {
				row(c, docValues(f, f.getName()));
			}
		} catch (FileNotFoundException ignored) {
			// empty cursor
		}
		return c;
	}

	@Override
	public ParcelFileDescriptor openDocument(String documentId, String mode,
			CancellationSignal signal) throws FileNotFoundException {
		if (!"r".equals(mode)) {
			throw new FileNotFoundException("read-only");
		}
		File f = fileFor(documentId);
		if (!f.isFile()) {
			throw new FileNotFoundException("not a file: " + documentId);
		}
		return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
	}

	@Override
	public boolean isChildDocument(String parentDocumentId, String documentId) {
		try {
			File parent = fileFor(parentDocumentId);
			if (!parent.isDirectory()) {
				return false;
			}
			if (ROOT_ID.equals(documentId)) {
				return false;
			}
			File child = fileFor(documentId);
			return parent.getCanonicalFile().equals(child.getParentFile());
		} catch (Exception e) {
			return false;
		}
	}

	/** The URI that opens this provider's root folder in any file browser. */
	static android.net.Uri rootUri() {
		return DocumentsContract.buildDocumentUri(
				"com.paras.tailbridge.docs", ROOT_ID);
	}
}