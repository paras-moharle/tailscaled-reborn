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
	private final View[] navs = new View[3];
	private int currentTab = 0;

	private Button sendBtn;
	/** row that offers the software update once a newer build is known. */
	private View updateRow;
	private TextView updateTitle;
	private TextView updateSub;

	private JSONObject status;
	private boolean polling;
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

	private ImageView pullSpinner;
	private int receivedCount;

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
		captureShare(launch);
		tab(0);
		checkRoot();
		askNotificationPermission();
		registerNetworkWatcher();
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
		currentTab = n;
		ScrollView[] scrolls = {
				findViewById(R.id.scrollSend),
				findViewById(R.id.scrollSettings),
				findViewById(R.id.scrollSystem),
		};
		for (int i = 0; i < scrolls.length; i++) {
			scrolls[i].setVisibility(i == n ? View.VISIBLE : View.GONE);
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
			View pill = navs[i].findViewById(R.id.navPill);
			if (pill != null) {
				pill.setVisibility(i == n ? View.VISIBLE : View.INVISIBLE);
			}
		}
	}


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

		List<Api.Peer> ps = Api.peersFrom(st);
		peers.clear();
		onlinePeers.clear();
		for (Api.Peer p : ps) {
			peers.put(p.name, p.ip);
			if (p.online) {
				onlinePeers.add(p.name);
			}
		}
		chosen.retainAll(onlinePeers);

		buildSend(ps);
		buildSettings(st);
		buildSystem(st);
	}


	private void refresh() {
		new Thread(() -> {
			JSONObject st = null;
			String err = null;
			try {
				st = Api.status();
			} catch (Api.ApiBroken e) {
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
				adbWireless = Api.adbWirelessOn(fSt);
				hidePullSpinner();
				if (fSt == null) {
					renderOffline(fErr);
				} else {
					render(fSt);
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
			setStatusColor(getColor(R.color.err));
			versionView.setText(getString(R.string.state_daemon_stopped) + "  ·  tailscaled "
					+ st.optString("version", "?"));
		} else if (!signedIn(st)) {
			setStatusColor(getColor(R.color.warn));
		} else if (!controlPlane) {
			setStatusColor(getColor(R.color.warn));
			versionView.setText(getString(R.string.state_no_internet) + "  ·  tailscaled "
					+ st.optString("version", "?"));
		} else {
			setStatusColor(getColor(R.color.ok));
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
							rebuildVisible();
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
			default:
				break;
		}
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

	private static boolean daemonUp(JSONObject st) {
		return st != null && "running".equals(st.optString("daemon", ""));
	}

	private static boolean supervisorUp(JSONObject st) {
		return st != null && "running".equals(st.optString("supervisor", ""));
	}

	private static boolean signedIn(JSONObject st) {
		return st != null && "Running".equals(st.optString("backend", ""));
	}


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

		if (!daemonUp(status)) {
			stoppedCard();
		} else if (!signedIn(status)) {
			signInCard();
		}

		JSONObject self = status == null ? null : status.optJSONObject("self");

		if (self != null && signedIn(status)) {
			LinearLayout g = group(pSend, getString(R.string.this_device));
			TextView nv = value(g, getString(R.string.device_name),
					self.optString("host", "\u2014"));
			nameValues.add(nv);
			nameShown.add(self.optString("host", "\u2014"));

			JSONArray ips = self.optJSONArray("tailscaleIPs");
			if (ips != null) {
				for (int i = 0; i < ips.length(); i++) {
					final String ip = ips.optString(i);
					copyRow(g, i == 0 ? getString(R.string.ipv4) : getString(R.string.ipv6), ip);
				}
			}
			String relay = self.optString("relay", "");
			value(g, getString(R.string.relay),
					relay.isEmpty() ? getString(R.string.direct) : relay);
		}

		LinearLayout t = group(pSend, getString(R.string.choose_devices));
		if (ps.isEmpty()) {
			plain(t, getString(R.string.no_peers_short));
		} else {
			for (Api.Peer p : ps) {
				line(t);
				t.addView(peerRow(p));
			}
		}

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

		if (!receivedChecked) {
			receivedChecked = true;
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
				}
			}).start();
		}

		LinearLayout d = group(pSend, getString(R.string.daemon));
		addSwitch(d, getString(R.string.service),
				getString(R.string.service_sub), daemonUp(status),
				v -> service(v.isChecked() ? "start" : "stop"));
		action(d, getString(R.string.restart), null, v -> service("restart"));
		View supRow = action(d, getString(R.string.supervisor),
				supervisorUp(status)
						? getString(R.string.supervisor_running)
						: getString(R.string.supervisor_stopped),
				null);
		supervisorSub = supRow.findViewById(R.id.rowSub);
		action(d, getString(R.string.console), null, v -> open(CONSOLE_URL));

		updateSendButton();
	}

	private boolean receivedChecked;

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
			TextView hint = new TextView(this);
			hint.setText(R.string.sign_in_needs_daemon);
			hint.setTextColor(getColor(R.color.hint));
			hint.setTextSize(12);
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

		ImageView dot = new ImageView(this);
		dot.setImageResource(R.drawable.dot);
		dot.setColorFilter(getColor(up ? R.color.ok : R.color.err));
		dot.setContentDescription(getString(up ? R.string.peer_online : R.string.peer_offline));
		LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
		dotLp.rightMargin = dp(10);
		row.addView(dot, dotLp);

		android.widget.Switch cb = toggle(up && chosen.contains(p.name));
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
		meta.setTextColor(getColor(R.color.text2));
		meta.setTextSize(12);
		col.addView(meta);
		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		slp.leftMargin = dp(4);
		row.addView(cb, slp);

		View send = iconButton(R.drawable.ic_send, up ? R.color.text : R.color.hint,
				R.string.send, v -> launchPicker(java.util.Collections.singletonList(p.ip)));
		send.setEnabled(up);
		send.setAlpha(up ? 1f : 0.35f);
		row.addView(send);

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
		sendBtn.setEnabled(signedIn(status) && n > 0);
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
		addToggle(c1, "accept-dns", getString(R.string.t_accept_dns), "", flags, online);
		addToggle(c1, "accept-routes", getString(R.string.t_accept_routes), "", flags, online);
		addToggle(c1, "shields-up", getString(R.string.t_shields_up), "", flags, online);

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

		LinearLayout c3 = group(pSettings, getString(R.string.this_device));
		JSONObject prefs = st.optJSONObject("prefs");
		LinearLayout hr = new LinearLayout(this);
		hr.setOrientation(LinearLayout.HORIZONTAL);
		hr.setGravity(Gravity.CENTER_VERTICAL);
		EditText in = new EditText(this);
		in.setHint(getString(R.string.hostname_hint));
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

		sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
			((android.widget.Switch) b).setThumbTintList(android.content.res.ColorStateList.valueOf(
					getColor(checked ? R.color.text : R.color.hint)));
			if (l != null) {
				l.on(b);
			}
		});
		if (l != null) {
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
}
