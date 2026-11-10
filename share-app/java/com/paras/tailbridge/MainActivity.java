// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
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
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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
	/** The same peers, parsed once per status document. */
	private List<Api.Peer> peerList = new ArrayList<>();
	/** The subset that is currently reachable. Sending is allowed to these only. */
	private final Set<String> onlinePeers = new LinkedHashSet<>();
	/** peers ticked as send targets. */
	private final Set<String> chosen = new LinkedHashSet<>();
	/** pref key -> the switch showing it, so a failed write can be undone. */
	private final Map<String, Ui.Toggle> switches = new LinkedHashMap<>();

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
	private final View[] navs = new View[3];
	private int currentTab = 0;

	private TextView sendBtn;
	/** row that offers the software update once a newer build is known. */
	private View updateRow;
	private TextView updateTitle;
	private TextView updateSub;

	private JSONObject status;
	private boolean polling;
	private static final long POLL_MS = 15000;
	/** A refresh is already running. A second one would only race it. */
	private boolean refreshing;
	/** Something asked for a refresh while one was running. */
	private boolean refreshAgain;
	/** Which tab bodies are stale. Only the visible one is rebuilt. */
	private final boolean[] dirty = { true, true, true };
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

	private ImageView pullSpinner;
	private int receivedCount;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// Resolve Material You before the layout is inflated: the theme
		// resource has to be in place before the first view exists.
		Theme.apply(this);
		setContentView(R.layout.activity_main);

		statusView = findViewById(R.id.status);
		versionView = findViewById(R.id.version);
		dotView = findViewById(R.id.dot);
		dotHaloView = findViewById(R.id.dotHalo);
		pSend = findViewById(R.id.pSend);
		pSettings = findViewById(R.id.pSettings);
		pSystem = findViewById(R.id.pSystem);
		styleChrome();

		navs[0] = findViewById(R.id.navSend);
		navs[1] = findViewById(R.id.navSettings);
		navs[2] = findViewById(R.id.navSystem);
		for (int i = 0; i < navs.length; i++) {
			final int n = i;
			navs[i].setOnClickListener(v -> tab(n));
		}

		final View refreshBtn = findViewById(R.id.refresh);
		refreshBtn.setOnClickListener(v -> manualRefresh());

		applyWindowInsets();

		pullSpinner = findViewById(R.id.pullSpinner);
		final Runnable pullRefresh = () -> {
			manualRefresh();
			if (refreshBtn != null) {
				refreshBtn.animate().rotationBy(360f).setDuration(520).start();
			}
		};
		attachPullToRefresh(findViewById(R.id.scrollSend), pullRefresh);
		attachPullToRefresh(findViewById(R.id.scrollSettings), pullRefresh);
		attachPullToRefresh(findViewById(R.id.scrollSystem), pullRefresh);

		defaultTargets = getPreferences(0).getString("targets", "");
		android.content.Intent launch = getIntent();
		if (launch != null && launch.getAction() != null
				&& (android.content.Intent.ACTION_SEND.equals(launch.getAction())
					|| android.content.Intent.ACTION_SEND_MULTIPLE.equals(launch.getAction()))) {
			shareLaunch = true;
		}
		// A recreated activity (process death, not rotation: the manifest
		// handles those) already restores its staged files in
		// onRestoreInstanceState. Capturing the launch intent again would
		// append the same share a second time.
		if (savedInstanceState == null) {
			captureShare(launch);
		}
		tab(savedInstanceState == null ? 0 : savedInstanceState.getInt("tab", 0));
		checkRoot();
		askNotificationPermission();
		registerNetworkWatcher();
		cleanCache();
	}

	/**
	 * Drop staged copies from previous runs.
	 *
	 * <p>Every send copies the picked files into the app cache before the
	 * daemon reads them. Nothing deleted them afterwards, so a few large
	 * transfers quietly filled the cache and Android eventually cleared it
	 * at a bad moment. A send cleans up after itself; this catches the one
	 * that was interrupted.
	 */
	private void cleanCache() {
		new Thread(() -> {
			// Anything staged in the last minute may belong to the share
			// intent that launched this activity, so leave it alone.
			long before = System.currentTimeMillis() - 60_000L;
			java.io.File send = new java.io.File(getCacheDir(), "send");
			java.io.File share = new java.io.File(getCacheDir(), "share");
			wipe(send, before);
			wipe(share, before);
		}).start();
	}

	private static void wipe(java.io.File dir, long before) {
		java.io.File[] files = dir.listFiles();
		if (files == null) {
			return;
		}
		for (java.io.File f : files) {
			if (f.isDirectory()) {
				wipe(f, before);
			}
			if (f.lastModified() < before) {
				//noinspection ResultOfMethodCallIgnored
				f.delete();
			}
		}
	}

	private static final int REQ_NOTIFY = 4242;

	private void askNotificationPermission() {
		if (android.os.Build.VERSION.SDK_INT < 33) {
			return;
		}
		if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
				== android.content.pm.PackageManager.PERMISSION_GRANTED) {
			return;
		}
		try {
			requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
					REQ_NOTIFY);
		} catch (Exception ignored) {
		}
	}

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
		setIntent(intent);
		if (intent != null && intent.getAction() != null
				&& (android.content.Intent.ACTION_SEND.equals(intent.getAction())
					|| android.content.Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) {
			shareLaunch = true;
			pickerShown = false;
		}
		captureShare(intent);
		tab(0);
	}

	@Override
	protected void onSaveInstanceState(android.os.Bundle out) {
		super.onSaveInstanceState(out);
		ArrayList<String> staged = new ArrayList<>();
		for (Uri u : pendingUris) {
			staged.add(u.toString());
		}
		out.putStringArrayList("pending", staged);
		out.putStringArrayList("pick", new ArrayList<>(lastPick));
		out.putInt("tab", currentTab);
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
				}
			}
		}
		ArrayList<String> pick = in.getStringArrayList("pick");
		if (pick != null && !pick.isEmpty()) {
			lastPick.clear();
			lastPick.addAll(pick);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		refresh();
		startPolling();
	}

	@Override
	protected void onPause() {
		super.onPause();
		stopPolling();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (networkCallback != null) {
			ConnectivityManager cm = getSystemService(ConnectivityManager.class);
			cm.unregisterNetworkCallback(networkCallback);
			networkCallback = null;
		}
		ui.removeCallbacksAndMessages(null);
	}

	private void captureShare(android.content.Intent in) {
		if (in == null || in.getAction() == null) {
			return;
		}
		String a = in.getAction();
		if (android.content.Intent.ACTION_SEND.equals(a)) {
			android.net.Uri u = in.getParcelableExtra(android.content.Intent.EXTRA_STREAM);
			if (u == null) {
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
		}
	}

	private void tab(int n) {
		int prev = currentTab;
		currentTab = n;
		ScrollView[] scrolls = {
				findViewById(R.id.scrollSend),
				findViewById(R.id.scrollSettings),
				findViewById(R.id.scrollSystem),
		};
		for (int i = 0; i < scrolls.length; i++) {
			boolean on = i == n;
			scrolls[i].setVisibility(on ? View.VISIBLE : View.GONE);
			int iconColor = on ? Theme.onSecondaryContainer : Theme.text2;
			int labelColor = on ? Theme.text : Theme.text2;
			ImageView icon = (ImageView) navs[i].findViewById(R.id.navIcon);
			if (icon != null) {
				icon.setColorFilter(iconColor);
			}
			TextView label = (TextView) navs[i].findViewById(R.id.navLabel);
			if (label != null) {
				label.setTextColor(labelColor);
				label.setTypeface(null, on
						? android.graphics.Typeface.BOLD
						: android.graphics.Typeface.NORMAL);
			}
			View pill = navs[i].findViewById(R.id.navPill);
			if (pill != null) {
				pill.setVisibility(View.VISIBLE);
				Ui.indicator(pill, on);
			}
		}
		if (prev != n) {
			// One short fade up. Switching tabs used to be a hard cut, which
			// on a dark page reads as a flicker rather than a move.
			ScrollView now = scrolls[n];
			now.setAlpha(0f);
			now.setTranslationY(dp(10));
			now.animate().alpha(1f).translationY(0f)
					.setDuration(220).setInterpolator(Ui.EMPHASIZED).start();
		}
		buildVisible();
	}

	/**
	 * The manifest handles this configuration itself so a rotation does not
	 * cost a full restart, but the theme is one of the things that changed.
	 * When the palette follows the system, the activity has to be rebuilt.
	 */
	@Override
	public void onConfigurationChanged(Configuration cfg) {
		super.onConfigurationChanged(cfg);
		// uiMode is handled here so a rotation does not restart the app, but
		// that also means the system flipping light/dark does not restart it.
		// Rebuild only when the resolved darkness actually changed.
		if (Theme.mode(this) == Theme.MODE_SYSTEM
				&& Theme.darkNow(this) != Theme.isDark) {
			recreate();
		}
	}

	/**
	 * Paint the parts of the layout that were inflated from XML.
	 *
	 * <p>The layout is static XML and its colours are first-frame fallbacks;
	 * every surface with a generated colour is set here, once, before the
	 * screens are built. Anything built in code reads {@link Theme} directly.
	 */
	private void styleChrome() {
		View root = findViewById(R.id.root);
		if (root != null) {
			root.setBackgroundColor(Theme.surface);
		}
		if (statusView != null) {
			statusView.setTextColor(Theme.text);
		}
		if (versionView != null) {
			versionView.setTextColor(Theme.text2);
		}

		View refresh = findViewById(R.id.refresh);
		if (refresh instanceof ImageView) {
			((ImageView) refresh).setColorFilter(Theme.text2);
			refresh.setBackground(Ui.ripple(this,
					new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
					Theme.withAlpha(Theme.onSurface, 0x1F), null));
		}
		View pull = findViewById(R.id.pullSpinner);
		if (pull instanceof ImageView) {
			((ImageView) pull).setColorFilter(Theme.accent);
		}

		View navBar = findViewById(R.id.navBar);
		if (navBar != null) {
			navBar.setBackground(Ui.navBg(this));
		}
		int[] navIds = {R.id.navSend, R.id.navSettings, R.id.navSystem};
		for (int id : navIds) {
			View nav = findViewById(id);
			if (nav == null) {
				continue;
			}
			nav.setBackground(Ui.press(this, 20f));
			View pill = nav.findViewById(R.id.navPill);
			if (pill != null) {
				pill.setBackground(Ui.pill(this));
				pill.setVisibility(View.VISIBLE);
				pill.setAlpha(0f);
				View holder = (View) pill.getParent();
				if (holder != null) {
					holder.setBackgroundColor(android.graphics.Color.TRANSPARENT);
				}
			}
		}
	}


	private void openTaildropFolder() {
		try {
			Intent i = new Intent(Intent.ACTION_VIEW)
					.setDataAndType(TailscaleDocuments.rootUri(),
							android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
							| Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(i);
		} catch (Exception e) {
			toast(getString(R.string.taildrop_folder_sub));
		}
	}

	private void attachPullToRefresh(android.widget.ScrollView sv, Runnable onPull) {
		final int touchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
		final int trigger = dp(64);
		final int travel = dp(40);
		final float[] downY = {0f};
		final boolean[] tracking = {false};
		final boolean[] armed = {false};
		final ImageView spin = pullSpinner;

		sv.setOnTouchListener((v, ev) -> {
			switch (ev.getActionMasked()) {
				case android.view.MotionEvent.ACTION_DOWN:
					downY[0] = ev.getY();
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
						armed[0] = true;
						pull = trigger + (raw - trigger) * 0.18f;
						if (!wasArmed) {
							v.performHapticFeedback(
									android.view.HapticFeedbackConstants.CLOCK_TICK);
						}
					}
					v.setTranslationY(pull);
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

	private void render(JSONObject st) {
		((ScrollView) findViewById(R.id.scrollSend)).setFillViewport(false);
		pSend.setGravity(Gravity.NO_GRAVITY);
		findViewById(R.id.navBar).setVisibility(View.VISIBLE);
		applyHeader(st);

		peerList = Api.peersFrom(st);
		peers.clear();
		onlinePeers.clear();
		for (Api.Peer p : peerList) {
			peers.put(p.name, p.ip);
			if (p.online) {
				onlinePeers.add(p.name);
			}
		}
		chosen.retainAll(onlinePeers);

		dirty[0] = dirty[1] = dirty[2] = true;
		buildVisible();
	}

	/** Re-index the peer list from a fresh status document. */
	private void indexPeers(JSONObject st) {
		peerList = Api.peersFrom(st);
		peers.clear();
		onlinePeers.clear();
		for (Api.Peer p : peerList) {
			peers.put(p.name, p.ip);
			if (p.online) {
				onlinePeers.add(p.name);
			}
		}
		chosen.retainAll(onlinePeers);
	}

	/** Rebuild only the tab on screen, and only when it is stale. */
	private void buildVisible() {
		if (status == null) {
			return;
		}
		switch (currentTab) {
			case 0:
				if (dirty[0]) {
					buildSend();
					dirty[0] = false;
				}
				break;
			case 1:
				if (dirty[1]) {
					buildSettings(status);
					dirty[1] = false;
				}
				break;
			case 2:
				if (dirty[2]) {
					buildSystem(status);
					dirty[2] = false;
				}
				break;
			default:
				break;
		}
	}


	private void refresh() {
		if (refreshing) {
			refreshAgain = true;
			return;
		}
		refreshing = true;
		new Thread(() -> {
			JSONObject st = null;
			String err = null;
			try {
				st = Api.status();
			} catch (Api.ApiBroken e) {
				err = "module: " + e.getMessage();
			} catch (Api.Slow e) {
				err = "timeout: " + e.getMessage();
			} catch (Root.Denied e) {
				err = getString(R.string.need_root_short);
			} catch (Exception e) {
				err = "module: " + e.getMessage();
			}

			final JSONObject fSt = st;
			final String fErr = err;
			ui.post(() -> {
				refreshing = false;
				status = fSt;
				adbWireless = Api.adbWirelessOn(fSt);
				hidePullSpinner();
				if (fSt == null) {
					renderOffline(fErr);
				} else {
					render(fSt);
				}
				if (refreshAgain) {
					refreshAgain = false;
					refresh();
				}
			});

			if (st == null) {
				return;
			}
			final JSONObject stFinal = st;
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
				}
			}).start();
		}).start();
	}

	private static final long UPDATE_CHECK_MIN_MS = 3600_000L;
	private long lastUpdateCheck;

	private void applyHeader(JSONObject st) {
		String backend = st.optString("backend", "?");
		JSONObject self = st.optJSONObject("self");
		String host = self == null ? "" : self.optString("host", "");
		boolean up = daemonUp(st);

		if (signedIn(st)) {
			statusView.setText(getString(R.string.connected));
		} else if ("NeedsLogin".equals(backend) || "NeedsMachineAuth".equals(backend)) {
			statusView.setText(getString(R.string.not_signed_in));
		} else if (!up) {
			statusView.setText(getString(R.string.stopped));
		} else {
			statusView.setText(getString(R.string.connecting));
		}

		if (supervisorSub != null) {
			supervisorSub.setText(supervisorUp(st)
					? getString(R.string.supervisor_running)
					: getString(R.string.supervisor_stopped));
		}

		boolean showHost = signedIn(st) && !host.isEmpty();
		versionView.setText((showHost ? host + "  ·  " : "") + "tailscaled "
				+ st.optString("version", "?"));

		boolean controlPlane = self != null && self.optBoolean("online", false);
		if (!up) {
			setStatusColor(Theme.err);
			versionView.setText(getString(R.string.state_daemon_stopped) + "  ·  tailscaled "
					+ st.optString("version", "?"));
		} else if (!signedIn(st)) {
			setStatusColor(Theme.warn);
		} else if (!controlPlane) {
			setStatusColor(Theme.warn);
			versionView.setText(getString(R.string.state_no_internet) + "  ·  tailscaled "
					+ st.optString("version", "?"));
		} else {
			setStatusColor(Theme.ok);
		}
	}

	private void setStatusColor(int c) {
		if (dotView != null) {
			dotView.getBackground().mutate().setTint(c);
		}
		if (dotHaloView != null) {
			dotHaloView.getBackground().mutate().setTint(c);
			dotHaloView.setAlpha(38);
		}
	}


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

	private final Runnable poll = new Runnable() {
		@Override
		public void run() {
			if (polling) {
				ui.postDelayed(this, POLL_MS);
				return;
			}
			polling = true;
			new Thread(() -> {
				JSONObject st = null;
				try {
					st = Api.status();
				} catch (Exception ignored) {
				}
				final JSONObject fSt = st;
				final String sig = bodySignature(st);
				ui.post(() -> {
					polling = false;
					if (fSt != null) {
						applyHeader(fSt);
						patchNames(fSt);
						if (!sig.equals(lastBodySignature)) {
							lastBodySignature = sig;
							status = fSt;
							indexPeers(fSt);
							dirty[0] = dirty[1] = dirty[2] = true;
							buildVisible();
						}
					}
				});
			}).start();
			ui.postDelayed(this, POLL_MS);
		}
	};

	private String lastBodySignature = "\u0000none";

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

	private void rebuildVisible() {
		dirty[currentTab] = true;
		buildVisible();
	}

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

	private void renderOffline(String err) {
		// Three different failures, three different screens. A command that
		// timed out is neither "no root" nor "module broken": root works and
		// the module is installed, the daemon was simply too slow to answer.
		// Saying "reflash the module" for that sends people to fix something
		// that is not broken.
		boolean slow = err != null && err.startsWith("timeout:");
		String detail = slow ? err.substring("timeout:".length()).trim() : err;
		boolean noRoot = !slow && !(detail != null && detail.startsWith("module"));

		statusView.setText(getString(slow ? R.string.daemon_slow_short
				: noRoot ? R.string.need_root_short : R.string.module_broken_short));
		versionView.setText(slow ? getString(R.string.daemon_slow_hint)
				: noRoot ? getString(R.string.grant_root)
				: getString(R.string.module_broken_hint));
		setStatusColor((slow || noRoot ? Theme.warn : Theme.err));

		pSend.removeAllViews();
		pSettings.removeAllViews();
		pSystem.removeAllViews();
		nameValues.clear();
		nameShown.clear();
		switches.clear();
		sendBtn = null;
		updateRow = null;
		updateTitle = null;
		updateSub = null;
		supervisorSub = null;

		findViewById(R.id.navBar).setVisibility(View.GONE);

		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setGravity(Gravity.CENTER_HORIZONTAL);

		ImageView mark = new ImageView(this);
		mark.setImageResource(noRoot ? R.drawable.ic_lock : R.drawable.ic_system);
		mark.setColorFilter(slow ? Theme.warn
				: noRoot ? Theme.text2 : Theme.err);
		mark.setContentDescription(getString(slow ? R.string.daemon_slow_short
				: noRoot ? R.string.grant_root : R.string.module_broken_short));
		LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(62), dp(62));
		llp.bottomMargin = dp(26);
		box.addView(mark, llp);

		TextView title = new TextView(this);
		title.setText(slow ? R.string.daemon_slow_title
				: noRoot ? R.string.grant_root_title : R.string.module_broken_title);
		title.setTextColor(Theme.text);
		title.setTextSize(26);
		title.setGravity(Gravity.CENTER);
		title.setLineSpacing(dp(4), 1f);
		title.setTypeface(null, android.graphics.Typeface.BOLD);
		box.addView(title);

		TextView body = new TextView(this);
		body.setText(slow ? R.string.daemon_slow_body
				: noRoot ? R.string.grant_root_body : R.string.module_broken_body);
		body.setTextColor(Theme.text2);
		body.setTextSize(15);
		body.setGravity(Gravity.CENTER);
		body.setLineSpacing(dp(6), 1f);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		blp.topMargin = dp(16);
		blp.leftMargin = dp(12);
		blp.rightMargin = dp(12);
		box.addView(body, blp);

		TextView open = Ui.button(this,
				getString(noRoot ? R.string.open_root_manager : R.string.retry), true);
		open.setOnClickListener(v -> {
			if (noRoot) {
				openRootManager();
				return;
			}
			// The module may have been reflashed while this screen was up.
			statusView.setText(R.string.loading);
			versionView.setText("");
			refresh();
		});
		LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
		olp.topMargin = dp(28);
		olp.leftMargin = dp(8);
		olp.rightMargin = dp(8);
		box.addView(open, olp);

		if (noRoot) {
			TextView back = new TextView(this);
			back.setText(R.string.grant_root_back);
			back.setTextColor(Theme.hint);
			back.setTextSize(13);
			back.setGravity(Gravity.CENTER);
			back.setPadding(dp(16), dp(16), dp(16), dp(0));
			back.setLineSpacing(dp(3), 1f);
			box.addView(back);
		}

		if (detail != null && !detail.isEmpty()
				&& !detail.equals(getString(R.string.need_root_short))) {
			TextView why = new TextView(this);
			why.setText(detail);
			why.setTextColor(Theme.hint);
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

	private static boolean daemonUp(JSONObject st) {
		return st != null && "running".equals(st.optString("daemon", ""));
	}

	private static boolean supervisorUp(JSONObject st) {
		return st != null && "running".equals(st.optString("supervisor", ""));
	}

	private static boolean signedIn(JSONObject st) {
		return st != null && "Running".equals(st.optString("backend", ""));
	}


	private void buildSend() {
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

		sendHero();

		if (!daemonUp(status)) {
			stoppedCard();
		} else if (!signedIn(status)) {
			signInCard();
		}

		if (!pendingUris.isEmpty()) {
			pendingCard();
		}

		LinearLayout t = group(pSend, getString(R.string.choose_devices));
		if (peerList.isEmpty()) {
			emptyPeers(t);
		} else {
			if (onlinePeers.size() > 1) {
				t.addView(selectTools());
			}
			for (Api.Peer p : peerList) {
				line(t);
				t.addView(peerRow(p));
			}
		}

		// A filled, full-width action. The selector is the signal that the
		// page has a primary job, so it gets a button and not a row.
		sendBtn = Ui.button(this, getString(R.string.send_files), false);
		sendBtn.setOnClickListener(v -> onSendPressed());
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
		slp.topMargin = dp(18);
		pSend.addView(sendBtn, slp);

		updateSendButton();
	}

	/** The page's opening panel: what this screen is for, in one breath. */
	private void sendHero() {
		LinearLayout hero = Ui.heroCard(this);
		hero.setPadding(dp(22), dp(22), dp(22), dp(20));
		pSend.addView(hero, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		ImageView mark = new ImageView(this);
		mark.setImageResource(R.drawable.ic_send);
		mark.setColorFilter(Theme.accent);
		mark.setContentDescription(getString(R.string.send_files));
		mark.setPadding(dp(11), dp(11), dp(11), dp(11));
		mark.setBackground(Ui.rounded(this, Theme.accentDim, 16f));
		LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(46), dp(46));
		mlp.bottomMargin = dp(16);
		hero.addView(mark, mlp);

		TextView title = Ui.label(this, getString(R.string.send_hero_title), 23f, Theme.text);
		title.setTypeface(null, android.graphics.Typeface.BOLD);
		title.setLetterSpacing(-0.02f);
		hero.addView(title);

		TextView body = Ui.label(this, getString(R.string.send_hero_body), 13.5f, Theme.text2);
		body.setLineSpacing(dp(4), 1f);
		body.setPadding(0, dp(8), 0, 0);
		hero.addView(body);

		LinearLayout stats = new LinearLayout(this);
		stats.setOrientation(LinearLayout.HORIZONTAL);
		stats.setGravity(Gravity.CENTER_VERTICAL);
		stats.setPadding(0, dp(16), 0, 0);
		boolean anyOnline = !onlinePeers.isEmpty();
		TextView online = statPill(getString(R.string.send_online, onlinePeers.size()),
				anyOnline);
		stats.addView(online);
		if (!chosen.isEmpty()) {
			TextView picked = statPill(getString(R.string.send_selected, chosen.size()), true);
			LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			plp.leftMargin = dp(8);
			stats.addView(picked, plp);
		}
		hero.addView(stats);
	}

	private TextView statPill(CharSequence label, boolean lit) {
		return toneChip(label,
				lit ? Theme.secondaryContainer : Theme.surfaceContainerHighest,
				lit ? Theme.onSecondaryContainer : Theme.text2);
	}

	private TextView toneChip(CharSequence label, int background, int foreground) {
		TextView t = Ui.label(this, label, 12f, foreground);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		t.setGravity(Gravity.CENTER);
		t.setPadding(dp(12), dp(7), dp(12), dp(7));
		t.setBackground(Ui.rounded(this, background, 999f));
		return t;
	}

	/** The System page's opening panel: who this device is, and whether it is up. */
	private void systemHero(JSONObject st) {
		LinearLayout hero = Ui.heroCard(this);
		hero.setPadding(dp(22), dp(22), dp(22), dp(20));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(6);
		pSystem.addView(hero, lp);

		JSONObject self = st.optJSONObject("self");
		String host = self == null ? "" : self.optString("host", "");
		String ip = tsIpv4(st);
		boolean up = daemonUp(st);

		TextView name = Ui.label(this,
				host.isEmpty() ? getString(R.string.this_device) : host, 22f, Theme.text);
		name.setTypeface(null, android.graphics.Typeface.BOLD);
		name.setLetterSpacing(-0.02f);
		name.setSingleLine(true);
		name.setEllipsize(android.text.TextUtils.TruncateAt.END);
		hero.addView(name);

		TextView addr = Ui.label(this,
				ip.isEmpty() ? getString(R.string.not_signed_in) : ip, 13f, Theme.text2);
		addr.setPadding(0, dp(5), 0, 0);
		hero.addView(addr);

		LinearLayout chips = new LinearLayout(this);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setGravity(Gravity.CENTER_VERTICAL);
		chips.setPadding(0, dp(15), 0, 0);

		chips.addView(toneChip(getString(up ? R.string.daemon_running : R.string.daemon_off),
				up ? Theme.successContainer : Theme.withAlpha(Theme.error, 0x24),
				up ? Theme.success : Theme.error));

		boolean in = signedIn(st);
		TextView account = toneChip(getString(in ? R.string.connected : R.string.not_signed_in),
				in ? Theme.secondaryContainer : Theme.surfaceContainerHighest,
				in ? Theme.onSecondaryContainer : Theme.text2);
		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		alp.leftMargin = dp(8);
		chips.addView(account, alp);

		if (supervisorUp(st)) {
			TextView sup = toneChip(getString(R.string.supervisor),
					Theme.surfaceContainerHighest, Theme.text2);
			LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			slp.leftMargin = dp(8);
			chips.addView(sup, slp);
		}
		hero.addView(chips);
	}

	/** The device list with nothing in it still has to explain itself. */
	private void emptyPeers(LinearLayout g) {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setGravity(Gravity.CENTER_HORIZONTAL);
		box.setPadding(dp(24), dp(30), dp(24), dp(30));

		ImageView mark = new ImageView(this);
		mark.setImageResource(R.drawable.ic_system);
		mark.setColorFilter(Theme.hint);
		box.addView(mark, new LinearLayout.LayoutParams(dp(34), dp(34)));

		TextView title = Ui.label(this, getString(R.string.no_peers_short), 16f, Theme.text);
		title.setTypeface(null, android.graphics.Typeface.BOLD);
		title.setGravity(Gravity.CENTER);
		title.setPadding(0, dp(12), 0, 0);
		box.addView(title);

		TextView sub = Ui.label(this, getString(R.string.no_peers_body), 13f, Theme.text2);
		sub.setGravity(Gravity.CENTER);
		sub.setLineSpacing(dp(4), 1f);
		sub.setPadding(dp(8), dp(6), dp(8), 0);
		box.addView(sub);

		g.addView(box);
	}

	/** Select all / none, for sending to more than one device. */
	private View selectTools() {
		LinearLayout tools = new LinearLayout(this);
		tools.setOrientation(LinearLayout.HORIZONTAL);
		tools.setGravity(Gravity.CENTER_VERTICAL);
		tools.setPadding(dp(16), dp(11), dp(16), dp(11));

		TextView all = chip(getString(R.string.select_all));
		all.setOnClickListener(v -> {
			chosen.addAll(onlinePeers);
			saveTargets();
			buildSend();
		});
		TextView none = chip(getString(R.string.select_none));
		none.setOnClickListener(v -> {
			chosen.clear();
			saveTargets();
			buildSend();
		});

		tools.addView(all);
		View gap = new View(this);
		tools.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
		tools.addView(none);
		return tools;
	}

	/** What is waiting to go: the files that arrived through the share sheet. */
	private void pendingCard() {
		LinearLayout card = Ui.card(this);
		card.setOrientation(LinearLayout.HORIZONTAL);
		card.setGravity(Gravity.CENTER_VERTICAL);
		card.setPadding(dp(14), dp(14), dp(12), dp(14));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(14);
		pSend.addView(card, lp);

		ImageView icon = new ImageView(this);
		icon.setImageResource(R.drawable.ic_send);
		icon.setColorFilter(Theme.accent);
		icon.setPadding(dp(9), dp(9), dp(9), dp(9));
		icon.setBackground(Ui.rounded(this, Theme.accentDim, 12f));
		LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(40), dp(40));
		ilp.rightMargin = dp(14);
		card.addView(icon, ilp);

		LinearLayout col = col();
		TextView t = Ui.label(this, getString(R.string.pending_ready, pendingUris.size()),
				15f, Theme.text);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		col.addView(t);
		TextView s = Ui.label(this, getString(R.string.pending_hint), 12.5f, Theme.text2);
		s.setPadding(0, dp(3), 0, 0);
		col.addView(s);
		card.addView(col, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		TextView clear = chip(getString(R.string.clear));
		clear.setOnClickListener(v -> {
			pendingUris.clear();
			buildSend();
		});
		card.addView(clear);
	}

	private boolean receivedChecked;

	private void stoppedCard() {
		LinearLayout card = Ui.heroCard(this);
		card.setPadding(dp(22), dp(22), dp(22), dp(20));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(14);
		lp.bottomMargin = dp(8);
		pSend.addView(card, lp);

		TextView t = Ui.label(this, getString(R.string.daemon_stopped_card), 20f, Theme.text);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		card.addView(t);

		TextView d = Ui.label(this, getString(R.string.daemon_stopped_card_body), 14f, Theme.text2);
		d.setLineSpacing(dp(4), 1f);
		d.setPadding(0, dp(8), 0, 0);
		card.addView(d);

		TextView go = Ui.button(this, getString(R.string.start_daemon), true);
		go.setOnClickListener(v -> service("start"));
		LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
		glp.topMargin = dp(20);
		card.addView(go, glp);
	}

	private void signInCard() {
		boolean up = daemonUp(status);
		LinearLayout card = Ui.heroCard(this);
		card.setPadding(dp(22), dp(22), dp(22), dp(20));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(14);
		lp.bottomMargin = dp(8);
		pSend.addView(card, lp);

		TextView t = Ui.label(this, getString(R.string.not_signed_in), 20f, Theme.text);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		card.addView(t);

		TextView d = Ui.label(this, getString(R.string.sign_in_card_body), 14f, Theme.text2);
		d.setLineSpacing(dp(4), 1f);
		d.setPadding(0, dp(8), 0, 0);
		card.addView(d);

		TextView go = Ui.button(this, getString(R.string.sign_in), true);
		go.setOnClickListener(v -> doLogin());
		LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
		glp.topMargin = dp(20);
		card.addView(go, glp);

		if (!up) {
			TextView hint = Ui.label(this, getString(R.string.sign_in_needs_daemon), 12f, Theme.hint);
			hint.setPadding(0, dp(14), 0, 0);
			card.addView(hint);
		}
	}

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

		row.addView(Ui.avatar(this, p.name, up));

		Ui.Toggle cb = Ui.toggle(this, up && chosen.contains(p.name));
		cb.setEnabled(up);
		cb.setAlpha(up ? 1f : 0.4f);

		LinearLayout col = col();
		col.setPadding(dp(12), 0, dp(6), 0);
		TextView n = Ui.label(this, p.name, 15f, (up ? Theme.text : Theme.hint));
		n.setSingleLine(true);
		col.addView(n);
		TextView meta = new TextView(this);
		String when = up ? "" : lastSeenLabel(p.lastSeen);
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
		meta.setTextColor(Theme.text2);
		meta.setTextSize(12);
		meta.setIncludeFontPadding(false);
		meta.setPadding(0, dp(3), 0, 0);
		col.addView(meta);
		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		slp.leftMargin = dp(4);
		row.addView(cb, slp);

		View send = iconButton(R.drawable.ic_send, up ? Theme.text : Theme.hint,
				R.string.send, v -> launchPicker(java.util.Collections.singletonList(p.ip)));
		send.setEnabled(up);
		send.setAlpha(up ? 1f : 0.35f);
		row.addView(send);

		OnToggle peer = () -> {
			if (!up) {
				toast(getString(R.string.peer_offline_toast));
				return;
			}
			boolean now = !chosen.contains(p.name);
			if (now) {
				chosen.add(p.name);
			} else {
				chosen.remove(p.name);
			}
			// Reflect the choice in place. Rebuilding the whole screen to
			// show a tick used to cost a root round-trip and reset the
			// scroll position for a switch that had already moved.
			cb.setChecked(now);
			saveTargets();
			updateSendButton();
		};
		row.setOnClickListener(v -> peer.run());
		row.setClickable(true);
		row.setBackground(Ui.press(this));
		cb.setOnCheckedChangeListener(on -> peer.run());
		return row;
	}

	private View iconButton(int drawable, int color, int label, View.OnClickListener l) {
		ImageView v = new ImageView(this);
		v.setImageResource(drawable);
		v.setColorFilter(color);
		v.setContentDescription(getString(label));
		v.setPadding(dp(12), dp(12), dp(12), dp(12));
		v.setClickable(true);
		v.setFocusable(true);
		v.setBackground(Ui.press(this, 999f));
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
		chosen.retainAll(onlinePeers);
		int n = chosen.size();
		boolean on = signedIn(status) && n > 0;
		sendBtn.setEnabled(on);
		sendBtn.setTextColor(on ? Theme.onPrimary : Theme.onSurfaceVariant);
		sendBtn.setBackground(Ui.primaryButton(this, on));
		if (n == 1) {
			sendBtn.setText(getString(R.string.send_to, chosen.iterator().next()));
		} else if (n > 1) {
			sendBtn.setText(getString(R.string.send_to_n, n));
		} else if (!signedIn(status)) {
			sendBtn.setText(getString(R.string.send_need_signin));
		} else if (!peers.isEmpty()) {
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

	private void dispatch(List<Uri> uris, List<String> to) {
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

				// The copies were only ever a hand-off between the picker and
				// the daemon. Leaving them behind filled the cache a little
				// more with every send.
				for (java.io.File f : files) {
					//noinspection ResultOfMethodCallIgnored
					f.delete();
				}
				// Text shared into the app is staged as a file:// in
				// cache/share. It is spent once the send is done.
				for (Uri u : uris) {
					if ("file".equals(u.getScheme()) && u.getPath() != null) {
						//noinspection ResultOfMethodCallIgnored
						new java.io.File(u.getPath()).delete();
					}
				}

				ui.post(() -> {
					if (fFailed == 0) {
						progress.success(getString(R.string.sent_summary,
								fOk, targets.size()));
					} else {
						progress.failure(getString(R.string.send_fail_title),
								getString(R.string.send_fail_sub, fOk, fTotal),
								p, null);
					}
					refresh();
				});
			} catch (Throwable t) {
				Log.w(TAG, "send run failed", t);
				ui.post(() -> progress.failure(
						getString(R.string.send_fail_title),
						String.valueOf(t.getMessage()), null, null));
			}
		}).start();
	}

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
		addToggle(c1, "accept-dns", getString(R.string.t_accept_dns),
				getString(R.string.t_accept_dns_sub), flags, online);
		addToggle(c1, "accept-routes", getString(R.string.t_accept_routes),
				getString(R.string.t_accept_routes_sub), flags, online);
		addToggle(c1, "shields-up", getString(R.string.t_shields_up),
				getString(R.string.t_shields_up_sub), flags, online);

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
		addToggle(c2, "run-ssh", getString(R.string.t_ssh),
				getString(R.string.t_ssh_sub), flags, online);

		LinearLayout c3 = group(pSettings, getString(R.string.this_device));
		JSONObject prefs = st.optJSONObject("prefs");
		LinearLayout hr = new LinearLayout(this);
		hr.setOrientation(LinearLayout.HORIZONTAL);
		hr.setGravity(Gravity.CENTER_VERTICAL);
		hr.setPadding(dp(16), dp(6), dp(16), dp(10));
		EditText in = new EditText(this);
		in.setHint(getString(R.string.hostname_hint));
		in.setText(prefs == null ? "" : prefs.optString("hostname", ""));
		in.setSingleLine(true);
		in.setTextColor(Theme.text);
		in.setHintTextColor(Theme.hint);
		in.setTextSize(15);
		in.setPadding(dp(14), 0, dp(14), 0);
		in.setBackground(Ui.fieldBg(this));
		hr.addView(in, new LinearLayout.LayoutParams(0, dp(50), 1f));

		TextView apply = Ui.tonal(this, getString(R.string.apply));
		apply.setEnabled(online);
		apply.setAlpha(online ? 1f : 0.4f);
		apply.setOnClickListener(v -> applyPref("hostname",
				in.getText().toString().trim(), getString(R.string.hostname)));
		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, dp(50));
		alp.leftMargin = dp(10);
		hr.addView(apply, alp);
		c3.addView(hr);
		line(c3);
		action(c3, getString(R.string.forget_targets),
				getString(R.string.forget_targets_sub), v -> {
					chosen.clear();
					saveTargets();
					toast(getString(R.string.forget_targets_done));
					buildSend();
				});

		// Appearance sits last on purpose: it is the group someone goes
		// looking for once, not the one they came to this tab for.
		LinearLayout ap = group(pSettings, getString(R.string.appearance));
		action(ap, getString(R.string.theme_mode), themeModeLabel(), v -> showThemePicker());
		Ui.Toggle dyn = addSwitch(ap, getString(R.string.dynamic_color),
				android.os.Build.VERSION.SDK_INT >= 31
						? getString(R.string.dynamic_color_sub)
						: getString(R.string.dynamic_color_unavailable),
				Theme.dynamic(this), on -> {
					Theme.setDynamic(this, on);
					recreate();
				});
		boolean canDynamic = android.os.Build.VERSION.SDK_INT >= 31;
		dyn.setEnabled(canDynamic);
		dyn.setAlpha(canDynamic ? 1f : 0.4f);

		boolean dark = Theme.darkNow(this);
		Ui.Toggle black = addSwitch(ap, getString(R.string.amoled),
				dark ? getString(R.string.amoled_sub) : getString(R.string.amoled_needs_dark),
				dark && Theme.amoled(this), on -> {
					Theme.setAmoled(this, on);
					recreate();
				});
		black.setEnabled(dark);
		black.setAlpha(dark ? 1f : 0.4f);

		if (!online) {
			note(pSettings, getString(R.string.daemon_stopped_note));
		}
		note(pSettings, getString(R.string.footnote));
	}

	private String themeModeLabel() {
		switch (Theme.mode(this)) {
			case Theme.MODE_LIGHT:
				return getString(R.string.theme_light);
			case Theme.MODE_DARK:
				return getString(R.string.theme_dark);
			default:
				return getString(R.string.theme_system);
		}
	}

	private void showThemePicker() {
		int current = Theme.mode(this);
		Sheet.choice(this, getString(R.string.theme_mode), new CharSequence[] {
				getString(R.string.theme_system),
				getString(R.string.theme_light),
				getString(R.string.theme_dark),
		}, current, which -> {
			if (which == Theme.mode(this)) {
				return;
			}
			Theme.setMode(this, which);
			recreate();
		});
	}

	private void addToggle(LinearLayout g, String key, String label, String desc,
			Map<String, Boolean> flags, boolean online) {
		Ui.Toggle sw = addSwitch(g, label, desc, flags.getOrDefault(key, false),
				checked -> applyPref(key, String.valueOf(checked), label));
		sw.setEnabled(online);
		sw.setAlpha(online ? 1f : 0.4f);
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
					// Rebuild from the last state the daemon confirmed. The
					// old path detached the listener and flipped the switch by
					// hand, which left that row dead for the rest of the
					// session.
					if (status != null) {
						buildSettings(status);
					}
				});
			}
		}).start();
	}

	private static boolean isTrue(String v) {
		return "true".equals(v);
	}

	private void buildSystem(JSONObject st) {
		pSystem.removeAllViews();
		updateRow = null;
		updateTitle = null;
		updateSub = null;

		systemHero(st);

		// The daemon lives with the rest of the system controls. It used to
		// be repeated on the Send screen as well, which put two Start/Stop
		// switches in front of the one thing Send is for.
		LinearLayout d = group(pSystem, getString(R.string.daemon));
		addSwitch(d, getString(R.string.service), getString(R.string.service_sub),
				daemonUp(st), on -> service(on ? "start" : "stop"));
		action(d, getString(R.string.restart), null, v -> service("restart"));
		View supRow = action(d, getString(R.string.supervisor),
				supervisorUp(st) ? getString(R.string.supervisor_running)
						: getString(R.string.supervisor_stopped), null);
		supervisorSub = supRow.findViewById(R.id.rowSub);
		action(d, getString(R.string.console), null, v -> open(CONSOLE_URL));

		LinearLayout c = group(pSystem, getString(R.string.this_device));
		value(c, getString(R.string.backend), st.optString("backend", "?"));
		JSONObject self = st.optJSONObject("self");
		if (self != null) {
			TextView nv = value(c, getString(R.string.device_name),
					self.optString("host", "\u2014"));
			nameValues.add(nv);
			nameShown.add(self.optString("host", "\u2014"));
			JSONArray ips = self.optJSONArray("tailscaleIPs");
			if (ips != null) {
				for (int i = 0; i < ips.length(); i++) {
					final String ip = ips.optString(i);
					copyRow(c, i == 0 ? getString(R.string.ipv4)
							: getString(R.string.ipv6), ip);
				}
			}
			String relay = self.optString("relay", "");
			value(c, getString(R.string.relay),
					relay.isEmpty() ? getString(R.string.direct) : relay);
		}
		JSONObject tn = st.optJSONObject("tailnet");
		String account = tn == null ? "" : tn.optString("name", "");

		// How many Taildrop files landed while the app was closed. One
		// question per launch; the answer repaints this tab once.
		if (!receivedChecked) {
			receivedChecked = true;
			new Thread(() -> {
				try {
					JSONObject got = Api.received();
					final int n = got.optInt("count", 0);
					receivedCount = n;
					if (n > 0) {
						ui.post(() -> {
							dirty[2] = true;
							buildVisible();
						});
					}
				} catch (Exception e) {
					// Try again on the next visit rather than remembering a
					// failure as an answer of zero.
					receivedChecked = false;
				}
			}).start();
		}

		LinearLayout a1 = group(pSystem, getString(R.string.account));
		value(a1, getString(R.string.tailnet), account.isEmpty()
				? getString(R.string.not_signed_in) : account);
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

		if (receivedCount > 0) {
			action(u, getString(R.string.received_files_row, receivedCount),
					getString(R.string.taildrop_folder_sub), v -> openTaildropFolder());
		} else {
			value(u, getString(R.string.received_files_row_none),
					getString(R.string.taildrop_folder_sub));
		}
		action(u, getString(R.string.rollback), null, v -> rollback());
		action(u, getString(R.string.logs), null, v -> showLogs());

		LinearLayout w = group(pSystem, getString(R.string.wireless_adb_group));
		addSwitch(w, getString(R.string.wireless_adb_switch),
				getString(R.string.wireless_adb_sub), adbWireless,
				on -> confirmWirelessAdb(on));
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
		body.setTextColor(Theme.text2);
		body.setTypeface(android.graphics.Typeface.MONOSPACE);
		body.setLineSpacing(dp(3), 1f);
		body.setPadding(dp(14), dp(12), dp(14), dp(12));
		body.setBackground(Ui.fieldBg(this));
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

	private TextView chip(CharSequence label) {
		TextView b = Ui.label(this, label, 12.5f, Theme.onSecondaryContainer);
		b.setTypeface(null, android.graphics.Typeface.BOLD);
		b.setGravity(Gravity.CENTER);
		b.setPadding(dp(14), dp(9), dp(14), dp(9));
		b.setBackground(Ui.ripple(this,
				Ui.rounded(this, Theme.secondaryContainer, 999f),
				Theme.withAlpha(Theme.onSecondaryContainer, 0x33), null));
		b.setClickable(true);
		b.setFocusable(true);
		return b;
	}

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
						Theme.hint), sb.length() - split, sb.length(),
						android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				sb.append(line, split, line.length());
			} else {
				sb.append(line);
			}
			int start = sb.length() - line.length();
			sb.setSpan(new android.text.style.ForegroundColorSpan(
					(problem ? Theme.err : Theme.text2)), start, sb.length(),
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
		// Accept both the daemon's RFC3339 ("2026-11-07T13:13:47") and the
		// module scripts' classic ("2026/11/07 13:13:47"). Only the date
		// separator differs; HH:MM:SS sits at the same offsets.
		boolean dateOk = s.charAt(4) == '/' && s.charAt(7) == '/'
				|| s.charAt(4) == '-' && s.charAt(7) == '-';
		boolean sepOk = s.charAt(10) == ' ' || s.charAt(10) == 'T';
		return Character.isDigit(s.charAt(0)) && dateOk && sepOk
				&& s.charAt(13) == ':' && s.charAt(16) == ':';
	}

	private void doLogin() {
		toast(getString(R.string.working));
		new Thread(() -> {
			try {
				final JSONObject r = Api.login();
				ui.post(() -> {
					if (!r.optBoolean("ok", true)) {
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

	private void openRootManager() {
		String[] managers = {
				"com.resukisu.resukisu",
				"me.weishu.kernelsu",
				"com.topjohnwu.magisk",
				"me.bmax.apatch",
				"eu.chainfire.supersu",
				"com.noshufou.android.su",
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
			}
		}
		openAppSettings();
	}

	private void openAppSettings() {
		try {
			startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
					Uri.fromParts("package", getPackageName(), null)));
		} catch (Exception ignored) {
		}
	}

	private void open(String url) {
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
		} catch (Exception e) {
			toast(getString(R.string.no_browser));
		}
	}

	private LinearLayout group(LinearLayout parent, String heading) {
		if (heading != null) {
			parent.addView(Ui.section(this, heading));
		} else {
			LinearLayout.LayoutParams s = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			s.topMargin = dp(16);
			parent.addView(new View(this), s);
		}

		LinearLayout g = new LinearLayout(this);
		g.setOrientation(LinearLayout.VERTICAL);
		g.setBackground(Ui.cardBg(this));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		parent.addView(g, lp);
		return g;
	}

	private void line(LinearLayout g) {
		if (g.getChildCount() == 0) {
			return;
		}
		View v = new View(this);
		v.setBackgroundColor(Theme.hairline);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, 1);
		lp.leftMargin = dp(16);
		lp.rightMargin = dp(16);
		g.addView(v, lp);
	}

	private TextView value(LinearLayout g, String label, String v) {
		line(g);
		LinearLayout row = row();
		TextView l = Ui.label(this, label, 15f, Theme.text);
		l.setSingleLine(true);
		row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		TextView val = Ui.label(this, v, 14f, Theme.text2);
		val.setMaxLines(2);
		val.setEllipsize(android.text.TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		vlp.leftMargin = dp(14);
		row.addView(val, vlp);
		g.addView(row);
		return val;
	}

	private void copyRow(LinearLayout g, String label, String v) {
		line(g);
		LinearLayout row = row();
		row.setBackground(Ui.press(this));
		row.setClickable(true);
		row.setOnClickListener(x -> copy(v));

		TextView l = Ui.label(this, label, 15f, Theme.text);
		l.setSingleLine(true);
		row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		TextView val = Ui.label(this, v, 14f, Theme.text2);
		val.setSingleLine(true);
		val.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		row.addView(val);

		ImageView cp = new ImageView(this);
		cp.setImageResource(R.drawable.ic_copy);
		cp.setColorFilter(Theme.hint);
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(15), dp(15));
		clp.leftMargin = dp(12);
		row.addView(cp, clp);
		g.addView(row);
	}

	private View action(LinearLayout g, String title, String sub, View.OnClickListener l) {
		line(g);
		LinearLayout row = row();

		LinearLayout col = col();
		TextView t = Ui.label(this, title, 15f, Theme.text);
		t.setId(R.id.rowTitle);
		t.setSingleLine(true);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		col.addView(t);

		if (sub != null && !sub.isEmpty()) {
			TextView s = Ui.label(this, sub, 12.5f, Theme.text2);
			s.setId(R.id.rowSub);
			s.setPadding(0, dp(3), 0, 0);
			col.addView(s);
		}

		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		if (l != null) {
			ImageView chev = new ImageView(this);
			chev.setImageResource(R.drawable.ic_chevron);
			chev.setColorFilter(Theme.hint);
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(16), dp(16));
			clp.leftMargin = dp(10);
			row.addView(chev, clp);
			row.setBackground(Ui.press(this));
			row.setClickable(true);
			row.setOnClickListener(l);
		}
		g.addView(row);
		return row;
	}

	private Ui.Toggle addSwitch(LinearLayout g, String title, String sub, boolean on,
			OnChecked l) {
		line(g);
		LinearLayout row = row();
		LinearLayout col = col();

		TextView t = Ui.label(this, title, 15f, Theme.text);
		t.setId(R.id.rowTitle);
		col.addView(t);

		if (sub != null && !sub.isEmpty()) {
			TextView s = Ui.label(this, sub, 12.5f, Theme.text2);
			s.setId(R.id.rowSub);
			s.setPadding(0, dp(3), 0, 0);
			col.addView(s);
		}
		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		Ui.Toggle sw = Ui.toggle(this, on);
		row.addView(sw);

		if (l != null) {
			sw.setOnCheckedChangeListener(l::on);
			row.setBackground(Ui.press(this));
			row.setClickable(true);
			// The whole row is a bigger target, but a disabled switch must stay
			// disabled: performClick() does not check enabled itself.
			row.setOnClickListener(v -> {
				if (sw.isEnabled()) {
					sw.performClick();
				}
			});
		}
		g.addView(row);
		return sw;
	}

	private void plain(LinearLayout g, String text) {
		TextView t = Ui.label(this, text, 14f, Theme.text2);
		t.setPadding(dp(16), dp(16), dp(16), dp(16));
		g.addView(t);
	}

	private LinearLayout row() {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(16), dp(14), dp(16), dp(14));
		return row;
	}

	private LinearLayout col() {
		LinearLayout c = new LinearLayout(this);
		c.setOrientation(LinearLayout.VERTICAL);
		return c;
	}

	private interface OnChecked {
		void on(boolean checked);
	}

	private void note(LinearLayout parent, String text) {
		TextView t = Ui.label(this, text, 12.5f, Theme.hint);
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
