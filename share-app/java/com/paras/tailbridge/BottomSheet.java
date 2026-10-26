// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The action sheet: what a long press opens.
 *
 * <p>A list of things that can be done to one item, at the bottom of the
 * screen where the thumb is, under a short title. It is deliberately not a
 * dialog with a row of buttons: actions are read top to bottom, and the
 * dangerous one is coloured so it cannot be tapped by accident.
 */
final class BottomSheet {

	private final Activity a;
	private final Sheet sheet;
	private final LinearLayout list;
	private final TextView title;

	BottomSheet(Activity a, String heading) {
		this.a = a;
		sheet = Sheet.bottom(a);

		// The grabber: the small bar that says "this came from the bottom".
		View grab = new View(a);
		GradientDrawable g = new GradientDrawable();
		g.setCornerRadius(Sheet.dp(a, 2));
		g.setColor(0x33FFFFFF);
		grab.setBackground(g);
		LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
				Sheet.dp(a, 34), Sheet.dp(a, 4));
		glp.gravity = Gravity.CENTER_HORIZONTAL;
		glp.topMargin = Sheet.dp(a, 2);
		glp.bottomMargin = Sheet.dp(a, 14);

		title = new TextView(a);
		title.setText(heading);
		title.setTextColor(a.getColor(R.color.text));
		title.setTextSize(16);
		title.setTypeface(null, android.graphics.Typeface.BOLD);
		title.setIncludeFontPadding(false);
		title.setMaxLines(2);
		title.setEllipsize(android.text.TextUtils.TruncateAt.END);
		title.setPadding(Sheet.dp(a, 20), 0, Sheet.dp(a, 20), Sheet.dp(a, 12));

		list = new LinearLayout(a);
		list.setOrientation(LinearLayout.VERTICAL);

		LinearLayout all = new LinearLayout(a);
		all.setOrientation(LinearLayout.VERTICAL);
		all.addView(grab, glp);
		all.addView(title);
		all.addView(list);
		sheet.add(all);
	}

	BottomSheet action(int icon, String label, boolean dangerous, Runnable action) {
		LinearLayout row = new LinearLayout(a);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(Sheet.dp(a, 20), Sheet.dp(a, 15),
				Sheet.dp(a, 20), Sheet.dp(a, 15));
		row.setBackgroundResource(R.drawable.row_press);
		row.setClickable(true);

		if (icon != 0) {
			ImageView ic = new ImageView(a);
			ic.setImageResource(icon);
			ic.setColorFilter(a.getColor(dangerous ? R.color.err : R.color.text2));
			LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
					Sheet.dp(a, 19), Sheet.dp(a, 19));
			ilp.rightMargin = Sheet.dp(a, 16);
			row.addView(ic, ilp);
		}

		TextView t = new TextView(a);
		t.setText(label);
		t.setTextColor(a.getColor(dangerous ? R.color.err : R.color.text));
		t.setTextSize(15.5f);
		t.setIncludeFontPadding(false);
		row.addView(t, new LinearLayout.LayoutParams(
				0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		row.setOnClickListener(v -> {
			sheet.dismiss();
			if (action != null) {
				new android.os.Handler(android.os.Looper.getMainLooper())
						.postDelayed(action, 170);
			}
		});
		list.addView(row);
		return this;
	}

	void heading(String text) {
		title.setText(text);
	}

	/** The always-present way out, under the actions. */
	void cancel() {
		TextView b = new TextView(a);
		b.setText(android.R.string.cancel);
		b.setGravity(Gravity.CENTER);
		b.setTextColor(a.getColor(R.color.text));
		b.setTextSize(15);
		b.setIncludeFontPadding(false);
		b.setBackground(Sheet.pill(a.getColor(R.color.surfaceHi), 0x26FFFFFF));
		b.setClickable(true);
		b.setFocusable(true);
		b.setOnClickListener(v -> sheet.dismiss());
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, Sheet.dp(a, 50));
		lp.topMargin = Sheet.dp(a, 14);
		lp.leftMargin = Sheet.dp(a, 16);
		lp.rightMargin = Sheet.dp(a, 16);
		lp.bottomMargin = Sheet.dp(a, 8);
		View parent = (View) list.getParent();
		((LinearLayout) parent).addView(b, lp);
	}

	BottomSheet show() {
		sheet.show();
		return this;
	}

	void dismiss() {
		sheet.dismiss();
	}
}
