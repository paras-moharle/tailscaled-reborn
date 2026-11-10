// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;

/**
 * Material 3 colour, produced at runtime.
 *
 * <p>The app is built with raw {@code javac}/{@code d8} and no dependencies,
 * so there is no Material library to hand this job to. This class does the
 * part that actually matters: it takes one seed colour, generates the two
 * tonal schemes Material 3 defines (light and dark), and exposes the colour
 * roles the rest of the app draws with.
 *
 * <p>On Android 12 and newer the seed comes from the wallpaper through
 * {@code system_accent1_*}, which is what makes TailBridge take the phone's
 * own palette instead of imposing a blue one. Below 12, and when the user
 * turns dynamic colour off, the seed is the app's own blue.
 *
 * <p>Dark mode has an extra option the Material spec does not: true black.
 * An OLED panel that is lit to #101419 is not "dark", it is a dark grey panel
 * paying for every pixel. The surface roles collapse to #000000 and the
 * container ladder restarts just above it.
 */
final class Theme {

	static final int MODE_SYSTEM = 0;
	static final int MODE_LIGHT = 1;
	static final int MODE_DARK = 2;

	private static final String PREFS = "appearance";
	private static final String K_MODE = "theme_mode";
	private static final String K_DYNAMIC = "dynamic_color";
	private static final String K_AMOLED = "amoled_black";

	private static final int SEED_FALLBACK = 0xFF3D8BFF;

	// ---------------------------------------------------------------- roles
	// Light or dark, resolved by apply() before any view is created.
	static boolean isDark;
	static boolean isAmoled;

	static int primary;
	static int onPrimary;
	static int primaryContainer;
	static int onPrimaryContainer;
	static int secondary;
	static int onSecondary;
	static int secondaryContainer;
	static int onSecondaryContainer;
	static int tertiary;
	static int onTertiary;

	static int surface;
	static int surfaceDim;
	static int surfaceBright;
	static int surfaceContainerLowest;
	static int surfaceContainerLow;
	static int surfaceContainer;
	static int surfaceContainerHigh;
	static int surfaceContainerHighest;
	static int onSurface;
	static int onSurfaceVariant;
	static int outline;
	static int outlineVariant;
	static int inverseSurface;
	static int inverseOnSurface;

	static int error;
	static int onError;
	static int errorContainer;
	static int onErrorContainer;
	static int success;
	static int warning;

	// Legacy role names the existing screens already draw with. Keeping them
	// as aliases means a screen keeps reading "text" and "hint" instead of
	// learning the Material vocabulary for the same two ideas.
	static int text;
	static int text2;
	static int hint;
	static int accent;
	static int accentHi;
	static int accentDim;
	static int onAccent;
	static int field;
	static int hairline;
	static int divider;
	static int trackOff;
	static int thumbOff;
	static int bg;
	static int surfaceTop;
	static int surfaceHi;
	static int ok;
	static int warn;
	static int err;
	static int scrim;
	static int successContainer;

	private Theme() {}

	// ------------------------------------------------------------- settings

	private static SharedPreferences prefs(Context c) {
		return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	static int mode(Context c) {
		return prefs(c).getInt(K_MODE, MODE_SYSTEM);
	}

	static void setMode(Context c, int mode) {
		prefs(c).edit().putInt(K_MODE, mode).apply();
	}

	static boolean dynamic(Context c) {
		return prefs(c).getBoolean(K_DYNAMIC, Build.VERSION.SDK_INT >= 31);
	}

	static void setDynamic(Context c, boolean on) {
		prefs(c).edit().putBoolean(K_DYNAMIC, on).apply();
	}

	static boolean amoled(Context c) {
		return prefs(c).getBoolean(K_AMOLED, true);
	}

	static void setAmoled(Context c, boolean on) {
		prefs(c).edit().putBoolean(K_AMOLED, on).apply();
	}

	static boolean systemIsDark(Context c) {
		int night = c.getResources().getConfiguration().uiMode
				& Configuration.UI_MODE_NIGHT_MASK;
		return night == Configuration.UI_MODE_NIGHT_YES;
	}

	static boolean darkNow(Context c) {
		int m = mode(c);
		if (m == MODE_DARK) {
			return true;
		}
		if (m == MODE_LIGHT) {
			return false;
		}
		return systemIsDark(c);
	}

	/** The seed: the wallpaper accent when allowed, the brand blue otherwise. */
	static int seedColor(Context c) {
		if (dynamic(c) && Build.VERSION.SDK_INT >= 31) {
			int[] tones = {
					android.R.color.system_accent1_500,
					android.R.color.system_accent1_400,
					android.R.color.system_accent1_600,
			};
			for (int t : tones) {
				try {
					int v = c.getColor(t);
					if (Color.alpha(v) == 255) {
						return v;
					}
				} catch (Exception ignored) {
					// A ROM without the Android 12 palette: fall through.
				}
			}
		}
		return SEED_FALLBACK;
	}

	// ------------------------------------------------------------- applying

	/**
	 * Resolve the palette and put the window into the right state.
	 *
	 * <p>Must run after {@code super.onCreate()} and before
	 * {@code setContentView()}: the theme resource has to be set before the
	 * layout is inflated, or the first frame is drawn with the old one.
	 */
	static void apply(Activity a) {
		boolean dark = darkNow(a);
		a.setTheme(dark ? R.style.Theme_Share_Dark : R.style.Theme_Share_Light);
		isDark = dark;
		isAmoled = dark && amoled(a);
		buildPalette(seedColor(a), dark, isAmoled);
		paintWindow(a);
	}

	private static void paintWindow(Activity a) {
		Window w = a.getWindow();
		w.setBackgroundDrawable(new ColorDrawable(surface));
		w.setStatusBarColor(Color.TRANSPARENT);
		w.setNavigationBarColor(surface);
		View decor = w.getDecorView();
		int flags = decor.getSystemUiVisibility();
		if (isDark) {
			flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
			if (Build.VERSION.SDK_INT >= 26) {
				flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
			}
		} else {
			flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
			if (Build.VERSION.SDK_INT >= 26) {
				flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
			}
		}
		decor.setSystemUiVisibility(flags);
	}

	// -------------------------------------------------------------- palette

	static void buildPalette(int seed, boolean dark, boolean black) {
		float seedHue = hue(seed);
		float seedSat = saturation(seed);

		if (!dark) {
			primary = tone(seed, 40);
			onPrimary = tone(seed, 100);
			primaryContainer = tone(seed, 90);
			onPrimaryContainer = tone(seed, 10);
			secondary = tone(desaturate(seed, 0.55f), 40);
			onSecondary = tone(seed, 100);
			secondaryContainer = tone(desaturate(seed, 0.45f), 90);
			onSecondaryContainer = tone(desaturate(seed, 0.45f), 10);
			tertiary = tone(shiftHue(seed, 60), 40);
			onTertiary = tone(seed, 100);

			surface = neutral(seedHue, 98.5f);
			surfaceDim = neutral(seedHue, 87);
			surfaceBright = neutral(seedHue, 99);
			surfaceContainerLowest = neutral(seedHue, 100);
			surfaceContainerLow = neutral(seedHue, 96);
			surfaceContainer = neutral(seedHue, 94);
			surfaceContainerHigh = neutral(seedHue, 92);
			surfaceContainerHighest = neutral(seedHue, 90);
			onSurface = neutral(seedHue, 10);
			onSurfaceVariant = neutral(seedHue, 30);
			outline = neutral(seedHue, 50);
			outlineVariant = neutral(seedHue, 80);
			inverseSurface = neutral(seedHue, 20);
			inverseOnSurface = neutral(seedHue, 95);

			error = 0xFFB3261E;
			onError = 0xFFFFFFFF;
			errorContainer = 0xFFF9DEDC;
			onErrorContainer = 0xFF410E0B;
			success = 0xFF146C2E;
			warning = 0xFF8A5100;
		} else {
			primary = tone(seed, 80);
			onPrimary = tone(seed, 20);
			primaryContainer = tone(seed, 30);
			onPrimaryContainer = tone(seed, 90);
			secondary = tone(desaturate(seed, 0.55f), 80);
			onSecondary = tone(desaturate(seed, 0.55f), 20);
			secondaryContainer = tone(desaturate(seed, 0.45f), 30);
			onSecondaryContainer = tone(desaturate(seed, 0.45f), 90);
			tertiary = tone(shiftHue(seed, 60), 80);
			onTertiary = tone(shiftHue(seed, 60), 20);

			surface = black ? 0xFF000000 : neutral(seedHue, 6);
			surfaceDim = black ? 0xFF000000 : neutral(seedHue, 6);
			surfaceBright = neutral(seedHue, 24);
			surfaceContainerLowest = black ? 0xFF000000 : neutral(seedHue, 4);
			surfaceContainerLow = black ? 0xFF07090C : neutral(seedHue, 10);
			surfaceContainer = black ? 0xFF0C0F13 : neutral(seedHue, 12);
			surfaceContainerHigh = black ? 0xFF12161C : neutral(seedHue, 17);
			surfaceContainerHighest = black ? 0xFF181D25 : neutral(seedHue, 22);
			onSurface = neutral(seedHue, 90);
			onSurfaceVariant = neutral(seedHue, 80);
			outline = neutral(seedHue, 60);
			outlineVariant = black ? 0xFF2A3038 : neutral(seedHue, 30);
			inverseSurface = neutral(seedHue, 90);
			inverseOnSurface = neutral(seedHue, 20);

			error = 0xFFF2B8B5;
			onError = 0xFF601410;
			errorContainer = 0xFF8C1D18;
			onErrorContainer = 0xFFF9DEDC;
			success = 0xFF6DD58C;
			warning = 0xFFFFB95C;
		}

		// Aliases.
		text = onSurface;
		text2 = onSurfaceVariant;
		hint = outline;
		accent = primary;
		accentHi = dark ? tone(seed, 88) : tone(seed, 50);
		accentDim = withAlpha(primary, dark ? 0x2E : 0x1F);
		onAccent = onPrimary;
		field = surfaceContainerHigh;
		hairline = withAlpha(outlineVariant, dark ? 0x66 : 0x55);
		divider = withAlpha(outlineVariant, dark ? 0x99 : 0x77);
		trackOff = withAlpha(onSurfaceVariant, 0x55);
		thumbOff = outline;
		bg = surface;
		surfaceTop = surfaceContainerLow;
		surfaceHi = surfaceContainerHighest;
		ok = success;
		warn = warning;
		err = error;
		scrim = withAlpha(0xFF000000, 0x99);
		successContainer = withAlpha(success, 0x24);
	}

	// ---------------------------------------------------------------- maths

	static int withAlpha(int color, int alpha) {
		return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
	}

	private static int neutral(float hue, float lightness) {
		return hsl(hue, 0.06f, lightness);
	}

	private static float hue(int color) {
		float[] h = new float[3];
		Color.colorToHSV(color, h);
		return h[0];
	}

	private static float saturation(int color) {
		float[] h = new float[3];
		Color.colorToHSV(color, h);
		return h[1];
	}

	private static int desaturate(int color, float factor) {
		float[] h = new float[3];
		Color.colorToHSV(color, h);
		h[1] = h[1] * factor;
		return Color.HSVToColor(h);
	}

	private static int shiftHue(int color, float degrees) {
		float[] h = new float[3];
		Color.colorToHSV(color, h);
		h[0] = (h[0] + degrees) % 360f;
		return Color.HSVToColor(h);
	}

	/** A Material tone: same chroma, the lightness the spec asks for. */
	private static int tone(int color, float tone) {
		float[] h = new float[3];
		Color.colorToHSV(color, h);
		float t = tone / 100f;
		// Chroma falls away at both ends of the scale, which is what keeps a
		// tone-95 container from reading as a saturated colour.
		float drop = (float) Math.pow(Math.abs(t - 0.5f) / 0.5f, 1.7);
		h[1] = h[1] * (1f - 0.9f * drop);
		h[2] = t;
		return Color.HSVToColor(h);
	}

	private static int hsl(float hue, float sat, float lightness) {
		float[] h = {hue, sat, lightness / 100f};
		return Color.HSVToColor(h);
	}

	static int mix(int a, int b, float t) {
		float p = 1f - t;
		int r = (int) (Color.red(a) * p + Color.red(b) * t);
		int g = (int) (Color.green(a) * p + Color.green(b) * t);
		int bl = (int) (Color.blue(a) * p + Color.blue(b) * t);
		return Color.rgb(r, g, bl);
	}

	/** Small helper for code that receives a resource id rather than a role. */
	static int role(int colorRes) {
		switch (colorRes) {
			case R.color.text: return text;
			case R.color.text2: return text2;
			case R.color.hint: return hint;
			case R.color.accent: return accent;
			case R.color.accentHi: return accentHi;
			case R.color.accentDim: return accentDim;
			case R.color.onAccent: return onAccent;
			case R.color.ok: return ok;
			case R.color.warn: return warn;
			case R.color.err: return err;
			case R.color.surfaceHi: return surfaceHi;
			case R.color.divider: return divider;
			case R.color.hairline: return hairline;
			case R.color.field: return field;
			case R.color.trackOff: return trackOff;
			case R.color.thumbOff: return thumbOff;
			default: return text;
		}
	}
}
