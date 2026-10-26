// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A short message that rises above the nav bar and leaves on its own.
 *
 * <p>This replaces {@code Toast}. A toast is a grey rectangle from another
 * decade in the middle of the screen; this is the app's own surface, with a
 * small accent light and a rounded edge, sitting where the thumb already is.
 * One at a time: a burst of writes should not stack five of them.
 */
final class Snack {

	private static final Handler UI = new Handler(Looper.getMainLooper());
	private static View current;

	private Snack() {
	}

	static void show(Activity a, CharSequence msg) {
		if (a == null || msg == null || msg.length() == 0) {
			return;
		}
		a.runOnUiThread(() -> present(a, msg));
	}

	private static void present(Activity a, CharSequence msg) {
		ViewGroup content = a.findViewById(android.R.id.content);
		if (content == null) {
			return;
		}
		if (current != null && current.getParent() == content) {
			content.removeView(current);
		}

		LinearLayout pill = new LinearLayout(a);
		pill.setOrientation(LinearLayout.HORIZONTAL);
		pill.setGravity(Gravity.CENTER_VERTICAL);
		pill.setPadding(Sheet.dp(a, 16), Sheet.dp(a, 13),
				Sheet.dp(a, 18), Sheet.dp(a, 13));

		GradientDrawable bg = new GradientDrawable();
		bg.setCornerRadius(Sheet.dp(a, 16));
		bg.setColor(0xFF171D25);
		bg.setStroke(1, 0x26FFFFFF);
		pill.setBackground(bg);
		pill.setElevation(Sheet.dp(a, 18));

		TextView dot = new TextView(a);
		dot.setText("●");
		dot.setTextColor(a.getColor(R.color.accent));
		dot.setTextSize(8);
		dot.setIncludeFontPadding(false);
		LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		dlp.rightMargin = Sheet.dp(a, 11);
		pill.addView(dot, dlp);

		TextView text = new TextView(a);
		text.setText(msg);
		text.setTextColor(a.getColor(R.color.text));
		text.setTextSize(13.5f);
		text.setIncludeFontPadding(false);
		text.setMaxLines(3);
		text.setEllipsize(android.text.TextUtils.TruncateAt.END);
		text.setLineSpacing(Sheet.dp(a, 2), 1f);
		pill.addView(text, new LinearLayout.LayoutParams(
				0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
		lp.bottomMargin = Sheet.dp(a, 104);
		lp.leftMargin = Sheet.dp(a, 22);
		lp.rightMargin = Sheet.dp(a, 22);
		content.addView(pill, lp);
		current = pill;

		pill.setAlpha(0f);
		pill.setTranslationY(Sheet.dp(a, 18));
		pill.animate().alpha(1f).translationY(0f).setDuration(190).start();

		UI.postDelayed(() -> {
			if (pill.getParent() == null) {
				return;
			}
			pill.animate().alpha(0f).translationY(Sheet.dp(a, 10))
					.setDuration(170)
					.withEndAction(() -> {
						ViewGroup p = (ViewGroup) pill.getParent();
						if (p != null) {
							p.removeView(pill);
						}
						if (current == pill) {
							current = null;
						}
					})
					.start();
		}, 2700);
	}
}
