// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The app's one dialog surface.
 *
 * <p>Every popup in the app is built from this class: one card, one radius,
 * one set of buttons, one entrance. Nothing here is an {@code AlertDialog},
 * because the platform dialog drags in its own title block, its own button
 * bar and its own spacing, and the result reads as a different app laid on
 * top of this one.
 *
 * <p>The card is a rounded surface on true black with a hairline edge and a
 * very slight vertical gradient, so it lifts off the page without a shadow.
 * The footer is two pill buttons: the action filled in the accent, the way
 * out quiet on a raised surface. That pairing is the whole visual grammar
 * of the app's popups.
 */
public final class Sheet {

	private static final int PRIMARY = 0;
	private static final int SECONDARY = 1;
	private static final int DANGER = 2;

	private final Activity a;
	private final Dialog d;
	private final LinearLayout card;
	private final LinearLayout head;
	private final TextView title;
	private final TextView subtitle;
	private final LinearLayout body;
	private final LinearLayout footer;
	private final Handler ui = new Handler(Looper.getMainLooper());

	private boolean closing;

	Sheet(Activity a, boolean bottom) {
		this.a = a;
		d = new Dialog(a);
		d.requestWindowFeature(Window.FEATURE_NO_TITLE);
		d.setCanceledOnTouchOutside(true);

		card = new LinearLayout(a);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(dp(a, 24), dp(a, 24), dp(a, 24), dp(a, 22));
		card.setBackground(cardBackground(dp(a, bottom ? 28 : 26), bottom));

		head = new LinearLayout(a);
		head.setOrientation(LinearLayout.VERTICAL);
		head.setVisibility(View.GONE);

		title = new TextView(a);
		title.setTextColor(a.getColor(R.color.text));
		title.setTextSize(20);
		title.setTypeface(null, android.graphics.Typeface.BOLD);
		title.setLetterSpacing(-0.01f);
		title.setIncludeFontPadding(false);
		head.addView(title);

		subtitle = new TextView(a);
		subtitle.setTextColor(a.getColor(R.color.text2));
		subtitle.setTextSize(13.5f);
		subtitle.setLineSpacing(dp(a, 3), 1f);
		subtitle.setIncludeFontPadding(false);
		subtitle.setPadding(0, dp(a, 6), 0, 0);
		subtitle.setVisibility(View.GONE);
		head.addView(subtitle);

		body = new LinearLayout(a);
		body.setOrientation(LinearLayout.VERTICAL);

		footer = new LinearLayout(a);
		footer.setOrientation(LinearLayout.HORIZONTAL);
		footer.setVisibility(View.GONE);
		LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		flp.topMargin = dp(a, 22);
		footer.setLayoutParams(flp);

		card.addView(head);
		card.addView(body, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		card.addView(footer);

		Window w = d.getWindow();
		if (w != null) {
			w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
			w.addFlags(Window.FEATURE_NO_TITLE);
			w.setDimAmount(0.68f);
			w.setWindowAnimations(0);
			w.setGravity(bottom ? Gravity.BOTTOM : Gravity.CENTER);
			// A number field inside a card must not end up under the
			// keyboard with its Save button out of reach.
			w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		}
		d.setContentView(card);
		d.setOnShowListener(x -> {
			Window win = d.getWindow();
			if (win == null) {
				return;
			}
			int screen = a.getResources().getDisplayMetrics().widthPixels;
			int width = bottom
					? ViewGroup.LayoutParams.MATCH_PARENT
					: Math.min(screen - dp(a, 48), dp(a, 430));
			win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);

			// A centre card appears at once. An entrance animation on a
			// dialog cannot start until the window exists, and on a busy
			// phone that first animated frame is a dropped one -- the popup
			// then reads as late even though the tap was instant. Appearing
			// immediately is both faster and steadier.
			//
			// The bottom sheet keeps a short rise, because a sheet that
			// materialises at the bottom edge looks like a glitch, not a
			// choice.
			if (bottom) {
				card.setTranslationY(dp(a, 36));
				card.animate()
						.translationY(0f)
						.setDuration(150)
						.setInterpolator(new DecelerateInterpolator(1.6f))
						.start();
			}
		});
	}

	/** A centred card, the default popup. */
	public static Sheet card(Activity a) {
		return new Sheet(a, false);
	}

	/** A bottom sheet: square at the bottom, rounded at the top. */
	public static Sheet bottom(Activity a) {
		return new Sheet(a, true);
	}

	public Sheet title(String text) {
		title.setText(text);
		title.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
		updateHead();
		return this;
	}

	public Sheet subtitle(String text) {
		subtitle.setText(text);
		subtitle.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
		updateHead();
		return this;
	}

	public Sheet add(View v) {
		body.addView(v);
		return this;
	}

	/** Adds content sized to a fixed height in dp; used for scrolling lists. */
	public Sheet add(View v, int heightDp) {
		body.addView(v, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(a, heightDp)));
		return this;
	}

	/**
	 * A block of text under the header. Long text scrolls inside the card
	 * instead of stretching the card past the screen.
	 */
	public Sheet message(CharSequence text) {
		message(text, false);
		return this;
	}

	public Sheet message(CharSequence text, boolean selectable) {
		TextView t = new TextView(a);
		t.setText(text);
		t.setTextColor(a.getColor(R.color.text2));
		t.setTextSize(14.5f);
		t.setLineSpacing(dp(a, 5), 1f);
		t.setIncludeFontPadding(false);
		t.setTextIsSelectable(selectable);

		boolean longText = text != null
				&& (text.length() > 520 || countLines(text) > 12);
		if (longText) {
			ScrollView sc = new ScrollView(a);
			sc.setVerticalScrollBarEnabled(false);
			sc.addView(t, new ScrollView.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			body.addView(sc, new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 330)));
		} else {
			body.addView(t);
		}
		return this;
	}

	private static int countLines(CharSequence s) {
		int n = 1;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == '\n') {
				n++;
			}
		}
		return n;
	}

	/** The filled action. Runs after the card has left the screen. */
	public Sheet primary(String label, Runnable after) {
		return button(label, PRIMARY, false, after);
	}

	/** The filled action, staying open so the caller can validate first. */
	public Sheet primaryKeep(String label, Runnable action) {
		return button(label, PRIMARY, true, action);
	}

	/** The quiet way out. */
	public Sheet secondary(String label) {
		return button(label, SECONDARY, false, null);
	}

	public Sheet secondary(String label, Runnable after) {
		return button(label, SECONDARY, false, after);
	}

	/** A destructive action, in the error colour. */
	public Sheet danger(String label, Runnable after) {
		return button(label, DANGER, false, after);
	}

	private Sheet button(String label, int kind, boolean keepOpen, Runnable after) {
		TextView b = new TextView(a);
		b.setText(label);
		b.setTextSize(15);
		b.setGravity(Gravity.CENTER);
		b.setSingleLine(true);
		b.setIncludeFontPadding(false);
		b.setTypeface(null, kind == SECONDARY
				? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
		if (kind == PRIMARY) {
			b.setTextColor(a.getColor(R.color.onAccent));
			b.setBackground(pill(a.getColor(R.color.accent), 0x33FFFFFF));
		} else if (kind == DANGER) {
			b.setTextColor(Color.WHITE);
			b.setBackground(pill(a.getColor(R.color.err), 0x33FFFFFF));
		} else {
			b.setTextColor(a.getColor(R.color.text));
			b.setBackground(pill(a.getColor(R.color.surfaceHi), 0x26FFFFFF));
		}
		b.setClickable(true);
		b.setFocusable(true);

		b.setOnClickListener(v -> {
			if (keepOpen) {
				if (after != null) {
					after.run();
				}
				return;
			}
			dismiss();
			if (after != null) {
				ui.postDelayed(after, 150);
			}
		});

		int count = footer.getChildCount();
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				0, dp(a, 50), 1f);
		if (count > 0) {
			lp.leftMargin = dp(a, 10);
		}
		footer.addView(b, lp);
		footer.setVisibility(View.VISIBLE);
		footer.requestLayout();
		return this;
	}

	public boolean isShowing() {
		return !closing && d.isShowing();
	}

	public void dismiss() {
		if (closing) {
			return;
		}
		closing = true;
		if (!d.isShowing()) {
			return;
		}
		card.animate()
				.alpha(0f)
				.setDuration(90)
				.withEndAction(() -> {
					try {
						d.dismiss();
					} catch (Exception ignored) {
					}
				})
				.start();
	}

	public Sheet show() {
		if (!d.isShowing()) {
			d.show();
		}
		if (head.getVisibility() == View.VISIBLE && body.getChildCount() > 0) {
			body.setPadding(0, dp(a, 18), 0, 0);
		}
		return this;
	}

	private void updateHead() {
		head.setVisibility(title.getVisibility() == View.VISIBLE
				|| subtitle.getVisibility() == View.VISIBLE
				? View.VISIBLE : View.GONE);
	}

	// ------------------------------------------------------------ helpers

	static int dp(Activity a, int v) {
		return (int) (v * a.getResources().getDisplayMetrics().density);
	}

	static GradientDrawable cardBackground(int radius, boolean bottomOnly) {
		GradientDrawable g = new GradientDrawable(
				GradientDrawable.Orientation.TOP_BOTTOM,
				new int[] { 0xFF171D25, 0xFF0F1319 });
		float r = radius;
		if (bottomOnly) {
			g.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
		} else {
			g.setCornerRadius(r);
		}
		g.setStroke(1, 0x1FFFFFFF);
		return g;
	}

	static Drawable pill(int color, int ripple) {
		GradientDrawable g = new GradientDrawable();
		g.setShape(GradientDrawable.RECTANGLE);
		g.setCornerRadius(999);
		g.setColor(color);
		return new RippleDrawable(ColorStateList.valueOf(ripple), g, g);
	}

	// ------------------------------------------------------- presets

	public static void info(Activity a, String title, String message) {
		Sheet s = card(a).title(title);
		if (message != null && !message.isEmpty()) {
			s.message(message);
		}
		s.primary(a.getString(android.R.string.ok), null).show();
	}

	public static void info(Activity a, String title, String message, boolean selectable) {
		Sheet s = card(a).title(title);
		if (message != null && !message.isEmpty()) {
			s.message(message, selectable);
		}
		s.primary(a.getString(android.R.string.ok), null).show();
	}

	public static void confirm(Activity a, String title, String message,
			String action, boolean dangerous, Runnable after) {
		Sheet s = card(a).title(title).message(message);
		if (dangerous) {
			s.danger(action, after);
		} else {
			s.primary(action, after);
		}
		s.secondary(a.getString(android.R.string.cancel)).show();
	}

	/** A title and a stack of single-choice rows. */
	public static void choice(Activity a, String title, CharSequence[] labels,
			int selected, java.util.function.IntConsumer pick) {
		Sheet s = card(a).title(title);
		LinearLayout list = new LinearLayout(a);
		list.setOrientation(LinearLayout.VERTICAL);

		for (int i = 0; i < labels.length; i++) {
			if (i > 0) {
				View line = new View(a);
				line.setBackgroundColor(a.getColor(R.color.divider));
				list.addView(line, new LinearLayout.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT, 1));
			}
			LinearLayout row = new LinearLayout(a);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(dp(a, 6), dp(a, 14), dp(a, 6), dp(a, 14));
			row.setBackgroundResource(R.drawable.row_press);
			row.setClickable(true);

			TextView label = new TextView(a);
			label.setText(labels[i]);
			label.setTextColor(a.getColor(i == selected ? R.color.text : R.color.text2));
			label.setTextSize(15);
			label.setIncludeFontPadding(false);
			label.setMaxLines(2);
			label.setEllipsize(TextUtils.TruncateAt.END);
			row.addView(label, new LinearLayout.LayoutParams(
					0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

			TextView mark = new TextView(a);
			mark.setText(i == selected ? "✓" : "");
			mark.setTextColor(a.getColor(R.color.accent));
			mark.setTextSize(15);
			mark.setTypeface(null, android.graphics.Typeface.BOLD);
			mark.setPadding(dp(a, 12), 0, 0, 0);
			row.addView(mark);

			final int which = i;
			row.setOnClickListener(v -> {
				s.dismiss();
				s.ui.postDelayed(() -> pick.accept(which), 150);
			});
			list.addView(row);
		}
		s.add(list);
		s.secondary(a.getString(android.R.string.cancel)).show();
	}
}
