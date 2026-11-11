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
import android.widget.ImageView;
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
	/** Settles with one small overshoot: the "springy" half of the motion. */
	static final android.view.animation.OvershootInterpolator SPRING =
			new android.view.animation.OvershootInterpolator(1.1f);

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

	/** A card: container surface, hairline edge, generous expressive corners. */
	static Drawable cardBg(Context c) {
		return outlined(c, Theme.surfaceContainer, Theme.hairline, 24f, 1f);
	}

	/**
	 * A card that leads the page: a tonal block rather than another grey box.
	 *
	 * <p>The gradient runs from a tinted container into the plain raised
	 * surface, so the panel reads as the page's headline even before any of
	 * its text does.
	 */
	static Drawable hero(Context c) {
		int top = Theme.mix(Theme.surfaceContainerHigh, Theme.primaryContainer,
				Theme.isDark ? 0.42f : 0.52f);
		GradientDrawable g = new GradientDrawable(
				GradientDrawable.Orientation.TL_BR,
				new int[] { top, Theme.surfaceContainerHigh });
		g.setCornerRadius(dp(c, 30));
		g.setStroke(dp(c, 1), Theme.hairline);
		return g;
	}

	/** An inset block inside a card: address plates, warning panels, stat tiles. */
	static Drawable inset(Context c) {
		return outlined(c, Theme.surfaceContainerHigh, Theme.hairline, 18f, 1f);
	}

	/** The navigation bar: a floating surface with a single hairline. */
	static Drawable navBg(Context c) {
		GradientDrawable g = new GradientDrawable();
		g.setShape(GradientDrawable.RECTANGLE);
		g.setColor(Theme.surfaceContainer);
		g.setStroke(dp(c, 1), Theme.hairline);
		g.setCornerRadius(dp(c, 26));
		return g;
	}

	static Drawable pill(Context c) {
		return rounded(c, Theme.secondaryContainer, 999f);
	}

	static Drawable pill(Context c, int color) {
		return rounded(c, color, 999f);
	}

	static Drawable fieldBg(Context c) {
		return outlined(c, Theme.field, Theme.hairline, 16f, 1f);
	}

	static Drawable primaryButton(Context c, boolean enabled) {
		if (!enabled) {
			return rounded(c, Theme.surfaceContainerHighest, 999f);
		}
		return ripple(c, rounded(c, Theme.primary, 999f),
				Theme.withAlpha(Theme.onPrimary, 0x33), null);
	}

	static Drawable tonalButton(Context c) {
		return ripple(c, rounded(c, Theme.secondaryContainer, 999f),
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
		l.setClipToOutline(true);
		return l;
	}

	static LinearLayout heroCard(Context c) {
		LinearLayout l = new LinearLayout(c);
		l.setOrientation(LinearLayout.VERTICAL);
		l.setBackground(hero(c));
		l.setClipToOutline(true);
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

	/**
	 * The small tracked line above a heading.
	 *
	 * <p>An eyebrow carries the category; the heading underneath carries the
	 * name. Two sizes and two colours for one idea is what gives a page an
	 * editorial rhythm instead of a list of equal labels.
	 */
	static TextView eyebrow(Context c, CharSequence s) {
		TextView t = label(c, s, 10.5f, Theme.primary);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		t.setLetterSpacing(0.16f);
		t.setAllCaps(true);
		t.setIncludeFontPadding(false);
		return t;
	}

	/** An expressive section heading: accent rule, then a real-sized title. */
	static LinearLayout section(Context c, CharSequence s) {
		LinearLayout row = new LinearLayout(c);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(c, 4), dp(c, 28), dp(c, 4), dp(c, 11));

		View rule = new View(c);
		rule.setBackground(rounded(c, Theme.primary, 999f));
		LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(c, 4), dp(c, 18));
		rlp.rightMargin = dp(c, 10);
		row.addView(rule, rlp);

		TextView t = label(c, s, 19f, Theme.text);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		t.setLetterSpacing(-0.02f);
		t.setIncludeFontPadding(false);
		row.addView(t);
		return row;
	}

	/** A tonal tile that gives a settings row something to look at first. */
	static FrameLayout iconTile(Context c, int drawable, int background, int tint, int sizeDp) {
		FrameLayout frame = new FrameLayout(c);
		frame.setBackground(rounded(c, background, sizeDp >= 44 ? 15f : 13f));
		ImageView icon = new ImageView(c);
		icon.setImageResource(drawable);
		icon.setColorFilter(tint);
		int pad = Math.round(sizeDp * 0.27f);
		icon.setPadding(dp(c, pad), dp(c, pad), dp(c, pad), dp(c, pad));
		frame.addView(icon, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		return frame;
	}

	static TextView button(Context c, CharSequence s, boolean enabled) {
		TextView b = label(c, s, 16f, enabled ? Theme.onPrimary : Theme.onSurfaceVariant);
		b.setTypeface(null, android.graphics.Typeface.BOLD);
		b.setLetterSpacing(0.01f);
		b.setGravity(Gravity.CENTER);
		b.setBackground(primaryButton(c, enabled));
		b.setClickable(true);
		b.setFocusable(true);
		b.setPadding(dp(c, 22), dp(c, 17), dp(c, 22), dp(c, 17));
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
		b.setPadding(dp(c, 20), dp(c, 13), dp(c, 20), dp(c, 13));
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
			float r = thumbR + (isPressed() ? dp(getContext(), 2) : 0f);
			knob.setColor(checked ? Theme.onPrimary : Theme.outline);
			canvas.drawCircle(tx, getHeight() / 2f, r, knob);
		}

		@Override
		public void setPressed(boolean pressed) {
			super.setPressed(pressed);
			invalidate();
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
			anim.setDuration(260);
			anim.setInterpolator(SPRING);
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
			performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
			if (listener != null) {
				listener.on(checked);
			}
			return true;
		}
	}

	static Toggle toggle(Context c, boolean on) {
		return new Toggle(c, on);
	}

	/** A tonal monogram tile with a presence dot: who the row is about. */
	static FrameLayout avatar(Context c, String name, boolean up) {
		FrameLayout frame = new FrameLayout(c);
		int size = dp(c, 46);

		TextView letter = label(c, monogram(name), 17f,
				up ? Theme.onSecondaryContainer : Theme.text2);
		letter.setTypeface(null, android.graphics.Typeface.BOLD);
		letter.setGravity(Gravity.CENTER);
		letter.setBackground(rounded(c,
				up ? Theme.secondaryContainer : Theme.surfaceContainerHighest, 16f));
		frame.addView(letter, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));

		View dot = new View(c);
		dot.setBackground(outlined(c, up ? Theme.success : Theme.outline,
				Theme.surfaceContainer, 999f, 2f));
		FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
				dp(c, 14), dp(c, 14), Gravity.BOTTOM | Gravity.END);
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

	/**
	 * Give a tappable view the small squash every physical control has.
	 *
	 * <p>The ripple says "you hit this"; the 2% scale says "this is a thing
	 * you pressed". The touch listener always returns false, so clicks,
	 * ripples and accessibility are untouched.
	 */
	static void pressable(View v) {
		final float pressed = 0.975f;
		v.setOnTouchListener((view, ev) -> {
			switch (ev.getActionMasked()) {
				case android.view.MotionEvent.ACTION_DOWN:
					view.animate().scaleX(pressed).scaleY(pressed)
							.setDuration(90).setInterpolator(STANDARD).start();
					break;
				case android.view.MotionEvent.ACTION_UP:
				case android.view.MotionEvent.ACTION_CANCEL:
					view.animate().scaleX(1f).scaleY(1f)
							.setDuration(240).setInterpolator(SPRING).start();
					break;
				default:
					break;
			}
			return false;
		});
	}

	/** Fade and lift a group of views in, one after the next. */
	static void stagger(View... views) {
		int i = 0;
		for (View v : views) {
			v.setAlpha(0f);
			v.setTranslationY(dp(v.getContext(), 18));
			v.animate()
					.alpha(1f)
					.translationY(0f)
					.setStartDelay(i * 45L)
					.setDuration(360)
					.setInterpolator(EMPHASIZED)
					.start();
			i++;
		}
	}

	static void pop(View v) {
		v.setScaleX(0.9f);
		v.setScaleY(0.9f);
		v.setAlpha(0f);
		v.animate().scaleX(1f).scaleY(1f).alpha(1f)
				.setDuration(300).setInterpolator(SPRING).start();
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
