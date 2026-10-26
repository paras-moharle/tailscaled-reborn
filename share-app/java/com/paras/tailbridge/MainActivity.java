// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tailscale for a rooted phone running the daemon in userspace mode.
 *
 * Deliberately small: every control here was verified to actually change
 * something on the device. Taildrop is the headline feature; the rest is
 * status, device list, the preference toggles that genuinely take effect,
 * service control, software updates and the daemon log.
 *
 * There is no web interface: this app is the whole thing.
 */
public class MainActivity extends Activity {

	private static final String TAG = "TailBridge";

	private static final String CONSOLE_URL = "https://login.tailscale.com/admin/machines";
	private static final String GITHUB_URL = "https://github.com/paras-moharle/tailscaled-reborn";
	private static final int REQ_PICK = 1001;

	private final Handler ui = new Handler(Looper.getMainLooper());

	/** The supervisor's state line in the daemon group, updated on every poll. */
	private TextView supervisorSub;

	/** Wakes the peer list when wifi or mobile data flips, so a toggle reflects
	 *  online/offline without restarting the app. */
	private ConnectivityManager.NetworkCallback networkCallback;

	/** peer name -> ip, for every known peer, online or not. */
	private final Map<String, String> peers = new LinkedHashMap<>();
	/** The subset that is currently reachable. Sending is allowed to these only. */
	private final Set<String> onlinePeers = new LinkedHashSet<>();
	/** peers ticked as send targets. */
	private final Set<String> chosen = new LinkedHashSet<>();
	/** pref key -> the switch showing it, so a failed write can be undone. */
	private final Map<String, android.widget.Switch> switches = new LinkedHashMap<>();

	/** Self-name / address values, patched in place by the poller. */
	private final List<TextView> nameValues = new ArrayList<>();
	private final List<String> nameShown = new ArrayList<>();

	private TextView statusView;
	private TextView versionView;
	private View dotView;
	/** The soft disc behind the status dot. */
	private View dotHaloView;

	private LinearLayout pSend;
	private LinearLayout pSettings;
	private LinearLayout pSystem;
	private final View[] navs = new View[4];

	/**
	 * A toggle switch, in the app's own colours.
	 *
	 * <p>Android's own Switch, tinted. The check mark that a checkbox draws
	 * says "this item is in a list"; a switch says "this is on or off",
	 * which is what every control here actually is.
	 */
	private android.widget.Switch toggle(boolean on) {
		android.widget.Switch sw = new android.widget.Switch(this);
		sw.setChecked(on);
		sw.setShowText(false);
		sw.setTextOn("");
		sw.setTextOff("");
		sw.setThumbTintList(android.content.res.ColorStateList.valueOf(
				getColor(on ? R.color.text : R.color.thumbOff)));
		sw.setTrackTintList(new android.content.res.ColorStateList(
				new int[][] {
						new int[] { android.R.attr.state_checked },
						new int[] { },
				},
				new int[] { getColor(R.color.accent), getColor(R.color.trackOff) }));
		return sw;
	}

	/** The clipboard history panel, rebuilt whenever the history changes. */
	private LinearLayout pClip;
	/** The list of entries, and the heading that counts them. */
	private LinearLayout clipList;
	private TextView clipListHeading;
	/** What the list says when it has stopped loading more. */
	private TextView clipFooter;

	/** package name -> the app's own name, so "com.android.chrome" reads as
	 *  "Chrome". Resolved once and kept: the label does not change. */
	private final Map<String, String> appLabels =
			new java.util.concurrent.ConcurrentHashMap<>();
	/** package name -> the app's icon, for the same reason.
	 *  A concurrent map cannot hold a null, so packages with no icon are
	 *  remembered here instead of being asked about again. */
	private final Map<String, android.graphics.drawable.Drawable> appIcons =
			new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<String> noIcon =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** The name an installed package goes by, or the package itself. */
	private String appLabel(String pkg) {
		if (pkg == null || pkg.isEmpty()) {
			return "";
		}
		String cached = appLabels.get(pkg);
		if (cached != null) {
			return cached;
		}
		String label = pkg;
		try {
			android.content.pm.ApplicationInfo ai =
					getPackageManager().getApplicationInfo(pkg, 0);
			CharSequence l = getPackageManager().getApplicationLabel(ai);
			if (l != null && l.length() > 0) {
				label = l.toString();
			}
		} catch (Exception ignored) {
			// Not installed, or not ours to look at. The package name is
			// the honest answer in that case.
		}
		appLabels.put(pkg, label);
		return label;
	}

	/** The icon an installed package goes by, or nothing. */
	private android.graphics.drawable.Drawable appIcon(String pkg) {
		if (pkg == null || pkg.isEmpty()) {
			return null;
		}
		android.graphics.drawable.Drawable cachedIcon = appIcons.get(pkg);
		if (cachedIcon != null) {
			return cachedIcon;
		}
		if (noIcon.contains(pkg)) {
			return null;
		}
		android.graphics.drawable.Drawable d = null;
		try {
			d = getPackageManager().getApplicationIcon(pkg);
		} catch (Exception ignored) {
			d = null;
		}
		if (d != null) {
			appIcons.put(pkg, d);
		} else {
			noIcon.add(pkg);
		}
		return d;
	}

	private Button sendBtn;
	/** row that offers the software update once a newer build is known. */
	private View updateRow;
	private TextView updateTitle;
	private TextView updateSub;

	private JSONObject status;
	private boolean polling;
	/**
	 * How often the screen checks for itself.
	 *
	 * <p>This is a backstop, not the mechanism. The module tells the app
	 * when the clipboard changes and when the daemon's state changes, and
	 * the app redraws then. Polling every five seconds as well was the
	 * reason the screen kept saying things that were minutes out of date:
	 * a change that happened between two polls was not shown until the
	 * next one, and the half of the screen that the poll did not refresh
	 * stayed stale while the half it did refresh moved on.
	 *
	 * <p>Fifteen seconds is the backstop for the things nothing announces:
	 * a peer coming online, a phone waking up.
	 */
	private static final long POLL_MS = 15000;
	private volatile String latestVersion = "";
	/** Whether adbd is listening for TCP connections. */
	private volatile boolean adbWireless;

	private final List<Uri> pendingUris = new ArrayList<>();
	/** Started from a share sheet rather than the launcher. */
	private boolean shareLaunch;
	/** The share-sheet device picker has already been offered this launch. */
	private boolean pickerShown;
	/** Destinations of the in-flight picker, handed straight to dispatch. */
	private final List<String> lastPick = new ArrayList<>();
	private String defaultTargets = "";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		statusView = findViewById(R.id.status);
		versionView = findViewById(R.id.version);
		dotView = findViewById(R.id.dot);
		dotHaloView = findViewById(R.id.dotHalo);
		pSend = findViewById(R.id.pSend);
		pSettings = findViewById(R.id.pSettings);
		pSystem = findViewById(R.id.pSystem);
		pClip = findViewById(R.id.pClip);
		{
			// Near the end of the list, fetch the next page. The page is
			// what makes the tab open instantly against any history size:
			// the newest screenful is drawn, and older entries arrive only
			// if somebody actually scrolls to them.
			android.widget.ScrollView cs =
					findViewById(R.id.scrollClip);
			cs.setOnScrollChangeListener((v, x, y, ox, oy) -> {
				if (currentTab != 3) {
					return;
				}
				View child = cs.getChildAt(0);
				if (child != null
						&& y + cs.getHeight() >= child.getHeight() - dp(700)) {
					loadMoreClips();
				}
			});
		}

		navs[0] = findViewById(R.id.navSend);
		navs[1] = findViewById(R.id.navSettings);
		navs[2] = findViewById(R.id.navSystem);
		navs[3] = findViewById(R.id.navClip);
		for (int i = 0; i < navs.length; i++) {
			final int n = i;
			navs[i].setOnClickListener(v -> tab(n));
		}

		final View refreshBtn = findViewById(R.id.refresh);
		refreshBtn.setOnClickListener(v -> manualRefresh());

		applyWindowInsets();

		pullSpinner = findViewById(R.id.pullSpinner);
		// Every tab refreshes identically. The old wiring spun the header
		// button once for 520ms on the Send tab and did nothing at all on the
		// other two, which is most of why the gesture felt dead.
		final Runnable pullRefresh = () -> {
			manualRefresh();
			if (refreshBtn != null) {
				refreshBtn.animate().rotationBy(360f).setDuration(520).start();
			}
		};
		attachPullToRefresh(findViewById(R.id.scrollSend), pullRefresh);
		attachPullToRefresh(findViewById(R.id.scrollSettings), pullRefresh);
		attachPullToRefresh(findViewById(R.id.scrollSystem), pullRefresh);
		attachPullToRefresh(findViewById(R.id.scrollClip), pullRefresh);

		defaultTargets = getPreferences(0).getString("targets", "");
		android.content.Intent launch = getIntent();
		if (launch != null && launch.getAction() != null
				&& (android.content.Intent.ACTION_SEND.equals(launch.getAction())
					|| android.content.Intent.ACTION_SEND_MULTIPLE.equals(launch.getAction()))) {
			shareLaunch = true;
		}
		if (savedInstanceState != null) {
			clipQuery = savedInstanceState.getString(KEY_CLIP_QUERY, "");
		}
		captureShare(launch);
		tab(0);
		checkRoot();
		askNotificationPermission();
		registerNetworkWatcher();
		wireClipButtons();
	}

	private static final int REQ_NOTIFY = 4242;

	/**
	 * Ask for POST_NOTIFICATIONS, which Taildrop alerts depend on.
	 *
	 * <p>Asked on every launch until it is answered rather than only once: the
	 * first prompt is easy to dismiss by accident, and until it is granted
	 * ReceiveNotifier runs, builds the notification, and has it silently
	 * dropped by the platform. The receiver cannot ask on the app's behalf --
	 * a background component has no way to raise a permission dialog -- so the
	 * only place this can happen is here.
	 */
	private void askNotificationPermission() {
		if (android.os.Build.VERSION.SDK_INT < 33) {
			return; // granted at install time before Android 13
		}
		if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
				== android.content.pm.PackageManager.PERMISSION_GRANTED) {
			return;
		}
		try {
			requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
					REQ_NOTIFY);
		} catch (Exception ignored) {
			// Nothing to do: alerts simply stay silent.
		}
	}

	/**
	 * Root first, before anything else is attempted.
	 *
	 * Without it every later failure looks like a broken daemon rather than an
	 * app the root manager has not been told to trust.
	 */
	private void checkRoot() {
		new Thread(() -> {
			try {
				Root.sh("id");
				ui.post(this::refresh);
			} catch (Exception e) {
				ui.post(() -> renderOffline(getString(R.string.need_root_short)));
			}
		}).start();
	}

	@Override
	protected void onNewIntent(android.content.Intent intent) {
		super.onNewIntent(intent);
		// Without this, getIntent() keeps returning the original MAIN intent and
		// a later recreation re-reads it instead of the share.
		setIntent(intent);
		if (intent != null && intent.getAction() != null
				&& (android.content.Intent.ACTION_SEND.equals(intent.getAction())
					|| android.content.Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) {
			// A new share is a new decision about the destination, so offer the
			// picker again rather than leaving the user on a tab.
			shareLaunch = true;
			pickerShown = false;
		}
		captureShare(intent);
		tab(0);
	}

	@Override
	protected void onSaveInstanceState(android.os.Bundle out) {
		super.onSaveInstanceState(out);
		// A share in progress is the headline feature; losing it to a rotation
		// or a low-memory kill is not acceptable.
		ArrayList<String> staged = new ArrayList<>();
		for (Uri u : pendingUris) {
			staged.add(u.toString());
		}
		out.putStringArrayList("pending", staged);
		// The search box is restored by Android itself, so the filter has
		// to be restored as well. Without this the box showed what was
		// typed while the list was not filtered by it, which is the one
		// thing a search box must never do.
		out.putString(KEY_CLIP_QUERY, clipQuery);
	}

	@Override
	protected void onRestoreInstanceState(android.os.Bundle in) {
		super.onRestoreInstanceState(in);
		ArrayList<String> staged = in.getStringArrayList("pending");
		if (staged != null) {
			for (String s : staged) {
				try {
					pendingUris.add(Uri.parse(s));
				} catch (Exception ignored) {
					// drop anything no longer resolvable
				}
			}
		}
	}

	/**
	 * Share-sheet entry point.
	 *
	 * A share lands on the Send tab with the files staged, and the same device
	 * list the app already has decides where they go — one interface, no
	 * separate share screen and no intermediate dialog.
	 */
	private void captureShare(android.content.Intent in) {
		if (in == null || in.getAction() == null) {
			return;
		}
		String a = in.getAction();
		if (android.content.Intent.ACTION_SEND.equals(a)) {
			android.net.Uri u = in.getParcelableExtra(android.content.Intent.EXTRA_STREAM);
			if (u == null) {
				// Some senders put the URI in as a plain string. getParcelableExtra
				// returns null for that, and the share would then look like it
				// arrived with nothing attached.
				Object raw = in.getExtras() == null ? null
						: in.getExtras().get(android.content.Intent.EXTRA_STREAM);
				if (raw instanceof String && !((String) raw).isEmpty()) {
					try {
						u = android.net.Uri.parse((String) raw);
					} catch (Exception ignored) {
						u = null;
					}
				}
			}
			if (u != null) {
				pendingUris.add(u);
			} else {
				addSharedText(in.getStringExtra(android.content.Intent.EXTRA_TEXT));
			}
		} else if (android.content.Intent.ACTION_SEND_MULTIPLE.equals(a)) {
			ArrayList<android.net.Uri> list =
					in.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM);
			if (list != null) {
				pendingUris.addAll(list);
			}
			addSharedText(in.getStringExtra(android.content.Intent.EXTRA_TEXT));
		}
		if (!pendingUris.isEmpty()) {
			refresh();
		}
	}

	/** Taildrop moves files, so shared text becomes one. */
	private void addSharedText(String text) {
		if (text == null || text.trim().isEmpty()) {
			return;
		}
		try {
			java.io.File dir = new java.io.File(getCacheDir(), "share");
			if (!dir.exists() && !dir.mkdirs()) {
				return;
			}
			java.io.File f = new java.io.File(dir, "shared-" + System.currentTimeMillis() + ".txt");
			try (java.io.OutputStream os = new java.io.FileOutputStream(f)) {
				os.write(text.getBytes("UTF-8"));
			}
			pendingUris.add(android.net.Uri.fromFile(f));
		} catch (Exception ignored) {
			// nothing to stage
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		refresh();
		startPolling();
		registerClipboardListener();
		ui.removeCallbacks(clipClock);
		ui.postDelayed(clipClock, 30_000);
	}





	/**
	 * The clipboard changed while the app was in the foreground.
	 *
	 * <p>It is shown at once, from what the system just handed us, and the
	 * module's history is asked for as well so the entry is still there
	 * after the app is closed.
	 */
	private void showLiveClipInstantly() {
		try {
			android.content.ClipboardManager cm =
					getSystemService(android.content.ClipboardManager.class);
			android.content.ClipData cd = cm.getPrimaryClip();
			if (cd == null || cd.getItemCount() == 0) {
				return;
			}
			CharSequence cs = cd.getItemAt(0).getText();
			if (cs == null || cs.length() == 0) {
				return;
			}
			final String text = cs.toString();
			ui.post(() -> {
				if (clipSearching) {
					return;
				}
				// The same text at the top already is the same copy --
				// the module wrote it down a moment ago and the history
				// may have answered in between. Two rows of one copy is
				// worse than the wait the fast path exists to spare.
				if (!clipEntries.isEmpty() && text.equals(clipEntries.get(0).text)) {
					return;
				}
				ClipEntry e = new ClipEntry();
				e.time = System.currentTimeMillis();
				e.text = text;
				// The source is not know the way a store entry's is, and
				// saying nothing is better than saying a wrong name; the
				// store's answer carries the real one.
				e.source = "";
				clipEntries.add(0, e);
				refreshClipList();
			});
		} catch (Exception ignored) {
			// The read was refused, or there was nothing to read. The
			// store's answer is the slow path, and it is still coming.
		}
	}

	/** The system's own list of who is watching the clipboard.
	 *
	 *  <p>This is what makes a copy instant. The module hears about a
	 *  copy, writes it down, and then has to start `am` to broadcast the
	 *  news -- that whole chain is hundreds of milliseconds. The system
	 *  tells this listener the moment the copy happens, before any of
	 *  that, and the app is in the foreground with permission to read
	 *  the clipboard, so it can show the copy straight away.
	 */
	private android.content.ClipboardManager.OnPrimaryClipChangedListener
			clipSystemListener;

	/** Listen for the module saying the clipboard changed. */
	private void registerClipboardListener() {
		if (clipReceiver != null) {
			// Registered already; the system listener follows the same
			// one-per-lifetime rule.
			return;
		}
		// The fastest of the three paths, registered first. A copy is
		// shown from the system clipboard alone; the module's news
		// arrives after and the history it records agrees with what is
		// already on the screen.
		try {
			android.content.ClipboardManager cm =
					getSystemService(android.content.ClipboardManager.class);
			if (clipSystemListener == null) {
				clipSystemListener = () -> showLiveClipInstantly();
			}
			cm.addPrimaryClipChangedListener(clipSystemListener);
		} catch (Exception ignored) {
			// Without it the module's broadcast is still a fast path in
			// its own right.
		}
		clipReceiver = new android.content.BroadcastReceiver() {
			@Override
			public void onReceive(android.content.Context c, android.content.Intent i) {
				String a = i.getAction();
				if ("com.paras.tailbridge.action.CLIPBOARD_CHANGED".equals(a)) {
					refreshClipboardList();
				} else {
					// The daemon's state changed.
					refresh();
				}
			}
		};
		android.content.IntentFilter f = new android.content.IntentFilter();
		f.addAction("com.paras.tailbridge.action.CLIPBOARD_CHANGED");
		f.addAction("com.paras.tailbridge.action.STATE_CHANGED");
		try {
			// Exported, deliberately.
			//
			// The sender is the module, which runs as root, and a
			// receiver registered as not-exported only hears from the
			// system and from this app -- so a private receiver would
			// never hear the module at all, and the clipboard screen
			// would only learn about a copy when the screen was pulled
			// to refresh.
			//
			// Exposing it costs nothing: the action names this app, and
			// all the receiver does is re-read the app's own history.
			// Nothing is sent anywhere and nothing is granted.
			registerReceiver(clipReceiver, f, android.content.Context.RECEIVER_EXPORTED);
		} catch (Exception e) {
			// The poll is still there; this is only the fast path.
			clipReceiver = null;
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		ui.removeCallbacks(clipClock);
		stopPolling();
		if (clipSystemListener != null) {
			try {
				android.content.ClipboardManager cm =
						getSystemService(android.content.ClipboardManager.class);
				cm.removePrimaryClipChangedListener(clipSystemListener);
			} catch (Exception ignored) {
				// Already gone.
			}
			clipSystemListener = null;
		}
		if (clipReceiver != null) {
			try {
				unregisterReceiver(clipReceiver);
			} catch (Exception ignored) {
				// Already gone.
			}
			clipReceiver = null;
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
			if (networkCallback != null) {
			ConnectivityManager cm = getSystemService(ConnectivityManager.class);
			cm.unregisterNetworkCallback(networkCallback);
			networkCallback = null;
		}
		// Worker threads post back into this Handler; anything still queued
		// would try to touch a window that no longer exists.
		ui.removeCallbacksAndMessages(null);
	}

	/**
	 * The clipboard service, and the listener the app puts on it.
	 *
	 * <p>While the app is in the foreground the system lets the focused app
	 * read the clipboard with the public API. That is the truth, it arrives
	 * in-process the moment it changes, and it needs no root at all.
	 *
	 * <p>Going through the module instead meant every copy travelled
	 * through a helper, a file, a broadcast and a root round trip, and
	 * arrived seconds late -- a copy of the truth, read back out of
	 * somebody else's notes. Root is needed for the two things the public
	 * API cannot do: keeping the history going while the app is closed, and
	 * putting something on the clipboard.
	 */

	/**
	 * Told when the clipboard changes.
	 *
	 * <p>The module sends this the moment it records a copy. Without it the
	 * clipboard screen only learned about a copy on the app's next poll,
	 * which is up to five seconds later -- long enough that copying
	 * something and watching the screen not change makes the whole thing
	 * look broken.
	 */
	private android.content.BroadcastReceiver clipReceiver;

	/** The tab on screen. */
	private int currentTab = 0;

	private void tab(int n) {
		currentTab = n;
		if (n == 3) {
			ensureClipLoaded();
		}
		ScrollView[] scrolls = {
				findViewById(R.id.scrollSend),
				findViewById(R.id.scrollSettings),
				findViewById(R.id.scrollSystem),
				findViewById(R.id.scrollClip),
		};
		for (int i = 0; i < scrolls.length; i++) {
			scrolls[i].setVisibility(i == n ? View.VISIBLE : View.GONE);
			// Active tab is white, inactive is grey. The accent is saved for
			// the one thing that should pull the eye: Send.
			int c = getColor(i == n ? R.color.text : R.color.hint);
			ImageView icon = (ImageView) navs[i].findViewById(R.id.navIcon);
			if (icon != null) {
				icon.setColorFilter(c);
			}
			TextView label = (TextView) navs[i].findViewById(R.id.navLabel);
			if (label != null) {
				label.setTextColor(c);
				label.setTypeface(null, i == n ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
			}
			// The mark behind the selected tab's icon.
			//
			// The label changes colour, but colour alone is easy to miss
			// on a small strip. A shape behind the icon makes the
			// selected tab findable at a glance.
			View pill = navs[i].findViewById(R.id.navPill);
			if (pill != null) {
				pill.setVisibility(i == n ? View.VISIBLE : View.INVISIBLE);
			}
		}
		// The clipboard's floating buttons belong to the clipboard screen.
		View acts = findViewById(R.id.clipActions);
		if (acts != null) {
			acts.setVisibility(n == 3 ? View.VISIBLE : View.GONE);
		}
	}

	private int receivedCount;

	/** Open Downloads/Tailscale in whichever file manager is installed. */
	private void openTaildropFolder() {
		try {
			android.net.Uri uri = android.provider.DocumentsContract.buildDocumentUri(
					"com.android.externalstorage.documents", "primary:Download/Tailscale");
			Intent i = new Intent(Intent.ACTION_VIEW)
					.setDataAndType(uri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
							| Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(i);
		} catch (Exception e) {
			toast(getString(R.string.taildrop_folder_sub));
		}
	}

	/** The one pull-to-refresh indicator, shared by all three tabs. */
	private ImageView pullSpinner;

	/**
	 * Drag down at the top of a list to refresh it.
	 *
	 * <p>The indicator is the whole point. A pull that moves content but shows
	 * nothing reads as lag, not as a gesture, so the arrow tracks the finger,
	 * rotates with the drag, and commits on release past the threshold. Firing
	 * the instant the threshold was crossed made the list jump out from under
	 * the finger.
	 */
	private void attachPullToRefresh(android.widget.ScrollView sv, Runnable onPull) {
		final int touchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
		final int trigger = dp(64);
		final int travel = dp(40);
		// In arrays so the listener can mutate them; plain locals would have to
		// be final to be reachable from the lambda.
		final float[] downY = {0f};
		final boolean[] tracking = {false};
		final boolean[] armed = {false};
		final ImageView spin = pullSpinner;

		sv.setOnTouchListener((v, ev) -> {
			switch (ev.getActionMasked()) {
				case android.view.MotionEvent.ACTION_DOWN:
					downY[0] = ev.getY();
					// Only arm at the top. Below it a downward drag is scroll-back
					// and belongs to the list, not to us.
					tracking[0] = sv.getScrollY() <= 0;
					armed[0] = false;
					return false;

				case android.view.MotionEvent.ACTION_MOVE:
					if (!tracking[0]) {
						return false;
					}
					float dy = ev.getY() - downY[0];
					if (dy <= touchSlop) {
						return false;
					}
					tracking[0] = true;

					float raw = (dy - touchSlop) * 0.5f;
					if (raw <= 0f) {
						return true;
					}

					boolean wasArmed = armed[0];
					float pull = raw;
					if (raw > trigger) {
						// Past the threshold the list resists hard, so it is obvious
						// that letting go will fire.
						armed[0] = true;
						pull = trigger + (raw - trigger) * 0.18f;
						if (!wasArmed) {
							v.performHapticFeedback(
									android.view.HapticFeedbackConstants.CLOCK_TICK);
						}
					}
					v.setTranslationY(pull);

					// The arrow rides down with the content, spinning as it goes.
					float pr = Math.min(1f, raw / travel);
					if (spin != null) {
						spin.clearAnimation();
						spin.animate().cancel();
						spin.setVisibility(View.VISIBLE);
						spin.setAlpha(Math.min(1f, pr * 1.6f));
						spin.setTranslationY(-dp(38) + pr * dp(38));
						spin.setRotation(pr * 300f);
					}
					return true;

				case android.view.MotionEvent.ACTION_UP:
				case android.view.MotionEvent.ACTION_CANCEL:
					if (!tracking[0]) {
						return false;
					}
					boolean fire = armed[0];
					tracking[0] = false;
					armed[0] = false;
					settle(spin, v, fire);
					if (fire) {
						onPull.run();
						return true;
					}
					return false;

				default:
					return false;
			}
		});
	}

	/**
	 * Put everything back. After a fire the arrow keeps turning briefly, so a
	 * refresh is visibly in progress instead of vanishing the moment it starts.
	 */
	private void settle(ImageView spin, android.view.View content, boolean fired) {
		content.animate().translationY(0f)
				.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
				.setDuration(220).start();

		if (spin == null) {
			return;
		}
		spin.animate().cancel();
		if (fired) {
			spin.animate().translationY(0f).setDuration(180).start();
			spin.postDelayed(() -> {
				if (spin.getVisibility() == View.VISIBLE) {
					spin.startAnimation(spinLoop());
				}
			}, 180);
		} else {
			spin.animate().translationY(0f).alpha(0f).setDuration(160)
					.withEndAction(() -> {
						spin.clearAnimation();
						spin.setVisibility(View.INVISIBLE);
						spin.setRotation(0f);
					}).start();
		}
	}

	/** Continuous turn of the arrow, so an in-flight refresh stays visible. */
	private android.view.animation.Animation spinLoop() {
		android.view.animation.RotateAnimation a = new android.view.animation.RotateAnimation(
				0f, 360f,
				android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
				android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
		a.setDuration(760);
		a.setRepeatCount(android.view.animation.Animation.INFINITE);
		a.setInterpolator(new android.view.animation.LinearInterpolator());
		return a;
	}

	/** Put the indicator away when a refresh finishes on its own. */
	private void hidePullSpinner() {
		if (pullSpinner == null) {
			return;
		}
		pullSpinner.clearAnimation();
		pullSpinner.animate().alpha(0f).setDuration(160)
				.withEndAction(() -> {
					pullSpinner.setVisibility(View.INVISIBLE);
					pullSpinner.setRotation(0f);
				}).start();
	}

	private void applyWindowInsets() {
		final View header = findViewById(R.id.header);
		final int floor = dp(22);
		header.setOnApplyWindowInsetsListener((v, insets) -> {
			int top = 0;
			if (android.os.Build.VERSION.SDK_INT >= 30) {
				android.graphics.Insets bars = insets.getInsets(
						android.view.WindowInsets.Type.systemBars()
								| android.view.WindowInsets.Type.displayCutout());
				top = bars.top;
			} else {
				top = insets.getSystemWindowInsetTop();
			}
			v.setPadding(v.getPaddingLeft(), floor + top, v.getPaddingRight(),
					v.getPaddingBottom());
			return insets;
		});
		header.requestApplyInsets();
	}

	private void manualRefresh() {
		View btn = findViewById(R.id.refresh);
		btn.setEnabled(false);
		// android.animation.*, not the AndroidX listener: this app has no
		// AndroidX dependency and must not gain one for a spin.
		btn.animate().rotationBy(360f).setDuration(520).setListener(
				new android.animation.AnimatorListenerAdapter() {
					@Override
					public void onAnimationEnd(android.animation.Animator a) {
						btn.setRotation(0f);
						ui.post(() -> btn.setEnabled(true));
					}

					@Override
					public void onAnimationCancel(android.animation.Animator a) {
						btn.setRotation(0f);
						ui.post(() -> btn.setEnabled(true));
					}
				}).start();

		startPolling();
		refresh();
	}

	/**
	 * Launched from the share sheet? Ask where to send, right here.
	 *
	 * <p>Previously a share dropped the files on the Send tab and left the user
	 * to find them, tick a device and press Send. From the share sheet the
	 * destination is the only thing left to decide, so it is the only thing
	 * asked.
	 *
	 * <p>Shown once per launch. A rotation or a backgrounded resume must not
	 * re-open a dialog over something the user is already doing.
	 */
	/**
	 * The share sheet's two-line title: the command, then the cargo.
	 *
	 * <p>Built by hand rather than as one string, because the two lines
	 * want different weight: the command is the decision being asked
	 * for, the count is the detail under it. A single line lost the
	 * count, and a count in the title read like part of the command.
	 */
	private android.view.View sheetTitle() {
		android.widget.LinearLayout block = new android.widget.LinearLayout(this);
		block.setOrientation(android.widget.LinearLayout.VERTICAL);
		block.setPadding(dp(24), dp(20), dp(24), dp(4));

		android.widget.TextView t = new android.widget.TextView(this);
		t.setText(getString(R.string.choose_devices));
		t.setTextColor(getColor(R.color.text));
		t.setTextSize(19);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		block.addView(t);

		android.widget.TextView sub = new android.widget.TextView(this);
		sub.setText(getString(R.string.share_n_files, pendingUris.size()));
		sub.setTextColor(getColor(R.color.text2));
		sub.setTextSize(12.5f);
		sub.setPadding(0, dp(4), 0, 0);
		block.addView(sub);

		return block;
	}

	private void maybeShowSharePicker() {
		if (!shareLaunch || pickerShown || pendingUris.isEmpty()) {
			return;
		}
		if (!signedIn(status)) {
			return; // renderOffline / the sign-in gate has its own message
		}
		final List<Api.Peer> ps = Api.peersFrom(status);
		if (ps.isEmpty()) {
			return;
		}
		pickerShown = true;

		final boolean[] picked = new boolean[ps.size()];
		for (int i = 0; i < ps.size(); i++) {
			Api.Peer p = ps.get(i);
			picked[i] = p.online && chosen.contains(p.name);
		}

		final Sheet sheet = Sheet.card(this)
				.title(getString(R.string.choose_devices))
				.subtitle(getString(R.string.share_n_files, pendingUris.size()));

		LinearLayout list = new LinearLayout(this);
		list.setOrientation(LinearLayout.VERTICAL);

		for (int i = 0; i < ps.size(); i++) {
			final Api.Peer p = ps.get(i);
			final int index = i;
			final boolean on = p.online;

			LinearLayout row = new LinearLayout(this);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(dp(6), dp(9), dp(6), dp(9));
			row.setBackgroundResource(R.drawable.row_press);
			row.setAlpha(on ? 1f : 0.62f);

			// A coin with the first letter: a list of machine names is read
			// by its initials before it is read at all.
			TextView coin = new TextView(this);
			String nm = p.name == null || p.name.isEmpty() ? "?" : p.name;
			coin.setText(nm.substring(0, 1).toUpperCase());
			coin.setGravity(Gravity.CENTER);
			coin.setTextSize(15);
			coin.setTypeface(null, android.graphics.Typeface.BOLD);
			coin.setTextColor(getColor(on ? R.color.accentHi : R.color.hint));
			android.graphics.drawable.GradientDrawable disc =
					new android.graphics.drawable.GradientDrawable();
			disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
			disc.setColor(getColor(on ? R.color.accentDim : R.color.hairline));
			coin.setBackground(disc);
			row.addView(coin, new LinearLayout.LayoutParams(dp(38), dp(38)));

			LinearLayout col = new LinearLayout(this);
			col.setOrientation(LinearLayout.VERTICAL);

			TextView name = new TextView(this);
			name.setText(p.name);
			name.setTextColor(getColor(on ? R.color.text : R.color.hint));
			name.setTextSize(15.5f);
			name.setTypeface(null, android.graphics.Typeface.BOLD);
			name.setIncludeFontPadding(false);
			name.setMaxLines(1);
			name.setEllipsize(android.text.TextUtils.TruncateAt.END);
			col.addView(name);

			// Online is a state worth a light, not a sentence: a small dot
			// beside the word is read in one glance.
			LinearLayout state = new LinearLayout(this);
			state.setOrientation(LinearLayout.HORIZONTAL);
			state.setGravity(Gravity.CENTER_VERTICAL);
			state.setPadding(0, dp(3), 0, 0);

			View dot = new View(this);
			android.graphics.drawable.GradientDrawable dotBg =
					new android.graphics.drawable.GradientDrawable();
			dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
			dotBg.setColor(getColor(on ? R.color.ok : R.color.hint));
			dot.setBackground(dotBg);
			LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(5), dp(5));
			dlp.rightMargin = dp(6);
			state.addView(dot, dlp);

			TextView stateText = new TextView(this);
			stateText.setText(on
					? getString(R.string.peer_online)
					: getString(R.string.peer_offline_label));
			stateText.setTextColor(getColor(on ? R.color.text2 : R.color.hint));
			stateText.setTextSize(11.5f);
			stateText.setIncludeFontPadding(false);
			state.addView(stateText);
			col.addView(state);

			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
					0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			clp.leftMargin = dp(13);
			clp.rightMargin = dp(8);
			row.addView(col, clp);

			final android.widget.Switch sw = toggle(false);
			sw.setChecked(picked[index] && on);
			sw.setEnabled(on);
			sw.setAlpha(on ? 1f : 0.5f);
			sw.setThumbTintList(android.content.res.ColorStateList.valueOf(
					getColor(picked[index] && on ? R.color.accent : R.color.thumbOff)));
			sw.setOnCheckedChangeListener((b, checked) -> {
				picked[index] = checked && on;
				((android.widget.Switch) b).setThumbTintList(
						android.content.res.ColorStateList.valueOf(
								getColor(picked[index] ? R.color.accent : R.color.thumbOff)));
			});
			row.setOnClickListener(v -> {
				if (on) {
					sw.toggle();
				}
			});
			row.addView(sw);

			list.addView(row);
		}

		ScrollView scroll = new ScrollView(this);
		scroll.setVerticalScrollBarEnabled(false);
		scroll.addView(list);
		sheet.add(scroll, Math.min(ps.size() * 62 + 4, 356));

		sheet.primary(getString(R.string.send_files), () -> {
			chosen.clear();
			List<String> targets = new ArrayList<>();
			for (int i = 0; i < ps.size(); i++) {
				if (picked[i] && ps.get(i).online) {
					chosen.add(ps.get(i).name);
					// dispatch() takes addresses, not names. Passing the name
					// made api_send reject it as an invalid peer, so every
					// share-sheet send failed while the in-app picker worked.
					if (ps.get(i).ip != null && !ps.get(i).ip.isEmpty()) {
						targets.add(ps.get(i).ip);
					}
				}
			}
			saveTargets();
			refresh();
			if (targets.isEmpty()) {
				toast(getString(R.string.pick_a_device));
				return;
			}
			dispatch(new ArrayList<>(pendingUris), targets);
		}).secondary(getString(android.R.string.cancel)).show();
	}

	/** One local root round-trip fills every panel; the network check follows. */
	private void refresh() {
		new Thread(() -> {
			JSONObject st = null;
			String err = null;
			try {
				st = Api.status();
			} catch (Api.ApiBroken e) {
				// Root is fine. Say so, rather than blaming permissions.
				err = "module: " + e.getMessage();
			} catch (Root.Denied e) {
				err = getString(R.string.need_root_short);
			} catch (Exception e) {
				err = "module: " + e.getMessage();
			}

			final JSONObject fSt = st;
			final String fErr = err;
			ui.post(() -> {
				status = fSt;
				// Whether adbd is listening, out of the same answer.
				adbWireless = Api.adbWirelessOn(fSt);
				// The refresh a pull started is now on screen, so the indicator
				// has done its job. Left alone it would spin forever.
				hidePullSpinner();
				if (fSt == null) {
					renderOffline(fErr);
				} else {
					render(fSt);
					// Arriving from the share sheet should not make the user
					// navigate to a screen that already has their files waiting
					// on it. Offer the devices right here instead.
					maybeShowSharePicker();
				}
			});

			if (st == null) {
				return;
			}
			final JSONObject stFinal = st;

			// Off the critical path.
			// A network call behind every refresh is why refreshing felt slow,
			// even with a warm daemon. Once an hour is plenty for a row of text.
			if (System.currentTimeMillis() - lastUpdateCheck < UPDATE_CHECK_MIN_MS) {
				return;
			}
			lastUpdateCheck = System.currentTimeMillis();
			new Thread(() -> {
				try {
					final String latest = Api.updateCheck();
					final String cur = stFinal.optString("version", "");
					if (latest != null && !latest.isEmpty() && !latest.equals(cur)) {
						latestVersion = latest;
						ui.post(this::paintUpdateRow);
					}
				} catch (Exception ignored) {
					// offline; leave the row as the plain "check" action
				}
			}).start();
		}).start();
	}

	/**
	 * Every read of the daemon goes through `su`, so any failure here means we
	 * do not have root. That is a gate the user has to act on, not an error to
	 * read past: it gets the whole screen, centred, with one obvious next step.
	 */
	private void renderOffline(String err) {
		// A failure here is not always missing root. If `su` worked and the
		// module simply could not answer, telling the user to grant permission
		// sends them to a setting that cannot fix it -- which is exactly what
		// happened when a broken module reported "grant root permission".
		boolean noRoot = !(err != null && err.startsWith("module"));

		statusView.setText(getString(noRoot ? R.string.need_root_short
				: R.string.module_broken_short));
		versionView.setText(noRoot ? getString(R.string.grant_root)
				: getString(R.string.module_broken_hint));
		setStatusColor(getColor(R.color.warn));

		pSend.removeAllViews();
		pSettings.removeAllViews();
		pSystem.removeAllViews();
		nameValues.clear();
		nameShown.clear();
		// These point at views we just detached; leaving them reachable lets a
		// late rollback flip a CheckBox nobody can see.
		switches.clear();
		sendBtn = null;
		updateRow = null;
		updateTitle = null;
		updateSub = null;
		supervisorSub = null;

		android.widget.ScrollView sc = findViewById(R.id.scrollSend);
		sc.setFillViewport(true);
		pSend.setGravity(Gravity.CENTER);
		// Nothing behind the gate is reachable, so do not offer it.
		findViewById(R.id.navBar).setVisibility(View.GONE);

		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setGravity(Gravity.CENTER_HORIZONTAL);

		ImageView lock = new ImageView(this);
		lock.setImageResource(R.drawable.ic_lock);
		lock.setContentDescription(getString(R.string.grant_root));
		LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(72), dp(72));
		llp.bottomMargin = dp(28);
		box.addView(lock, llp);

		TextView title = new TextView(this);
		title.setText(R.string.grant_root_title);
		title.setTextColor(getColor(R.color.text));
		title.setTextSize(26);
		title.setGravity(Gravity.CENTER);
		title.setLineSpacing(dp(4), 1f);
		title.setTypeface(null, android.graphics.Typeface.BOLD);
		box.addView(title);

		TextView body = new TextView(this);
		body.setText(R.string.grant_root_body);
		body.setTextColor(getColor(R.color.text2));
		body.setTextSize(15);
		body.setGravity(Gravity.CENTER);
		body.setLineSpacing(dp(6), 1f);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		blp.topMargin = dp(16);
		blp.leftMargin = dp(12);
		blp.rightMargin = dp(12);
		box.addView(body, blp);

		TextView open = new TextView(this);
		open.setText(R.string.open_root_manager);
		open.setTextColor(getColor(R.color.onAccent));
		open.setTextSize(16);
		open.setGravity(Gravity.CENTER);
		open.setBackgroundResource(R.drawable.btn_primary);
		open.setPadding(dp(16), dp(16), dp(16), dp(16));
		open.setClickable(true);
		open.setOnClickListener(v -> openRootManager());
		LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		olp.topMargin = dp(28);
		olp.leftMargin = dp(8);
		olp.rightMargin = dp(8);
		box.addView(open, olp);

		TextView back = new TextView(this);
		back.setText(R.string.grant_root_back);
		back.setTextColor(getColor(R.color.hint));
		back.setTextSize(13);
		back.setGravity(Gravity.CENTER);
		back.setPadding(dp(16), dp(16), dp(16), dp(0));
		back.setLineSpacing(dp(3), 1f);
		box.addView(back);

		// The underlying reason stays at the very bottom and quiet: useful when
		// reporting a problem, not the thing to show as the headline.
		if (err != null && !err.isEmpty() && !err.equals(getString(R.string.need_root_short))) {
			TextView why = new TextView(this);
			why.setText(err);
			why.setTextColor(getColor(R.color.hint));
			why.setTextSize(10);
			why.setGravity(Gravity.CENTER);
			why.setPadding(dp(20), dp(20), dp(20), dp(20));
			why.setLineSpacing(dp(2), 1f);
			box.addView(why);
		}

		LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		pSend.addView(box, boxLp);
	}

	/** The daemon process is alive unless the module reports otherwise. */
	private static boolean daemonUp(JSONObject st) {
		return st != null && "running".equals(st.optString("daemon", ""));
	}

	/** Whether the module's supervisor is alive. */
	private static boolean supervisorUp(JSONObject st) {
		return st != null && "running".equals(st.optString("supervisor", ""));
	}

	/** Fully authenticated: the only state where peers are reachable. */
	private static boolean signedIn(JSONObject st) {
		return st != null && "Running".equals(st.optString("backend", ""));
	}

	/** Paint the header from a status document. */
	/**
	 * The colour of the status light.
	 *
	 * <p>The dot and the disc behind it are set together: a dot of one colour
	 * inside a halo of another would read as two separate things rather than
	 * as one light.
	 */
	private void setStatusColor(int c) {
		if (dotView != null) {
			dotView.getBackground().mutate().setTint(c);
		}
		if (dotHaloView != null) {
			dotHaloView.getBackground().mutate().setTint(c);
			// The halo is the same colour, barely there.
			dotHaloView.setAlpha(38);
		}
	}

	private void applyHeader(JSONObject st) {
		String backend = st.optString("backend", "?");
		JSONObject self = st.optJSONObject("self");
		String host = self == null ? "" : self.optString("host", "");
		boolean up = daemonUp(st);

		// Never show Tailscale's internal enum names like "NeedsLogin".
		if (signedIn(st)) {
			statusView.setText(getString(R.string.connected));
		} else if ("NeedsLogin".equals(backend) || "NeedsMachineAuth".equals(backend)) {
			statusView.setText(getString(R.string.not_signed_in));
		} else if (!up) {
			statusView.setText(getString(R.string.stopped));
		} else {
			statusView.setText(getString(R.string.connecting));
		}

		// The supervisor's state changes on its own -- it restarts the daemon
		// when the daemon dies -- so the line has to follow the poll rather
		// than being fixed when the screen was drawn.
		if (supervisorSub != null) {
			supervisorSub.setText(supervisorUp(st)
					? getString(R.string.supervisor_running)
					: getString(R.string.supervisor_stopped));
		}

		// "localhost" is what tailscaled calls an unauthenticated node; showing
		// it looks like a real hostname.
		boolean showHost = signedIn(st) && !host.isEmpty();
		versionView.setText((showHost ? host + "  \u00b7  " : "") + "tailscaled "
				+ st.optString("version", "?"));

		// Three different problems look identical if they share one colour:
		// the daemon is not running, it is running but signed out, and it is
		// running and signed in but cannot reach the control plane. The last
		// one is the confusing one, because the tailnet often still works --
		// peers on the same network connect directly -- so the device looks
		// fine while login, relays and DNS quietly do not.
		boolean controlPlane = self != null && self.optBoolean("online", false);
		if (!up) {
			setStatusColor(getColor(R.color.err));
			versionView.setText(getString(R.string.state_daemon_stopped) + "  \u00b7  tailscaled "
					+ st.optString("version", "?"));
		} else if (!signedIn(st)) {
			setStatusColor(getColor(R.color.warn));
		} else if (!controlPlane) {
			setStatusColor(getColor(R.color.warn));
			versionView.setText(getString(R.string.state_no_internet) + "  \u00b7  tailscaled "
					+ st.optString("version", "?"));
		} else {
			setStatusColor(getColor(R.color.ok));
		}
	}

	/**
	 * Update only what can change under the user's fingers.
	 *
	 * Renaming a device in the admin console is the common case, and waiting for
	 * a manual refresh made the app look stale. Nothing here rebuilds a panel,
	 * so scroll position and any half-finished tap survive.
	 */
	private void patchNames(JSONObject st) {
		JSONObject self = st.optJSONObject("self");
		if (self == null) {
			return;
		}
		String host = self.optString("host", "");
		for (int i = 0; i < nameValues.size() && i < nameShown.size(); i++) {
			if (!nameShown.get(i).equals(host)) {
				nameValues.get(i).setText(host);
				nameShown.set(i, host);
			}
		}
	}

	/** Foreground poll. Cheap: one local read, no network. */
	private final Runnable poll = new Runnable() {
		@Override
		public void run() {
			if (polling) {
				// A slow `su` call can outlast the interval. Reschedule anyway,
				// or the chain dies silently and the header freezes until the
				// app is backgrounded and resumed.
				ui.postDelayed(this, POLL_MS);
				return;
			}
			polling = true;
			new Thread(() -> {
				JSONObject st = null;
				try {
					st = Api.status();
				} catch (Exception ignored) {
					// offline or root gone; leave the screen as it is
				}
				final JSONObject fSt = st;
				final String sig = bodySignature(st);
				ui.post(() -> {
					polling = false;
					if (fSt != null) {
						applyHeader(fSt);
						patchNames(fSt);
						// The header and the body have to agree.
						//
						// The poll updates the header every few seconds, but
						// the body was only rebuilt on a manual refresh, so
						// the two could say different things: the header
						// saying "Connected" over a body saying "Not signed
						// in". The body is rebuilt when what it depends on
						// changes, and not otherwise -- rebuilding it every
						// five seconds would throw away the scroll position
						// and anything half typed.
						if (!sig.equals(lastBodySignature)) {
							lastBodySignature = sig;
							// The panel is built from the status that was
							// just fetched, not from the one it had before.
							// Without this the body was rebuilt from stale
							// data and showed the old state: a switch still
							// ticked for a daemon that was stopped.
							status = fSt;
							rebuildVisible();
						}
					}
				});
			}).start();
			ui.postDelayed(this, POLL_MS);
		}
	};

	/** What the body of the screen was last built from. */
	private String lastBodySignature = "\u0000none";

	/**
	 * A fingerprint of everything the body of the screen depends on.
	 *
	 * <p>The header is refreshed on every poll; the body is not, because
	 * rebuilding it is expensive and throws away the scroll position. So the
	 * body is rebuilt when this changes and not otherwise.
	 */
	private String bodySignature(JSONObject st) {
		if (st == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		sb.append(st.optString("backend")).append('|');
		sb.append(st.optString("daemon")).append('|');
		for (Api.Peer p : Api.peersFrom(st)) {
			sb.append(p.name).append(':').append(p.ip).append(':')
					.append(p.online).append(',');
		}
		return sb.toString();
	}

	/**
	 * Rebuild the panel that is on screen.
	 *
	 * <p>Only the visible one. The other panels keep their state, so a peer
	 * coming online does not wipe half-typed text in a setting the user is
	 * looking at.
	 *
	 * <p>The clipboard panel is left alone: its list has a search box in it,
	 * and rebuilding it while somebody is typing would throw their query
	 * away.
	 */
	private void rebuildVisible() {
		switch (currentTab) {
			case 0:
				buildSend(Api.peersFrom(status));
				break;
			case 1:
				buildSettings(status);
				break;
			case 2:
				buildSystem(status);
				break;
			case 3:
				// Only the list. The search box above it must survive, and
				// the focus must stay where the user put it.
				refreshClipboardList();
				break;
			default:
				break;
		}
	}

	/** The "N files arrived" note is fetched once per launch, not per render. */
	private boolean receivedChecked;

	/** At most one network update check per hour. */
	private static final long UPDATE_CHECK_MIN_MS = 3600_000L;
	private long lastUpdateCheck;

	/** Listen for wifi / mobile data toggling. On any change, refresh immediately
	 * so the peer list shows online/offline without an app restart. The 5-second
	 * poll catches slower transitions; this catches the toggle itself. */
	private void registerNetworkWatcher() {
		if (networkCallback != null) {
			return;
		}
		ConnectivityManager cm = getSystemService(ConnectivityManager.class);
		if (cm == null) {
			return;
		}
		NetworkRequest req = new NetworkRequest.Builder()
				.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
				.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
				.build();
		networkCallback = new ConnectivityManager.NetworkCallback() {
			@Override public void onAvailable(Network network) {
				ui.post(MainActivity.this::manualRefresh);
			}
			@Override public void onLost(Network network) {
				ui.post(MainActivity.this::manualRefresh);
			}
		};
		cm.registerNetworkCallback(req, networkCallback);
	}

	private void startPolling() {
		ui.removeCallbacks(poll);
		ui.postDelayed(poll, POLL_MS);
	}

	private void stopPolling() {
		ui.removeCallbacks(poll);
	}

	private void render(JSONObject st) {
		((ScrollView) findViewById(R.id.scrollSend)).setFillViewport(false);
		pSend.setGravity(Gravity.NO_GRAVITY);
		findViewById(R.id.navBar).setVisibility(View.VISIBLE);
		applyHeader(st);

		List<Api.Peer> ps = Api.peersFrom(st);
		peers.clear();
		onlinePeers.clear();
		for (Api.Peer p : ps) {
			peers.put(p.name, p.ip);
			if (p.online) {
				onlinePeers.add(p.name);
			}
		}
		// A device that went offline while it was ticked must stop being a
		// target immediately, not on the next manual tap. Taildrop reports a
		// queued send to an offline peer as successful and delivers nothing,
		// so leaving it selected turns a red dot into a lie.
		chosen.retainAll(onlinePeers);

		buildSend(ps);
		buildSettings(st);
		buildSystem(st);
		// The history is not fetched here. It is the most expensive read the
		// app makes, and the Send tab was paying for it on every launch and
		// every refresh just because the Clip tab might be opened later. It
		// loads when that tab is actually opened.
		if (currentTab == 3) {
			ensureClipLoaded();
		}
	}

	// ------------------------------------------------------------ clipboard

	/**
	 * The clipboard's settings.
	 *
	 * <p>Read from the module, because the watcher is what they configure.
	 * They are fetched separately from the daemon's status: the status is one
	 * local read, while these live in the module's own configuration.
	 */
	private void buildClipboardSettings(LinearLayout g) {
		new Thread(() -> {
			try {
				final JSONObject cfg = Api.clipboardConfig();
				final JSONArray apps = Api.clipboardApps();
				ui.post(() -> {
					g.removeAllViews();
					clipSettings(g, cfg, apps);
				});
			} catch (Exception e) {
				ui.post(() -> {
					g.removeAllViews();
					note(g, getString(R.string.failed, String.valueOf(e.getMessage())));
				});
			}
		}).start();
	}

	/** The clipboard's settings, drawn. */
	private void clipSettings(LinearLayout g, JSONObject cfg, JSONArray apps) {
		final boolean watch = cfg.optBoolean("watch", true);
		final int max = cfg.optInt("max", 5000);
		final String ignore = cfg.optString("ignore", "");

		addSwitch(g, getString(R.string.clip_t_watch), "", watch, b -> {
					new Thread(() -> {
						try {
							Api.clipboardSet("watch", b.isChecked() ? "true" : "false");
							ui.post(() -> toast(getString(R.string.done)));
						} catch (Exception e) {
							ui.post(() -> toast(getString(R.string.failed,
									String.valueOf(e.getMessage()))));
						}
					}).start();
				});

		// How much to keep.
		//
		// A number you can type, not a list to pick from. The list was
		// three values, and a person who wants two thousand entries should
		// not have to choose between one thousand and five thousand.
		action(g, getString(R.string.clip_t_keep),
				getString(R.string.clip_kept, max),
				v -> showKeepEditor(max));

		// How long to keep it.
		//
		// A count on its own does not bound a history: five thousand
		// copies a day for a year is a lot of text, and the entries that
		// matter are the recent ones. Zero days means keep them for ever.
		final int days = cfg.optInt("days", 0);
		action(g, getString(R.string.clip_t_days),
				getString(R.string.clip_kept_days, days),
				v -> showDaysEditor(days));

		action(g, getString(R.string.clip_clear), null, v -> confirmClearClipboard());
	}









	/**
	 * How much history to keep.
	 *
	 * <p>A field with the number in it, so any number will do.
	 */
	private void showKeepEditor(int current) {
		showNumberEditor(getString(R.string.clip_t_keep), String.valueOf(current),
				10, 50000, n -> {
					Api.clipboardSet("max", String.valueOf(n));
				});
	}

	/** Something that sets a number, and may fail. */
	private interface Setter {
		void set(int n) throws Exception;
	}

	/**
	 * A number to type in.
	 *
	 * <p>Used for both limits. The point of a field rather than a list of
	 * choices is that the choice is not the point: how many entries to keep
	 * is a number somebody sets once and forgets.
	 */
	private void showNumberEditor(String title, String value, int lo, int hi,
			Setter onSet) {
		final EditText in = new EditText(this);
		in.setText(value);
		in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
		in.setTextColor(getColor(R.color.text));
		in.setHintTextColor(getColor(R.color.hint));
		in.setTextSize(18);
		in.setGravity(Gravity.CENTER);
		in.setSingleLine(true);
		in.setPadding(dp(16), 0, dp(16), 0);
		in.setBackgroundResource(R.drawable.field);

		final TextView error = new TextView(this);
		error.setTextColor(getColor(R.color.err));
		error.setTextSize(12.5f);
		error.setIncludeFontPadding(false);
		error.setPadding(dp(4), dp(9), dp(4), 0);
		error.setVisibility(View.GONE);

		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.addView(in, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
		box.addView(error);

		final Sheet sheet = Sheet.card(this).title(title).add(box);

		sheet.primaryKeep(getString(R.string.save), () -> {
			String v = in.getText().toString().trim();
			int n;
			try {
				n = Integer.parseInt(v);
			} catch (NumberFormatException e) {
				error.setText(getString(R.string.number_range, lo, hi));
				error.setVisibility(View.VISIBLE);
				return;
			}
			if (n < lo || n > hi) {
				error.setText(getString(R.string.number_range, lo, hi));
				error.setVisibility(View.VISIBLE);
				return;
			}
			sheet.dismiss();
			new Thread(() -> {
				try {
					onSet.set(n);
					ui.post(() -> {
						toast(getString(R.string.done));
						clipboardSettingsChanged();
					});
				} catch (Exception e) {
					ui.post(() -> toast(getString(R.string.failed,
							String.valueOf(e.getMessage()))));
				}
			}).start();
		}).secondary(getString(android.R.string.cancel)).show();
	}

	/**
	 * The settings changed, so the panel that shows them has to be redrawn.
	 *
	 * <p>Without this the row kept saying the old number: the value was
	 * changed and the screen still claimed it had not been.
	 */
	private void clipboardSettingsChanged() {
		ui.post(() -> {
			loadClipboard();
			buildSettings(status);
		});
	}

	/**
	 * How long to keep the history.
	 *
	 * <p>A field with the number of days in it. Zero means for ever.
	 */
	private void showDaysEditor(int current) {
		showNumberEditor(getString(R.string.clip_t_days), String.valueOf(current),
				0, 3650, n -> {
					Api.clipboardSet("days", String.valueOf(n));
				});
	}

	/** How much history to keep. */
	private void showKeepChooser(int current) {
		final int[] options = { 500, 1000, 5000 };
		String[] labels = new String[options.length];
		for (int i = 0; i < options.length; i++) {
			labels[i] = getString(R.string.clip_kept, options[i]);
		}
		Sheet.choice(this, getString(R.string.clip_t_keep),
				labels, indexOf(options, current), which -> {
					new Thread(() -> {
						try {
							Api.clipboardSet("max", String.valueOf(options[which]));
							ui.post(() -> {
								toast(getString(R.string.done));
								loadClipboard();
							});
						} catch (Exception e) {
							ui.post(() -> toast(getString(R.string.failed,
									String.valueOf(e.getMessage()))));
						}
					}).start();
				});
	}

	private static int indexOf(int[] a, int v) {
		for (int i = 0; i < a.length; i++) {
			if (a[i] == v) {
				return i;
			}
		}
		return 0;
	}

	/** Delete everything, after asking. */
	private void confirmClearClipboard() {
		Sheet.confirm(this, getString(R.string.clip_clear),
				getString(R.string.clip_clear_confirm),
				getString(R.string.clip_clear), true, this::clearClipboard);
	}

	/**
	 * The clipboard history.
	 *
	 * <p>Loaded separately from the status, because it is a different size of
	 * thing: the status is one local read, while the history is a list of
	 * everything that has been copied, and it changes on its own.
	 */
	private void loadClipboard() {
		synchronized (clipFetchLock) {
			if (clipFetching) {
				// Somebody already asked. A second su round trip would only
				// queue behind the first on the single root shell and make
				// both slower; remember that the answer must be refreshed
				// once more and let the one in flight land.
				clipFetchAgain = true;
				return;
			}
			clipFetching = true;
		}
		new Thread(this::fetchClipboard).start();
	}

	/**
	 * One fetch, with a retry.
	 *
	 * <p>The first call after the app starts can race the root shell coming
	 * up, and the module can be busy launching. A single failure used to put
	 * "the clipboard could not be read" on screen until the user left the
	 * tab, which made a half-second hiccup look like a broken app.
	 */
	private void fetchClipboard() {
		final String query = clipQuery;
		final boolean search = !query.isEmpty();
		final int gen;
		synchronized (clipFetchLock) {
			gen = ++clipSearchGen;
		}
		JSONArray entries = null;
		int total = 0;
		Exception failure = null;
		for (int attempt = 0; attempt < 3 && entries == null; attempt++) {
			if (attempt > 0) {
				try {
					Thread.sleep(250L * attempt);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			try {
				if (search) {
					entries = Api.clipboardSearch(query);
				} else {
					Api.ClipPage page = Api.clipboardPage(CLIP_PAGE, 0);
					entries = page.entries;
					total = page.total;
				}
			} catch (Exception e) {
				failure = e;
			}
		}
		synchronized (clipFetchLock) {
			if (search && gen != clipSearchGen) {
				// A newer query is already in flight; this answer is stale.
				clipFetching = false;
				clipFetchAgain = false;
				return;
			}
		}

		if (entries != null) {
			// Resolve names and icons here, off the UI thread. The first
			// render used to spend a few hundred milliseconds asking the
			// package manager what each source was, in the middle of
			// building the list.
			try {
				for (int i = 0; i < entries.length(); i++) {
					JSONObject o = entries.optJSONObject(i);
					if (o == null) {
						continue;
					}
					String s = o.optString("s", "");
					if (!s.isEmpty()) {
						appLabel(s);
						appIcon(s);
					}
				}
			} catch (Exception ignored) {
				// A missing icon is cosmetic; the list still draws.
			}
			if (!search) {
				saveClipCache(entries, total);
			}
			final JSONArray ok = entries;
			final boolean fSearch = search;
			final int fTotal = total;
			ui.post(() -> clipFetchFinished(ok, null, fSearch, fTotal));
		} else {
			final Exception f = failure;
			ui.post(() -> clipFetchFinished(null, f, search, 0));
		}
	}

	/** The single place a fetch ends, so the in-flight flag is never stuck. */
	private void clipFetchFinished(JSONArray entries, Exception failure, boolean search, int total) {
		boolean again;
		synchronized (clipFetchLock) {
			clipFetching = false;
			again = clipFetchAgain;
			clipFetchAgain = false;
		}
		if (entries != null) {
			clipFetchedAt = System.currentTimeMillis();
			clipSearching = search;
			List<ClipEntry> fresh = clipsFrom(entries);
			if (search) {
				// A search can match a great many entries; the point is
				// finding one, not scrolling the matches.
				if (fresh.size() > 200) {
					fresh = new ArrayList<>(fresh.subList(0, 200));
				}
				clipHasMore = false;
				clipTotal = fresh.size();
			} else {
				clipTotal = Math.max(total, fresh.size());
				clipHasMore = clipLoadedTotal(fresh) < clipTotal;
			}
			clipEntries = fresh;
			clipLoaded = fresh.size();
			if (clipBuilt) {
				refreshClipList();
			} else {
				renderClipboard(entries);
			}
		} else if (!clipBuilt) {
			// Nothing to keep on screen. Say so once, and let the retry in
			// the next poll try again rather than replacing a good list.
			renderClipboard(null);
		}
		if (again) {
			loadClipboard();
		}
	}

	/**
	 * Open the Clip tab.
	 *
	 * <p>The saved list is painted first, synchronously, so the tab has rows
	 * in it the frame it appears. The fresh answer follows in the background.
	 * Before this, the tab was blank until a root round trip and a few
	 * hundred rows of view construction had finished.
	 */
	private void ensureClipLoaded() {
		if (pClip == null) {
			return;
		}
		if (clipBuilt) {
			// Opening the tab again is a good moment to refresh, but not if
			// the list was just fetched, and not when more than one page is
			// loaded: that refresh returns page one and would discard the
			// pages somebody scrolled to.
			if (clipLoaded <= CLIP_PAGE
					&& System.currentTimeMillis() - clipFetchedAt > 5000) {
				loadClipboard();
			}
			return;
		}
		long t0 = android.os.SystemClock.uptimeMillis();
		JSONArray cached = readClipCache();
		if (cached != null) {
			renderClipboard(cached);
			android.util.Log.d("TailBridge", "PERF paint cached " + cached.length()
					+ " in " + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
		} else {
			renderClipboardLoading();
			android.util.Log.d("TailBridge", "PERF no cache, placeholder in "
					+ (android.os.SystemClock.uptimeMillis() - t0) + "ms");
		}
		loadClipboard();
	}

	/** The panel's frame, before there is a list to put in it. */
	private void renderClipboardLoading() {
		if (pClip == null) {
			return;
		}
		pClip.removeAllViews();
		LinearLayout g = group(pClip, null);
		g.setPadding(dp(2), dp(2), dp(2), 0);
		searchField(g, new ArrayList<>());
		note(pClip, getString(R.string.loading));
	}

	/** The last list that was fetched, on disk.
	 *
	 *  <p>So a cold start has something to draw before the daemon answers,
	 *  and so the history does not blink empty every time the app is
	 *  reopened. It is a cache of a list the module owns; if it is stale the
	 *  fresh answer replaces it a moment later.
	 */
	private JSONArray readClipCache() {
		clipTotal = 0;
		java.io.File f = new java.io.File(getFilesDir(), "clips.json");
		if (!f.isFile() || f.length() == 0) {
			return null;
		}
		try (java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader(
						new java.io.FileInputStream(f), "UTF-8"))) {
			JSONArray arr = new JSONArray();
			String line;
			while ((line = r.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) {
					continue;
				}
				try {
					JSONObject o = new JSONObject(line);
					if (o.has("total")) {
						clipTotal = o.optInt("total", 0);
					} else {
						arr.put(o);
					}
				} catch (Exception ignored) {
					// A truncated cache is not worth failing the tab for.
				}
			}
			return arr.length() == 0 ? null : arr;
		} catch (Exception e) {
			return null;
		}
	}

	/** Keep the last good list for the next open. Written off the UI thread. */
	private void saveClipCache(JSONArray arr, int total) {
		if (arr == null) {
			return;
		}
		java.io.File dir = getFilesDir();
		java.io.File tmp = new java.io.File(dir, "clips.json.tmp");
		java.io.File dst = new java.io.File(dir, "clips.json");
		try (java.io.BufferedWriter w = new java.io.BufferedWriter(
				new java.io.OutputStreamWriter(
						new java.io.FileOutputStream(tmp), "UTF-8"))) {
			w.write("{\"total\":" + Math.max(total, arr.length()) + "}");
			w.write('\n');
			for (int i = 0; i < arr.length(); i++) {
				JSONObject o = arr.optJSONObject(i);
				if (o != null) {
					w.write(o.toString());
					w.write('\n');
				}
			}
		} catch (Exception e) {
			tmp.delete();
			return;
		}
		if (!tmp.renameTo(dst)) {
			dst.delete();
			tmp.renameTo(dst);
		}
	}

	/** One history entry, as the module recorded it. */
	private static final class ClipEntry {
		/** The store's own handle for this entry. Everything the user can
		 *  do to one entry -- star it, delete it -- is done by id, because
		 *  a timestamp is reused when a repeated copy collapses into the
		 *  line it replaced. */
		String id = "";
		long time;
		/** The text, decoded once when the entry is read. */
		String text = "";
		String source = "";
		boolean fromPeer;
		/** Starred: a limit never removes it, and only the person who
		 *  starred it can unstar it. */
		boolean starred;
	}

	private static List<ClipEntry> clipsFrom(JSONArray arr) {
		List<ClipEntry> out = new ArrayList<>();
		if (arr == null) {
			return out;
		}
		for (int i = 0; i < arr.length(); i++) {
			JSONObject o = arr.optJSONObject(i);
			if (o == null) {
				continue;
			}
			ClipEntry e = new ClipEntry();
			e.id = o.optString("id", "");
			e.time = o.optLong("t", 0);
			e.text = decode(o.optString("c", ""));
			e.source = o.optString("s", "");
			e.fromPeer = o.optInt("d", 0) == 1;
			e.starred = o.optInt("p", 0) == 1;
			out.add(e);
		}
		return out;
	}

	/** Base64 text, decoded. */
	private static String decode(String b64) {
		try {
			return new String(android.util.Base64.decode(b64,
					android.util.Base64.DEFAULT), "UTF-8");
		} catch (Exception ex) {
			return "";
		}
	}

	private static String clipText(ClipEntry e) {
		return e.text;
	}

	private void renderClipboard(JSONArray arr) {
		if (pClip == null) {
			return;
		}
		clipBuilt = true;
		pClip.removeAllViews();

		List<ClipEntry> entries = clipsFrom(arr);
		clipEntries = entries;
		clipShown.clear();

		// The search box, and nothing else.
		//
		// The list is what this screen is for. Everything else that used
		// to sit above it -- a switch on its own row, a paragraph about
		// root -- pushed the list down the screen.
		LinearLayout g = group(pClip, null);
		g.setPadding(dp(2), dp(2), dp(2), 0);
		searchField(g, entries);

		if (entries.isEmpty()) {
			// An empty state that looks designed: an icon and friendly
			// copy centred on the screen, not a bare line of text tucked
			// into a corner.
			boolean failed = arr == null;
			LinearLayout empty = new LinearLayout(this);
			empty.setOrientation(LinearLayout.VERTICAL);
			empty.setGravity(android.view.Gravity.CENTER);
			empty.setPadding(dp(16), dp(40), dp(16), dp(16));

			ImageView ic = new ImageView(this);
			ic.setImageResource(failed ? R.drawable.ic_lock : R.drawable.ic_clip);
			ic.setColorFilter(getColor(R.color.hint));
			LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(
					dp(56), dp(56));
			icp.topMargin = dp(4);
			icp.bottomMargin = dp(16);
			empty.addView(ic, icp);

			TextView title = new TextView(this);
			title.setText(failed
					? getString(R.string.clip_load_failed_title)
					: getString(R.string.clip_empty_title));
			title.setTextColor(getColor(R.color.text));
			title.setTextSize(16f);
			title.setTypeface(null, android.graphics.Typeface.BOLD);
			title.setIncludeFontPadding(false);
			title.setGravity(android.view.Gravity.CENTER);
			empty.addView(title);

			TextView body = new TextView(this);
			body.setText(failed
					? getString(R.string.clip_load_failed_body)
					: getString(R.string.clip_empty_body));
			body.setTextColor(getColor(R.color.text2));
			body.setTextSize(13.5f);
			body.setIncludeFontPadding(false);
			body.setGravity(android.view.Gravity.CENTER);
			body.setLineSpacing(dp(4), 1f);
			empty.addView(body);

			if (failed) {
				TextView retry = new TextView(this);
				retry.setText(getString(R.string.clip_retry));
				retry.setTextColor(getColor(R.color.accent));
				retry.setTextSize(14f);
				retry.setIncludeFontPadding(false);
				retry.setGravity(android.view.Gravity.CENTER);
				retry.setPadding(0, dp(16), 0, 0);
				retry.setOnClickListener(v -> loadClipboard());
				empty.addView(retry);
			}

			pClip.addView(empty);
			return;
		}

		// The entries.
		//
		// Each row carries the icon of the app it came from. A list of
		// package names is a list of nothing a person can read; an icon and
		// a name is a list of the things they actually copied.
		// The heading: how many there are on the left, and the switch
		// that turns recording on and off on the right.
		//
		// A switch on its own with nothing next to it says nothing. Here
		// it has a word beside it, so it is obvious what it does, and it
		// costs the list no vertical space at all.
		LinearLayout head = new LinearLayout(this);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setGravity(Gravity.CENTER_VERTICAL);
		head.setPadding(dp(4), dp(20), dp(4), dp(8));

		// The star that shows only the starred entries.
		//
		// Starred entries are the ones somebody decided to keep, so
		// they need to be reachable at once rather than hunted for in a
		// list that grows.
		ImageView only = new ImageView(this);
		only.setImageResource(R.drawable.ic_star);
		only.setColorFilter(getColor(clipStarOnly ? R.color.accent : R.color.hint));
		only.setPadding(dp(8), dp(8), dp(8), dp(8));
		only.setContentDescription(getString(R.string.clip_starred_only));
		only.setOnClickListener(v -> {
			clipStarOnly = !clipStarOnly;
			only.setColorFilter(getColor(clipStarOnly ? R.color.accent : R.color.hint));
			refreshClipList();
		});
		head.addView(only, new LinearLayout.LayoutParams(dp(34), dp(34)));

		clipListHeading = new TextView(this);
		clipListHeading.setTextColor(getColor(R.color.hint));
		clipListHeading.setTextSize(11f);
		clipListHeading.setTypeface(null, android.graphics.Typeface.BOLD);
		clipListHeading.setAllCaps(true);
		clipListHeading.setLetterSpacing(0.1f);
		clipListHeading.setIncludeFontPadding(false);
		head.addView(clipListHeading, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		clipSwitch(head);
		pClip.addView(head);

		clipList = new LinearLayout(this);
		clipList.setOrientation(LinearLayout.VERTICAL);
		pClip.addView(clipList);

		clipFooter = new TextView(this);
		clipFooter.setTextColor(getColor(R.color.hint));
		clipFooter.setTextSize(12f);
		clipFooter.setPadding(dp(4), dp(10), dp(4), dp(2));
		clipFooter.setVisibility(View.GONE);
		pClip.addView(clipFooter);

		clipRenderSig = "none";
		clipRenderGen++;
		refreshClipList();
		paintClipFooter();

		// The note about root goes at the bottom. It is reference, not
		// something to read before using the screen.
		note(pClip, getString(R.string.clip_needs_root));

		action(pClip, getString(R.string.clip_clear), null, v -> clearClipboard());
	}

	/** One entry of the history: what was copied, and which app copied it. */
	private void clipRow(LinearLayout g, ClipEntry e) {
		line(g);
		LinearLayout row = row();
		row.setBackgroundResource(R.drawable.row_press);
		row.setClickable(true);

		// The app's icon.
		ImageView ic = new ImageView(this);
		android.graphics.drawable.Drawable d = appIcon(e.source);
		if (d != null) {
			ic.setImageDrawable(d);
		} else {
			ic.setImageResource(R.drawable.ic_clip);
			ic.setColorFilter(getColor(R.color.hint));
		}
		LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(dp(30), dp(30));
		icp.setMargins(0, 0, dp(14), 0);
		row.addView(ic, icp);

		LinearLayout col = col();

		// Title: the copied text.
		TextView t = new TextView(this);
		t.setText(clipShown(e));
		t.setTextColor(getColor(R.color.text));
		t.setTextSize(14.5f);
		t.setMaxLines(2);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		t.setIncludeFontPadding(false);
		col.addView(t);

		// Subtitle: who copied it and when.
		// The source never changes; the age does. Keeping them apart lets
		// the clock tick repaint the age without rebuilding a single row.
		String who = clipWho(e);
		TextView sub = new TextView(this);
		sub.setText(who + "  ·  " + clipTime(e.time));
		sub.setTextColor(getColor(R.color.text2));
		sub.setTextSize(12f);
		sub.setPadding(0, dp(3), 0, 0);
		sub.setIncludeFontPadding(false);
		col.addView(sub);
		clipStamps.add(new Object[] { sub, who, e.time });

		row.addView(col, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		// The star.
		ImageView star = new ImageView(this);
		star.setImageResource(R.drawable.ic_star);
		if (e.starred) {
			star.setColorFilter(getColor(R.color.ok));
			star.setAlpha(1f);
		} else {
			star.setColorFilter(getColor(R.color.hint));
			star.setAlpha(0.5f);
		}
		star.setPadding(dp(8), dp(8), dp(8), dp(8));
		star.setContentDescription(e.starred
				? getString(R.string.clip_unstar)
				: getString(R.string.clip_star));
		star.setOnClickListener(v -> toggleStar(e, star));
		row.addView(star, new LinearLayout.LayoutParams(dp(40), dp(40)));

		row.setOnClickListener(v -> showClipEntry(e));
		row.setOnLongClickListener(v -> {
			clipRowMenu(e);
			return true;
		});
		g.addView(row);
	}
	/** Star an entry, or unstar it. */
	private void toggleStar(ClipEntry e, ImageView star) {
		new Thread(() -> {
			try {
				Api.clipboardStar(e.id, e.time, !e.starred);
				ui.post(() -> {
					toast(getString(R.string.done));
					refreshClipboardList();
				});
			} catch (Exception ex) {
				ui.post(() -> toast(getString(R.string.failed,
						String.valueOf(ex.getMessage()))));
			}
		}).start();
	}

	/** What a long press on an entry offers. */
	private void clipRowMenu(ClipEntry e) {
		BottomSheet sheet = new BottomSheet(this, clipShown(e));
		sheet.action(R.drawable.ic_copy, getString(R.string.clip_copy), false,
				() -> copyToClipboard(clipText(e)));
		sheet.action(R.drawable.ic_send, getString(R.string.clip_send_to), false,
				() -> showClipEntry(e));
		sheet.action(R.drawable.ic_delete, getString(R.string.clip_delete), true,
				() -> confirmDeleteClip(e));
		sheet.cancel();
		sheet.show();
	}

	/** Delete one entry, after asking. */
	private void confirmDeleteClip(ClipEntry e) {
		Sheet.confirm(this, getString(R.string.clip_delete),
				getString(R.string.clip_delete_confirm),
				getString(R.string.clip_delete), true, () -> {
					new Thread(() -> {
						try {
							Api.clipboardDelete(e.id, e.time);
							ui.post(() -> {
								toast(getString(R.string.done));
								refreshClipboardList();
							});
						} catch (Exception ex) {
							ui.post(() -> toast(getString(R.string.failed,
									String.valueOf(ex.getMessage()))));
						}
					}).start();
				});
	}

	/** Put some text on the clipboard. */
	private void copyToClipboard(String text) {
		try {
			android.content.ClipboardManager cm =
					(android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
			cm.setPrimaryClip(android.content.ClipData.newPlainText("TailBridge", text));
			toast(getString(R.string.clip_copied));
		} catch (Exception e) {
			toast(getString(R.string.failed, String.valueOf(e.getMessage())));
		}
	}

	/**
	 * The two floating buttons on the clipboard screen.
	 *
	 * <p>They live in the corner, over the list, and they are the only way
	 * to act on what is on the clipboard.
	 */
	private void wireClipButtons() {
		View send = findViewById(R.id.clipSendFab);
		View now = findViewById(R.id.clipNowFab);
		if (send != null) {
			send.setOnClickListener(v -> confirmSendClipboard());
		}
		if (now != null) {
			now.setOnClickListener(v -> showClipboardNow());
		}
	}

	/**
	 * Fetch the history and redraw the list.
	 *
	 * <p>Only the list. The search box above it must keep its text and its
	 * focus, so the whole panel is never rebuilt.
	 *
	 * <p>The poll calls this while the clipboard screen is open, so a copy
	 * shows up without anybody having to pull to refresh. Without it the
	 * screen showed a history that was minutes out of date, and a copy that
	 * had plainly been recorded looked like it had not been.
	 */
	private void refreshClipboardList() {
		// What the system has in hand, right now, at the top of the list.
		// The module's answer is still asked for below; a su round trip to
		// it costs hundreds of milliseconds on a good moment and seconds
		// on a bad one, and a copy in front of somebody who is waiting is
		// not something to make them wait for.
		showLiveClipInstantly();
		// Once older pages are loaded, a full first-page refresh would throw
		// them away and shrink the list back to one page. The copy that just
		// happened is already on screen; the module's entry for it arrives
		// with the next tab open or search.
		if (clipLoaded <= CLIP_PAGE) {
			loadClipboard();
		}
	}

	/** The entries the list is showing. */
	private List<ClipEntry> clipEntries = new ArrayList<>();

	/** Each painted "who · when" line, so the when can be refreshed where
	 *  it stands. Rebuilding the list every minute would throw away the
	 *  scroll position for a label that changed by one character. */
	private final List<Object[]> clipStamps = new ArrayList<>();

	private final Runnable clipClock = new Runnable() {
		@Override public void run() {
			for (Object[] o : clipStamps) {
				TextView v = (TextView) o[0];
				String who = (String) o[1];
				long t = (Long) o[2];
				if (t > 0) {
					v.setText(who + "  ·  " + clipTime(t));
				}
			}
			ui.postDelayed(this, 30_000);
		}
	};
	/** Fetch bookkeeping. One fetch at a time: the root shell is a single
	 *  serialized pipe, and three callers asking at once made each answer
	 *  slower than the one before it. */
	private final Object clipFetchLock = new Object();
	private boolean clipFetching;
	private boolean clipFetchAgain;
	/** Whether the panel has ever been built. Until it has, the Clip tab
	 *  paints from the saved list instead of waiting for a fresh one. */
	private boolean clipBuilt;
	/** When the list last came back, for "is this stale enough to refetch". */
	private long clipFetchedAt;
	/** How many entries are held, and whether the module has more. */
	private int clipLoaded;
	/** How many entries the module holds, not just how many are loaded. */
	private int clipTotal;
	private boolean clipHasMore;
	/** The rows currently built, and whether they are a search result. */
	private final List<ClipEntry> clipShown = new ArrayList<>();
	private boolean clipSearching;
	private boolean clipLoadingMore;
	/** Bumped on every keystroke so a slow search cannot overwrite a newer one. */
	private int clipSearchGen;
	/** One page is one root round trip and one screenful and a bit. */
	private static final int CLIP_PAGE = 100;
	/** A bound on how much of a huge history is ever built as views. Beyond
	 *  this the list says so and the search box is the way further back. */
	private static final int CLIP_MAX_LOADED = 1000;
	private final Runnable clipSearchTick = this::loadClipboard;
	/** The listing last drawn; an unchanged answer is not redrawn. */
	private String clipRenderSig = "none";
	/** Bumped on every rebuild so pending row batches from an older list
	 *  cannot append themselves to the new one. */
	private int clipRenderGen;
	/** What is in the search box. */
	private String clipQuery = "";
	private static final String KEY_CLIP_QUERY = "clipQuery";
	/** Whether the list is showing only the starred entries. */
	private boolean clipStarOnly = false;

	/**
	 * Redraw the list, and nothing else.
	 *
	 * <p>The search box and the switch above it are left alone, so a copy
	 * that arrives while somebody is typing does not throw their query away
	 * or take the focus out of the field.
	 */
	private void refreshClipList() {
		if (clipList == null || clipListHeading == null) {
			return;
		}
		List<ClipEntry> shown = visibleClips();
		int count = (clipStarOnly || clipSearching) ? shown.size()
				: Math.max(clipTotal, shown.size());
		clipListHeading.setText(getString(R.string.clip_entry_count, count));

		// An unchanged answer is not redrawn. The list is refreshed every
		// fifteen seconds while it is on screen, and tearing down and
		// rebuilding every row for an answer that had not changed was the
		// reason scrolling stuttered and the screen jumped to the top.
		String s = clipSignature(shown);
		if (s.equals(clipRenderSig)) {
			return;
		}
		clipRenderSig = s;

		// A page appended to the end is appended, not rebuilt. Only a change
		// in what is already on screen -- a star, a new copy, a search --
		// costs a rebuild.
		boolean extendsShown = shown.size() >= clipShown.size();
		if (extendsShown) {
			for (int i = 0; i < clipShown.size(); i++) {
				ClipEntry a = clipShown.get(i);
				ClipEntry b = shown.get(i);
				if (!a.id.equals(b.id) || a.starred != b.starred
						|| a.text.length() != b.text.length()) {
					extendsShown = false;
					break;
				}
			}
		}
		if (extendsShown) {
			int from = clipShown.size();
			clipShown.addAll(shown.subList(from, shown.size()));
			appendClipRows(shown, from, ++clipRenderGen);
			return;
		}

		clipShown.clear();
		clipShown.addAll(shown);
		clipStamps.clear();
		final int gen = ++clipRenderGen;
		clipList.removeAllViews();
		appendClipRows(shown, 0, gen);
	}

	/** The rows the list should have, in order: starred first if asked, and
	 *  the same text twice collapsed to one entry. */
	private int clipLoadedTotal(List<ClipEntry> entries) {
		return entries.size();
	}

	private List<ClipEntry> visibleClips() {
		List<ClipEntry> out = new ArrayList<>();
		String last = null;
		for (ClipEntry e : clipEntries) {
			if (clipStarOnly && !e.starred) {
				continue;
			}
			if (e.text.equals(last)) {
				continue;
			}
			last = e.text;
			out.add(e);
		}
		return out;
	}

	private String clipSignature(List<ClipEntry> shown) {
		StringBuilder sig = new StringBuilder(shown.size() * 24);
		for (ClipEntry e : shown) {
			sig.append(e.id).append(':').append(e.starred ? '1' : '0')
					.append(':').append(e.text.length()).append(':')
					.append(e.text.hashCode()).append('\n');
		}
		return sig.toString();
	}

	/** Fetch the next page, when the end of the list is coming up. */
	private void loadMoreClips() {
		synchronized (clipFetchLock) {
			if (clipFetching || clipLoadingMore || clipSearching || !clipHasMore) {
				return;
			}
			if (clipLoaded >= CLIP_MAX_LOADED) {
				return;
			}
			clipLoadingMore = true;
		}
		final int offset = clipLoaded;
		new Thread(() -> {
			JSONArray arr = null;
			try {
				arr = Api.clipboardList(CLIP_PAGE, offset);
			} catch (Exception ignored) {
				// The next scroll tries again.
			}
			final JSONArray got = arr;
			ui.post(() -> {
				synchronized (clipFetchLock) {
					clipLoadingMore = false;
				}
				if (got == null) {
					return;
				}
				clipHasMore = clipTotal > 0 ? clipLoaded < clipTotal
						: got.length() >= CLIP_PAGE;
				int added = 0;
				java.util.HashSet<String> have = new java.util.HashSet<>();
				for (ClipEntry e : clipEntries) {
					if (!e.id.isEmpty()) {
						have.add(e.id);
					}
				}
				for (int i = 0; i < got.length(); i++) {
					JSONObject o = got.optJSONObject(i);
					if (o == null) {
						continue;
					}
					String id = o.optString("id", "");
					if (!id.isEmpty() && have.contains(id)) {
						continue;
					}
					ClipEntry e = new ClipEntry();
					e.id = id;
					e.time = o.optLong("t", 0);
					e.text = decode(o.optString("c", ""));
					e.source = o.optString("s", "");
					e.fromPeer = o.optInt("d", 0) == 1;
					e.starred = o.optInt("p", 0) == 1;
					have.add(id);
					clipEntries.add(e);
					added++;
				}
				clipLoaded += got.length();
				if (added > 0) {
					// The icons for this page, resolved before the rows are
					// built rather than during the frame.
					new Thread(() -> {
						for (ClipEntry e : clipEntries) {
							if (!e.source.isEmpty()) {
								appLabel(e.source);
								appIcon(e.source);
							}
						}
					}).start();
					refreshClipList();
				}
				if (!clipHasMore || clipLoaded >= CLIP_MAX_LOADED) {
					paintClipFooter();
				}
			});
		}).start();
	}

	private void paintClipFooter() {
		if (clipFooter == null) {
			return;
		}
		if (clipSearching) {
			clipFooter.setVisibility(View.GONE);
			return;
		}
		if (clipLoaded >= CLIP_MAX_LOADED && clipHasMore) {
			clipFooter.setText(getString(R.string.clip_cap, String.valueOf(clipLoaded)));
			clipFooter.setVisibility(View.VISIBLE);
		} else {
			clipFooter.setVisibility(View.GONE);
		}
	}

	/** Add the next batch of rows, yielding to the frame between batches.
	 *
	 *  <p>Two hundred rows of six views each is a lot to build in one go on
	 *  the UI thread; the screen stayed blank until the last one was done.
	 *  The first screenful appears immediately and the rest follows, which
	 *  is what makes the tab feel like it opened rather than loaded.
	 */
	private void appendClipRows(List<ClipEntry> shown, int from, int gen) {
		if (clipList == null || gen != clipRenderGen) {
			return;
		}
		int to = Math.min(shown.size(), from + 30);
		for (int i = from; i < to; i++) {
			clipRow(clipList, shown.get(i));
		}
		if (to < shown.size()) {
			clipList.postDelayed(() -> appendClipRows(shown, to, gen), 16);
		}
	}

	/**
	 * The switch that turns recording on and off.
	 *
	 * <p>It sits at the end of the search box rather than in a row of its
	 * own. A row with a title and a description would push the list down
	 * the screen, and the list is the whole point of this screen.
	 */
	private void clipSwitch(LinearLayout g) {
		new Thread(() -> {
			try {
				final JSONObject cfg = Api.clipboardConfig();
				ui.post(() -> {
					if (g == null) {
						return;
					}
					final boolean watch = cfg.optBoolean("watch", true);
					// The word beside the switch. Without it the switch
					// is a mystery: a small control that nobody can
					// tell what it is for.
					LinearLayout box = new LinearLayout(this);
					box.setOrientation(LinearLayout.HORIZONTAL);
					box.setGravity(Gravity.CENTER_VERTICAL);

					android.widget.Switch sw = toggle(watch);
					sw.setContentDescription(getString(R.string.clip_t_watch));
					sw.setOnCheckedChangeListener((b, checked) -> {
						((android.widget.Switch) b).setThumbTintList(android.content.res.ColorStateList.valueOf(
								getColor(checked ? R.color.text : R.color.hint)));
						new Thread(() -> {
							try {
								Api.clipboardSet("watch", checked ? "true" : "false");
								ui.post(() -> toast(getString(R.string.done)));
							} catch (Exception e) {
								ui.post(() -> toast(getString(R.string.failed,
										String.valueOf(e.getMessage()))));
							}
						}).start();
					});
					box.addView(sw, new LinearLayout.LayoutParams(
							ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

					TextView lbl = new TextView(this);
					lbl.setText(getString(R.string.clip_recording));
					lbl.setTextColor(getColor(watch ? R.color.text2 : R.color.hint));
					lbl.setTextSize(12f);
					lbl.setIncludeFontPadding(false);
					lbl.setPadding(dp(7), 0, 0, 0);
					box.addView(lbl);

					g.addView(box, new LinearLayout.LayoutParams(
							ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
				});
			} catch (Exception e) {
				ui.post(() -> note(g, getString(R.string.failed,
						String.valueOf(e.getMessage()))));
			}
		}).start();
	}

	/** The text of an entry, on one line for the list. */
	private String clipShown(ClipEntry e) {
		String body = clipText(e).replace('\n', ' ').trim();
		if (body.isEmpty()) {
			return getString(R.string.clip_now_empty);
		}
		return body;
	}

	/** Which app an entry came from, and when. */
	private String clipWho(ClipEntry e) {
		if (e.fromPeer) {
			return getString(R.string.clip_from_peer);
		}
		if ("com.android.shell".equals(e.source)) {
			// The shell user is how the module reads and writes the
			// clipboard, so this entry was put there by the module itself
			// rather than by an app.
			return getString(R.string.clip_by_module);
		}
		if (e.source.isEmpty()) {
			return getString(R.string.clip_no_source);
		}
		return appLabel(e.source);
	}

	private String clipSource(ClipEntry e) {
		return clipWho(e) + "  ·  " + clipTime(e.time);
	}

	/** A time, said the way a person would. */
	private String clipTime(long ms) {
		if (ms <= 0) {
			return "";
		}
		long diff = System.currentTimeMillis() - ms;
		if (diff < 60_000) {
			return getString(R.string.clip_just_now);
		}
		if (diff < 3_600_000) {
			return (diff / 60_000) + "m";
		}
		if (diff < 86_400_000) {
			return (diff / 3_600_000) + "h";
		}
		return (diff / 86_400_000) + "d";
	}

	/**
	 * The search box.
	 *
	 * <p>It filters the list as it is typed, and it is the only control on
	 * this screen, so it gets the top of it.
	 */
	private LinearLayout searchField(LinearLayout g, List<ClipEntry> entries) {
		// The search box and the recording switch share one row, so the
		// switch costs no vertical space and the list starts as high as
		// it can.
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);

		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.HORIZONTAL);
		box.setGravity(Gravity.CENTER_VERTICAL);
		box.setBackgroundResource(R.drawable.field);
		box.setPadding(dp(12), 0, dp(12), 0);

		ImageView ic = new ImageView(this);
		ic.setImageResource(R.drawable.ic_search);
		LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(dp(18), dp(18));
		icp.setMargins(0, 0, dp(10), 0);
		box.addView(ic, icp);

		final EditText in = new EditText(this);
		if (!clipQuery.isEmpty()) {
			in.setText(clipQuery);
		}
		in.setHint(getString(R.string.clip_search_hint));
		in.setSingleLine(true);
		in.setTextColor(getColor(R.color.text));
		in.setHintTextColor(getColor(R.color.hint));
		in.setTextSize(14.5f);
		in.setPadding(0, 0, 0, 0);
		in.setBackgroundColor(0x00000000);
		box.addView(in, new LinearLayout.LayoutParams(0, dp(46), 1f));

		row.addView(box, new LinearLayout.LayoutParams(0, dp(46), 1f));

		LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
		bp.bottomMargin = dp(4);
		g.addView(row, bp);

		in.addTextChangedListener(new android.text.TextWatcher() {
			@Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
			@Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
			@Override public void afterTextChanged(android.text.Editable e) {
				clipQuery = e.toString().trim().toLowerCase();
				// The module searches its whole store, not the page that
				// happens to be loaded, and that is a root round trip: wait
				// for the typing to stop rather than one per keystroke.
				ui.removeCallbacks(clipSearchTick);
				ui.postDelayed(clipSearchTick, 300);
			}
		});
		return row;
	}

	/** The entries that match a search. */
	private List<ClipEntry> clipFiltered(List<ClipEntry> entries, String q) {
		List<ClipEntry> out = new ArrayList<>();
		for (ClipEntry e : entries) {
			if (q.isEmpty()
					|| clipText(e).toLowerCase().contains(q)
					|| appLabel(e.source).toLowerCase().contains(q)) {
				out.add(e);
			}
		}
		return out;
	}

	/**
	 * Ask before sending the clipboard to everybody.
	 *
	 * <p>The clipboard is whatever was last copied, and that is very often a
	 * password, a one-time code or a private message. Sending it to a
	 * machine is the point; sending it to the wrong machine by accident is
	 * not something to make easy, so the content is shown first.
	 */
	private void confirmSendClipboard() {
		new Thread(() -> {
			try {
				final JSONObject now = Api.clipboardNow();
				ui.post(() -> {
					String b64 = now.optString("text", "");
					String body = "";
					try {
						body = new String(android.util.Base64.decode(b64,
								android.util.Base64.DEFAULT), "UTF-8");
					} catch (Exception ignored) {
					}
					if (body.isEmpty()) {
						toast(getString(R.string.clip_now_empty));
						return;
					}
					String shown = body.length() > 240 ? body.substring(0, 240) + "…" : body;
					Sheet.confirm(this, getString(R.string.clip_send_all),
							getString(R.string.clip_send_confirm, shown),
							getString(R.string.clip_send), false,
							this::sendClipboardToAll);
				});
			} catch (Exception e) {
				ui.post(() -> toast(getString(R.string.failed, String.valueOf(e.getMessage()))));
			}
		}).start();
	}

	/** The clipboard as it is right now. */
	private void showClipboardNow() {
		new Thread(() -> {
			try {
				final JSONObject now = Api.clipboardNow();
				ui.post(() -> {
					String b64 = now.optString("text", "");
					String body = "";
					try {
						body = new String(android.util.Base64.decode(b64,
								android.util.Base64.DEFAULT), "UTF-8");
					} catch (Exception ignored) {
					}
					if (body.isEmpty()) {
						toast(getString(R.string.clip_now_empty));
						return;
					}
					final String text = body;
					Sheet s = Sheet.card(this).title(getString(R.string.clip_now));
					s.message(text, true);
					s.primary(getString(R.string.copy),
							() -> copyToClipboard(text));
					s.secondary(getString(android.R.string.cancel)).show();
				});
			} catch (Exception e) {
				ui.post(() -> toast(getString(R.string.failed, String.valueOf(e.getMessage()))));
			}
		}).start();
	}

	/** One entry of the history: show it, and offer to send or copy it. */
	private void showClipEntry(ClipEntry e) {
		String body = clipText(e);
		// A source label like "com.paras.tailbridge" is honest but not
		// friendly; show the label an installed package goes by, or the
		// plain words for the special sources.
		String who;
		if (e.fromPeer) {
			who = getString(R.string.clip_from_peer);
		} else if ("com.android.shell".equals(e.source)) {
			who = getString(R.string.clip_by_module);
		} else if (e.source.isEmpty()) {
			who = getString(R.string.clip_no_source);
		} else {
			who = appLabel(e.source);
		}
		// A very long clip is shown shortened: laying out a hundred thousand
		// characters is what makes a popup feel late on a busy phone. Copy
		// still puts the whole thing on the clipboard.
		String preview = body;
		if (preview.length() > 12000) {
			preview = preview.substring(0, 12000) + "\n…";
		}
		Sheet s = Sheet.card(this).title(
				who.isEmpty() ? getString(R.string.clip_heading) : who);
		s.message(body.isEmpty() ? getString(R.string.clip_now_empty) : preview, true);
		if (!body.isEmpty()) {
			s.primary(getString(R.string.copy), () -> copyToClipboard(body));
			s.secondary(getString(android.R.string.cancel));
		} else {
			s.primary(getString(android.R.string.ok), null);
		}
		s.show();
	}

	/** Send whatever is on the clipboard to every device that is online. */
	private void sendClipboardToAll() {
		List<String> targets = new ArrayList<>();
		for (String n : onlinePeers) {
			String ip = peers.get(n);
			if (ip != null && !ip.isEmpty()) {
				targets.add(ip);
			}
		}
		if (targets.isEmpty()) {
			toast(getString(R.string.no_peers));
			return;
		}
		toast(getString(R.string.working));
		new Thread(() -> {
			int ok = 0;
			String err = null;
			for (String ip : targets) {
				try {
					Api.clipboardSend(ip);
					ok++;
				} catch (Exception e) {
					err = String.valueOf(e.getMessage());
				}
			}
			final int sent = ok;
			final String failure = err;
			ui.post(() -> {
				if (failure == null && sent == targets.size()) {
					toast(getString(R.string.clip_sent, String.valueOf(sent)));
				} else {
					toast(getString(R.string.failed,
							String.valueOf(sent) + "/" + targets.size()
									+ (failure == null ? "" : " " + failure)));
				}
			});
		}).start();
	}

	private void clearClipboard() {
		new Thread(() -> {
			try {
				Api.clipboardClear();
				ui.post(() -> {
					toast(getString(R.string.done));
					loadClipboard();
				});
			} catch (Exception e) {
				ui.post(() -> toast(getString(R.string.failed, String.valueOf(e.getMessage()))));
			}
		}).start();
	}

	// ------------------------------------------------------------------ home

	/**
	 * Home: what this device is, where files go, and the daemon switch. Plain
	 * grouped rows, the way a system settings screen reads.
	 */
	private void buildSend(List<Api.Peer> ps) {
		pSend.removeAllViews();
		nameValues.clear();
		nameShown.clear();
		switches.clear();

		if (chosen.isEmpty() && !defaultTargets.isEmpty()) {
			for (String n : defaultTargets.split(",")) {
				if (!n.isEmpty() && peers.containsKey(n)) {
					chosen.add(n);
				}
			}
		}

		// Unauthenticated is the most common first-run state, and it is useless
		// without a clear next step: say so up front rather than leaving an empty
		// device list to puzzle over.
		// A daemon that is not running is a different thing from a daemon
		// that is running and signed out.
		//
		// With the daemon stopped there is nobody signed in and nobody
		// signed out, and saying "not signed in" sends the user to the
		// sign-in screen for a problem that signing in cannot fix. The
		// header already says the daemon is stopped; this says what to do
		// about it.
		if (!daemonUp(status)) {
			stoppedCard();
		} else if (!signedIn(status)) {
			signInCard();
		}

		JSONObject self = status == null ? null : status.optJSONObject("self");

		// ── this device ──
		// While signed out every value here is a placeholder ("localhost", no
		// addresses), so the card is noise rather than information.
		if (self != null && signedIn(status)) {
			LinearLayout g = group(pSend, getString(R.string.this_device));
			TextView nv = value(g, getString(R.string.device_name),
					self.optString("host", "\u2014"));
			nameValues.add(nv);
			nameShown.add(self.optString("host", "\u2014"));

			JSONArray ips = self.optJSONArray("tailscaleIPs");
			if (ips != null) {
				for (int i = 0; i < ips.length(); i++) {
					// The address is the thing people actually want, so the
					// whole row copies it and says so.
					final String ip = ips.optString(i);
					copyRow(g, i == 0 ? getString(R.string.ipv4) : getString(R.string.ipv6), ip);
				}
			}
			String relay = self.optString("relay", "");
			value(g, getString(R.string.relay),
					relay.isEmpty() ? getString(R.string.direct) : relay);
		}

		// ── send targets ──
		LinearLayout t = group(pSend, getString(R.string.choose_devices));
		if (ps.isEmpty()) {
			plain(t, getString(R.string.no_peers_short));
		} else {
			for (Api.Peer p : ps) {
				line(t);
				t.addView(peerRow(p));
			}
		}

		// The one accent in the app, placed directly under what it acts on.
		sendBtn = new Button(this);
		sendBtn.setBackgroundResource(R.drawable.btn_primary);
		sendBtn.setTextColor(getColor(R.color.onAccent));
		sendBtn.setTextSize(16);
		sendBtn.setTypeface(null, android.graphics.Typeface.BOLD);
		sendBtn.setAllCaps(false);
		sendBtn.setSingleLine(true);
		sendBtn.setStateListAnimator(null);
		sendBtn.setMinWidth(0);
		sendBtn.setMinHeight(0);
		sendBtn.setOnClickListener(v -> onSendPressed());
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
		slp.topMargin = dp(16);
		pSend.addView(sendBtn, slp);

		if (!pendingUris.isEmpty()) {
			note(pSend, getString(R.string.shared_n, pendingUris.size()));
		}

		// Files that arrived while the app was closed. The notification covers
		// the moment of arrival, but the app is where you look later, and a
		// file that is only discoverable by opening a shade you have since
		// cleared is a file you will assume never arrived.
		if (!receivedChecked) {
			receivedChecked = true;
			// Off the render path: this is a second `su` round-trip and running
			// it inline doubled the cost of opening the app.
			new Thread(() -> {
				try {
					JSONObject got = Api.received();
					final int n = got.optInt("count", 0);
					receivedCount = n;
					if (n > 0) {
						ui.post(() -> {
							if (pSend != null) {
								note(pSend, getString(R.string.received_while_away, n));
							}
						});
					}
				} catch (Exception ignored) {
					// The marker is a nicety, not a requirement.
				}
			}).start();
		}

		// ── daemon ──
		LinearLayout d = group(pSend, getString(R.string.daemon));
		addSwitch(d, getString(R.string.service),
				getString(R.string.service_sub), daemonUp(status),
				v -> service(v.isChecked() ? "start" : "stop"));
		action(d, getString(R.string.restart), null, v -> service("restart"));
		// The supervisor is the thing that puts the daemon back when it dies.
		// Showing whether it is alive is the difference between "the module
		// is broken" and "the module was working, and then something killed
		// the daemon and the supervisor went with it".
		View supRow = action(d, getString(R.string.supervisor),
				supervisorUp(status)
						? getString(R.string.supervisor_running)
						: getString(R.string.supervisor_stopped),
				null);
		supervisorSub = supRow.findViewById(R.id.rowSub);
		action(d, getString(R.string.console), null, v -> open(CONSOLE_URL));

		updateSendButton();
	}

	/** Big, obvious, and the only thing that matters before signing in. */
	/** The card shown when the daemon is not running. */
	private void stoppedCard() {
		LinearLayout card = new LinearLayout(this);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setBackgroundResource(R.drawable.group);
		card.setPadding(dp(20), dp(22), dp(20), dp(20));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.bottomMargin = dp(8);
		pSend.addView(card, lp);

		TextView t = new TextView(this);
		t.setText(R.string.daemon_stopped_card);
		t.setTextColor(getColor(R.color.text));
		t.setTextSize(21);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		t.setIncludeFontPadding(false);
		card.addView(t);

		TextView d = new TextView(this);
		d.setText(R.string.daemon_stopped_card_body);
		d.setTextColor(getColor(R.color.text2));
		d.setTextSize(14);
		d.setLineSpacing(dp(4), 1f);
		d.setPadding(0, dp(8), 0, 0);
		card.addView(d);

		TextView go = new TextView(this);
		go.setText(R.string.start_daemon);
		go.setTextColor(getColor(R.color.onAccent));
		go.setTextSize(16);
		go.setGravity(Gravity.CENTER);
		go.setTypeface(null, android.graphics.Typeface.BOLD);
		go.setBackgroundResource(R.drawable.btn_primary);
		go.setPadding(dp(16), dp(15), dp(16), dp(15));
		go.setClickable(true);
		go.setOnClickListener(v -> service("start"));
		LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		glp.topMargin = dp(20);
		card.addView(go, glp);
	}

	private void signInCard() {
		boolean up = daemonUp(status);
		LinearLayout card = new LinearLayout(this);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setBackgroundResource(R.drawable.group);
		card.setPadding(dp(20), dp(22), dp(20), dp(20));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.bottomMargin = dp(8);
		pSend.addView(card, lp);

		TextView t = new TextView(this);
		t.setText(R.string.not_signed_in);
		t.setTextColor(getColor(R.color.text));
		t.setTextSize(21);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		card.addView(t);

		TextView d = new TextView(this);
		d.setText(R.string.sign_in_card_body);
		d.setTextColor(getColor(R.color.text2));
		d.setTextSize(14);
		d.setLineSpacing(dp(4), 1f);
		d.setPadding(0, dp(8), 0, 0);
		card.addView(d);

		TextView go = new TextView(this);
		go.setText(R.string.sign_in);
		go.setTextColor(getColor(R.color.onAccent));
		go.setTextSize(16);
		go.setGravity(Gravity.CENTER);
		go.setTypeface(null, android.graphics.Typeface.BOLD);
		go.setBackgroundResource(R.drawable.btn_primary);
		go.setPadding(dp(16), dp(15), dp(16), dp(15));
		go.setClickable(true);
		go.setOnClickListener(v -> doLogin());
		LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		glp.topMargin = dp(20);
		card.addView(go, glp);

		if (!up) {
			// Signed out AND stopped: the switch on this screen cannot help.
			TextView hint = new TextView(this);
			hint.setText(R.string.sign_in_needs_daemon);
			hint.setTextColor(getColor(R.color.hint));
			hint.setTextSize(12);
			hint.setPadding(0, dp(14), 0, 0);
			card.addView(hint);
		}
	}


	/** "just now" / "4m ago" / "3h ago" / "2d ago", or "" if unknown.
	 *
	 * <p>Parsed here rather than pre-formatted by the daemon, because the app
	 * has to say "4 minutes ago" relative to now, and the daemon's clock and
	 * the phone's are the same but the language is not.
	 */
	private String lastSeenLabel(String iso) {
		if (iso == null || iso.isEmpty() || iso.startsWith("0001-")) {
			return "";
		}
		long then;
		try {
			then = java.time.Instant.parse(iso.replaceAll("\\.[0-9]+Z?$", "Z")).toEpochMilli();
		} catch (Exception e) {
			return "";
		}
		long mins = (System.currentTimeMillis() - then) / 60000L;
		if (mins < 1) {
			return getString(R.string.time_now);
		}
		if (mins < 60) {
			return mins + getString(R.string.time_min);
		}
		long hours = mins / 60;
		if (hours < 24) {
			return hours + getString(R.string.time_hour);
		}
		return (hours / 24) + getString(R.string.time_day);
	}


	private View peerRow(Api.Peer p) {
		LinearLayout row = row();
		final boolean up = p.online;

		// Presence, so an offline device reads as offline rather than as a
		// device that has silently vanished from the list. The list shows every
		// known peer; only the online ones can be sent to.
		ImageView dot = new ImageView(this);
		dot.setImageResource(R.drawable.dot);
		dot.setColorFilter(getColor(up ? R.color.ok : R.color.err));
		dot.setContentDescription(getString(up ? R.string.peer_online : R.string.peer_offline));
		LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
		dotLp.rightMargin = dp(10);
		row.addView(dot, dotLp);

		android.widget.Switch cb = toggle(up && chosen.contains(p.name));
		// Force-clear an offline peer rather than only disabling it: a ticked
		// switch next to a red dot reads as "this will be sent".
		cb.setEnabled(up);
		cb.setAlpha(up ? 1f : 0.4f);
		cb.setThumbTintList(android.content.res.ColorStateList.valueOf(
				getColor(up && chosen.contains(p.name) ? R.color.text : R.color.hint)));

		LinearLayout col = col();
		TextView n = new TextView(this);
		n.setText(p.name);
		n.setTextColor(getColor(up ? R.color.text : R.color.hint));
		n.setTextSize(15);
		n.setSingleLine(true);
		col.addView(n);
		TextView meta = new TextView(this);
		// "offline · 4m ago" tells you whether to wait or give up. A bare
		// "offline" is a dead end: it gives no hint of how stale it is.
		String when = up ? "" : lastSeenLabel(p.lastSeen);
		// How the two devices are actually talking right now.
		//
		// Direct means the tailnet found a path between them and the transfer
		// runs at LAN speed. Through a DERP relay it goes via a third-party
		// machine, which is slower and worth knowing about before sending
		// something large -- the same send can be seconds or minutes.
		//
		// The relay is named, because DERP relays are region codes: "blr" is
		// Bangalore. Knowing which one means knowing roughly how far the data
		// is travelling.
		String link;
		if (p.direct) {
			link = getString(R.string.link_direct);
		} else if (!p.relay.isEmpty()) {
			link = getString(R.string.link_relay, p.relay);
		} else {
			link = getString(R.string.link_none);
		}
		meta.setText(p.ip
				+ (p.os.isEmpty() ? "" : "  ·  " + p.os)
				+ (up ? "  ·  " + link : "")
				+ (p.exitNode ? "  ·  " + getString(R.string.exit_node_suffix) : "")
				+ (p.exitNodeOption && !p.exitNode
					? "  ·  " + getString(R.string.exit_node_offer) : "")
				+ (up ? "" : "  ·  " + getString(R.string.peer_offline_label)
					+ (when.isEmpty() ? "" : "  ·  " + when)));
		meta.setTextColor(getColor(R.color.text2));
		meta.setTextSize(12);
		col.addView(meta);
		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		// The switch is the selection, and it sits where a trailing control
		// belongs: after the name, before the one-off send.
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		slp.leftMargin = dp(4);
		row.addView(cb, slp);

		// Send straight to this one device without touching the selection.
		// Disabled when the peer is offline: Taildrop would report success and
		// deliver nothing, which is worse than refusing here.
		View send = iconButton(R.drawable.ic_send, up ? R.color.text : R.color.hint,
				R.string.send, v -> launchPicker(java.util.Collections.singletonList(p.ip)));
		send.setEnabled(up);
		send.setAlpha(up ? 1f : 0.35f);
		row.addView(send);


		// Tapping anywhere else on the row is the same as the checkbox.
		// Offline peers are inert: selecting one would queue a send that
		// Taildrop reports as successful and never delivers.
		OnToggle peer = () -> {
			if (!up) {
				toast(getString(R.string.peer_offline_toast));
				return;
			}
			if (!chosen.remove(p.name)) {
				chosen.add(p.name);
			}
			saveTargets();
			refresh();
		};
		row.setOnClickListener(v -> peer.run());
		row.setClickable(true);
		cb.setOnClickListener(v -> peer.run());
		return row;
	}

	/** A small square icon that acts as a trailing row button. */
	private View iconButton(int drawable, int tint, int label, View.OnClickListener l) {
		ImageView v = new ImageView(this);
		v.setImageResource(drawable);
		v.setColorFilter(getColor(tint));
		v.setContentDescription(getString(label));
		v.setPadding(dp(13), dp(14), dp(13), dp(14));
		v.setClickable(true);
		v.setBackgroundResource(R.drawable.row_press);
		v.setOnClickListener(l);
		return v;
	}

	private interface OnToggle {
		void run();
	}

	private void copy(String ip) {
		android.content.ClipboardManager cm = (android.content.ClipboardManager)
				getSystemService(CLIPBOARD_SERVICE);
		if (cm != null) {
			cm.setPrimaryClip(android.content.ClipData.newPlainText("tailscale", ip));
			toast(getString(R.string.copied, ip));
		}
	}

	/** Send button: either deliver an incoming share, or open the file picker. */
	private void onSendPressed() {
		List<String> to = currentTargets();
		if (to.isEmpty()) {
			toast(getString(R.string.pick_a_device));
			return;
		}
		if (!pendingUris.isEmpty()) {
			dispatch(new ArrayList<>(pendingUris), to);
			pendingUris.clear();
			return;
		}
		launchPicker(to);
	}

	private void saveTargets() {
		StringBuilder b = new StringBuilder();
		for (String n : chosen) {
			if (b.length() > 0) {
				b.append(',');
			}
			b.append(n);
		}
		defaultTargets = b.toString();
		getPreferences(0).edit().putString("targets", defaultTargets).apply();
	}

	private List<String> currentTargets() {
		List<String> out = new ArrayList<>();
		for (String n : chosen) {
			String ip = peers.get(n);
			// One send per device, always. A duplicate address here sent the
			// same file two or three times in a row, which looked like the app
			// misbehaving rather than a list that had quietly grown a repeat.
			if (ip != null && !out.contains(ip)) {
				out.add(ip);
			}
		}
		return out;
	}

	private void updateSendButton() {
		if (sendBtn == null) {
			return;
		}
		// Only reachable devices can be targets. An offline peer is pruned from
		// `chosen` the moment it goes down, so `n` counts live devices only.
		// No "if nothing is online, allow everything" fallback: that is exactly
		// backwards, and it lets a fully offline tailnet keep a live send button.
		chosen.retainAll(onlinePeers);
		int n = chosen.size();
		sendBtn.setEnabled(signedIn(status) && n > 0);
		if (n == 1) {
			sendBtn.setText(getString(R.string.send_to, chosen.iterator().next()));
		} else if (n > 1) {
			sendBtn.setText(getString(R.string.send_to_n, n));
		} else if (!signedIn(status)) {
			sendBtn.setText(getString(R.string.send_need_signin));
		} else if (!peers.isEmpty()) {
			// Devices exist but none are reachable. Say so, instead of a dead
			// button labelled "Send files" that looks like a broken app.
			sendBtn.setText(getString(R.string.send_no_online));
		} else {
			sendBtn.setText(getString(R.string.send_files));
		}
	}

	private void launchPicker(List<String> ips) {
		if (ips.isEmpty()) {
			toast(getString(R.string.pick_a_device));
			return;
		}
		// Remember the chosen peers for the picker's result.
		//
		// This line was missing. onActivityResult dispatches to lastPick and
		// bails out when it is empty -- without this assignment it was always
		// empty, so every in-app send died silently at the picker: the sheet
		// opened, a file was chosen, and nothing happened with no error shown.
		// The share-sheet path never noticed because it routes through
		// pendingUris instead.
		lastPick.clear();
		lastPick.addAll(ips);
		Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		i.addCategory(Intent.CATEGORY_OPENABLE);
		i.setType("*/*");
		i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
		try {
			startActivityForResult(i, REQ_PICK);
		} catch (Exception e) {
			toast(getString(R.string.no_picker));
		}
	}

	@Override
	protected void onActivityResult(int req, int result, Intent data) {
		super.onActivityResult(req, result, data);
		if (req != REQ_PICK || result != RESULT_OK || data == null) {
			return;
		}
		final List<Uri> uris = new ArrayList<>();
		if (data.getClipData() != null) {
			for (int i = 0; i < data.getClipData().getItemCount(); i++) {
				uris.add(data.getClipData().getItemAt(i).getUri());
			}
		} else if (data.getData() != null) {
			uris.add(data.getData());
		}
		if (uris.isEmpty() || lastPick.isEmpty()) {
			// Dropping a completed pick quietly is how the missing lastPick
			// assignment stayed hidden. Say something, even in the log.
			Log.w(TAG, "picker result dropped: " + uris.size() + " uri(s), "
					+ lastPick.size() + " target(s)");
			if (uris.isEmpty()) {
				toast(getString(R.string.nothing_picked));
			}
			return;
		}
		dispatch(uris, new ArrayList<>(lastPick));
		lastPick.clear();
	}

	/** every file -> every chosen peer, reporting partial failures. */
	private void dispatch(List<Uri> uris, List<String> to) {
		// One send per device, enforced here rather than trusted from the
		// caller. A duplicate address used to send the same file to the
		// same machine that many times, and a file delivered three times
		// cannot be undone at the other end.
		final List<String> targets = new ArrayList<>();
		for (String p : to) {
			if (p != null && !p.isEmpty() && !targets.contains(p)) {
				targets.add(p);
			}
		}
		if (targets.isEmpty()) {
			toast(getString(R.string.pick_a_device));
			return;
		}

		// One popup for the whole run. It breathes while the files move,
		// turns into a check when they land and leaves on its own; a failed
		// run stays until it has been read. The dialog is the progress: no
		// second toast saying what just happened.
		final TransferDialog progress = new TransferDialog(this,
				getString(R.string.send_progress_staging));

		new Thread(() -> {
			try {
				java.io.File dir = new java.io.File(getCacheDir(), "send");
				List<java.io.File> files = new ArrayList<>();
				int failed = 0;
				for (Uri u : uris) {
					try {
						files.add(Stage.copy(this, u, dir));
					} catch (Exception e) {
						// Could not even read the file. Without this the run would
						// report "sent 0" as a success.
						failed++;
						Log.w(TAG, "could not stage " + u + ": " + e);
					}
				}

				if (files.isEmpty()) {
					ui.post(() -> progress.failure(
							getString(R.string.send_fail_title),
							getString(R.string.send_no_files), null, null));
					return;
				}

				final int total = targets.size() * files.size();
				int done = 0;
				int ok = 0;
				StringBuilder problems = new StringBuilder();

				for (String peer : targets) {
					String peerName = null;
					for (Map.Entry<String, String> e : peers.entrySet()) {
						if (peer.equals(e.getValue())) {
							peerName = e.getKey();
							break;
						}
					}
					final String label = peerName == null ? peer : peerName;
					for (java.io.File f : files) {
						final int n = ++done;
						ui.post(() -> progress.subtitle(getString(
								R.string.send_progress_current, n, total,
								f.getName(), label)));
						try {
							Api.send(peer, f.getAbsolutePath());
							ok++;
						} catch (Root.Denied e) {
							// Root was refused part way through a run. It is not
							// a crash, but a lost file; the report below says
							// so, and the user has the permission screen for
							// the next attempt.
							failed++;
							problems.append(label).append(": ")
									.append(getString(R.string.need_root_short)).append('\n');
						} catch (Exception e) {
							failed++;
							if (problems.length() < 320) {
								problems.append(label).append(": ")
										.append(e.getMessage()).append('\n');
							}
						}
					}
				}

				final int fOk = ok, fFailed = failed, fTotal = total;
				final String p = problems.toString().trim();
				ui.post(() -> {
					if (fFailed == 0) {
						progress.success(getString(R.string.sent_summary,
								fOk, targets.size()));
					} else {
						progress.failure(getString(R.string.send_fail_title),
								getString(R.string.send_fail_sub, fOk, fTotal),
								p, null);
					}
					// Re-read so the staged-items note clears once it is sent.
					refresh();
				});
			} catch (Throwable t) {
				// One worker for a whole share. Whatever it throws, the app
				// stays alive to report it; an uncaught throw up here would
				// take the process down, and with it the share the user was
				// still waiting on.
				Log.w(TAG, "send run failed", t);
				ui.post(() -> progress.failure(
						getString(R.string.send_fail_title),
						String.valueOf(t.getMessage()), null, null));
			}
		}).start();
	}

	// -------------------------------------------------------------- settings


	/** IP of the exit node currently in use, or "" for none. */
	private String currentExitNodeIp(JSONObject st) {
		if (st == null) {
			return "";
		}
		JSONObject en = st.optJSONObject("exitNode");
		return en == null ? "" : en.optString("ip", "");
	}

	private String exitNodeSummary(JSONObject st) {
		String ip = currentExitNodeIp(st);
		if (ip.isEmpty()) {
			return getString(R.string.exit_node_none);
		}
		JSONObject en = st.optJSONObject("exitNode");
		if (en != null) {
			List<Api.Peer> ps = Api.peersFrom(st);
			for (Api.Peer p : ps) {
				if (ip.equals(p.ip)) {
					return p.name;
				}
			}
		}
		return ip;
	}

	private void showExitNodePicker(JSONObject st, List<Api.Peer> candidates) {
		final List<String> labels = new ArrayList<>();
		final List<String> values = new ArrayList<>();
		labels.add(getString(R.string.exit_node_none));
		values.add("none");
		for (Api.Peer p : candidates) {
			labels.add(p.name + (p.online ? "" : "  (" + getString(R.string.peer_offline_label) + ")"));
			values.add(p.ip);
		}

		final String cur = currentExitNodeIp(st);
		int checked = values.indexOf(cur);
		if (checked < 0) {
			checked = 0;
		}

		Sheet.choice(this, getString(R.string.t_use_exit),
				labels.toArray(new CharSequence[0]), checked, which -> {
					final String value = values.get(which);
					if (value.equals(cur)) {
						return;
					}
					new Thread(() -> {
						String err = null;
						try {
							Api.set("exit-node", value);
						} catch (Exception e) {
							err = String.valueOf(e.getMessage());
						}
						final String e2 = err;
						ui.post(() -> {
							if (e2 == null) {
								toast(getString(R.string.exit_node_set,
										"none".equals(value)
												? getString(R.string.exit_node_none)
												: labels.get(values.indexOf(value))));
							}
							refresh();
						});
					}).start();
				});
	}

	private void buildSettings(JSONObject st) {
		pSettings.removeAllViews();
		switches.clear();
		Map<String, Boolean> flags = Api.flagsFrom(st);
		boolean online = daemonUp(st);

		LinearLayout c1 = group(pSettings, getString(R.string.connectivity));
		addToggle(c1, "accept-dns", getString(R.string.t_accept_dns), "", flags, online);
		addToggle(c1, "accept-routes", getString(R.string.t_accept_routes), "", flags, online);
		addToggle(c1, "shields-up", getString(R.string.t_shields_up), "", flags, online);

		// Use an exit node.
		//
		// Advertising one is a toggle; *choosing* one was missing entirely, which
		// is the half people actually use. A node only appears here when it says
		// it offers itself -- there is no point listing peers that will refuse.
		List<Api.Peer> candidates = new ArrayList<>();
		for (Api.Peer pp : Api.peersFrom(st)) {
			if (pp.exitNodeOption) {
				candidates.add(pp);
			}
		}
		if (online) {
			final String curIp = currentExitNodeIp(st);
			final List<Api.Peer> finalCands = candidates;
			action(c1, getString(R.string.t_use_exit), exitNodeSummary(st),
					v -> showExitNodePicker(st, finalCands));
		}

		LinearLayout c2 = group(pSettings, getString(R.string.services));
		addToggle(c2, "run-ssh", getString(R.string.t_ssh), "", flags, online);
		// The web client is not offered.
		//
		// The daemon takes the setting and says the interface is running,
		// and then nothing answers on the port: measured on this phone, and
		// the same on a desktop where it does work, so it is the port and
		// not the setting. A switch that reports something that is not
		// there is worse than no switch.

		LinearLayout c3 = group(pSettings, getString(R.string.this_device));
		// The phone cannot be an exit node, and it does not masquerade.
		//
		// The daemon runs with netfilter off, which is what a phone has to
		// run with, and the netfilter rules are what rewrite the address
		// of traffic leaving through another device. So a phone that
		// offered itself as an exit node would take the other device's
		// traffic and drop it, and there would be no way for anyone to
		// tell that from the exit node simply being unreachable.
		//
		// The two switches that set those things are gone rather than
		// left there doing nothing. A switch that does nothing is worse
		// than no switch: it says the phone can do a thing it cannot.
		//
		// Using another device as an exit node is the other direction,
		// and that the phone can do, so that row stays.

		// The clipboard.
		//
		// A clipboard history is a record of whatever passed through the
		// clipboard, and a lot of that is secret: a password from a password
		// manager, a one-time code, a line from a private message. So the
		// settings here are about what gets kept and what gets ignored,
		// not about a colour.
		LinearLayout c4 = group(pSettings, getString(R.string.clip_settings));
		buildClipboardSettings(c4);

		// device name
		LinearLayout hr = new LinearLayout(this);
		hr.setOrientation(LinearLayout.HORIZONTAL);
		hr.setGravity(Gravity.CENTER_VERTICAL);
		EditText in = new EditText(this);
		in.setHint(getString(R.string.hostname_hint));
		JSONObject prefs = st.optJSONObject("prefs");
		in.setText(prefs == null ? "" : prefs.optString("hostname", ""));
		in.setSingleLine(true);
		in.setTextColor(getColor(R.color.text));
		in.setHintTextColor(getColor(R.color.hint));
		in.setTextSize(15);
		in.setPadding(dp(12), 0, dp(12), 0);
		in.setBackgroundResource(R.drawable.field);
		hr.addView(in, new LinearLayout.LayoutParams(0, dp(46), 1f));

		TextView apply = new TextView(this);
		apply.setText(R.string.apply);
		apply.setTextColor(getColor(online ? R.color.text : R.color.hint));
		apply.setTextSize(14);
		apply.setGravity(Gravity.CENTER);
		apply.setPadding(dp(18), dp(13), dp(6), dp(13));
		apply.setEnabled(online);
		apply.setOnClickListener(v -> applyPref("hostname",
				in.getText().toString().trim(), getString(R.string.hostname)));
		hr.addView(apply);
		c3.addView(hr);

		if (!online) {
			note(pSettings, getString(R.string.daemon_stopped_note));
		}
		note(pSettings, getString(R.string.footnote));
	}

	private void addToggle(LinearLayout g, String key, String label, String desc,
			Map<String, Boolean> flags, boolean online) {
		android.widget.Switch sw = addSwitch(g, label, desc, flags.getOrDefault(key, false), v -> {
			if (online) {
				applyPref(key, String.valueOf(v.isChecked()), label);
			} else {
				v.setChecked(!v.isChecked());
			}
		});
		switches.put(key, sw);
	}

	private void applyPref(String key, String value, String label) {
		new Thread(() -> {
			try {
				final JSONObject st = Api.set(key, value);
				ui.post(() -> {
					toast(label + " " + (isTrue(value) ? "on" : "off"));
					status = st;
					buildSettings(st);
				});
			} catch (Root.Denied e) {
				ui.post(() -> toast(getString(R.string.need_root_short)));
			} catch (Exception e) {
				ui.post(() -> {
					toast(getString(R.string.failed, String.valueOf(e.getMessage())));
					// Put the switch back: the change did not stick.
					android.widget.Switch sw = switches.get(key);
					if (sw != null) {
						sw.setOnCheckedChangeListener(null);
						sw.setChecked(!sw.isChecked());
					}
				});
			}
		}).start();
	}

	private static boolean isTrue(String v) {
		return "true".equals(v);
	}

	// ---------------------------------------------------------------- system

	private void buildSystem(JSONObject st) {
		pSystem.removeAllViews();
		updateRow = null;
		updateTitle = null;
		updateSub = null;

		LinearLayout c = group(pSystem, getString(R.string.connectivity));
		value(c, getString(R.string.backend), st.optString("backend", "?"));
		JSONObject self = st.optJSONObject("self");
		if (self != null) {
			TextView nv = value(c, getString(R.string.device_name), self.optString("host", "\u2014"));
			nameValues.add(nv);
			nameShown.add(self.optString("host", "\u2014"));
		}
		JSONObject tn = st.optJSONObject("tailnet");
		String account = tn == null ? "" : tn.optString("name", "");

		// ── account ──
		LinearLayout a1 = group(pSystem, getString(R.string.account));
		value(a1, getString(R.string.tailnet), account.isEmpty()
				? getString(R.string.not_signed_in) : account);
		// Only offer the action that makes sense. Showing "Sign in" to someone
		// who is already signed in reads as though the app did not notice.
		if (account.isEmpty()) {
			action(a1, getString(R.string.sign_in), null, v -> doLogin());
		} else {
			action(a1, getString(R.string.sign_out), null, v -> confirmSignOut());
		}

		LinearLayout u = group(pSystem, getString(R.string.software));
		value(u, getString(R.string.tailscaled_version), st.optString("version", "?"));
		value(u, getString(R.string.module_version), st.optString("module", "?"));

		updateRow = action(u, getString(R.string.check_updates),
				getString(R.string.update_check_hint), v -> updateNow());
		updateTitle = updateRow.findViewById(R.id.rowTitle);
		updateSub = updateRow.findViewById(R.id.rowSub);
		paintUpdateRow();

		// No "Receive files" button.
		//
		// The receiver script pulls the Taildrop inbox every couple of seconds,
		// so by the time anyone looked at this the inbox was already empty and
		// the button could only ever report "nothing received". It advertised a
		// manual step that stopped existing.
		//
		// What is worth showing instead is where deliveries land and how many.
		if (receivedCount > 0) {
			action(u, getString(R.string.received_files_row, receivedCount),
					getString(R.string.taildrop_folder_sub), v -> openTaildropFolder());
		} else {
			value(u, getString(R.string.received_files_row_none),
					getString(R.string.taildrop_folder_sub));
		}
		action(u, getString(R.string.rollback), null, v -> rollback());
		action(u, getString(R.string.logs), null, v -> showLogs());

		// ── wireless debugging ──
		//
		// adb over TCP, on the phone's Tailscale address. The point of it is
		// that the Tailscale address does not move: the address on the local
		// network changes with the lease, and a phone whose address moves is
		// a phone you cannot reach.
		//
		// It is a risk, and it is marked as one. Anything that can reach the
		// phone on the tailnet, and that has the adb key, has the phone.
		LinearLayout w = group(pSystem, getString(R.string.wireless_adb_group));
		addSwitch(w, getString(R.string.wireless_adb_switch),
				getString(R.string.wireless_adb_sub), adbWireless,
				v -> confirmWirelessAdb(v.isChecked()));
		if (adbWireless) {
			String ip = tsIpv4(st);
			if (!ip.isEmpty()) {
				value(w, getString(R.string.wireless_adb_address), ip + ":5555");
			}
		}
		note(w, getString(R.string.wireless_adb_warning));

		LinearLayout a = group(pSystem, getString(R.string.about));
		action(a, getString(R.string.github_link), null, v -> open(GITHUB_URL));
		note(pSystem, getString(R.string.credits_body));
	}

	/** Offer the update by name once the feed tells us there is a newer build. */
	private void paintUpdateRow() {
		if (updateRow == null || updateTitle == null) {
			return;
		}
		boolean has = !latestVersion.isEmpty();
		updateTitle.setText(has ? getString(R.string.install_update, latestVersion)
				: getString(R.string.check_updates));
		if (updateSub != null) {
			updateSub.setText(has
					? getString(R.string.update_available, latestVersion)
					: getString(R.string.update_check_hint));
		}
	}

	private void service(String action) {
		toast(getString(R.string.working));
		new Thread(() -> {
			try {
				final JSONObject st = Api.service(action);
				ui.post(() -> {
					toast(getString(R.string.done));
					status = st;
					latestVersion = "";
					render(st);
				});
			} catch (Exception e) {
				ui.post(() -> {
					toast(getString(R.string.failed, String.valueOf(e.getMessage())));
					refresh();
				});
			}
		}).start();
	}

	/**
	 * The update row: find out what is available, then act on it.
	 *
	 * <p>Previously this ran the updater and then said "Done" whatever happened.
	 * A failed download produced the same "Done" as a successful install, and
	 * the user was told their daemon had been updated while it had not. The
	 * daemon's log showed "Could not obtain the new binary; keeping 1.104.1" at
	 * the same moment the app claimed success.
	 *
	 * <p>So: ask the feed first. If the running version is already the latest,
	 * say so and stop -- restarting a daemon and downloading a binary to change
	 * nothing is not an update. If there is something newer, install it and
	 * report the version it moved to, or the reason it did not move.
	 */
	private void updateNow() {
		toast(getString(R.string.checking));
		new Thread(() -> {
			try {
				final JSONObject info = Api.updateCheckInfo();
				final String cur = info.optString("current", "");
				final String latest = info.optString("latest", "");
				final boolean available = info.optBoolean("available", false)
						&& !latest.isEmpty() && !latest.equals(cur);

				if (!available) {
					// Say which of the two it is. A reachable feed with no
					// newer build and an unreachable feed both come back
					// without "available", and claiming "up to date" on a
					// failed check would be inventing knowledge.
					final boolean knowLatest = !latest.isEmpty() && !latest.equals(cur);
					ui.post(() -> {
						latestVersion = "";
						paintUpdateRow();
						toast(knowLatest
								? getString(R.string.up_to_date, cur.isEmpty() ? latest : cur)
								: getString(R.string.update_unchanged,
										cur.isEmpty() ? "?" : cur));
						refresh();
					});
					return;
				}

				ui.post(() -> toast(getString(R.string.installing_update, latest)));
				final JSONObject res = Api.update();
				final boolean moved = res.optBoolean("updated", false);
				final String after = res.optString("after", cur);
				ui.post(() -> {
					latestVersion = "";
					toast(moved
							? getString(R.string.update_done, cur, after)
							: getString(R.string.update_unchanged, after));
					refresh();
				});
			} catch (Exception e) {
				ui.post(() -> toast(getString(R.string.failed, String.valueOf(e.getMessage()))));
			}
		}).start();
	}

	private void rollback() {
		Sheet.confirm(this, getString(R.string.rollback),
				getString(R.string.rollback_confirm),
				getString(R.string.rollback), true, () -> {
					new Thread(() -> {
						try {
							ui.post(() -> toast(getString(R.string.done)));
							Api.rollback();
							ui.post(() -> refresh());
						} catch (Exception e) {
							ui.post(() -> toast(getString(R.string.failed,
									String.valueOf(e.getMessage()))));
						}
					}).start();
				});
	}

	/** Log viewer: monospace, colour-coded by severity, filterable, copyable. */
	private void showLogs() {
		new Thread(() -> {
			try {
				final String log = Api.logs(400);
				ui.post(() -> logDialog(log));
			} catch (Exception e) {
				ui.post(() -> toast(getString(R.string.failed, String.valueOf(e.getMessage()))));
			}
		}).start();
	}

	private void logDialog(String log) {
		final String[] lines = log.split("\n");
		final boolean[] problemsOnly = {false};

		final Sheet sheet = Sheet.card(this).title(getString(R.string.logs));

		final TextView filter = chip(getString(R.string.filter_problems));
		TextView reload = chip(getString(R.string.refresh));
		LinearLayout chips = new LinearLayout(this);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setGravity(Gravity.CENTER_VERTICAL);
		chips.addView(filter);
		View gap = new View(this);
		chips.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
		chips.addView(reload);
		sheet.add(chips);

		final ScrollView scroll = new ScrollView(this);
		scroll.setHorizontalScrollBarEnabled(false);
		scroll.setVerticalScrollBarEnabled(false);
		final TextView body = new TextView(this);
		body.setTextSize(11);
		body.setTextColor(getColor(R.color.text2));
		body.setTypeface(android.graphics.Typeface.MONOSPACE);
		body.setLineSpacing(dp(3), 1f);
		body.setPadding(dp(14), dp(12), dp(14), dp(12));
		body.setBackgroundResource(R.drawable.field);
		scroll.addView(body, new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		sheet.add(scroll, 380);

		Runnable paint = () -> {
			body.setText(paintLog(lines, problemsOnly[0]));
			int shown = problemsOnly[0] ? countProblems(lines) : lines.length;
			sheet.subtitle(getString(R.string.log_lines, shown));
			filter.setText(problemsOnly[0] ? R.string.filter_all : R.string.filter_problems);
			scroll.post(() -> scroll.fullScroll(View.FOCUS_UP));
		};

		filter.setOnClickListener(v -> {
			problemsOnly[0] = !problemsOnly[0];
			paint.run();
		});
		reload.setOnClickListener(v -> {
			sheet.dismiss();
			showLogs();
		});
		sheet.primary(getString(R.string.copy), () -> {
			android.content.ClipboardManager cm =
					(android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
			if (cm != null) {
				cm.setPrimaryClip(android.content.ClipData.newPlainText("tailscale",
						String.join("\n", lines)));
				toast(getString(R.string.log_copied));
			}
		});
		sheet.secondary(getString(R.string.close));

		paint.run();
		sheet.show();
	}

	/** A small pill button used inside a dialog body. */
	private TextView chip(CharSequence label) {
		TextView b = new TextView(this);
		b.setText(label);
		b.setTextColor(getColor(R.color.accent));
		b.setTextSize(12.5f);
		b.setTypeface(null, android.graphics.Typeface.BOLD);
		b.setIncludeFontPadding(false);
		b.setGravity(Gravity.CENTER);
		b.setPadding(dp(14), dp(8), dp(14), dp(8));
		android.graphics.drawable.GradientDrawable g =
				new android.graphics.drawable.GradientDrawable();
		g.setCornerRadius(dp(999));
		g.setColor(getColor(R.color.accentDim));
		b.setBackground(g);
		b.setClickable(true);
		return b;
	}

	/** Tailscale logs as "YYYY/MM/DD HH:MM:SS [v1] thing: detail". */
	private CharSequence paintLog(String[] lines, boolean problemsOnly) {
		android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
		boolean any = false;
		for (String line : lines) {
			if (line.trim().isEmpty()) {
				continue;
			}
			boolean problem = isProblem(line);
			if (problemsOnly && !problem) {
				continue;
			}
			any = true;

			int split = 19;
			if (line.length() > split && isTimestamp(line.substring(0, split))) {
				sb.append(line, 0, split);
				sb.setSpan(new android.text.style.ForegroundColorSpan(
						getColor(R.color.hint)), sb.length() - split, sb.length(),
						android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				sb.append(line, split, line.length());
			} else {
				sb.append(line);
			}
			int start = sb.length() - line.length();
			sb.setSpan(new android.text.style.ForegroundColorSpan(
					getColor(problem ? R.color.err : R.color.text2)), start, sb.length(),
					android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			sb.append('\n');
		}
		if (!any) {
			sb.append(getString(R.string.no_logs));
		}
		return sb;
	}

	private int countProblems(String[] lines) {
		int n = 0;
		for (String l : lines) {
			if (!l.trim().isEmpty() && isProblem(l)) {
				n++;
			}
		}
		return n;
	}

	private static boolean isProblem(String line) {
		String l = line.toLowerCase(java.util.Locale.ROOT);
		return l.contains("error") || l.contains("fatal") || l.contains("panic")
				|| l.contains("warn") || l.contains("failed") || l.contains("denied");
	}

	private static boolean isTimestamp(String s) {
		if (s.length() < 19) {
			return false;
		}
		return Character.isDigit(s.charAt(0)) && s.charAt(4) == '/' && s.charAt(7) == '/'
				&& s.charAt(10) == ' ' && s.charAt(13) == ':' && s.charAt(16) == ':';
	}

	/**
	 * Sign in hands off to the browser: Tailscale has no headless auth flow, and
	 * this is the only way to authorise a device.
	 */
	/** Pull anything Taildrop has received into Downloads/Tailscale. */
	private void doLogin() {
		toast(getString(R.string.working));
		new Thread(() -> {
			try {
				final JSONObject r = Api.login();
				ui.post(() -> {
					if (!r.optBoolean("ok", true)) {
						// Silent failure is worse than none: say what went wrong.
						String why = r.optString("raw", "");
						Sheet.info(this, getString(R.string.sign_in),
								why.trim().isEmpty()
										? getString(R.string.no_login_url) : why.trim());
						return;
					}
					if (r.optBoolean("alreadyLoggedIn", false)) {
						toast(getString(R.string.already_signed_in));
						refresh();
						return;
					}
					String url = r.optString("url", "");
					if (url.isEmpty()) {
						String raw = r.optString("raw", "").trim();
						Sheet.info(this, getString(R.string.sign_in),
								raw.isEmpty()
										? getString(R.string.no_login_url)
										: raw);
						return;
					}
					open(url);
					toast(getString(R.string.sign_in_opened));
				});
			} catch (Exception e) {
				ui.post(() -> toast(getString(R.string.failed, String.valueOf(e.getMessage()))));
			}
		}).start();
	}

	/**
	 * Turning wireless debugging on is a risk, so it is asked about first.
	 *
	 * <p>The switch has already moved by the time this runs, so if the
	 * answer is no the screen is drawn again to put it back.
	 */
	private void confirmWirelessAdb(boolean on) {
		if (!on) {
			setWirelessAdb(false);
			return;
		}
		Sheet s = Sheet.card(this)
				.title(getString(R.string.wireless_adb_switch))
				.message(getString(R.string.wireless_adb_confirm));
		s.primary(getString(R.string.wireless_adb_turn_on),
				() -> setWirelessAdb(true));
		s.secondary(getString(android.R.string.cancel), () -> {
			if (status != null) {
				render(status);
			}
		});
		s.show();
	}

	private void setWirelessAdb(boolean on) {
		new Thread(() -> {
			try {
				Api.adbWireless(on);
				ui.post(() -> {
					toast(getString(on ? R.string.wireless_adb_on_toast
							: R.string.wireless_adb_off_toast));
					refresh();
				});
			} catch (Exception e) {
				ui.post(() -> {
					toast(getString(R.string.failed, String.valueOf(e.getMessage())));
					if (status != null) {
						render(status);
					}
				});
			}
		}).start();
	}

	/** The phone's Tailscale address, the IPv4 one. */
	private String tsIpv4(JSONObject st) {
		JSONObject self = st == null ? null : st.optJSONObject("self");
		if (self == null) {
			return "";
		}
		JSONArray ips = self.optJSONArray("tailscaleIPs");
		if (ips == null) {
			return "";
		}
		for (int i = 0; i < ips.length(); i++) {
			String ip = ips.optString(i, "");
			if (ip.indexOf('.') > 0) {
				return ip;
			}
		}
		return "";
	}

	private void confirmSignOut() {
		Sheet.confirm(this, getString(R.string.sign_out),
				getString(R.string.sign_out_confirm),
				getString(R.string.sign_out), true, () -> {
					new Thread(() -> {
						try {
							Api.logout();
							ui.post(() -> {
								toast(getString(R.string.signed_out));
								refresh();
							});
						} catch (Exception e) {
							ui.post(() -> toast(getString(R.string.failed,
									String.valueOf(e.getMessage()))));
						}
					}).start();
				});
	}

	/**
	 * Superuser permission lives in the root manager, not in Android's app
	 * settings, so send the user where the switch actually is.
	 */
	private void openRootManager() {
		String[] managers = {
				"com.resukisu.resukisu",     // ReSuSFS
				"me.weishu.kernelsu",        // KernelSU
				"com.topjohnwu.magisk",      // Magisk
				"me.bmax.apatch",            // APatch
				"eu.chainfire.supersu",      // SuperSU
				"com.noshufou.android.su",   // SU
				"com.koushikdutta.supertool",
		};
		for (String pkg : managers) {
			try {
				Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
				if (i != null) {
					i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					startActivity(i);
					return;
				}
			} catch (Exception ignored) {
				// try the next one
			}
		}
		// No known manager: fall back rather than dead-ending the user.
		openAppSettings();
	}

	private void openAppSettings() {
		try {
			startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
					Uri.fromParts("package", getPackageName(), null)));
		} catch (Exception ignored) {
			// nothing to open
		}
	}

	private void open(String url) {
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
		} catch (Exception e) {
			toast(getString(R.string.no_browser));
		}
	}

	// --------------------------------------------------------------- widgets

	/**
	 * A grouped list surface: a heading, then a rounded box of contiguous rows
	 * with hairlines between them. This is the only shape on the page.
	 */
	private LinearLayout group(LinearLayout parent, String heading) {
		if (heading != null) {
			TextView t = new TextView(this);
			t.setText(heading);
			t.setTextColor(getColor(R.color.hint));
			t.setTextSize(11);
			t.setTypeface(null, android.graphics.Typeface.BOLD);
			t.setAllCaps(true);
			t.setLetterSpacing(0.1f);
			t.setIncludeFontPadding(false);
			t.setPadding(dp(4), dp(22), 0, dp(10));
			parent.addView(t);
		} else {
			LinearLayout.LayoutParams s = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			s.topMargin = dp(16);
			parent.addView(new View(this), s);
		}

		LinearLayout g = new LinearLayout(this);
		g.setOrientation(LinearLayout.VERTICAL);
		g.setBackgroundResource(R.drawable.group);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		parent.addView(g, lp);
		return g;
	}

	/** Hairline between rows in a group; never above the first one. */
	private void line(LinearLayout g) {
		if (g.getChildCount() == 0) {
			return;
		}
		View v = new View(this);
		v.setBackgroundResource(R.drawable.divider);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, 1);
		lp.leftMargin = dp(16);
		lp.rightMargin = dp(16);
		g.addView(v, lp);
	}

	/** A non-interactive row: label left, value right. */
	private TextView value(LinearLayout g, String label, String v) {
		line(g);
		LinearLayout row = row();
		TextView l = new TextView(this);
		l.setText(label);
		l.setTextColor(getColor(R.color.text));
		l.setTextSize(15);
		l.setSingleLine(true);
		row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		TextView val = new TextView(this);
		val.setText(v);
		val.setTextColor(getColor(R.color.text2));
		val.setTextSize(14);
		val.setMaxLines(2);
		val.setEllipsize(android.text.TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		vlp.leftMargin = dp(14);
		row.addView(val, vlp);
		g.addView(row);
		return val;
	}

	/** A value row that copies when tapped. */
	private void copyRow(LinearLayout g, String label, String v) {
		line(g);
		LinearLayout row = row();
		row.setBackgroundResource(R.drawable.row_press);
		row.setClickable(true);
		row.setOnClickListener(x -> copy(v));

		TextView l = new TextView(this);
		l.setText(label);
		l.setTextColor(getColor(R.color.text));
		l.setTextSize(15);
		l.setSingleLine(true);
		row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		TextView val = new TextView(this);
		val.setText(v);
		val.setTextColor(getColor(R.color.text2));
		val.setTextSize(14);
		val.setSingleLine(true);
		val.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		row.addView(val);

		ImageView cp = new ImageView(this);
		cp.setImageResource(R.drawable.ic_copy);
		cp.setColorFilter(getColor(R.color.hint));
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(15), dp(15));
		clp.leftMargin = dp(12);
		row.addView(cp, clp);
		g.addView(row);
	}

	/** A tappable row: title, optional sub-label, optional trailing hint. */
	private View action(LinearLayout g, String title, String sub, View.OnClickListener l) {
		line(g);
		LinearLayout row = row();
		row.setBackgroundResource(R.drawable.row_press);
		row.setClickable(true);

		LinearLayout col = col();
		TextView t = new TextView(this);
		t.setId(R.id.rowTitle);
		t.setText(title);
		t.setTextColor(getColor(R.color.text));
		t.setTextSize(15);
		t.setIncludeFontPadding(false);
		t.setSingleLine(true);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		col.addView(t);

		if (sub != null && !sub.isEmpty()) {
			TextView s = new TextView(this);
			s.setId(R.id.rowSub);
			s.setText(sub);
			s.setTextColor(getColor(R.color.text2));
			s.setTextSize(12.5f);
			s.setIncludeFontPadding(false);
			s.setPadding(0, dp(3), 0, 0);
			col.addView(s);
		}

		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		if (l != null) {
			ImageView chev = new ImageView(this);
			chev.setImageResource(R.drawable.ic_chevron);
			chev.setColorFilter(getColor(R.color.hint));
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(16), dp(16));
			clp.leftMargin = dp(10);
			row.addView(chev, clp);
			row.setOnClickListener(l);
		}
		g.addView(row);
		return row;
	}

	/** A row with a trailing switch. Returns it so a failed write can be undone. */
	private android.widget.Switch addSwitch(LinearLayout g, String title, String sub, boolean on,
			OnChecked l) {
		line(g);
		LinearLayout row = row();
		LinearLayout col = col();

		TextView t = new TextView(this);
		t.setId(R.id.rowTitle);
		t.setText(title);
		t.setTextColor(getColor(R.color.text));
		t.setTextSize(15);
		t.setIncludeFontPadding(false);
		col.addView(t);

		if (sub != null && !sub.isEmpty()) {
			TextView s = new TextView(this);
			s.setId(R.id.rowSub);
			s.setText(sub);
			s.setTextColor(getColor(R.color.text2));
			s.setTextSize(12.5f);
			s.setIncludeFontPadding(false);
			s.setPadding(0, dp(3), 0, 0);
			col.addView(s);
		}
		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		android.widget.Switch sw = toggle(on);
		row.addView(sw);

		// Set the value before wiring the listener so painting the screen can
		// never be mistaken for the user flipping it.
		sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
			((android.widget.Switch) b).setThumbTintList(android.content.res.ColorStateList.valueOf(
					getColor(checked ? R.color.text : R.color.hint)));
			if (l != null) {
				l.on(b);
			}
		});
		if (l != null) {
			// Tapping anywhere on the row flips it, like a system switch, so
			// the label is a target too and not just a caption.
			row.setClickable(true);
			row.setOnClickListener(v -> sw.performClick());
		}
		g.addView(row);
		return sw;
	}

	private void plain(LinearLayout g, String text) {
		TextView t = new TextView(this);
		t.setText(text);
		t.setTextColor(getColor(R.color.text2));
		t.setTextSize(14);
		t.setPadding(dp(16), dp(16), dp(16), dp(16));
		g.addView(t);
	}

	private LinearLayout row() {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(16), dp(15), dp(16), dp(15));
		return row;
	}

	private LinearLayout col() {
		LinearLayout c = new LinearLayout(this);
		c.setOrientation(LinearLayout.VERTICAL);
		return c;
	}

	private interface OnChecked {
		void on(CompoundButton b);
	}

	private void note(LinearLayout parent, String text) {
		TextView t = new TextView(this);
		t.setText(text);
		t.setTextColor(getColor(R.color.hint));
		t.setTextSize(12.5f);
		t.setLineSpacing(dp(4), 1f);
		t.setPadding(dp(4), dp(14), dp(4), 0);
		parent.addView(t);
	}

	private void toast(String msg) {
		Snack.show(this, msg);
	}

	private int dp(int v) {
		return (int) (v * getResources().getDisplayMetrics().density);
	}
}