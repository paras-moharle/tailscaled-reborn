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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
	private TextView eyebrowView;
	private TextView versionView;
	private View dotView;
	/** The soft disc behind the status dot. */
	private View dotHaloView;

	private LinearLayout pSend;
	private LinearLayout pDns;
	private LinearLayout pSettings;
	private LinearLayout pSystem;
	private final View[] navs = new View[4];
	private int currentTab = 0;

	private TextView sendBtn;
	/** the selected count inside the Send hero, patched without a rebuild. */
	private TextView heroSelected;
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
	private final boolean[] dirty = { true, true, true, true };
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
		eyebrowView = findViewById(R.id.eyebrow);
		versionView = findViewById(R.id.version);
		dotView = findViewById(R.id.dot);
		dotHaloView = findViewById(R.id.dotHalo);
		pSend = findViewById(R.id.pSend);
		pDns = findViewById(R.id.pDns);
		pSettings = findViewById(R.id.pSettings);
		pSystem = findViewById(R.id.pSystem);
		styleChrome();

		navs[0] = findViewById(R.id.navSend);
		navs[1] = findViewById(R.id.navDns);
		navs[2] = findViewById(R.id.navSettings);
		navs[3] = findViewById(R.id.navSystem);
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
		attachPullToRefresh(findViewById(R.id.scrollDns), pullRefresh);
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
				findViewById(R.id.scrollDns),
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
			now.setTranslationY(dp(14));
			now.animate().alpha(1f).translationY(0f)
					.setDuration(260).setInterpolator(Ui.EMPHASIZED).start();
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
					Ui.rounded(this, Theme.surfaceContainerHigh, 999f),
					Theme.withAlpha(Theme.onSurface, 0x1F), null));
			Ui.pressable(refresh);
		}
		if (eyebrowView != null) {
			eyebrowView.setTextColor(Theme.accent);
		}
		View pull = findViewById(R.id.pullSpinner);
		if (pull instanceof ImageView) {
			((ImageView) pull).setColorFilter(Theme.accent);
		}

		View navBar = findViewById(R.id.navBar);
		if (navBar != null) {
			navBar.setBackground(Ui.navBg(this));
			navBar.setClipToOutline(true);
			navBar.setElevation(dp(3));
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
		final View navBar = findViewById(R.id.navBar);
		final int floor = dp(22);
		header.setOnApplyWindowInsetsListener((v, insets) -> {
			int top = 0;
			int bottom = 0;
			if (android.os.Build.VERSION.SDK_INT >= 30) {
				android.graphics.Insets bars = insets.getInsets(
						android.view.WindowInsets.Type.systemBars()
								| android.view.WindowInsets.Type.displayCutout());
				top = bars.top;
				bottom = bars.bottom;
			} else {
				top = insets.getSystemWindowInsetTop();
				bottom = insets.getSystemWindowInsetBottom();
			}
			v.setPadding(v.getPaddingLeft(), floor + top, v.getPaddingRight(),
					v.getPaddingBottom());
			if (navBar != null && navBar.getLayoutParams()
					instanceof ViewGroup.MarginLayoutParams) {
				ViewGroup.MarginLayoutParams lp =
						(ViewGroup.MarginLayoutParams) navBar.getLayoutParams();
				int want = dp(10) + (bottom > dp(24) ? bottom : 0);
				if (lp.bottomMargin != want) {
					lp.bottomMargin = want;
					navBar.requestLayout();
				}
			}
			return insets;
		});
		header.requestApplyInsets();
	}

	private void manualRefresh() {
		// A manual refresh means "read everything again", including the DNS
		// document the page otherwise caches for a minute.
		if (CloudApi.connected(this)) {
			dnsData = null;
		}
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

		dirty[0] = dirty[1] = dirty[2] = dirty[3] = true;
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
					buildDns(status);
					dirty[1] = false;
				}
				break;
			case 2:
				if (dirty[2]) {
					buildSettings(status);
					dirty[2] = false;
				}
				break;
			case 3:
				if (dirty[3]) {
					buildSystem(status);
					dirty[3] = false;
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
		statusView.setTextSize(26f);
		statusView.setSingleLine(true);
		statusView.setMaxLines(1);
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
			dotHaloView.setBackground(Ui.rounded(this, Theme.withAlpha(c, 0x26), 14f));
			dotHaloView.setAlpha(1f);
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
							dirty[0] = dirty[1] = dirty[2] = dirty[3] = true;
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
		statusView.setTextSize(24f);
		statusView.setSingleLine(false);
		statusView.setMaxLines(2);
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

		LinearLayout t = group(pSend, getString(R.string.choose_devices),
				peerList.isEmpty() ? null
						: getString(R.string.send_online, onlinePeers.size()));
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
		sendBtn.setCompoundDrawablePadding(dp(10));
		android.graphics.drawable.Drawable arrow =
				getResources().getDrawable(R.drawable.ic_send, getTheme()).mutate();
		arrow.setBounds(0, 0, dp(19), dp(19));
		arrow.setTint(Theme.onSurfaceVariant);
		sendBtn.setCompoundDrawables(arrow, null, null, null);
		sendBtn.setOnClickListener(v -> onSendPressed());
		Ui.pressable(sendBtn);
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
		slp.topMargin = dp(20);
		pSend.addView(sendBtn, slp);

		updateSendButton();
	}

	/** The page's opening panel: what this screen is for, in one breath. */
	private void sendHero() {
		heroSelected = null;
		LinearLayout hero = Ui.heroCard(this);
		hero.setPadding(dp(24), dp(24), dp(24), dp(22));
		LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		hlp.topMargin = dp(4);
		pSend.addView(hero, hlp);

		LinearLayout top = new LinearLayout(this);
		top.setOrientation(LinearLayout.HORIZONTAL);
		top.setGravity(Gravity.CENTER_VERTICAL);
		hero.addView(top);

		FrameLayout mark = Ui.iconTile(this, R.drawable.ic_send,
				Theme.accentDim, Theme.accent, 52);
		LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(52), dp(52));
		mlp.rightMargin = dp(14);
		mlp.bottomMargin = dp(2);
		top.addView(mark, mlp);

		LinearLayout titles = col();
		titles.addView(Ui.eyebrow(this, getString(R.string.page_send)));
		TextView title = Ui.label(this, getString(R.string.send_hero_title), 29f, Theme.text);
		title.setTypeface(null, android.graphics.Typeface.BOLD);
		title.setLetterSpacing(-0.035f);
		title.setPadding(0, dp(2), 0, 0);
		titles.addView(title);
		top.addView(titles, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		TextView body = Ui.label(this, getString(R.string.send_hero_body), 13.5f, Theme.text2);
		body.setLineSpacing(dp(5), 1f);
		body.setPadding(0, dp(14), 0, 0);
		hero.addView(body);

		LinearLayout stats = new LinearLayout(this);
		stats.setOrientation(LinearLayout.HORIZONTAL);
		stats.setGravity(Gravity.CENTER_VERTICAL);
		stats.setPadding(0, dp(20), 0, 0);

		boolean anyOnline = !onlinePeers.isEmpty();
		stats.addView(statTile(String.valueOf(onlinePeers.size()),
				getString(R.string.stat_online), anyOnline), statParams());

		heroSelected = statTile(String.valueOf(chosen.size()),
				getString(R.string.stat_selected), !chosen.isEmpty())
				.findViewById(R.id.statValue);
		LinearLayout.LayoutParams slp = statParams();
		slp.leftMargin = dp(10);
		stats.addView((View) heroSelected.getParent(), slp);
		hero.addView(stats);

		Ui.stagger(top, body, stats);
	}

	private LinearLayout.LayoutParams statParams() {
		return new LinearLayout.LayoutParams(0, dp(70), 1f);
	}

	/** A small inset block with the number first and its name underneath. */
	private LinearLayout statTile(String value, String label, boolean lit) {
		LinearLayout tile = new LinearLayout(this);
		tile.setOrientation(LinearLayout.VERTICAL);
		tile.setGravity(Gravity.CENTER_VERTICAL);
		tile.setPadding(dp(16), dp(12), dp(14), dp(12));
		tile.setBackground(Ui.outlined(this,
				lit ? Theme.secondaryContainer : Theme.surfaceContainerHigh,
				lit ? Theme.withAlpha(Theme.onSecondaryContainer, 0x26) : Theme.hairline,
				18f, 1f));

		TextView v = Ui.label(this, value, 24f,
				lit ? Theme.onSecondaryContainer : Theme.text);
		v.setTypeface(null, android.graphics.Typeface.BOLD);
		v.setLetterSpacing(-0.03f);
		v.setId(R.id.statValue);
		tile.addView(v);

		TextView l = Ui.label(this, label, 10.5f,
				lit ? Theme.onSecondaryContainer : Theme.text2);
		l.setTypeface(null, android.graphics.Typeface.BOLD);
		l.setLetterSpacing(0.1f);
		l.setAllCaps(true);
		l.setPadding(0, dp(2), 0, 0);
		tile.addView(l);
		return tile;
	}

	/** The System page's opening panel: who this device is, and whether it is up. */
	private void systemHero(JSONObject st) {
		LinearLayout hero = Ui.heroCard(this);
		hero.setPadding(dp(24), dp(24), dp(24), dp(22));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(4);
		pSystem.addView(hero, lp);

		JSONObject self = st.optJSONObject("self");
		String host = self == null ? "" : self.optString("host", "");
		String ip = tsIpv4(st);
		boolean up = daemonUp(st);

		LinearLayout top = new LinearLayout(this);
		top.setOrientation(LinearLayout.HORIZONTAL);
		top.setGravity(Gravity.CENTER_VERTICAL);

		LinearLayout titles = col();
		titles.addView(Ui.eyebrow(this, getString(R.string.page_system)));
		TextView name = Ui.label(this,
				host.isEmpty() ? getString(R.string.this_device) : host, 27f, Theme.text);
		name.setTypeface(null, android.graphics.Typeface.BOLD);
		name.setLetterSpacing(-0.035f);
		name.setSingleLine(true);
		name.setEllipsize(android.text.TextUtils.TruncateAt.END);
		name.setPadding(0, dp(2), 0, 0);
		titles.addView(name);
		top.addView(titles, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		top.addView(Ui.iconTile(this, R.drawable.ic_device,
				Theme.accentDim, Theme.accent, 50),
				new LinearLayout.LayoutParams(dp(50), dp(50)));
		hero.addView(top);

		LinearLayout addrRow = new LinearLayout(this);
		addrRow.setOrientation(LinearLayout.HORIZONTAL);
		addrRow.setGravity(Gravity.CENTER_VERTICAL);
		addrRow.setPadding(0, dp(12), 0, 0);

		TextView addr = Ui.label(this,
				ip.isEmpty() ? getString(R.string.not_signed_in) : ip, 14f, Theme.text2);
		addr.setTypeface(android.graphics.Typeface.MONOSPACE, 0);
		addrRow.addView(addr);
		if (!ip.isEmpty()) {
			final String copyIp = ip;
			View copy = iconButton(R.drawable.ic_copy, Theme.accent, R.string.copy,
					v -> copy(copyIp));
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(36), dp(36));
			clp.leftMargin = dp(6);
			addrRow.addView(copy, clp);
		}
		hero.addView(addrRow);

		LinearLayout chips = new LinearLayout(this);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setGravity(Gravity.CENTER_VERTICAL);
		chips.setPadding(0, dp(16), 0, 0);

		boolean in = signedIn(st);
		chips.addView(statusChip(getString(up ? R.string.daemon_running : R.string.daemon_off),
				up ? Theme.successContainer : Theme.withAlpha(Theme.error, 0x24),
				up ? Theme.success : Theme.error,
				up ? Theme.success : Theme.error));

		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		alp.leftMargin = dp(8);
		chips.addView(statusChip(getString(in ? R.string.connected : R.string.not_signed_in),
				in ? Theme.secondaryContainer : Theme.surfaceContainerHighest,
				in ? Theme.onSecondaryContainer : Theme.text2,
				in ? Theme.success : Theme.hint), alp);
		hero.addView(chips);

		Ui.stagger(top, addrRow, chips);
	}

	private LinearLayout statusChip(String label, int background, int foreground, int dot) {
		LinearLayout chip = new LinearLayout(this);
		chip.setOrientation(LinearLayout.HORIZONTAL);
		chip.setGravity(Gravity.CENTER_VERTICAL);
		chip.setPadding(dp(12), dp(8), dp(14), dp(8));
		chip.setBackground(Ui.rounded(this, background, 999f));

		View mark = new View(this);
		mark.setBackground(Ui.rounded(this, dot, 999f));
		LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(7), dp(7));
		mlp.rightMargin = dp(8);
		chip.addView(mark, mlp);

		TextView t = Ui.label(this, label, 12f, foreground);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		chip.addView(t);
		return chip;
	}

	/** The device list with nothing in it still has to explain itself. */
	private void emptyPeers(LinearLayout g) {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setGravity(Gravity.CENTER_HORIZONTAL);
		box.setPadding(dp(28), dp(36), dp(28), dp(36));

		box.addView(Ui.iconTile(this, R.drawable.ic_device,
				Theme.surfaceContainerHighest, Theme.hint, 56),
				new LinearLayout.LayoutParams(dp(56), dp(56)));

		TextView title = Ui.label(this, getString(R.string.no_peers_short), 18f, Theme.text);
		title.setTypeface(null, android.graphics.Typeface.BOLD);
		title.setGravity(Gravity.CENTER);
		title.setPadding(0, dp(16), 0, 0);
		box.addView(title);

		TextView sub = Ui.label(this, getString(R.string.no_peers_body), 13.5f, Theme.text2);
		sub.setGravity(Gravity.CENTER);
		sub.setLineSpacing(dp(4), 1f);
		sub.setPadding(dp(8), dp(7), dp(8), 0);
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
		LinearLayout card = stateCard(R.drawable.ic_power,
				getString(R.string.daemon_stopped_card),
				getString(R.string.daemon_stopped_card_body),
				Theme.error, Theme.errorContainer);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(14);
		pSend.addView(card, lp);

		TextView go = Ui.button(this, getString(R.string.start_daemon), true);
		go.setOnClickListener(v -> service("start"));
		Ui.pressable(go);
		LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
		glp.topMargin = dp(18);
		card.addView(go, glp);
	}

	private void signInCard() {
		boolean up = daemonUp(status);
		LinearLayout card = stateCard(R.drawable.ic_person,
				getString(R.string.not_signed_in),
				getString(R.string.sign_in_card_body),
				Theme.accent, Theme.primaryContainer);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(14);
		pSend.addView(card, lp);

		TextView go = Ui.button(this, getString(R.string.sign_in), true);
		go.setOnClickListener(v -> doLogin());
		Ui.pressable(go);
		LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
		glp.topMargin = dp(18);
		card.addView(go, glp);

		if (!up) {
			TextView hint = Ui.label(this, getString(R.string.sign_in_needs_daemon), 12.5f,
					Theme.hint);
			hint.setPadding(0, dp(12), 0, 0);
			card.addView(hint);
		}
	}

	/** A card that states one problem, then offers the one thing that fixes it. */
	private LinearLayout stateCard(int icon, String title, String body, int tone, int container) {
		LinearLayout card = new LinearLayout(this);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(dp(20), dp(20), dp(20), dp(20));
		card.setBackground(Ui.outlined(this,
				Theme.mix(Theme.surfaceContainerHigh, container,
						Theme.isDark ? 0.26f : 0.34f),
				Theme.withAlpha(tone, 0x3D), 24f, 1f));
		card.setClipToOutline(true);

		LinearLayout head = new LinearLayout(this);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setGravity(Gravity.CENTER_VERTICAL);
		head.addView(Ui.iconTile(this, icon, Theme.withAlpha(tone, 0x1F), tone, 42),
				tileParams());

		LinearLayout col = col();
		TextView t = Ui.label(this, title, 17f, Theme.text);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		t.setLetterSpacing(-0.02f);
		col.addView(t);
		TextView d = Ui.label(this, body, 13.5f, Theme.text2);
		d.setLineSpacing(dp(4), 1f);
		d.setPadding(0, dp(5), 0, 0);
		col.addView(d);
		head.addView(col, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		card.addView(head);
		return card;
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
		final boolean picked = up && chosen.contains(p.name);

		row.addView(Ui.avatar(this, p.name, up));

		Ui.Toggle cb = Ui.toggle(this, picked);
		cb.setEnabled(up);
		cb.setAlpha(up ? 1f : 0.4f);

		LinearLayout col = col();
		col.setPadding(dp(13), 0, dp(6), 0);
		TextView n = Ui.label(this, p.name, 15.5f, (up ? Theme.text : Theme.text2));
		n.setTypeface(null, android.graphics.Typeface.BOLD);
		n.setSingleLine(true);
		n.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
				+ (up ? "  ·  " + link
					: "  ·  " + getString(R.string.peer_offline_label)
						+ (when.isEmpty() ? "" : "  ·  " + when)));
		meta.setTextColor(Theme.text2);
		meta.setTextSize(12.5f);
		meta.setMaxLines(2);
		meta.setLineSpacing(dp(2), 1f);
		meta.setIncludeFontPadding(false);
		meta.setPadding(0, dp(4), 0, 0);
		col.addView(meta);
		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		slp.leftMargin = dp(4);
		row.addView(cb, slp);

		View send = iconButton(R.drawable.ic_send, up ? Theme.accent : Theme.hint,
				R.string.send, v -> launchPicker(java.util.Collections.singletonList(p.ip)));
		send.setEnabled(up);
		send.setAlpha(up ? 1f : 0.35f);
		LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(44), dp(44));
		sendLp.leftMargin = dp(2);
		row.addView(send, sendLp);

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
			paintPeerRow(row, now);
			saveTargets();
			updateSendButton();
		};
		row.setOnClickListener(v -> peer.run());
		row.setClickable(true);
		paintPeerRow(row, picked);
		Ui.pressable(row);
		cb.setOnCheckedChangeListener(on -> peer.run());
		return row;
	}

	/** The row itself carries the selection, not just the switch on its end. */
	private void paintPeerRow(View row, boolean picked) {
		if (picked) {
			row.setBackground(Ui.ripple(this,
					Ui.rounded(this, Theme.withAlpha(Theme.secondaryContainer,
							Theme.isDark ? 0x66 : 0x99), 20f),
					Theme.withAlpha(Theme.onSurface, 0x1F), null));
		} else {
			row.setBackground(Ui.press(this, 20f));
		}
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
		if (heroSelected != null) {
			heroSelected.setText(String.valueOf(n));
		}
		android.graphics.drawable.Drawable arrow = sendBtn.getCompoundDrawables()[0];
		if (arrow != null) {
			arrow.setTint(on ? Theme.onPrimary : Theme.onSurfaceVariant);
		}
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

	private void buildSettings(JSONObject st) {
		pSettings.removeAllViews();
		switches.clear();
		Map<String, Boolean> flags = Api.flagsFrom(st);
		boolean online = daemonUp(st);

		settingsHero(st, online);

		LinearLayout c1 = group(pSettings, getString(R.string.connectivity));
		action(c1, R.drawable.ic_globe, getString(R.string.dns_dns_row),
				getString(R.string.dns_dns_row_sub), v -> tab(1));
		addToggle(c1, R.drawable.ic_check, "accept-dns", getString(R.string.t_accept_dns),
				getString(R.string.t_accept_dns_sub), flags, online);
		addToggle(c1, R.drawable.ic_route, "accept-routes", getString(R.string.t_accept_routes),
				getString(R.string.t_accept_routes_sub), flags, online);
		addToggle(c1, R.drawable.ic_shield, "shields-up", getString(R.string.t_shields_up),
				getString(R.string.t_shields_up_sub), flags, online);

		LinearLayout c2 = group(pSettings, getString(R.string.services));
		addToggle(c2, R.drawable.ic_terminal, "run-ssh", getString(R.string.t_ssh),
				getString(R.string.t_ssh_sub), flags, online);
		addToggle(c2, R.drawable.ic_globe, "run-webclient", getString(R.string.t_webclient),
				getString(R.string.t_webclient_sub), flags, online);

		// Advertising ranges and NATing them is what turns this phone into a
		// subnet router. Both flags exist on `tailscale set`, so both are
		// reachable here instead of only from a terminal.
		LinearLayout c4 = group(pSettings, getString(R.string.routing));
		JSONObject prefsForRoutes = st.optJSONObject("prefs");
		c4.addView(routesRow(prefsForRoutes, online));
		line(c4);
		Ui.Toggle snat = addToggle(c4, R.drawable.ic_route, "snat-subnet-routes",
				getString(R.string.t_snat), getString(R.string.t_snat_sub), flags, online);
		// NAT only exists for traffic that a route brought in. With nothing
		// advertised the switch cannot do anything, so it says so by being
		// dimmed rather than accepting a write that changes nothing.
		boolean hasRoutes = !currentRoutes(prefsForRoutes).isEmpty();
		if (online && !hasRoutes) {
			setRowEnabled(snat, false);
		}

		LinearLayout c3 = group(pSettings, getString(R.string.this_device));
		c3.addView(hostnameRow(prefsForRoutes == null ? ""
				: prefsForRoutes.optString("hostname", ""), online));
		line(c3);
		action(c3, R.drawable.ic_trash, getString(R.string.forget_targets),
				getString(R.string.forget_targets_sub), v -> {
					chosen.clear();
					saveTargets();
					toast(getString(R.string.forget_targets_done));
					buildSend();
				});

		// Appearance sits last on purpose: it is the group someone goes
		// looking for once, not the one they came to this tab for.
		LinearLayout ap = group(pSettings, getString(R.string.appearance));
		TextView themeSub = Ui.label(this, themeModeSub(), 12.5f, Theme.text2);
		themeSub.setId(R.id.rowSub);
		ap.addView(themeRow(themeSub));
		ap.addView(themeSegment());
		line(ap);
		Ui.Toggle dyn = addSwitch(ap, R.drawable.ic_spark,
				getString(R.string.dynamic_color),
				android.os.Build.VERSION.SDK_INT >= 31
						? getString(R.string.dynamic_color_sub)
						: getString(R.string.dynamic_color_unavailable),
				Theme.dynamic(this), on -> {
					Theme.setDynamic(this, on);
					recreate();
				});
		boolean canDynamic = android.os.Build.VERSION.SDK_INT >= 31;
		setRowEnabled(dyn, canDynamic);

		boolean dark = Theme.darkNow(this);
		Ui.Toggle black = addSwitch(ap, R.drawable.ic_moon, getString(R.string.amoled),
				dark ? getString(R.string.amoled_sub) : getString(R.string.amoled_needs_dark),
				dark && Theme.amoled(this), on -> {
					Theme.setAmoled(this, on);
					recreate();
				});
		setRowEnabled(black, dark);

		if (!online) {
			note(pSettings, getString(R.string.daemon_stopped_note));
		}
		note(pSettings, getString(R.string.footnote));
	}

	/** The Settings page leads with a sentence, not with the first switch. */
	private void settingsHero(JSONObject st, boolean online) {
		LinearLayout hero = Ui.heroCard(this);
		hero.setPadding(dp(24), dp(24), dp(24), dp(22));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(4);
		pSettings.addView(hero, lp);

		LinearLayout top = new LinearLayout(this);
		top.setOrientation(LinearLayout.HORIZONTAL);
		top.setGravity(Gravity.CENTER_VERTICAL);
		hero.addView(top);

		LinearLayout titles = col();
		titles.addView(Ui.eyebrow(this, getString(R.string.page_settings)));
		TextView title = Ui.label(this, getString(R.string.settings_hero_title), 28f,
				Theme.text);
		title.setTypeface(null, android.graphics.Typeface.BOLD);
		title.setLetterSpacing(-0.035f);
		title.setPadding(0, dp(2), 0, 0);
		titles.addView(title);
		top.addView(titles, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		top.addView(Ui.iconTile(this, R.drawable.ic_settings,
				Theme.accentDim, Theme.accent, 50),
				new LinearLayout.LayoutParams(dp(50), dp(50)));

		TextView body = Ui.label(this, getString(R.string.settings_hero_body), 13.5f,
				Theme.text2);
		body.setLineSpacing(dp(5), 1f);
		body.setPadding(0, dp(14), 0, 0);
		hero.addView(body);

		LinearLayout chips = new LinearLayout(this);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setPadding(0, dp(16), 0, 0);
		chips.addView(statusChip(getString(online ? R.string.daemon_running
						: R.string.daemon_off),
				online ? Theme.successContainer : Theme.withAlpha(Theme.error, 0x24),
				online ? Theme.success : Theme.error,
				online ? Theme.success : Theme.error));

		boolean in = signedIn(st);
		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		alp.leftMargin = dp(8);
		chips.addView(statusChip(getString(in ? R.string.connected
						: R.string.not_signed_in),
				in ? Theme.secondaryContainer : Theme.surfaceContainerHighest,
				in ? Theme.onSecondaryContainer : Theme.text2,
				in ? Theme.success : Theme.hint), alp);
		hero.addView(chips);

		Ui.stagger(top, body, chips);
	}

	/** The device-name editor, laid out as a row rather than a bare field. */
	private View hostnameRow(String current, boolean online) {
		LinearLayout row = row();
		row.addView(Ui.iconTile(this, R.drawable.ic_device,
				Theme.accentDim, Theme.accent, 40), tileParams());

		LinearLayout col = col();
		TextView label = Ui.label(this, getString(R.string.hostname), 15.5f, Theme.text);
		label.setTypeface(null, android.graphics.Typeface.BOLD);
		col.addView(label);

		LinearLayout entry = new LinearLayout(this);
		entry.setOrientation(LinearLayout.HORIZONTAL);
		entry.setGravity(Gravity.CENTER_VERTICAL);
		entry.setPadding(0, dp(9), 0, 0);

		EditText in = new EditText(this);
		in.setHint(getString(R.string.hostname_hint));
		in.setText(current);
		in.setSingleLine(true);
		in.setTextColor(Theme.text);
		in.setHintTextColor(Theme.hint);
		in.setTextSize(14.5f);
		in.setGravity(Gravity.CENTER_VERTICAL);
		in.setPadding(dp(14), 0, dp(14), 0);
		in.setBackground(Ui.fieldBg(this));
		entry.addView(in, new LinearLayout.LayoutParams(0, dp(46), 1f));

		TextView apply = Ui.tonal(this, getString(R.string.apply));
		apply.setEnabled(online);
		apply.setAlpha(online ? 1f : 0.4f);
		apply.setOnClickListener(v -> applyPref("hostname",
				in.getText().toString().trim(), getString(R.string.hostname)));
		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
		alp.leftMargin = dp(8);
		entry.addView(apply, alp);
		col.addView(entry);

		row.addView(col, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		return row;
	}

	/**
	 * Advertised subnet routes: a small plate, not a dialog.
	 *
	 * <p>Most people either have no routes or have exactly one, so the
	 * current value stays visible and editable in place. Formatting is
	 * normalised on save, because "192.168.1.0/24,10.0.0.0/16" and
	 * "192.168.1.0/24, 10.0.0.0/16" mean the same thing.
	 */
	private View routesRow(JSONObject prefs, boolean online) {
		LinearLayout row = row();
		row.addView(Ui.iconTile(this, R.drawable.ic_route,
				Theme.accentDim, Theme.accent, 40), tileParams());

		LinearLayout col = col();
		TextView label = Ui.label(this, getString(R.string.advertise_routes), 15.5f,
				Theme.text);
		label.setTypeface(null, android.graphics.Typeface.BOLD);
		col.addView(label);
		TextView sub = Ui.label(this, getString(R.string.advertise_routes_sub), 12.5f,
				Theme.text2);
		sub.setPadding(0, dp(3), 0, 0);
		col.addView(sub);

		LinearLayout entry = new LinearLayout(this);
		entry.setOrientation(LinearLayout.HORIZONTAL);
		entry.setGravity(Gravity.CENTER_VERTICAL);
		entry.setPadding(0, dp(9), 0, 0);

		EditText in = new EditText(this);
		in.setHint(getString(R.string.routes_hint));
		in.setText(currentRoutes(prefs));
		in.setSingleLine(true);
		in.setTextColor(Theme.text);
		in.setHintTextColor(Theme.hint);
		in.setTextSize(13.5f);
		in.setTypeface(android.graphics.Typeface.MONOSPACE);
		in.setGravity(Gravity.CENTER_VERTICAL);
		in.setPadding(dp(14), 0, dp(14), 0);
		in.setBackground(Ui.fieldBg(this));
		entry.addView(in, new LinearLayout.LayoutParams(0, dp(46), 1f));

		TextView apply = Ui.tonal(this, getString(R.string.apply));
		apply.setEnabled(online);
		apply.setAlpha(online ? 1f : 0.4f);
		apply.setOnClickListener(v -> {
			String raw = in.getText().toString().replace(" ", "");
			if (!validCidrs(raw)) {
				toast(getString(R.string.routes_invalid));
				Ui.pop(in);
				return;
			}
			applyPref("advertise-routes", raw, getString(R.string.advertise_routes));
		});
		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
		alp.leftMargin = dp(8);
		entry.addView(apply, alp);
		col.addView(entry);

		row.addView(col, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		return row;
	}

	/** The daemon's advertised ranges, minus the exit-node default route. */
	private static String currentRoutes(JSONObject prefs) {
		if (prefs == null) {
			return "";
		}
		JSONArray arr = prefs.optJSONArray("advertiseRoutes");
		if (arr == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < arr.length(); i++) {
			String r = arr.optString(i, "").trim();
			if (r.isEmpty() || "0.0.0.0/0".equals(r) || "::/0".equals(r)) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(r);
		}
		return sb.toString();
	}

	private static boolean validCidrs(String v) {
		if (v.isEmpty()) {
			return true;
		}
		for (String part : v.split(",")) {
			if (!part.matches("^[0-9a-fA-F.:]+/[0-9]{1,3}$")) {
				return false;
			}
		}
		return true;
	}

	/** The label half of the Theme row; the segment control sits underneath. */
	private View themeRow(TextView sub) {
		LinearLayout row = row();
		row.addView(Ui.iconTile(this, R.drawable.ic_palette,
				Theme.accentDim, Theme.accent, 40), tileParams());

		LinearLayout col = col();
		TextView t = Ui.label(this, getString(R.string.theme_mode), 15.5f, Theme.text);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		col.addView(t);
		sub.setPadding(0, dp(3), 0, 0);
		col.addView(sub);
		row.addView(col, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		return row;
	}

	/** Three choices, all visible, one already filled: no popup needed. */
	private View themeSegment() {
		LinearLayout seg = new LinearLayout(this);
		seg.setOrientation(LinearLayout.HORIZONTAL);
		seg.setBackground(Ui.fieldBg(this));
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
		slp.leftMargin = dp(18);
		slp.rightMargin = dp(18);
		slp.bottomMargin = dp(16);
		seg.setLayoutParams(slp);
		seg.setPadding(dp(4), dp(4), dp(4), dp(4));

		int now = Theme.mode(this);
		int[] modes = { Theme.MODE_SYSTEM, Theme.MODE_LIGHT, Theme.MODE_DARK };
		int[] labels = { R.string.theme_system, R.string.theme_light, R.string.theme_dark };
		for (int i = 0; i < modes.length; i++) {
			final int mode = modes[i];
			boolean on = mode == now;
			TextView b = Ui.label(this, getString(labels[i]), 13f,
					on ? Theme.onSecondaryContainer : Theme.text2);
			b.setTypeface(null, on ? android.graphics.Typeface.BOLD
					: android.graphics.Typeface.NORMAL);
			b.setGravity(Gravity.CENTER);
			b.setBackground(on
					? Ui.ripple(this, Ui.rounded(this, Theme.secondaryContainer, 999f),
							Theme.withAlpha(Theme.onSecondaryContainer, 0x33), null)
					: Ui.press(this, 999f));
			b.setClickable(true);
			b.setFocusable(true);
			b.setOnClickListener(v -> {
				if (Theme.mode(this) != mode) {
					Theme.setMode(this, mode);
					recreate();
				}
			});
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
			seg.addView(b, lp);
		}
		return seg;
	}

	private String themeModeSub() {
		switch (Theme.mode(this)) {
			case Theme.MODE_LIGHT:
				return getString(R.string.theme_light_sub);
			case Theme.MODE_DARK:
				return getString(R.string.theme_dark_sub);
			default:
				return getString(R.string.theme_system_sub);
		}
	}

	private Ui.Toggle addToggle(LinearLayout g, int icon, String key, String label,
			String desc, Map<String, Boolean> flags, boolean online) {
		Ui.Toggle sw = addSwitch(g, icon, label,
				online ? desc : desc + "  ·  " + getString(R.string.needs_daemon),
				flags.getOrDefault(key, false),
				checked -> applyPref(key, String.valueOf(checked), label));
		setRowEnabled(sw, online);
		switches.put(key, sw);
		return sw;
	}

	/** Dim the whole row, not just the 52dp switch, when a control is unavailable. */
	private void setRowEnabled(Ui.Toggle sw, boolean on) {
		sw.setEnabled(on);
		View row = sw.getParent() instanceof View ? (View) sw.getParent() : null;
		if (row != null) {
			row.setEnabled(on);
			row.setAlpha(on ? 1f : 0.52f);
		}
	}

	private void applyPref(String key, String value, String label) {
		new Thread(() -> {
			try {
				final JSONObject st = Api.set(key, value);
				ui.post(() -> {
					if ("hostname".equals(key)) {
						toast(getString(R.string.hostname_saved));
					} else if ("advertise-routes".equals(key)) {
						toast(getString(R.string.routes_saved));
					} else {
						toast(getString(isTrue(value) ? R.string.turned_on
								: R.string.turned_off, label));
					}
					status = st;
					// Both tabs carry daemon preferences (accept-dns lives on
					// Settings and on DNS), so mark them and rebuild whichever
					// is on screen instead of assuming Settings.
					dirty[1] = true;
					dirty[2] = true;
					buildVisible();
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
						dirty[1] = true;
						dirty[2] = true;
						buildVisible();
					}
				});
			}
		}).start();
	}

	private static boolean isTrue(String v) {
		return "true".equals(v);
	}

	// ──────────────────────────────────────────────────────────────── DNS
	//
	// Two layers, on purpose.
	//
	// The daemon owns a few DNS facts: whether this phone accepts the
	// tailnet's resolvers, and the tailnet's own domain. Those work without
	// any account and stay live through the module's API.
	//
	// Global nameservers, search domains and MagicDNS are tailnet state held
	// by the control plane. The daemon cannot change them, so the page asks
	// for one API token, keeps it in the app's private storage, and edits
	// them over Tailscale's v2 API. Without a token the page is still
	// useful; with one it replaces the console tab.

	/** One read of the control-plane DNS document, cached for the page. */
	private static final class DnsData {
		final List<String> nameservers;
		final List<String> searchPaths;
		final boolean magicDns;
		/** Non-null when the last read failed; the lists are then stale. */
		final String error;
		final long at;

		DnsData(List<String> nameservers, List<String> searchPaths, boolean magicDns,
				String error, long at) {
			this.nameservers = nameservers;
			this.searchPaths = searchPaths;
			this.magicDns = magicDns;
			this.error = error;
			this.at = at;
		}
	}

	private DnsData dnsData;
	private boolean dnsLoading;
	/** Bumped per request so a slow answer cannot overwrite a newer one. */
	private int dnsEpoch;

	private static final String DNS_CONSOLE_URL = "https://login.tailscale.com/admin/dns";
	private static final String TOKEN_URL = "https://login.tailscale.com/admin/settings/keys";

	/** The providers most people are actually reaching for. */
	private static final int[] DNS_PRESET_LABELS = {
			R.string.dns_provider_cloudflare,
			R.string.dns_provider_google,
			R.string.dns_provider_quad9,
			R.string.dns_provider_adguard,
			R.string.dns_provider_nextdns,
	};
	private static final String[] DNS_PRESET_VALUES = {
			"1.1.1.1, 1.0.0.1",
			"8.8.8.8, 8.8.4.4",
			"9.9.9.9, 149.112.112.112",
			"94.140.14.14, 94.140.15.15",
			"https://dns.nextdns.io/",
	};

	private void buildDns(JSONObject st) {
		pDns.removeAllViews();
		boolean online = daemonUp(st);
		boolean wired = CloudApi.connected(this);

		dnsHero(st, online, wired);

		if (wired) {
			maybeLoadDns(false);
			if (dnsData != null && dnsData.error != null) {
				dnsErrorCard(dnsData.error);
			} else if (dnsData == null) {
				dnsLoadingCard();
			} else {
				dnsMagicCard();
				dnsNameserverCard();
				dnsSearchCard();
			}
			dnsAccountCard();
		} else {
			dnsConnectCard();
		}

		Map<String, Boolean> flags = Api.flagsFrom(st);
		LinearLayout local = group(pDns, getString(R.string.this_device));
		addToggle(local, R.drawable.ic_device, "accept-dns",
				getString(R.string.t_accept_dns),
				getString(R.string.dns_local_row_sub), flags, online);

		JSONObject tn = st.optJSONObject("tailnet");
		String name = tn == null ? "" : tn.optString("name", "").trim();
		if (!name.isEmpty()) {
			local.addView(dnsNameRow(name), insetBlockParams());
		}

		LinearLayout console = group(pDns, getString(R.string.dns_console_group));
		action(console, R.drawable.ic_globe, getString(R.string.dns_open_console),
				getString(R.string.dns_console_row_sub), v -> open(DNS_CONSOLE_URL));
		note(pDns, getString(R.string.dns_rename_hint));
	}

	private void dnsHero(JSONObject st, boolean online, boolean wired) {
		LinearLayout hero = Ui.heroCard(this);
		hero.setPadding(dp(24), dp(24), dp(24), dp(22));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(4);
		pDns.addView(hero, lp);

		JSONObject tn = st.optJSONObject("tailnet");
		String name = tn == null ? "" : tn.optString("name", "").trim();
		boolean magic = dnsData != null && dnsData.error == null
				? dnsData.magicDns
				: tn != null && tn.optBoolean("magicDNS", false);

		LinearLayout top = new LinearLayout(this);
		top.setOrientation(LinearLayout.HORIZONTAL);
		top.setGravity(Gravity.CENTER_VERTICAL);

		LinearLayout titles = col();
		titles.addView(Ui.eyebrow(this, getString(R.string.page_dns)));
		TextView title = Ui.label(this,
				name.isEmpty() ? getString(R.string.dns_hero_fallback_title) : name,
				26f, Theme.text);
		title.setTypeface(null, android.graphics.Typeface.BOLD);
		title.setLetterSpacing(-0.035f);
		title.setSingleLine(true);
		title.setEllipsize(android.text.TextUtils.TruncateAt.END);
		title.setPadding(0, dp(2), 0, 0);
		titles.addView(title);
		top.addView(titles, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		top.addView(Ui.iconTile(this, R.drawable.ic_globe,
				Theme.accentDim, Theme.accent, 50),
				new LinearLayout.LayoutParams(dp(50), dp(50)));
		hero.addView(top);

		TextView body = Ui.label(this, getString(R.string.dns_hero_body), 13.5f, Theme.text2);
		body.setLineSpacing(dp(5), 1f);
		body.setPadding(0, dp(14), 0, 0);
		hero.addView(body);

		LinearLayout chips = new LinearLayout(this);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setGravity(Gravity.CENTER_VERTICAL);
		chips.setPadding(0, dp(16), 0, 0);
		chips.addView(statusChip(getString(magic ? R.string.dns_magic_on
						: R.string.dns_magic_off),
				magic ? Theme.successContainer : Theme.withAlpha(Theme.error, 0x1F),
				magic ? Theme.success : Theme.text2,
				magic ? Theme.success : Theme.hint));

		if (dnsData != null && dnsData.error == null) {
			int n = dnsData.nameservers.size();
			String label = n == 0 ? getString(R.string.dns_ns_none)
					: n == 1 ? getString(R.string.dns_ns_one)
					: getString(R.string.dns_ns_many, n);
			LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			alp.leftMargin = dp(8);
			chips.addView(statusChip(label, Theme.secondaryContainer,
					Theme.onSecondaryContainer, Theme.secondary), alp);
		} else if (!wired) {
			LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			alp.leftMargin = dp(8);
			chips.addView(statusChip(getString(R.string.dns_not_linked),
					Theme.surfaceContainerHighest, Theme.text2, Theme.hint), alp);
		}
		hero.addView(chips);

		Ui.stagger(top, body, chips);
	}

	/** Read the tailnet's DNS once per minute, or when forced. */
	private void maybeLoadDns(boolean force) {
		if (!CloudApi.connected(this) || dnsLoading) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!force && dnsData != null && now - dnsData.at < 60_000) {
			return;
		}
		final int epoch = ++dnsEpoch;
		dnsLoading = true;
		new Thread(() -> {
			DnsData next;
			try {
				CloudApi.Snapshot s = CloudApi.snapshot(this);
				next = new DnsData(s.nameservers, s.searchPaths, s.magicDns, null,
						System.currentTimeMillis());
			} catch (Exception e) {
				next = new DnsData(new ArrayList<>(), new ArrayList<>(), false,
						cloudMessage(e), System.currentTimeMillis());
			}
			final DnsData done = next;
			ui.post(() -> {
				if (epoch != dnsEpoch) {
					return;
				}
				dnsLoading = false;
				dnsData = done;
				dirty[1] = true;
				if (currentTab == 1 && status != null) {
					buildDns(status);
				}
			});
		}).start();
	}

	private static String cloudMessage(Exception e) {
		String m = e.getMessage();
		return m == null || m.isEmpty() ? "unknown error" : m;
	}

	private void dnsErrorCard(String why) {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(dp(18), dp(18), dp(18), dp(16));
		box.setBackground(Ui.outlined(this,
				Theme.mix(Theme.surfaceContainer, Theme.errorContainer,
						Theme.isDark ? 0.34f : 0.26f),
				Theme.withAlpha(Theme.error, 0x38), 22f, 1f));
		box.setClipToOutline(true);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(18);
		pDns.addView(box, lp);

		LinearLayout head = new LinearLayout(this);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setGravity(Gravity.CENTER_VERTICAL);
		head.addView(Ui.iconTile(this, R.drawable.ic_warn,
				Theme.withAlpha(Theme.error, 0x1F), Theme.error, 40), tileParams());
		TextView t = Ui.label(this, getString(R.string.dns_error, why), 13.5f, Theme.text);
		t.setLineSpacing(dp(3), 1f);
		head.addView(t, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		box.addView(head);

		LinearLayout acts = new LinearLayout(this);
		acts.setOrientation(LinearLayout.HORIZONTAL);
		acts.setPadding(0, dp(14), 0, 0);
		TextView retry = Ui.tonal(this, getString(R.string.dns_retry));
		retry.setOnClickListener(v -> {
			dnsData = null;
			dirty[1] = true;
			if (status != null) {
				buildDns(status);
			}
			maybeLoadDns(true);
		});
		acts.addView(retry, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
		box.addView(acts);
	}

	private void dnsLoadingCard() {
		LinearLayout g = group(pDns, getString(R.string.dns_nameservers_group));
		LinearLayout row = row();
		row.addView(Ui.iconTile(this, R.drawable.ic_globe,
				Theme.accentDim, Theme.accent, 40), tileParams());
		TextView t = Ui.label(this, getString(R.string.dns_loading), 14.5f, Theme.text2);
		row.addView(t);
		g.addView(row);
	}

	/** MagicDNS: one switch, written straight to the control plane. */
	private void dnsMagicCard() {
		LinearLayout g = group(pDns, getString(R.string.dns_magic_group));
		// The control plane refuses to keep MagicDNS on with no nameserver to
		// answer for it. Rather than let the switch fail, say what it needs.
		boolean ready = !dnsData.nameservers.isEmpty();
		Ui.Toggle sw = addSwitch(g, R.drawable.ic_shield, getString(R.string.dns_magic_row),
				getString(ready ? R.string.dns_magic_row_sub
						: R.string.dns_magic_needs_ns),
				dnsData.magicDns, ready ? this::writeMagicDns : null);
		setRowEnabled(sw, ready);
		switches.put("magicdns", sw);
	}

	private void dnsNameserverCard() {
		int n = dnsData.nameservers.size();
		String trailing = n == 0 ? null : String.valueOf(n);
		LinearLayout g = group(pDns, getString(R.string.dns_nameservers_group), trailing);

		LinearLayout intro = new LinearLayout(this);
		intro.setOrientation(LinearLayout.VERTICAL);
		intro.setPadding(dp(18), dp(14), dp(18), dp(2));
		TextView sub = Ui.label(this, getString(R.string.dns_nameservers_sub), 12.5f,
				Theme.text2);
		sub.setLineSpacing(dp(3), 1f);
		intro.addView(sub);
		g.addView(intro);

		if (n == 0) {
			LinearLayout empty = new LinearLayout(this);
			empty.setOrientation(LinearLayout.HORIZONTAL);
			empty.setGravity(Gravity.CENTER_VERTICAL);
			empty.setPadding(dp(18), dp(10), dp(18), dp(14));
			empty.addView(Ui.iconTile(this, R.drawable.ic_info,
					Theme.surfaceContainerHighest, Theme.hint, 34), tileParams());
			TextView t = Ui.label(this, getString(R.string.dns_nameservers_empty), 12.5f,
					Theme.hint);
			t.setLineSpacing(dp(3), 1f);
			empty.addView(t, new LinearLayout.LayoutParams(0,
					ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
			g.addView(empty);
		}

		for (final String server : dnsData.nameservers) {
			line(g);
			g.addView(dnsEntryRow(R.drawable.ic_globe, server, providerOf(server),
					v -> confirmRemoveNameserver(server)));
		}

		action(g, R.drawable.ic_plus, getString(R.string.dns_add_nameserver),
				getString(R.string.dns_add_nameserver_sub), v -> showAddNameserver());
	}

	private void dnsSearchCard() {
		int n = dnsData.searchPaths.size();
		LinearLayout g = group(pDns, getString(R.string.dns_search_group),
				n == 0 ? null : String.valueOf(n));

		LinearLayout intro = new LinearLayout(this);
		intro.setOrientation(LinearLayout.VERTICAL);
		intro.setPadding(dp(18), dp(14), dp(18), dp(2));
		TextView sub = Ui.label(this, getString(R.string.dns_search_sub), 12.5f,
				Theme.text2);
		sub.setLineSpacing(dp(3), 1f);
		intro.addView(sub);
		g.addView(intro);

		if (n == 0) {
			TextView empty = Ui.label(this, getString(R.string.dns_search_empty), 12.5f,
					Theme.hint);
			empty.setPadding(dp(18), dp(8), dp(18), dp(12));
			g.addView(empty);
		}

		for (final String domain : dnsData.searchPaths) {
			line(g);
			g.addView(dnsEntryRow(R.drawable.ic_route, domain, null,
					v -> confirmRemoveSearch(domain)));
		}

		action(g, R.drawable.ic_plus, getString(R.string.dns_add_search),
				getString(R.string.dns_add_search_sub), v -> showAddSearch());
	}

	/** One nameserver or search domain: value, where it comes from, remove. */
	private View dnsEntryRow(int icon, String value, String sub,
			View.OnClickListener remove) {
		LinearLayout row = row();
		row.addView(Ui.iconTile(this, icon,
				Theme.accentDim, Theme.accent, 40), tileParams());

		LinearLayout col = col();
		TextView t = Ui.label(this, value, 14.5f, Theme.text);
		t.setTypeface(android.graphics.Typeface.MONOSPACE,
				android.graphics.Typeface.BOLD);
		t.setSingleLine(true);
		t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		col.addView(t);
		if (sub != null && !sub.isEmpty()) {
			TextView s = Ui.label(this, sub, 12f, Theme.text2);
			s.setPadding(0, dp(3), 0, 0);
			col.addView(s);
		}
		row.addView(col, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		View del = iconButton(R.drawable.ic_trash, Theme.hint, R.string.dns_remove, remove);
		LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(40), dp(40));
		dlp.leftMargin = dp(6);
		row.addView(del, dlp);
		return row;
	}

	private View dnsNameRow(String name) {
		// A plate inside the device card: the tailnet name is a value to
		// copy, not a control to toggle.
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.HORIZONTAL);
		box.setGravity(Gravity.CENTER_VERTICAL);
		box.setPadding(dp(14), dp(13), dp(10), dp(13));
		box.setBackground(Ui.inset(this));
		box.setClipToOutline(true);

		LinearLayout col = col();
		TextView t = Ui.label(this, getString(R.string.dns_tailnet_name), 13f,
				Theme.text2);
		col.addView(t);
		TextView value = Ui.label(this, name, 16f, Theme.text);
		value.setTypeface(android.graphics.Typeface.MONOSPACE,
				android.graphics.Typeface.BOLD);
		value.setSingleLine(true);
		value.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		value.setPadding(0, dp(4), 0, 0);
		col.addView(value);
		box.addView(col, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		View cp = iconButton(R.drawable.ic_copy, Theme.accent, R.string.copy,
				v -> copy(name));
		box.addView(cp, new LinearLayout.LayoutParams(dp(40), dp(40)));
		return box;
	}

	/** Which provider an address or DoH URL belongs to, when it is obvious. */
	private String providerOf(String server) {
		String s = server.toLowerCase(java.util.Locale.ROOT);
		if (s.contains("nextdns")) {
			return getString(R.string.dns_provider_nextdns);
		}
		if (s.contains("cloudflare") || s.startsWith("1.1.1.1")
				|| s.startsWith("1.0.0.1")) {
			return getString(R.string.dns_provider_cloudflare);
		}
		if (s.contains("dns.google") || s.startsWith("8.8.8.8")
				|| s.startsWith("8.8.4.4")) {
			return getString(R.string.dns_provider_google);
		}
		if (s.contains("quad9") || s.startsWith("9.9.9.9")
				|| s.startsWith("149.112.112.112")) {
			return getString(R.string.dns_provider_quad9);
		}
		if (s.contains("adguard") || s.startsWith("94.140.")) {
			return getString(R.string.dns_provider_adguard);
		}
		return getString(R.string.dns_provider_custom);
	}

	private void dnsAccountCard() {
		LinearLayout g = group(pDns, getString(R.string.dns_account_group));
		LinearLayout row = row();
		row.addView(Ui.iconTile(this, R.drawable.ic_key,
				Theme.withAlpha(Theme.success, 0x1F), Theme.success, 40), tileParams());

		LinearLayout col = col();
		TextView t = Ui.label(this, getString(R.string.dns_connected_as, maskedToken()),
				14.5f, Theme.text);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		t.setSingleLine(true);
		col.addView(t);
		TextView s = Ui.label(this, getString(R.string.dns_connected_named), 12f, Theme.text2);
		s.setPadding(0, dp(3), 0, 0);
		col.addView(s);
		row.addView(col, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		g.addView(row);

		action(g, R.drawable.ic_logout, getString(R.string.dns_disconnect),
				getString(R.string.dns_disconnect_sub), v -> confirmDisconnect());
	}

	private String maskedToken() {
		return CloudApi.masked(this);
	}

	/** The card that turns a read-only page into a full DNS console. */
	private void dnsConnectCard() {
		LinearLayout g = group(pDns, getString(R.string.dns_connect_title));

		LinearLayout head = new LinearLayout(this);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setPadding(dp(18), dp(16), dp(18), dp(6));
		head.addView(Ui.iconTile(this, R.drawable.ic_key,
				Theme.accentDim, Theme.accent, 42), tileParams());
		LinearLayout hc = col();
		TextView body = Ui.label(this, getString(R.string.dns_connect_body), 13f,
				Theme.text2);
		body.setLineSpacing(dp(4), 1f);
		hc.addView(body);
		head.addView(hc, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		g.addView(head);

		g.addView(dnsStep(1, getString(R.string.dns_step_1)));
		g.addView(dnsStep(2, getString(R.string.dns_step_2)));
		g.addView(dnsStep(3, getString(R.string.dns_step_3)));

		LinearLayout entry = new LinearLayout(this);
		entry.setOrientation(LinearLayout.HORIZONTAL);
		entry.setGravity(Gravity.CENTER_VERTICAL);
		entry.setPadding(dp(18), dp(16), dp(18), dp(18));

		final EditText in = input(getString(R.string.dns_token_label),
				getString(R.string.dns_token_hint));
		in.setTypeface(android.graphics.Typeface.MONOSPACE);
		in.setTextSize(13.5f);
		entry.addView(in, new LinearLayout.LayoutParams(0, dp(48), 1f));

		final TextView connect = Ui.button(this, getString(R.string.dns_token_apply), true);
		connect.setOnClickListener(v -> connectTailnet(in, connect));
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
		blp.leftMargin = dp(10);
		entry.addView(connect, blp);
		g.addView(entry);

		action(g, R.drawable.ic_globe, getString(R.string.dns_create_token),
				getString(R.string.dns_token_link_sub), v -> open(TOKEN_URL));
	}

	/** A numbered instruction: a disc, then the sentence beside it. */
	private View dnsStep(int n, String text) {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setPadding(dp(18), dp(6), dp(18), dp(6));

		TextView num = Ui.label(this, String.valueOf(n), 11.5f, Theme.onSecondaryContainer);
		num.setTypeface(null, android.graphics.Typeface.BOLD);
		num.setGravity(Gravity.CENTER);
		num.setBackground(Ui.rounded(this, Theme.secondaryContainer, 999f));
		row.addView(num, new LinearLayout.LayoutParams(dp(22), dp(22)));

		TextView t = Ui.label(this, text, 12.5f, Theme.text2);
		t.setLineSpacing(dp(3), 1f);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		lp.leftMargin = dp(12);
		row.addView(t, lp);
		return row;
	}

	private EditText input(String hint) {
		return input("", hint);
	}

	private EditText input(String label, String hint) {
		EditText in = new EditText(this);
		in.setHint(hint);
		in.setSingleLine(true);
		in.setTextColor(Theme.text);
		in.setHintTextColor(Theme.hint);
		in.setTextSize(14f);
		in.setGravity(Gravity.CENTER_VERTICAL);
		in.setPadding(dp(14), 0, dp(14), 0);
		in.setBackground(Ui.fieldBg(this));
		in.setContentDescription(label);
		return in;
	}

	private void connectTailnet(EditText in, TextView button) {
		final String token = in.getText().toString().trim();
		if (token.isEmpty()) {
			toast(getString(R.string.dns_token_empty));
			return;
		}
		button.setEnabled(false);
		button.setAlpha(0.5f);
		button.setText(getString(R.string.checking));
		new Thread(() -> {
			// Check before storing. Saving first and failing later leaves a
			// dead token on disk that makes the page look connected while
			// every read errors.
			CloudApi.save(this, token);
			String why = null;
			try {
				CloudApi.verify(this);
			} catch (Exception e) {
				why = cloudMessage(e);
			}
			final String fail = why;
			ui.post(() -> {
				button.setEnabled(true);
				button.setAlpha(1f);
				button.setText(getString(R.string.dns_token_apply));
				if (fail != null) {
					CloudApi.forget(this);
					toast(getString(R.string.dns_token_bad, fail));
					return;
				}
				toast(getString(R.string.dns_token_ok));
				dnsData = null;
				dirty[1] = true;
				if (status != null && currentTab == 1) {
					buildDns(status);
				}
				maybeLoadDns(true);
			});
		}).start();
	}

	private void writeMagicDns(boolean on) {
		new Thread(() -> {
			try {
				CloudApi.setMagicDns(this, on);
				ui.post(() -> {
					if (dnsData != null) {
						dnsData = new DnsData(dnsData.nameservers,
								dnsData.searchPaths, on, null,
								System.currentTimeMillis());
					}
					toast(getString(R.string.dns_saved));
					refreshDnsPage();
				});
			} catch (Exception e) {
				final String why = cloudMessage(e);
				ui.post(() -> {
					toast(getString(R.string.dns_save_failed, why));
					dnsData = null;
					refreshDnsPage();
					maybeLoadDns(true);
				});
			}
		}).start();
	}

	private void refreshDnsPage() {
		dirty[1] = true;
		if (currentTab == 1 && status != null) {
			buildDns(status);
		}
	}

	private void writeNameservers(List<String> values) {
		final List<String> out = new ArrayList<>(values);
		new Thread(() -> {
			try {
				CloudApi.setNameservers(this, out);
				ui.post(() -> {
					toast(getString(R.string.dns_saved));
					dnsData = null;
					refreshDnsPage();
					maybeLoadDns(true);
				});
			} catch (Exception e) {
				final String why = cloudMessage(e);
				ui.post(() -> toast(getString(R.string.dns_save_failed, why)));
			}
		}).start();
	}

	private void writeSearchPaths(List<String> values) {
		final List<String> out = new ArrayList<>(values);
		new Thread(() -> {
			try {
				CloudApi.setSearchPaths(this, out);
				ui.post(() -> {
					toast(getString(R.string.dns_saved));
					dnsData = null;
					refreshDnsPage();
					maybeLoadDns(true);
				});
			} catch (Exception e) {
				final String why = cloudMessage(e);
				ui.post(() -> toast(getString(R.string.dns_save_failed, why)));
			}
		}).start();
	}

	private void confirmRemoveNameserver(final String server) {
		if (dnsData == null) {
			return;
		}
		Sheet.card(this)
				.title(getString(R.string.dns_remove_ns_title))
				.message(getString(R.string.dns_remove_ns_body, server))
				.danger(getString(R.string.dns_remove), () -> {
					List<String> next = new ArrayList<>(dnsData.nameservers);
					next.remove(server);
					writeNameservers(next);
				})
				.secondary(getString(android.R.string.cancel))
				.show();
	}

	private void confirmRemoveSearch(final String domain) {
		if (dnsData == null) {
			return;
		}
		Sheet.card(this)
				.title(getString(R.string.dns_remove_sp_title))
				.message(getString(R.string.dns_remove_sp_body, domain))
				.danger(getString(R.string.dns_remove), () -> {
					List<String> next = new ArrayList<>(dnsData.searchPaths);
					next.remove(domain);
					writeSearchPaths(next);
				})
				.secondary(getString(android.R.string.cancel))
				.show();
	}

	private void confirmDisconnect() {
		Sheet.card(this)
				.title(getString(R.string.dns_disconnect_title))
				.message(getString(R.string.dns_disconnect_body))
				.danger(getString(R.string.dns_disconnect), () -> {
					CloudApi.forget(this);
					// Invalidate any read that is still in flight: it was
					// made with the token that no longer exists.
					dnsEpoch++;
					dnsLoading = false;
					dnsData = null;
					toast(getString(R.string.dns_disconnect_done));
					refreshDnsPage();
				})
				.secondary(getString(android.R.string.cancel))
				.show();
	}

	/**
	 * The add sheet: a field, the five providers most people want, and a
	 * validation that keeps the button honest.
	 */
	private void showAddNameserver() {
		final Sheet s = Sheet.bottom(this)
				.title(getString(R.string.dns_add_nameserver))
				.subtitle(getString(R.string.dns_add_nameserver_sub));

		final EditText in = input(getString(R.string.dns_field_ns));
		LinearLayout fieldWrap = new LinearLayout(this);
		fieldWrap.setPadding(0, dp(16), 0, 0);
		fieldWrap.addView(in, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
		s.add(fieldWrap);

		TextView presets = Ui.eyebrow(this, getString(R.string.dns_preset_label));
		presets.setPadding(0, dp(20), 0, 0);
		s.add(presets);

		HorizontalScrollView hsv = new HorizontalScrollView(this);
		hsv.setHorizontalScrollBarEnabled(false);
		hsv.setClipToPadding(false);
		LinearLayout chips = new LinearLayout(this);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setPadding(0, dp(10), 0, 0);
		for (int i = 0; i < DNS_PRESET_LABELS.length; i++) {
			final String value = DNS_PRESET_VALUES[i];
			TextView chip = Ui.label(this, getString(DNS_PRESET_LABELS[i]),
					13f, Theme.onSecondaryContainer);
			chip.setTypeface(null, android.graphics.Typeface.BOLD);
			chip.setSingleLine(true);
			chip.setGravity(Gravity.CENTER);
			chip.setPadding(dp(16), dp(10), dp(16), dp(10));
			chip.setBackground(Ui.ripple(this,
					Ui.rounded(this, Theme.secondaryContainer, 999f),
					Theme.withAlpha(Theme.onSecondaryContainer, 0x33), null));
			chip.setClickable(true);
			chip.setFocusable(true);
			chip.setOnClickListener(v -> {
				in.setText(value);
				in.setSelection(value.length());
			});
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			lp.rightMargin = dp(8);
			chips.addView(chip, lp);
		}
		hsv.addView(chips);
		s.add(hsv);

		final TextView err = Ui.label(this, "", 12f, Theme.error);
		err.setVisibility(View.GONE);
		err.setPadding(0, dp(12), 0, 0);
		s.add(err);

		s.primaryKeep(getString(R.string.dns_add), () -> {
			List<String> add = parseServers(in.getText().toString());
			if (add.isEmpty()) {
				err.setText(getString(R.string.dns_invalid_ns));
				err.setVisibility(View.VISIBLE);
				Ui.pop(in);
				return;
			}
			s.dismiss();
			List<String> next = new ArrayList<>(dnsData == null
					? new ArrayList<>() : dnsData.nameservers);
			for (String a : add) {
				boolean dup = false;
				for (String e : next) {
					if (e.equalsIgnoreCase(a)) {
						dup = true;
						break;
					}
				}
				if (!dup) {
					next.add(a);
				}
			}
			writeNameservers(next);
		});
		s.secondary(getString(android.R.string.cancel));
		s.show();
	}

	private void showAddSearch() {
		final Sheet s = Sheet.bottom(this)
				.title(getString(R.string.dns_add_search))
				.subtitle(getString(R.string.dns_add_search_sub));

		final EditText in = input(getString(R.string.dns_field_search));
		LinearLayout fieldWrap = new LinearLayout(this);
		fieldWrap.setPadding(0, dp(16), 0, 0);
		fieldWrap.addView(in, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
		s.add(fieldWrap);

		final TextView err = Ui.label(this, "", 12f, Theme.error);
		err.setVisibility(View.GONE);
		err.setPadding(0, dp(12), 0, 0);
		s.add(err);

		s.primaryKeep(getString(R.string.dns_add), () -> {
			String d = in.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
			while (d.startsWith(".")) {
				d = d.substring(1);
			}
			if (!validDomain(d)) {
				err.setText(getString(R.string.dns_invalid_domain));
				err.setVisibility(View.VISIBLE);
				Ui.pop(in);
				return;
			}
			s.dismiss();
			List<String> next = new ArrayList<>(dnsData == null
					? new ArrayList<>() : dnsData.searchPaths);
			for (String e : next) {
				if (e.equalsIgnoreCase(d)) {
					err.setText(getString(R.string.dns_dup));
					err.setVisibility(View.VISIBLE);
					return;
				}
			}
			next.add(d);
			writeSearchPaths(next);
		});
		s.secondary(getString(android.R.string.cancel));
		s.show();
	}

	/** Split "1.1.1.1, 1.0.0.1" into entries; reject anything unpasteable. */
	private List<String> parseServers(String raw) {
		List<String> out = new ArrayList<>();
		for (String part : raw.split("[,\\s]+")) {
			String v = part.trim();
			if (v.isEmpty()) {
				continue;
			}
			// A DoH URL pasted from a provider page often ends in a slash.
			// The control plane accepts either, so normalise instead of
			// refusing what the user just copied.
			while (v.length() > 1 && v.endsWith("/")) {
				v = v.substring(0, v.length() - 1);
			}
			if (!validServer(v)) {
				return new ArrayList<>();
			}
			out.add(v);
		}
		return out;
	}

	private static boolean validServer(String v) {
		if (v.length() > 255 || v.endsWith("/")) {
			return false;
		}
		// IP literal, hostname, or a DoH URL. The control plane does the real
		// validation; this only catches the field being filled with prose.
		return v.matches("^[A-Za-z0-9](?:[A-Za-z0-9._:/-]*[A-Za-z0-9])?$")
				&& v.contains(".");
	}

	private static boolean validDomain(String d) {
		return d.length() > 1 && d.length() < 254
				&& d.matches("^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$");
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
		addSwitch(d, R.drawable.ic_power, getString(R.string.service),
				getString(R.string.service_sub), daemonUp(st),
				on -> service(on ? "start" : "stop"));
		action(d, R.drawable.ic_refresh, getString(R.string.restart), null,
				v -> service("restart"));
		View supRow = action(d, R.drawable.ic_heart, getString(R.string.supervisor),
				supervisorUp(st) ? getString(R.string.supervisor_running)
						: getString(R.string.supervisor_stopped), null);
		supervisorSub = supRow.findViewById(R.id.rowSub);
		action(d, R.drawable.ic_globe, getString(R.string.console), null,
				v -> open(CONSOLE_URL));

		LinearLayout c = group(pSystem, getString(R.string.this_device));
		value(c, R.drawable.ic_chip, getString(R.string.backend),
				st.optString("backend", "?"));
		JSONObject self = st.optJSONObject("self");
		if (self != null) {
			TextView nv = value(c, R.drawable.ic_device, getString(R.string.device_name),
					self.optString("host", "\u2014"));
			nameValues.add(nv);
			nameShown.add(self.optString("host", "\u2014"));
			JSONArray ips = self.optJSONArray("tailscaleIPs");
			if (ips != null) {
				for (int i = 0; i < ips.length(); i++) {
					final String ip = ips.optString(i);
					copyRow(c, R.drawable.ic_globe, i == 0 ? getString(R.string.ipv4)
							: getString(R.string.ipv6), ip);
				}
			}
			String relay = self.optString("relay", "");
			value(c, R.drawable.ic_route, getString(R.string.relay),
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
							dirty[3] = true;
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
		value(a1, R.drawable.ic_person, getString(R.string.tailnet), account.isEmpty()
				? getString(R.string.not_signed_in) : account);
		if (account.isEmpty()) {
			action(a1, R.drawable.ic_logout, getString(R.string.sign_in), null,
					v -> doLogin());
		} else {
			action(a1, R.drawable.ic_logout, getString(R.string.sign_out), null,
					v -> confirmSignOut());
		}

		LinearLayout u = group(pSystem, getString(R.string.software));
		value(u, R.drawable.ic_terminal, getString(R.string.tailscaled_version),
				st.optString("version", "?"));
		value(u, R.drawable.ic_settings, getString(R.string.module_version),
				st.optString("module", "?"));

		updateRow = action(u, R.drawable.ic_download, getString(R.string.check_updates),
				getString(R.string.update_check_hint), v -> updateNow());
		updateTitle = updateRow.findViewById(R.id.rowTitle);
		updateSub = updateRow.findViewById(R.id.rowSub);
		paintUpdateRow();

		if (receivedCount > 0) {
			action(u, R.drawable.ic_folder, getString(R.string.received_files_row,
					receivedCount), getString(R.string.taildrop_folder_sub),
					v -> openTaildropFolder());
		} else {
			value(u, R.drawable.ic_folder, getString(R.string.received_files_row_none),
					getString(R.string.taildrop_folder_sub));
		}
		action(u, R.drawable.ic_history, getString(R.string.rollback), null, v -> rollback());
		action(u, R.drawable.ic_list, getString(R.string.logs), null, v -> showLogs());

		buildWirelessAdb(st);

		LinearLayout a = group(pSystem, getString(R.string.about));
		action(a, R.drawable.ic_globe, getString(R.string.github_link), null,
				v -> open(GITHUB_URL));
		note(pSystem, getString(R.string.credits_body));
	}

	/**
	 * Wireless debugging, with the warning treated like one.
	 *
	 * <p>The old version ended the card in a single small grey paragraph,
	 * which is exactly what a warning should never be: the least visible
	 * text on the page describing the most dangerous switch on it. Now the
	 * port, the command and the risk are three full-size blocks.
	 */
	private void buildWirelessAdb(JSONObject st) {
		LinearLayout w = group(pSystem, getString(R.string.wireless_adb_group));
		addSwitch(w, R.drawable.ic_wifi, getString(R.string.wireless_adb_switch),
				getString(R.string.wireless_adb_sub), adbWireless,
				on -> confirmWirelessAdb(on));

		String ip = tsIpv4(st);
		if (adbWireless && !ip.isEmpty()) {
			w.addView(adbPanel(ip), insetBlockParams());
		} else if (!adbWireless) {
			w.addView(adbOffNote(), insetBlockParams());
		}
	}

	private LinearLayout.LayoutParams insetBlockParams() {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.leftMargin = dp(18);
		lp.rightMargin = dp(18);
		lp.bottomMargin = dp(18);
		return lp;
	}

	private View adbOffNote() {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.HORIZONTAL);
		box.setGravity(Gravity.CENTER_VERTICAL);
		box.setPadding(dp(16), dp(15), dp(16), dp(15));
		box.setBackground(Ui.outlined(this, Theme.surfaceContainerHigh,
				Theme.hairline, 18f, 1f));
		box.setClipToOutline(true);

		box.addView(Ui.iconTile(this, R.drawable.ic_shield,
				Theme.withAlpha(Theme.success, 0x1F), Theme.success, 40), tileParams());

		LinearLayout col = col();
		TextView t = Ui.label(this, getString(R.string.adb_off_title), 14.5f, Theme.text);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		col.addView(t);
		TextView b = Ui.label(this, getString(R.string.adb_off_body), 12.5f, Theme.text2);
		b.setLineSpacing(dp(3), 1f);
		b.setPadding(0, dp(3), 0, 0);
		col.addView(b);
		box.addView(col, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		return box;
	}

	private View adbPanel(String ip) {
		final String address = ip + ":5555";
		final String command = "adb connect " + address;

		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(dp(16), dp(16), dp(16), dp(16));
		box.setBackground(Ui.outlined(this,
				Theme.mix(Theme.surfaceContainerHigh, Theme.errorContainer,
						Theme.isDark ? 0.30f : 0.22f),
				Theme.withAlpha(Theme.error, 0x40), 18f, 1f));
		box.setClipToOutline(true);

		LinearLayout head = new LinearLayout(this);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setGravity(Gravity.CENTER_VERTICAL);
		head.addView(Ui.iconTile(this, R.drawable.ic_warn,
				Theme.withAlpha(Theme.error, 0x1F), Theme.error, 42), tileParams());

		LinearLayout hc = col();
		TextView t = Ui.label(this, getString(R.string.adb_risk_title), 15.5f, Theme.error);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		hc.addView(t);
		TextView b = Ui.label(this, getString(R.string.adb_risk_body), 13f, Theme.text2);
		b.setLineSpacing(dp(4), 1f);
		b.setPadding(0, dp(4), 0, 0);
		hc.addView(b);
		head.addView(hc, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		box.addView(head);

		// The address and the exact command: what you need, at a size you
		// can read while holding the phone at arm's length.
		box.addView(Ui.eyebrow(this, getString(R.string.adb_address_eyebrow)),
				blockGap(16));

		LinearLayout addrRow = new LinearLayout(this);
		addrRow.setOrientation(LinearLayout.HORIZONTAL);
		addrRow.setGravity(Gravity.CENTER_VERTICAL);
		addrRow.setPadding(dp(14), dp(10), dp(8), dp(10));
		addrRow.setBackground(Ui.fieldBg(this));

		TextView addr = Ui.label(this, address, 17f, Theme.text);
		addr.setTypeface(android.graphics.Typeface.MONOSPACE,
				android.graphics.Typeface.BOLD);
		addr.setLetterSpacing(0.01f);
		addrRow.addView(addr, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		View copyAddr = iconButton(R.drawable.ic_copy, Theme.accent,
				R.string.wireless_adb_copy, v -> copy(address));
		addrRow.addView(copyAddr, new LinearLayout.LayoutParams(dp(40), dp(40)));
		box.addView(addrRow, blockGap(6));

		LinearLayout cmdRow = new LinearLayout(this);
		cmdRow.setOrientation(LinearLayout.HORIZONTAL);
		cmdRow.setGravity(Gravity.CENTER_VERTICAL);
		cmdRow.setPadding(dp(14), dp(10), dp(8), dp(10));
		cmdRow.setBackground(Ui.fieldBg(this));

		TextView cmd = Ui.label(this, command, 12.5f, Theme.text2);
		cmd.setTypeface(android.graphics.Typeface.MONOSPACE, 0);
		cmd.setSingleLine(true);
		cmd.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		cmdRow.addView(cmd, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		View copyCmd = iconButton(R.drawable.ic_copy, Theme.text2,
				R.string.wireless_adb_copy_command, v -> copy(command));
		cmdRow.addView(copyCmd, new LinearLayout.LayoutParams(dp(40), dp(40)));
		box.addView(cmdRow, blockGap(8));

		TextView done = Ui.label(this, getString(R.string.adb_turn_off_hint),
				12f, Theme.hint);
		done.setPadding(0, dp(10), 0, 0);
		box.addView(done);
		return box;
	}

	private LinearLayout.LayoutParams blockGap(int topDp) {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(topDp);
		return lp;
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
		TextView b = Ui.label(this, label, 13f, Theme.onSecondaryContainer);
		b.setTypeface(null, android.graphics.Typeface.BOLD);
		b.setGravity(Gravity.CENTER);
		b.setMinHeight(dp(38));
		b.setPadding(dp(16), dp(9), dp(16), dp(9));
		b.setBackground(Ui.ripple(this,
				Ui.rounded(this, Theme.secondaryContainer, 999f),
				Theme.withAlpha(Theme.onSecondaryContainer, 0x33), null));
		b.setClickable(true);
		b.setFocusable(true);
		Ui.pressable(b);
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
		return group(parent, heading, null);
	}

	private LinearLayout group(LinearLayout parent, String heading, String trailing) {
		if (heading != null) {
			LinearLayout head = Ui.section(this, heading);
			if (trailing != null && !trailing.isEmpty()) {
				View gap = new View(this);
				head.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
				TextView tail = Ui.label(this, trailing, 12f, Theme.text2);
				tail.setTypeface(null, android.graphics.Typeface.BOLD);
				head.addView(tail);
			}
			parent.addView(head);
		} else {
			LinearLayout.LayoutParams s = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			s.topMargin = dp(18);
			parent.addView(new View(this), s);
		}

		LinearLayout g = new LinearLayout(this);
		g.setOrientation(LinearLayout.VERTICAL);
		g.setBackground(Ui.cardBg(this));
		g.setClipToOutline(true);
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
		lp.leftMargin = dp(18);
		lp.rightMargin = dp(18);
		g.addView(v, lp);
	}

	private TextView value(LinearLayout g, String label, String v) {
		return value(g, 0, label, v);
	}

	private TextView value(LinearLayout g, int icon, String label, String v) {
		line(g);
		LinearLayout row = row();

		if (icon != 0) {
			row.addView(Ui.iconTile(this, icon, Theme.accentDim, Theme.accent, 40),
					tileParams());
		}

		TextView l = Ui.label(this, label, 15f, Theme.text);
		l.setSingleLine(true);
		row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		TextView val = Ui.label(this, v, 13.5f, Theme.text2);
		val.setMaxLines(2);
		val.setGravity(Gravity.END);
		val.setEllipsize(android.text.TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		vlp.leftMargin = dp(14);
		row.addView(val, vlp);
		g.addView(row);
		return val;
	}

	private void copyRow(LinearLayout g, String label, String v) {
		copyRow(g, 0, label, v);
	}

	private void copyRow(LinearLayout g, int icon, String label, String v) {
		line(g);
		LinearLayout row = row();
		row.setBackground(Ui.press(this, 18f));
		row.setClickable(true);
		row.setOnClickListener(x -> copy(v));
		Ui.pressable(row);

		if (icon != 0) {
			row.addView(Ui.iconTile(this, icon, Theme.accentDim, Theme.accent, 40),
					tileParams());
		}

		TextView l = Ui.label(this, label, 15f, Theme.text);
		l.setSingleLine(true);
		row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		TextView val = Ui.label(this, v, 13.5f, Theme.text2);
		val.setSingleLine(true);
		val.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		row.addView(val);

		ImageView cp = new ImageView(this);
		cp.setImageResource(R.drawable.ic_copy);
		cp.setColorFilter(Theme.hint);
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(16), dp(16));
		clp.leftMargin = dp(12);
		row.addView(cp, clp);
		g.addView(row);
	}

	private View action(LinearLayout g, String title, String sub, View.OnClickListener l) {
		return action(g, 0, title, sub, l);
	}

	private View action(LinearLayout g, int icon, String title, String sub,
			View.OnClickListener l) {
		line(g);
		LinearLayout row = row();

		if (icon != 0) {
			row.addView(Ui.iconTile(this, icon, Theme.accentDim, Theme.accent, 40),
					tileParams());
		}

		LinearLayout col = col();
		TextView t = Ui.label(this, title, 15.5f, Theme.text);
		t.setId(R.id.rowTitle);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		t.setSingleLine(true);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		col.addView(t);

		if (sub != null && !sub.isEmpty()) {
			TextView s = Ui.label(this, sub, 12.5f, Theme.text2);
			s.setId(R.id.rowSub);
			s.setPadding(0, dp(3), 0, 0);
			s.setMaxLines(2);
			s.setLineSpacing(dp(2), 1f);
			col.addView(s);
		}

		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		if (l != null) {
			ImageView chev = new ImageView(this);
			chev.setImageResource(R.drawable.ic_chevron);
			chev.setColorFilter(Theme.hint);
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(17), dp(17));
			clp.leftMargin = dp(10);
			row.addView(chev, clp);
			row.setBackground(Ui.press(this, 18f));
			row.setClickable(true);
			row.setOnClickListener(l);
			Ui.pressable(row);
		}
		g.addView(row);
		return row;
	}

	private Ui.Toggle addSwitch(LinearLayout g, String title, String sub, boolean on,
			OnChecked l) {
		return addSwitch(g, 0, title, sub, on, l);
	}

	private Ui.Toggle addSwitch(LinearLayout g, int icon, String title, String sub, boolean on,
			OnChecked l) {
		line(g);
		LinearLayout row = row();

		if (icon != 0) {
			row.addView(Ui.iconTile(this, icon, Theme.accentDim, Theme.accent, 40),
					tileParams());
		}

		LinearLayout col = col();

		TextView t = Ui.label(this, title, 15.5f, Theme.text);
		t.setId(R.id.rowTitle);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		col.addView(t);

		if (sub != null && !sub.isEmpty()) {
			TextView s = Ui.label(this, sub, 12.5f, Theme.text2);
			s.setId(R.id.rowSub);
			s.setPadding(0, dp(3), 0, 0);
			s.setMaxLines(3);
			s.setLineSpacing(dp(2), 1f);
			col.addView(s);
		}
		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		Ui.Toggle sw = Ui.toggle(this, on);
		row.addView(sw);

		if (l != null) {
			sw.setOnCheckedChangeListener(l::on);
			row.setBackground(Ui.press(this, 18f));
			row.setClickable(true);
			// The whole row is a bigger target, but a disabled switch must stay
			// disabled: performClick() does not check enabled itself.
			row.setOnClickListener(v -> {
				if (sw.isEnabled()) {
					sw.performClick();
				}
			});
			Ui.pressable(row);
		}
		g.addView(row);
		return sw;
	}

	private LinearLayout.LayoutParams tileParams() {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
		lp.rightMargin = dp(14);
		return lp;
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
		row.setPadding(dp(18), dp(15), dp(18), dp(15));
		row.setMinimumHeight(dp(68));
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
		TextView t = Ui.label(this, text, 13f, Theme.hint);
		t.setLineSpacing(dp(4), 1f);
		t.setPadding(dp(6), dp(16), dp(6), 0);
		parent.addView(t);
	}

	private void toast(String msg) {
		Snack.show(this, msg);
	}

	private int dp(int v) {
		return (int) (v * getResources().getDisplayMetrics().density);
	}
}
