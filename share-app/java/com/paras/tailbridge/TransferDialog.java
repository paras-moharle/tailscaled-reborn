// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The popup shown while files are moving.
 *
 * <p>No spinner and no percentage bar: three dots breathe in sequence while
 * the transfer runs, then the loader is replaced by a check that draws
 * itself, the title becomes "Sent", and the card leaves on its own. It is
 * one object that changes state rather than one dialog dismissed and another
 * shown, so a finished transfer reads as a finished thing, not as an alarm
 * that went away.
 */
final class TransferDialog {

	private final Activity a;
	private final Sheet sheet;
	private final FrameLayout indicator;
	private final DotsView dots;
	private final CheckView check;
	private final TextView title;
	private final TextView sub;
	private final LinearLayout column;
	private final Handler ui = new Handler(Looper.getMainLooper());

	private boolean finished;

	TransferDialog(Activity a, String firstLine) {
		this.a = a;
		sheet = Sheet.card(a);

		column = new LinearLayout(a);
		column.setOrientation(LinearLayout.VERTICAL);
		column.setGravity(Gravity.CENTER_HORIZONTAL);

		indicator = new FrameLayout(a);
		dots = new DotsView(a);
		check = new CheckView(a);
		check.setVisibility(View.INVISIBLE);
		indicator.addView(dots, new FrameLayout.LayoutParams(
				Sheet.dp(a, 52), Sheet.dp(a, 52), Gravity.CENTER));
		indicator.addView(check, new FrameLayout.LayoutParams(
				Sheet.dp(a, 52), Sheet.dp(a, 52), Gravity.CENTER));
		LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
				Sheet.dp(a, 52), Sheet.dp(a, 52));
		ilp.topMargin = Sheet.dp(a, 4);
		column.addView(indicator, ilp);

		title = new TextView(a);
		title.setText(R.string.send_progress_title);
		title.setTextColor(a.getColor(R.color.text));
		title.setTextSize(17);
		title.setTypeface(null, android.graphics.Typeface.BOLD);
		title.setIncludeFontPadding(false);
		title.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		tlp.topMargin = Sheet.dp(a, 18);
		column.addView(title, tlp);

		sub = new TextView(a);
		sub.setText(firstLine);
		sub.setTextColor(a.getColor(R.color.text2));
		sub.setTextSize(13.5f);
		sub.setIncludeFontPadding(false);
		sub.setGravity(Gravity.CENTER);
		sub.setMaxLines(2);
		sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
		sub.setPadding(Sheet.dp(a, 2), Sheet.dp(a, 7), Sheet.dp(a, 2), 0);
		sub.setLineSpacing(Sheet.dp(a, 3), 1f);
		column.addView(sub);

		sheet.add(column);

		// A transfer is not something to dismiss by tapping beside it: the
		// files are already moving, and a card that vanishes mid-flight
		// says nothing about whether they arrived.
		sheet.show();
	}

	void subtitle(String text) {
		if (finished) {
			return;
		}
		sub.setText(text);
	}

	boolean isShowing() {
		return sheet.isShowing();
	}

	/** The transfer landed: draw the check, say so, then leave. */
	void success(String line) {
		if (finished) {
			return;
		}
		finished = true;
		dots.setVisibility(View.GONE);
		check.setOk(true);
		check.setVisibility(View.VISIBLE);
		title.setText(R.string.send_done);
		sub.setText(line);
		check.play();
		ui.postDelayed(sheet::dismiss, 1250);
	}

	/** The transfer failed: say what was sent, then wait for a close. */
	void failure(String heading, String line, String detail, Runnable afterClose) {
		if (finished) {
			return;
		}
		finished = true;
		dots.setVisibility(View.GONE);
		check.setOk(false);
		check.setVisibility(View.VISIBLE);
		title.setText(heading);
		sub.setText(line);
		check.play();

		if (detail != null && !detail.trim().isEmpty()) {
			TextView why = new TextView(a);
			why.setText(detail.trim());
			why.setTextColor(a.getColor(R.color.hint));
			why.setTextSize(12);
			why.setLineSpacing(Sheet.dp(a, 3), 1f);
			why.setIncludeFontPadding(false);
			why.setGravity(Gravity.CENTER);
			why.setMaxLines(4);
			why.setEllipsize(android.text.TextUtils.TruncateAt.END);
			why.setPadding(0, Sheet.dp(a, 12), 0, 0);
			column.addView(why);
		}

		sheet.primary(a.getString(android.R.string.ok), afterClose);
	}

	// ---------------------------------------------------------- the dots

	/**
	 * Three dots pulsing in a travelling wave. Drawn rather than animated
	 * with three views so the rhythm is one clock and the dots cannot drift
	 * apart into three unrelated blinks.
	 */
	static final class DotsView extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final float base;
		private final float gap;
		private final long start = SystemClock.uptimeMillis();

		DotsView(Context c) {
			super(c);
			base = c.getResources().getDisplayMetrics().density * 4.2f;
			gap = c.getResources().getDisplayMetrics().density * 5.5f;
			paint.setColor(0xFF3D8BFF);
		}

		@Override protected void onDraw(Canvas canvas) {
			float t = (SystemClock.uptimeMillis() - start) / 1000f;
			float cy = getHeight() / 2f;
			float cx = getWidth() / 2f;
			float step = base * 2 + gap;
			float x0 = cx - step;
			for (int i = 0; i < 3; i++) {
				// A wave, offset a third of a cycle per dot.
				float phase = t * 3.4f - i * 0.42f;
				float k = (float) (0.5 + 0.5 * Math.sin(phase * Math.PI * 2));
				float r = base * (0.62f + 0.38f * k);
				paint.setAlpha((int) (110 + 145 * k));
				canvas.drawCircle(x0 + step * i, cy, r, paint);
			}
			postInvalidateOnAnimation();
		}
	}

	// ----------------------------------------------------------- the check

	/**
	 * A circle and a tick that draws itself. The circle is the container and
	 * the tick is the news; animating the tick rather than the whole mark is
	 * what makes it read as confirmation instead of decoration.
	 */
	static final class CheckView extends View {
		private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path path = new Path();
		private final PathMeasure measure = new PathMeasure();
		private final RectF box = new RectF();
		private float progress;
		private boolean ok = true;

		CheckView(Context c) {
			super(c);
			float d = c.getResources().getDisplayMetrics().density;
			ring.setStyle(Paint.Style.STROKE);
			ring.setStrokeWidth(d * 2.2f);
			ring.setStrokeCap(Paint.Cap.ROUND);
			mark.setStyle(Paint.Style.STROKE);
			mark.setStrokeWidth(d * 2.6f);
			mark.setStrokeCap(Paint.Cap.ROUND);
			mark.setStrokeJoin(Paint.Join.ROUND);
		}

		void setOk(boolean good) {
			ok = good;
			ring.setColor(getContext().getColor(good ? R.color.ok : R.color.err));
			mark.setColor(getContext().getColor(good ? R.color.ok : R.color.err));
			invalidate();
		}

		void play() {
			progress = 0f;
			ValueAnimator v = ValueAnimator.ofFloat(0f, 1f);
			v.setDuration(380);
			v.setInterpolator(new DecelerateInterpolator(1.4f));
			v.addUpdateListener(x -> {
				progress = (float) x.getAnimatedValue();
				invalidate();
			});
			v.start();
		}

		@Override protected void onDraw(Canvas canvas) {
			float w = getWidth();
			float h = getHeight();
			float r = Math.min(w, h) / 2f - ring.getStrokeWidth();

			// The ring grows a touch as the mark draws, so the pop has a
			// direction without a scale animation fighting the path.
			float rr = r * (0.82f + 0.18f * progress);
			canvas.drawCircle(w / 2f, h / 2f, rr, ring);

			if (ok) {
				path.reset();
				path.moveTo(w * 0.30f, h * 0.52f);
				path.lineTo(w * 0.44f, h * 0.66f);
				path.lineTo(w * 0.71f, h * 0.37f);
			} else {
				path.reset();
				path.moveTo(w * 0.35f, h * 0.35f);
				path.lineTo(w * 0.65f, h * 0.65f);
				path.moveTo(w * 0.65f, h * 0.35f);
				path.lineTo(w * 0.35f, h * 0.65f);
			}
			measure.setPath(path, false);
			float len = measure.getLength();
			Path drawn = new Path();
			measure.getSegment(0, len * progress, drawn, true);
			canvas.drawPath(drawn, mark);
		}
	}
}
