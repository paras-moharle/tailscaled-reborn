// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

/**
 * Posts the Taildrop arrival notification.
 *
 * <p>The receiver script runs as root and cannot post a useful notification
 * itself: {@code cmd notification post} returns success but only creates a
 * transient shell channel with no actions, so the alert vanishes and offers
 * nothing to tap. Action buttons need a real Notification posted by a real
 * app, so the script detects the delivery and hands the news here over an
 * explicit broadcast.
 *
 * <p>"Open" points at {@link FilesProvider}. It used to point at a storage
 * document URI, which throws SecurityException: only an app handed a document
 * URI through SAF may use one, and a grant flag cannot manufacture that.
 */
public class ReceiveNotifier extends BroadcastReceiver {

	private static final String CHANNEL = "taildrop";
	private static final String TAG = "taildrop";

	static final String ACTION = "com.paras.tailbridge.RECEIVED";
	static final String EXTRA_NAMES = "names";
	static final String EXTRA_COUNT = "count";
	static final String EXTRA_DIR = "dir";
	static final String EXTRA_FIRST = "first";

	@Override
	public void onReceive(Context ctx, Intent intent) {
		if (intent == null || !ACTION.equals(intent.getAction())) {
			return;
		}
		int count = intent.getIntExtra(EXTRA_COUNT, 0);
		if (count <= 0) {
			return;
		}
		String names = intent.getStringExtra(EXTRA_NAMES);
		String dir = intent.getStringExtra(EXTRA_DIR);
		String first = intent.getStringExtra(EXTRA_FIRST);
		if (names == null || names.trim().isEmpty()) {
			names = "Taildrop";
		}

		NotificationManager nm =
				(NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
		if (nm == null) {
			return;
		}

		// A channel is mandatory from Android 8 and this app is minSdk 26.
		NotificationChannel ch = new NotificationChannel(CHANNEL,
				ctx.getString(R.string.taildrop_channel), NotificationManager.IMPORTANCE_HIGH);
		ch.setDescription(ctx.getString(R.string.taildrop_channel_desc));
		ch.enableVibration(true);
		nm.createNotificationChannel(ch);

		// Tapping the notification opens the app.
		Intent open = new Intent(ctx, MainActivity.class)
				.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		PendingIntent openPi = PendingIntent.getActivity(ctx, 0, open,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		String title = ctx.getResources().getQuantityString(R.plurals.taildrop_title, count, count);

		Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
				.setSmallIcon(R.drawable.ic_send)
				.setContentTitle(title)
				.setContentText(names)
				.setStyle(new Notification.BigTextStyle().bigText(names + "\n\n" + dir))
				.setAutoCancel(true)
				.setContentIntent(openPi);

		// One action: open the first file, through our own provider. An action
		// per file would exceed the three-action limit and the platform drops
		// the rest without complaint.
		if (first != null && !first.trim().isEmpty()) {
			String name = first.substring(first.lastIndexOf('/') + 1);
			Uri uri = FilesProvider.uriFor(name);
			Intent view = new Intent(Intent.ACTION_VIEW)
					.setDataAndType(uri, mimeOf(name))
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
							| Intent.FLAG_ACTIVITY_NEW_TASK);
			PendingIntent viewPi = PendingIntent.getActivity(ctx, 1, view,
					PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			b.addAction(new Notification.Action.Builder(null,
					ctx.getString(R.string.taildrop_open_file), viewPi).build());
		}

		// "Open folder": hand the folder to the system file browser. Works
		// because TailscaleDocuments is a real DocumentsProvider, so the URI is
		// ours to grant -- unlike a storage document URI, which only an app
		// handed it through SAF may use.
		// MIME_TYPE_DIR, not "resource/folder": the latter is what MiXplorer
		// registers for, but MiXplorer is not SAF-aware for directories and
		// cannot actually browse a DocumentsProvider. The standard type hands
		// this to the system file manager, which can.
		Intent browse = new Intent(Intent.ACTION_VIEW)
				.setDataAndType(TailscaleDocuments.rootUri(),
						DocumentsContract.Document.MIME_TYPE_DIR)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent browsePi = PendingIntent.getActivity(ctx, 2, browse,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		b.addAction(new Notification.Action.Builder(null,
				ctx.getString(R.string.taildrop_open_folder), browsePi).build());

		// Reuse one tag: a second delivery replaces the first rather than
		// stacking notifications nobody is going to clear.
		nm.notify(TAG, 1, b.build());
	}

	/** Kept in sync with FilesProvider.getType(). */
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
}