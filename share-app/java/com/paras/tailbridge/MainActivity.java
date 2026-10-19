// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
	private final Map<String, CheckBox> switches = new LinkedHashMap<>();

	/** Self-name / address values, patched in place by the poller. */
	private final List<TextView> nameValues = new ArrayList<>();
	private final List<String> nameShown = new ArrayList<>();

	private TextView statusView;
	private TextView versionView;
	private View dotView;

	private LinearLayout pSend;
	private LinearLayout pSettings;
	private LinearLayout pSystem;
	private final View[] navs = new View[3];

	private Button sendBtn;
	/** row that offers the software update once a newer build is known. */
	private View updateRow;
	private TextView updateTitle;
	private TextView updateSub;

	private JSONObject status;
	private boolean polling;
	private static final long POLL_MS = 5000;
	private volatile String latestVersion = "";

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
		// Worker threads post back into this Handler; anything still queued
		// would try to touch a window that no longer exists.
		ui.removeCallbacksAndMessages(null);
	}

	private void tab(int n) {
		ScrollView[] scrolls = {
				findViewById(R.id.scrollSend),
				findViewById(R.id.scrollSettings),
				findViewById(R.id.scrollSystem),
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
		}
	}

	/** Every dialog goes through here so they all share the app's surface. */
	private AlertDialog.Builder alert() {
		return new AlertDialog.Builder(this, R.style.Theme_Share_Dialog);
	}

	// ----------------------------------------------------------------- data

	/**
	 * The refresh button.
	 *
	 * <p>Forces a fresh read instead of re-rendering what is already held:
	 * peer presence, the daemon state and the address list all change on the
	 * device, not in this process, so only a new root round-trip can see it.
	 * Restarts the poll chain too, so the next automatic read is a full
	 * interval away rather than firing immediately on top of this one.
	 */
	/**
	 * Push the status-bar height into the header's top padding.
	 *
	 * <p>The layout carries a paddingTop as a floor, but a fixed number cannot
	 * be right: on this device the header sat under the clock. Reading the
	 * actual inset is the difference between a title that has room and one that
	 * fights the system bar, and it adapts to cutouts and gesture bars too.
	 */

	/**
	 * Pull down anywhere on a list to refresh.
	 *
	 * <p>Hand-rolled because this app has no AndroidX, and SwipeRefreshLayout is
	 * an AndroidX view: adding it for one gesture would mean the app depends on
	 * a library it currently has no use for. The gesture is small -- a downward
	 * drag past the threshold while the list is already at the top.
	 */
	/** Deliveries the receiver has exported, for the System tab. */
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

		final CharSequence[] labels = new CharSequence[ps.size()];
		final boolean[] enabled = new boolean[ps.size()];
		final boolean[] checked = new boolean[ps.size()];
		for (int i = 0; i < ps.size(); i++) {
			Api.Peer p = ps.get(i);
			enabled[i] = p.online;
			checked[i] = p.online && chosen.contains(p.name);
			labels[i] = (p.online ? "●  " : "○  ") + p.name
					+ (p.online ? "" : "  (" + getString(R.string.peer_offline_label) + ")");
		}

		// Per-row disable needs a ListAdapter whose isEnabled() is consulted by
		// ListView. setMultiChoiceItems() cannot take a custom adapter, so the
		// rows are built here: an offline device stays visible but cannot be
		// ticked, which is the point -- hiding it would make a machine that is
		// merely down look deleted.
		final boolean[] picked = checked.clone();
		// The row click handler below recolours the Send button through this,
		// rather than installing its own listener on the button -- which would
		// replace the dialog's and silently break sending.
		final android.widget.Button[] sendRef = new android.widget.Button[1];

		android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
			@Override
			public int getCount() {
				return labels.length;
			}

			@Override
			public Object getItem(int i) {
				return labels[i];
			}

			@Override
			public long getItemId(int i) {
				return i;
			}

			@Override
			public boolean isEnabled(int i) {
				return enabled[i];
			}

			@Override
			public android.view.View getView(int i, android.view.View convert,
					android.view.ViewGroup parent) {
				android.view.View v = convert;
				if (v == null) {
					v = new android.widget.CheckBox(MainActivity.this);
					android.widget.CheckBox cb = (android.widget.CheckBox) v;
					cb.setTextColor(getColor(enabled[i] ? R.color.text : R.color.hint));
					cb.setTextSize(16);
					cb.setPadding(dp(22), dp(14), dp(22), dp(14));
					cb.setOnClickListener(x -> {
						if (!enabled[i]) {
							return;
						}
						cb.setChecked(!cb.isChecked());
						picked[i] = cb.isChecked();
						if (sendRef[0] != null) {
							boolean any = false;
							for (int k = 0; k < picked.length; k++) {
								if (picked[k] && enabled[k]) {
									any = true;
									break;
								}
							}
							sendRef[0].setTextColor(getColor(
									any ? R.color.accent : R.color.hint));
						}
					});
					v = cb;
				}
				android.widget.CheckBox cb = (android.widget.CheckBox) v;
				cb.setEnabled(enabled[i]);
				cb.setAlpha(enabled[i] ? 1f : 0.45f);
				cb.setText(labels[i]);
				cb.setChecked(picked[i] && enabled[i]);
				return v;
			}
		};

		android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
				.setTitle(getString(R.string.choose_devices))
				// The listener is required by the API, but a null one would
				// dismiss the dialog on the first row tap. No-op keeps it open
				// so more than one device can be chosen, which is the point of
				// coming here from a share sheet.
				.setAdapter(adapter, (d, which) -> { })
				.setPositiveButton(R.string.send_files, (d, w) -> {
					chosen.clear();
					List<String> targets = new ArrayList<>();
					for (int i = 0; i < ps.size(); i++) {
						if (picked[i] && enabled[i]) {
							chosen.add(ps.get(i).name);
							// dispatch() takes addresses, not names. Passing the
							// name makes api_send reject it as an invalid peer,
							// so every share-sheet send failed while the in-app
							// picker (which passes addresses) worked.
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
				})
				.setNegativeButton(android.R.string.cancel, null)
				.create();

		dlg.show();

		android.view.Window win = dlg.getWindow();
		if (win != null) {
			win.setBackgroundDrawableResource(R.drawable.dialog_bg);
		}

		// Material upper-cases its buttons; the rest of the app is sentence
		// case, so a stock dialog reads as foreign in two small ways.
		android.widget.Button pos = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
		android.widget.Button neg = dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE);
		if (pos != null) {
			sendRef[0] = pos;
			boolean any = false;
			for (int k = 0; k < picked.length; k++) {
				if (picked[k] && enabled[k]) {
					any = true;
					break;
				}
			}
			pos.setTextColor(getColor(any ? R.color.accent : R.color.hint));
		}

		for (android.widget.Button b : new android.widget.Button[]{pos, neg}) {
			if (b != null) {
				b.setAllCaps(false);
				b.setTextColor(getColor(R.color.accent));
				b.setTextSize(15);
			}
		}

		// Colour the Send button from the row click handler below, NOT by
		// installing a listener on the button. setOnClickListener replaces the
		// one the dialog gave it, and the result was a Send button that
		// recoloured itself and did nothing else -- the picker looked alive and
		// silently sent nothing.

		// android.R.id.alertTitle does not exist in the public SDK, so the
		// title is reached by resource name instead. Absent on some OEM builds,
		// hence the null check: this is polish, not something worth failing on.
		android.view.View title = dlg.findViewById(
				getResources().getIdentifier("alertTitle", "id", "android"));
		if (title instanceof android.widget.TextView) {
			android.widget.TextView t = (android.widget.TextView) title;
			t.setTextColor(getColor(R.color.text));
			t.setTextSize(19);
			t.setTypeface(null, android.graphics.Typeface.BOLD);
		}

		android.widget.ListView lv = dlg.getListView();
		if (lv != null) {
			lv.setDivider(new android.graphics.drawable.ColorDrawable(getColor(R.color.divider)));
			lv.setDividerHeight(dp(1));
			lv.setSelector(new android.graphics.drawable.ColorDrawable(getColor(R.color.surfaceHi)));
			lv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
		}
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
		dotView.getBackground().mutate().setTint(getColor(R.color.warn));

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
			dotView.getBackground().mutate().setTint(getColor(R.color.err));
			versionView.setText(getString(R.string.state_daemon_stopped) + "  \u00b7  tailscaled "
					+ st.optString("version", "?"));
		} else if (!signedIn(st)) {
			dotView.getBackground().mutate().setTint(getColor(R.color.warn));
		} else if (!controlPlane) {
			dotView.getBackground().mutate().setTint(getColor(R.color.warn));
			versionView.setText(getString(R.string.state_no_internet) + "  \u00b7  tailscaled "
					+ st.optString("version", "?"));
		} else {
			dotView.getBackground().mutate().setTint(getColor(R.color.ok));
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
				ui.post(() -> {
					polling = false;
					if (fSt != null) {
						applyHeader(fSt);
						patchNames(fSt);
					}
				});
			}).start();
			ui.postDelayed(this, POLL_MS);
		}
	};

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
		if (!signedIn(status)) {
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

		CheckBox cb = new CheckBox(this);
		// Force-clear an offline peer rather than only disabling it: a ticked
		// box next to a red dot reads as "this will be sent".
		cb.setChecked(up && chosen.contains(p.name));
		cb.setEnabled(up);
		cb.setAlpha(up ? 1f : 0.4f);
		cb.setButtonTintList(android.content.res.ColorStateList.valueOf(
				getColor(up && chosen.contains(p.name) ? R.color.text : R.color.hint)));
		row.addView(cb);

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
					? "  ·  " + getString(R.string.exit_node_suffix) : "")
				+ (up ? "" : "  ·  " + getString(R.string.peer_offline_label)
					+ (when.isEmpty() ? "" : "  ·  " + when)));
		meta.setTextColor(getColor(R.color.text2));
		meta.setTextSize(12);
		col.addView(meta);
		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		// A peer's address is worth keeping too, so it copies like one.
		row.addView(iconButton(R.drawable.ic_copy, R.color.hint, R.string.copy, v -> copy(p.ip)));

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
		// caller.
		//
		// This made a copy of the target list and then iterated the original,
		// so the copy did nothing: any repeated address in `to` sent the same
		// file to the same machine that many times. The addresses arrive
		// already de-duplicated from currentTargets(), but that is a
		// convention three call sites have to remember, and a file delivered
		// three times cannot be undone at the receiving end -- it simply
		// turns into three files and three notifications there.
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
		toast(getString(R.string.sending));
		new Thread(() -> {
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
			int ok = 0;
			StringBuilder problems = new StringBuilder();
			for (String peer : targets) {
				for (java.io.File f : files) {
					try {
						Api.send(peer, f.getAbsolutePath());
						ok++;
					} catch (Root.Denied e) {
						throw e;
					} catch (Exception e) {
						failed++;
						if (problems.length() < 300) {
							problems.append(peer).append(": ").append(e.getMessage()).append('\n');
						}
					}
				}
			}
			final int fOk = ok, fFailed = failed;
			final String p = problems.toString().trim();
			ui.post(() -> {
				if (fFailed == 0) {
					toast(getString(R.string.sent_ok, fOk, targets.size()));
				} else {
					alert()
							.setTitle(R.string.send_failed)
							.setMessage(getString(R.string.sent_partial, fOk, fFailed) + "\n\n" + p)
							.setPositiveButton(android.R.string.ok, null)
							.show();
				}
				// Re-read so the staged-items note clears once it is sent.
				refresh();
			});
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

		new android.app.AlertDialog.Builder(this, R.style.Theme_Share_Dialog)
				.setTitle(getString(R.string.t_use_exit))
				.setSingleChoiceItems(labels.toArray(new CharSequence[0]), checked,
						(d, which) -> {
							d.dismiss();
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
						})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void buildSettings(JSONObject st) {
		pSettings.removeAllViews();
		switches.clear();
		Map<String, Boolean> flags = Api.flagsFrom(st);
		boolean online = daemonUp(st);

		LinearLayout c1 = group(pSettings, getString(R.string.connectivity));
		addToggle(c1, "accept-dns", getString(R.string.t_accept_dns),
				getString(R.string.d_accept_dns), flags, online);
		addToggle(c1, "accept-routes", getString(R.string.t_accept_routes),
				getString(R.string.d_accept_routes), flags, online);
		addToggle(c1, "shields-up", getString(R.string.t_shields_up),
				getString(R.string.d_shields_up), flags, online);

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
			action(c1, getString(R.string.t_use_exit),
					exitNodeSummary(st) + "  ·  " + getString(R.string.d_use_exit),
					v -> showExitNodePicker(st, finalCands));
		}

		LinearLayout c2 = group(pSettings, getString(R.string.services));
		addToggle(c2, "run-ssh", getString(R.string.t_ssh), getString(R.string.d_ssh), flags, online);
		addToggle(c2, "run-webclient", getString(R.string.t_webclient),
				getString(R.string.d_webclient), flags, online);

		LinearLayout c3 = group(pSettings, getString(R.string.this_device));
		addToggle(c3, "advertise-exit-node", getString(R.string.t_adv_exit),
				getString(R.string.d_adv_exit), flags, online);
		addToggle(c3, "snat-subnet-routes", getString(R.string.t_snat),
				getString(R.string.d_snat), flags, online);

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
		CheckBox sw = addSwitch(g, label, desc, flags.getOrDefault(key, false), v -> {
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
					CheckBox sw = switches.get(key);
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
		alert()
				.setTitle(R.string.rollback)
				.setMessage(R.string.rollback_confirm)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
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
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
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
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);

		// ── header ──
		LinearLayout head = new LinearLayout(this);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setGravity(Gravity.CENTER_VERTICAL);
		head.setPadding(dp(20), dp(18), dp(12), dp(12));

		LinearLayout titles = new LinearLayout(this);
		titles.setOrientation(LinearLayout.VERTICAL);
		TextView t = new TextView(this);
		t.setText(R.string.logs);
		t.setTextColor(getColor(R.color.text));
		t.setTextSize(17);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		titles.addView(t);
		final TextView count = new TextView(this);
		count.setTextColor(getColor(R.color.hint));
		count.setTextSize(12);
		titles.addView(count);
		head.addView(titles, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		final TextView filter = new TextView(this);
		filter.setText(R.string.filter_all);
		filter.setTextColor(getColor(R.color.text2));
		filter.setTextSize(13);
		filter.setGravity(Gravity.CENTER);
		filter.setPadding(dp(14), dp(8), dp(14), dp(8));
		head.addView(filter);
		root.addView(head);

		// ── body ──
		android.widget.ScrollView scroll = new android.widget.ScrollView(this);
		scroll.setPadding(dp(20), 0, dp(20), 0);
		scroll.setHorizontalScrollBarEnabled(false);
		TextView body = new TextView(this);
		body.setTextSize(11);
		body.setTextColor(getColor(R.color.text2));
		body.setTypeface(android.graphics.Typeface.MONOSPACE);
		body.setLineSpacing(dp(3), 1f);
		body.setPadding(0, dp(4), 0, dp(12));
		scroll.addView(body, new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		root.addView(scroll, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

		// ── footer ──
		LinearLayout foot = new LinearLayout(this);
		foot.setOrientation(LinearLayout.HORIZONTAL);
		foot.setPadding(dp(12), dp(4), dp(12), dp(12));
		TextView copy = footButton(R.string.copy);
		TextView reload = footButton(R.string.refresh);
		TextView close = footButton(R.string.close);
		foot.addView(copy, new LinearLayout.LayoutParams(0, dp(44), 1));
		foot.addView(reload, new LinearLayout.LayoutParams(0, dp(44), 1));
		foot.addView(close, new LinearLayout.LayoutParams(0, dp(44), 1));
		root.addView(foot);

		final AlertDialog d = alert().setView(root).create();
		final String[] lines = log.split("\n");
		final boolean[] problemsOnly = {false};

		Runnable paint = () -> {
			CharSequence out = paintLog(lines, problemsOnly[0]);
			body.setText(out);
			int shown = problemsOnly[0] ? countProblems(lines) : lines.length;
			count.setText(getString(R.string.log_lines, shown));
			filter.setText(problemsOnly[0] ? R.string.filter_all : R.string.filter_problems);
			scroll.post(() -> scroll.fullScroll(View.FOCUS_UP));
		};

		filter.setOnClickListener(v -> {
			problemsOnly[0] = !problemsOnly[0];
			paint.run();
		});
		copy.setOnClickListener(v -> {
			android.content.ClipboardManager cm = (android.content.ClipboardManager)
					getSystemService(CLIPBOARD_SERVICE);
			if (cm != null) {
				cm.setPrimaryClip(android.content.ClipData.newPlainText("tailscale",
						String.join("\n", lines)));
				toast(getString(R.string.log_copied));
			}
		});
		reload.setOnClickListener(v -> {
			d.dismiss();
			showLogs();
		});
		close.setOnClickListener(v -> d.dismiss());

		paint.run();
		d.show();
	}

	private TextView footButton(int label) {
		TextView b = new TextView(this);
		b.setText(label);
		b.setTextColor(getColor(R.color.text));
		b.setTextSize(14);
		b.setGravity(Gravity.CENTER);
		b.setClickable(true);
		b.setBackgroundResource(R.drawable.row_press);
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
						alert()
								.setTitle(R.string.sign_in)
								.setMessage(why.trim().isEmpty()
										? getString(R.string.no_login_url) : why.trim())
								.setPositiveButton(android.R.string.ok, null)
								.show();
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
						alert()
								.setTitle(R.string.sign_in)
								.setMessage(raw.isEmpty()
										? getString(R.string.no_login_url)
										: raw)
								.setPositiveButton(android.R.string.ok, null)
								.show();
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

	private void confirmSignOut() {
		alert()
				.setTitle(R.string.sign_out)
				.setMessage(R.string.sign_out_confirm)
				.setPositiveButton(R.string.sign_out, (d, w) -> {
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
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
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
			t.setTextColor(getColor(R.color.text2));
			t.setTextSize(12);
			t.setTypeface(null, android.graphics.Typeface.BOLD);
			t.setAllCaps(true);
			t.setLetterSpacing(0.08f);
			t.setPadding(dp(4), dp(20), 0, dp(8));
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
		t.setSingleLine(true);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		col.addView(t);

		if (sub != null && !sub.isEmpty()) {
			TextView s = new TextView(this);
			s.setId(R.id.rowSub);
			s.setText(sub);
			s.setTextColor(getColor(R.color.text2));
			s.setTextSize(12);
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
	private CheckBox addSwitch(LinearLayout g, String title, String sub, boolean on,
			OnChecked l) {
		line(g);
		LinearLayout row = row();
		LinearLayout col = col();

		TextView t = new TextView(this);
		t.setId(R.id.rowTitle);
		t.setText(title);
		t.setTextColor(getColor(R.color.text));
		t.setTextSize(15);
		col.addView(t);

		if (sub != null && !sub.isEmpty()) {
			TextView s = new TextView(this);
			s.setId(R.id.rowSub);
			s.setText(sub);
			s.setTextColor(getColor(R.color.text2));
			s.setTextSize(12);
			col.addView(s);
		}
		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		CheckBox sw = new CheckBox(this);
		sw.setButtonTintList(android.content.res.ColorStateList.valueOf(
				getColor(on ? R.color.text : R.color.hint)));
		sw.setChecked(on);
		row.addView(sw);

		// Set the value before wiring the listener so painting the screen can
		// never be mistaken for the user flipping it.
		sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
			b.setButtonTintList(android.content.res.ColorStateList.valueOf(
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
		row.setPadding(dp(16), dp(14), dp(16), dp(14));
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
		t.setTextSize(12);
		t.setLineSpacing(dp(4), 1f);
		t.setPadding(dp(4), dp(16), dp(4), 0);
		parent.addView(t);
	}

	private void toast(String msg) {
		Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
	}

	private int dp(int v) {
		return (int) (v * getResources().getDisplayMetrics().density);
	}
}