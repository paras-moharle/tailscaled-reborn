// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Material 3 pieces this app draws itself.
 *
 * <p>No library: every control is a small arrangement of platform views and
 * generated drawables. The shapes and spacing are the ones Material 3
 * specifies — the 12/16/24/28dp corner scale, the 52x32dp switch, a 64x32dp
 * navigation indicator, tonal state layers and clipped ripples — so the
 * result reads as Material without shipping it.
 *
 * <p>The colours are not hard-coded here either: every value comes from
 * {@link Theme}, which generates the palette at runtime from the wallpaper.
 */
final class Ui {

	static final PathInterpolator EMPHASIZED =
			new PathInterpolator(0.2f, 0f, 0f, 1f);
	static final PathInterpolator STANDARD =
			new PathInterpolator(0.4f, 0f, 0.2f, 1f);

	private Ui() {}

	static int dp(Context c, float v) {
		return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
	}

	// ------------------------------------------------------------ drawables

	static Drawable rounded(Context c, int color, float radiusDp) {
		GradientDrawable g = new GradientDrawable();
		g.setShape(GradientDrawable.RECTANGLE);
		g.setColor(color);
		g.setCornerRadius(dp(c, radiusDp));
		return g;
	}

	static Drawable outlined(Context c, int fill, int stroke, float radiusDp, float strokeDp) {
		GradientDrawable g = new GradientDrawable();
		g.setShape(GradientDrawable.RECTANGLE);
		g.setColor(fill);
		g.setCornerRadius(dp(c, radiusDp));
		if (strokeDp > 0) {
			g.setStroke(dp(c, strokeDp), stroke);
		}
		return g;
	}

	/** A card: container surface, hairline edge, 20dp corners. */
	static Drawable cardBg(Context c) {
		return outlined(c, Theme.surfaceContainer, Theme.hairline, 20f, 1f);
	}

	/** A card that leads the page: one step up, so it reads first. */
	static Drawable hero(Context c) {
		return outlined(c, Theme.surfaceContainerHigh, Theme.hairline, 24f, 1f);
	}

	/** The navigation bar: a container with a single hairline along the top. */
	static Drawable navBg(Context c) {
		GradientDrawable g = new GradientDrawable();
		g.setShape(GradientDrawable.RECTANGLE);
		g.setColor(Theme.surfaceContainer);
		g.setStroke(dp(c, 1), Theme.hairline);
		return g;
	}

	static Drawable pill(Context c) {
		return rounded(c, Theme.secondaryContainer, 999f);
	}

	static Drawable pill(Context c, int color) {
		return rounded(c, color, 999f);
	}

	static Drawable fieldBg(Context c) {
		return outlined(c, Theme.field, Theme.hairline, 14f, 1f);
	}

	static Drawable primaryButton(Context c, boolean enabled) {
		if (!enabled) {
			return rounded(c, Theme.surfaceContainerHighest, 20f);
		}
		return ripple(c, rounded(c, Theme.primary, 20f),
				Theme.withAlpha(Theme.onPrimary, 0x33), null);
	}

	static Drawable tonalButton(Context c) {
		return ripple(c, rounded(c, Theme.secondaryContainer, 20f),
				Theme.withAlpha(Theme.onSecondaryContainer, 0x33), null);
	}

	static Drawable textButton(Context c) {
		return ripple(c, rounded(c, Color.TRANSPARENT, 20f),
				Theme.withAlpha(Theme.primary, 0x33), null);
	}

	static Drawable ripple(Context c, Drawable base, int rippleColor, Drawable mask) {
		return new RippleDrawable(ColorStateList.valueOf(rippleColor), base, mask);
	}

	/** A state layer clipped to a shape; the whole row is the target. */
	static Drawable press(Context c) {
		return press(c, 0f);
	}

	static Drawable press(Context c, float radiusDp) {
		return new RippleDrawable(
				ColorStateList.valueOf(Theme.withAlpha(Theme.onSurface, 0x1F)),
				null,
				rounded(c, 0xFFFFFFFF, radiusDp));
	}

	/** A shape with a fixed intrinsic size, which is what Switch measures. */
	static Drawable sized(final Drawable d, final Context c, final float w, final float h) {
		final int ww = dp(c, w);
		final int hh = dp(c, h);
		return new Drawable() {
			@Override
			public void draw(Canvas canvas) {
				d.setBounds(0, 0, ww, hh);
				d.draw(canvas);
			}

			@Override
			public void setAlpha(int alpha) {
				d.setAlpha(alpha);
			}

			@Override
			public void setColorFilter(android.graphics.ColorFilter cf) {
				d.setColorFilter(cf);
			}

			@Override
			public int getOpacity() {
				return android.graphics.PixelFormat.TRANSLUCENT;
			}

			@Override
			public int getIntrinsicWidth() {
				return ww;
			}

			@Override
			public int getIntrinsicHeight() {
				return hh;
			}
		};
	}

	// ---------------------------------------------------------------- views

	static LinearLayout card(Context c) {
		LinearLayout l = new LinearLayout(c);
		l.setOrientation(LinearLayout.VERTICAL);
		l.setBackground(cardBg(c));
		return l;
	}

	static LinearLayout heroCard(Context c) {
		LinearLayout l = new LinearLayout(c);
		l.setOrientation(LinearLayout.VERTICAL);
		l.setBackground(hero(c));
		return l;
	}

	static TextView label(Context c, CharSequence s, float sp, int color) {
		TextView t = new TextView(c);
		t.setText(s);
		t.setTextSize(sp);
		t.setTextColor(color);
		t.setIncludeFontPadding(false);
		return t;
	}

	/** MD3 section header: 11sp, medium weight, wide tracking. */
	static TextView section(Context c, CharSequence s) {
		TextView t = label(c, s, 11f, Theme.primary);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		t.setLetterSpacing(0.08f);
		t.setAllCaps(true);
		t.setPadding(dp(c, 6), dp(c, 22), 0, dp(c, 8));
		return t;
	}

	static TextView button(Context c, CharSequence s, boolean enabled) {
		TextView b = label(c, s, 15f, enabled ? Theme.onPrimary : Theme.onSurfaceVariant);
		b.setTypeface(null, android.graphics.Typeface.BOLD);
		b.setGravity(Gravity.CENTER);
		b.setBackground(primaryButton(c, enabled));
		b.setClickable(true);
		b.setFocusable(true);
		b.setPadding(dp(c, 20), dp(c, 15), dp(c, 20), dp(c, 15));
		b.setEnabled(enabled);
		return b;
	}

	static TextView tonal(Context c, CharSequence s) {
		TextView b = label(c, s, 14f, Theme.onSecondaryContainer);
		b.setTypeface(null, android.graphics.Typeface.BOLD);
		b.setGravity(Gravity.CENTER);
		b.setBackground(tonalButton(c));
		b.setClickable(true);
		b.setFocusable(true);
		b.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
		return b;
	}

	/**
	 * The MD3 switch, drawn rather than themed.
	 *
	 * <p>A platform {@code Switch} is a Holo control: a thin track, a small
	 * thumb, and a tint list that cannot produce the 52x32dp track with a
	 * travelling 20dp thumb that Material 3 specifies. Drawing it is a
	 * hundred lines and gives exact control of every state, plus the same
	 * click, focus and accessibility behaviour as any other view.
	 */
	static final class Toggle extends View {

		interface OnChange {
			void on(boolean checked);
		}

		private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF track = new RectF();

		private final float trackW;
		private final float trackH;
		private final float thumbR;
		private final float inset;

		private boolean checked;
		private float progress;
		private OnChange listener;
		private ValueAnimator anim;

		Toggle(Context c, boolean on) {
			super(c);
			trackW = dp(c, 52);
			trackH = dp(c, 32);
			thumbR = dp(c, 12);
			inset = dp(c, 4);
			checked = on;
			progress = on ? 1f : 0f;
			edge.setStyle(Paint.Style.STROKE);
			edge.setStrokeWidth(dp(c, 2));
			setClickable(true);
			setFocusable(true);
		}

		@Override
		protected void onMeasure(int widthSpec, int heightSpec) {
			setMeasuredDimension((int) (trackW + dp(getContext(), 8)),
					(int) (trackH + dp(getContext(), 16)));
		}

		@Override
		protected void onDraw(Canvas canvas) {
			float left = (getWidth() - trackW) / 2f;
			float top = (getHeight() - trackH) / 2f;
			track.set(left, top, left + trackW, top + trackH);
			float radius = trackH / 2f;

			if (checked) {
				fill.setColor(Theme.primary);
				canvas.drawRoundRect(track, radius, radius, fill);
			} else {
				fill.setColor(Theme.surfaceContainerHighest);
				canvas.drawRoundRect(track, radius, radius, fill);
				edge.setColor(Theme.outline);
				canvas.drawRoundRect(track, radius, radius, edge);
			}

			float travel = trackW - 2f * (thumbR + inset);
			float tx = left + inset + thumbR + travel * progress;
			knob.setColor(checked ? Theme.onPrimary : Theme.outline);
			canvas.drawCircle(tx, getHeight() / 2f, thumbR, knob);
		}

		boolean isChecked() {
			return checked;
		}

		void setChecked(boolean on) {
			if (checked == on) {
				return;
			}
			checked = on;
			float target = on ? 1f : 0f;
			if (anim != null) {
				anim.cancel();
			}
			anim = ValueAnimator.ofFloat(progress, target);
			anim.setDuration(200);
			anim.setInterpolator(EMPHASIZED);
			anim.addUpdateListener(a -> {
				progress = (float) a.getAnimatedValue();
				invalidate();
			});
			anim.start();
		}

		void setOnCheckedChangeListener(OnChange l) {
			listener = l;
		}

		@Override
		public boolean performClick() {
			super.performClick();
			setChecked(!checked);
			if (listener != null) {
				listener.on(checked);
			}
			return true;
		}
	}

	static Toggle toggle(Context c, boolean on) {
		return new Toggle(c, on);
	}

	/** A round monogram with a presence dot: who the row is about. */
	static FrameLayout avatar(Context c, String name, boolean up) {
		FrameLayout frame = new FrameLayout(c);
		int size = dp(c, 42);

		TextView letter = label(c, monogram(name), 16f,
				up ? Theme.onSecondaryContainer : Theme.text2);
		letter.setTypeface(null, android.graphics.Typeface.BOLD);
		letter.setGravity(Gravity.CENTER);
		letter.setBackground(rounded(c,
				up ? Theme.secondaryContainer : Theme.surfaceContainerHighest, 999f));
		frame.addView(letter, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));

		View dot = new View(c);
		dot.setBackground(outlined(c, up ? Theme.success : Theme.outline,
				Theme.surfaceContainer, 999f, 2f));
		FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
				dp(c, 13), dp(c, 13), Gravity.BOTTOM | Gravity.END);
		frame.addView(dot, dlp);
		frame.setLayoutParams(new LinearLayout.LayoutParams(size, size));
		return frame;
	}

	private static String monogram(String name) {
		if (name == null || name.isEmpty()) {
			return "?";
		}
		// Device names are usually hostnames; the first letter is enough and
		// survives any script, unlike a "first two letters" rule.
		return name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
	}

	static View spacer(Context c, int heightDp) {
		View v = new View(c);
		v.setLayoutParams(new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
		return v;
	}

	// ------------------------------------------------------------- motion

	/** Fade and lift a group of views in, one after the next. */
	static void stagger(View... views) {
		int i = 0;
		for (View v : views) {
			v.setAlpha(0f);
			v.setTranslationY(dp(v.getContext(), 10));
			v.animate()
					.alpha(1f)
					.translationY(0f)
					.setStartDelay(i * 35L)
					.setDuration(260)
					.setInterpolator(EMPHASIZED)
					.start();
			i++;
		}
	}

	static void pop(View v) {
		v.setScaleX(0.92f);
		v.setScaleY(0.92f);
		v.setAlpha(0f);
		v.animate().scaleX(1f).scaleY(1f).alpha(1f)
				.setDuration(240).setInterpolator(EMPHASIZED).start();
	}

	/** The selected navigation indicator: grow in, do not just appear. */
	static void indicator(View pill, boolean selected) {
		float target = selected ? 1f : 0.6f;
		float alpha = selected ? 1f : 0f;
		pill.animate().scaleX(target).scaleY(target).alpha(alpha)
				.setDuration(220).setInterpolator(EMPHASIZED).start();
	}

	/** Colour a compound drawable without losing the tint on API 26. */
	static void tint(TextView v, int color) {
		v.setTextColor(color);
		Drawable[] d = v.getCompoundDrawables();
		for (Drawable x : d) {
			if (x != null) {
				x.setTint(color);
			}
		}
	}
}
