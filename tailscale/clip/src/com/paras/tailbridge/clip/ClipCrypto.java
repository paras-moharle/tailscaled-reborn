// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge.clip;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.KeyStore;
import java.security.Security;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts and decrypts the clipboard history.
 *
 * <p>The history is a file of things people copied. Some of those things
 * are passwords, one-time codes and private messages, and a file of them
 * sitting in plain text is a file anyone with a copy of the device's
 * storage can read. Base64 is encoding, not encryption, and it stops
 * nobody.
 *
 * <p>So the text is encrypted with a key that lives in the device's
 * hardware keystore. The key is made on first use, never leaves the secure
 * hardware, and cannot be exported even by root: what root can do is ask
 * the hardware to use the key, and the hardware will only do that for this
 * device. A copy of the storage is a copy of ciphertext.
 *
 * <p>Getting at the keystore from here takes a little doing. The platform
 * splits it into two providers -- one that holds the keys and makes them,
 * one that does the cryptography -- and the zygote registers both for
 * every app it starts. This program is not started by the zygote, so it
 * registers them itself. Everything else is ordinary JCA.
 *
 * <p>The key belongs to whoever runs this program. The module runs it as
 * root, so the key lives in root's own keystore namespace, and the file
 * cannot be read by anything else on the device either.
 *
 * <p>Usage:
 *
 * <pre>
 *   probe          says whether the keystore can be reached at all
 *   seal           one entry, ready to be written down
 *   encrypt        base64 plaintext in, base64 ciphertext out
 *   decrypt        base64 ciphertext in, base64 plaintext out
 *   encrypt-store  a whole store, sealed
 *   decrypt-store  a whole store, opened
 * </pre>
 */
public class ClipCrypto {

	/** The name the encryption key is stored under. */
	private static final String ALIAS = "tailbridge-clipboard-v1";

	/** The name the hashing key is stored under. */
	private static final String MAC_ALIAS = "tailbridge-clipboard-hmac-v1";

	/** The transformation. AES in Galois/Counter mode: authenticated, so a
	 *  file that has been tampered with is refused rather than decrypted
	 *  into nonsense. */
	private static final String TRANSFORM = "AES/GCM/NoPadding";

	/** The provider that does the cryptography for keys held in the
	 *  keystore. The other one holds the keys and makes them. */
	private static final String CRYPTO_PROVIDER = "AndroidKeyStoreBCWorkaround";

	/** The provider that holds the keys. */
	private static final String KEYSTORE = "AndroidKeyStore";

	/** The length of the nonce GCM uses. */
	private static final int IV_BYTES = 12;

	/**
	 * What has been opened already.
	 *
	 * <p>Asking the hardware to open an entry costs a few milliseconds, and
	 * the list of the history is asked for over and over -- every time the
	 * clipboard screen is refreshed. The text of an entry does not change
	 * once it is written, so the answer is kept.
	 *
	 * <p>The key is the ciphertext, which is different for every entry and
	 * does not change when the entry is starred or moved.
	 */
	private static final Map<String, byte[]> OPENED =
			Collections.synchronizedMap(new LinkedHashMap<String, byte[]>(256, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
					return size() > 6000;
				}
			});

	/** The authentication tag, which GCM appends to the ciphertext. */
	private static final int TAG_BITS = 128;

	public static void main(String[] args) {
		String mode = args.length > 0 ? args[0] : "";
		try {
			if ("probe".equals(mode)) {
				probe();
				System.out.println("ok");
			} else if ("seal".equals(mode)) {
				System.out.println(sealEntry(readAll().trim(),
						args.length > 1 ? args[1] : "",
						args.length > 2 ? args[2] : "0"));
			} else if ("encrypt".equals(mode)) {
				byte[] plain = Base64.getDecoder().decode(readAll());
				byte[] out = seal(key(), plain);
				System.out.println(Base64.getEncoder().encodeToString(out));
			} else if ("decrypt".equals(mode)) {
				byte[] in = Base64.getDecoder().decode(readAll());
				byte[] out = open(key(), in);
				System.out.println(Base64.getEncoder().encodeToString(out));
			} else if ("encrypt-store".equals(mode)) {
				System.out.print(encryptStore(readAll()));
			} else if ("decrypt-store".equals(mode)) {
				System.out.print(decryptStore(readAll()));
			} else {
				System.out.println("err:unknown mode " + mode);
				System.exit(2);
			}
		} catch (Throwable t) {
			Throwable c = t;
			while (c.getCause() != null) {
				c = c.getCause();
			}
			// A line the caller can recognise. The caller falls back to
			// storing the text as it is, and says so, rather than
			// pretending the history is protected when it is not.
			System.out.println("err:" + c);
			System.exit(1);
		}
	}

	/**
	 * Does the keystore work for us at all?
	 *
	 * <p>The keystore keeps a separate set of keys for every user of the
	 * device, and a key that lives in the wrong one cannot read a history
	 * written with the other. So the caller asks first, and remembers the
	 * answer.
	 */
	static void probe() throws Exception {
		registerProviders();
		key();
		macKey();
	}

	/**
	 * One history entry, ready to be written down.
	 *
	 * <p>The text is encrypted, and a keyed hash of it goes beside it. The
	 * hash is what lets the watcher notice that the same thing has been
	 * copied again without decrypting anything -- and because it is keyed
	 * with a key in the hardware, the hash of a password cannot be attacked
	 * the way a plain hash of one can.
	 */
	static String sealEntry(String b64, String source, String dir) throws Exception {
		SecretKey key = key();
		SecretKey macKey = macKey();
		byte[] plain = Base64.getDecoder().decode(b64);
		org.json.JSONObject o = new org.json.JSONObject();
		o.put("t", System.currentTimeMillis());
		o.put("c", Base64.getEncoder().encodeToString(seal(key, plain)));
		o.put("h", Base64.getEncoder().encodeToString(mac(macKey, plain)));
		o.put("s", source);
		o.put("d", dir.isEmpty() ? 0 : Integer.parseInt(dir));
		o.put("p", 0);
		return o.toString();
	}

	/**
	 * The two providers the platform keeps the keystore in.
	 *
	 * <p>The zygote registers them for every app it starts, so an ordinary
	 * app never has to think about it. This program is started by
	 * app_process rather than by the zygote, so it has to do it itself.
	 */
	private static void registerProviders() throws Exception {
		addProvider("android.security.keystore2.AndroidKeyStoreProvider", KEYSTORE, 1);
		addProvider("android.security.keystore2.AndroidKeyStoreBCWorkaroundProvider",
				CRYPTO_PROVIDER, 2);
	}

	/** Adds one of them, if it is not there already. */
	private static void addProvider(String name, String providerName, int position) throws Exception {
		try {
			if (Security.getProvider(providerName) != null) {
				return;
			}
		} catch (Throwable ignored) {
			// Fall through and try to add it.
		}
		Class<?> p = Class.forName(name);
		java.lang.reflect.Constructor<?> ctor = p.getDeclaredConstructor();
		ctor.setAccessible(true);
		Security.insertProviderAt((java.security.Provider) ctor.newInstance(), position);
	}

	/**
	 * The key, made on first use and fetched after that.
	 *
	 * <p>It is generated inside the secure hardware. That is the whole
	 * point: a key that lives in a file can be copied out with the file,
	 * and a key that lives in the hardware cannot.
	 */
	private static SecretKey key() throws Exception {
		registerProviders();
		KeyStore ks = KeyStore.getInstance(KEYSTORE);
		ks.load(null);
		if (ks.containsAlias(ALIAS)) {
			SecretKey k = (SecretKey) ks.getKey(ALIAS, null);
			if (k != null) {
				return k;
			}
		}
		KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
		int purposes = KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT;
		try {
			kg.init(spec(ALIAS, purposes, true, true));
			return kg.generateKey();
		} catch (Throwable ignored) {
			// No StrongBox on this device. The default keystore is still
			// hardware backed, which is what matters.
		}
		kg.init(spec(ALIAS, purposes, true, false));
		return kg.generateKey();
	}

	/**
	 * The key the hash is made with.
	 *
	 * <p>It is a key of its own because the hardware will not make a keyed
	 * hash with an encryption key. The hash is what lets the watcher notice
	 * that the same thing has been copied twice without decrypting
	 * anything, and because it is made with a key in the hardware, the hash
	 * of a password cannot be attacked the way a plain hash of one can.
	 */
	private static SecretKey macKey() throws Exception {
		registerProviders();
		KeyStore ks = KeyStore.getInstance(KEYSTORE);
		ks.load(null);
		if (ks.containsAlias(MAC_ALIAS)) {
			SecretKey k = (SecretKey) ks.getKey(MAC_ALIAS, null);
			if (k != null) {
				return k;
			}
		}
		KeyGenerator kg = KeyGenerator.getInstance("HmacSHA256", KEYSTORE);
		kg.init(spec(MAC_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY, false, false));
		return kg.generateKey();
	}

	/**
	 * What a key is allowed to do.
	 *
	 * <p>StrongBox is a separate chip that is slower but even harder to
	 * get at. Not every device has one, and a key that cannot be made is
	 * worse than a key that can, so it is only asked for when the hardware
	 * offers it.
	 */
	private static KeyGenParameterSpec spec(String alias, int purposes, boolean cipher,
			boolean strongBox) throws Exception {
		KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias, purposes);
		if (cipher) {
			b.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
					.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
					.setKeySize(256);
			if (strongBox) {
				b.setIsStrongBoxBacked(true);
			}
		} else {
			b.setDigests(KeyProperties.DIGEST_SHA256).setKeySize(256);
		}
		return b.build();
	}

	/**
	 * A keyed hash of the text.
	 *
	 * <p>Made with a key in the hardware, so the result is useless to
	 * anyone who does not have the key -- which is the point of it being
	 * keyed rather than a plain hash.
	 */
	private static byte[] mac(SecretKey key, byte[] plain) throws Exception {
		Mac m = Mac.getInstance("HmacSHA256", CRYPTO_PROVIDER);
		m.init(key);
		return m.doFinal(plain);
	}

	/** Encrypt, with the nonce in front of the ciphertext. */
	private static byte[] seal(SecretKey key, byte[] plain) throws Exception {
		Cipher c = Cipher.getInstance(TRANSFORM, CRYPTO_PROVIDER);
		c.init(Cipher.ENCRYPT_MODE, key);
		byte[] iv = c.getIV();
		byte[] body = c.doFinal(plain);
		byte[] out = new byte[iv.length + body.length];
		System.arraycopy(iv, 0, out, 0, iv.length);
		System.arraycopy(body, 0, out, iv.length, body.length);
		return out;
	}

	/** Decrypt, taking the nonce off the front. */
	private static byte[] open(SecretKey key, byte[] in) throws Exception {
		if (in.length <= IV_BYTES) {
			throw new IllegalArgumentException("ciphertext is too short");
		}
		byte[] iv = new byte[IV_BYTES];
		System.arraycopy(in, 0, iv, 0, IV_BYTES);
		byte[] body = new byte[in.length - IV_BYTES];
		System.arraycopy(in, IV_BYTES, body, 0, body.length);
		Cipher c = Cipher.getInstance(TRANSFORM, CRYPTO_PROVIDER);
		c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
		return c.doFinal(body);
	}

	/**
	 * The whole store, in one pass.
	 *
	 * <p>Reads JSON lines on stdin, works on the "c" field of each, and
	 * writes the lines back out. Encrypting or decrypting five thousand
	 * entries one helper process at a time would take minutes; doing it
	 * here is one process and one pass.
	 *
	 * <p>An entry that will not decrypt is left exactly as it is. That is
	 * what lets a store written before there was any encryption be read: the
	 * old entries are still plain text, and the watcher encrypts them when
	 * it next starts.
	 */
	/** The whole store, sealed. */
	static String encryptStore(String text) throws Exception {
		return store(key(), macKey(), true, text);
	}

	/** The whole store, opened. */
	static String decryptStore(String text) throws Exception {
		return store(key(), macKey(), false, text);
	}

	/**
	 * The whole store, in one pass.
	 *
	 * <p>Reads JSON lines, works on the "c" field of each, and writes the
	 * lines back out. Encrypting or decrypting five thousand entries one
	 * helper process at a time would take minutes; doing it here is one
	 * pass.
	 *
	 * <p>An entry that will not decrypt is left exactly as it is. That is
	 * what lets a store written before there was any encryption be read: the
	 * old entries are still plain text, and the watcher encrypts them when
	 * it next starts.
	 */
	private static String store(SecretKey key, SecretKey macKey, boolean encrypt, String text)
			throws Exception {
		StringBuilder out = new StringBuilder();
		BufferedReader in = new BufferedReader(new InputStreamReader(
				new java.io.ByteArrayInputStream(text.getBytes("UTF-8")), "UTF-8"));
		String line;
		while ((line = in.readLine()) != null) {
			line = line.trim();
			if (line.isEmpty()) {
				continue;
			}
			try {
				org.json.JSONObject o = new org.json.JSONObject(line);
				String c = o.optString("c", "");
				if (!c.isEmpty()) {
					byte[] raw = Base64.getDecoder().decode(c);
					if (encrypt) {
						// An entry that is already sealed is left exactly as
						// it is: sealing it twice would leave a history that
						// can never be read.
						//
						// The hash says which is which, and asking is free.
						// Asking the hardware to open the entry is not: it
						// costs a few milliseconds each, and a history of
						// five thousand entries would take a minute and a
						// half to go through.
						//
						// A sealed entry has a hash made with the hardware
						// key, which is base64 and so never contains a
						// colon. An entry written when the keystore could
						// not be reached is marked "plain:", and an entry
						// from before there was any encryption has no hash
						// at all. Both of those need sealing.
						String hash = o.optString("h", "");
						if (hash.isEmpty() || hash.startsWith("plain:")) {
							o.put("c", Base64.getEncoder().encodeToString(seal(key, raw)));
							o.put("h", Base64.getEncoder().encodeToString(mac(macKey, raw)));
						}
					} else {
						byte[] plain = OPENED.get(c);
						if (plain == null) {
							plain = open(key, raw);
							OPENED.put(c, plain);
						}
						o.put("c", Base64.getEncoder().encodeToString(plain));
						// The hash is for the store, not for whoever
						// is reading it.
						o.remove("h");
					}
				}
				out.append(o.toString()).append('\n');
			} catch (Throwable t) {
				// Not ours to read, or not something we wrote. Keep it.
				out.append(line).append('\n');
			}
		}
		return out.toString();
	}

	/** Everything on stdin, with the trailing newline removed. */
	private static String readAll() throws Exception {
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = in.readLine()) != null) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(line);
		}
		return sb.toString();
	}
}
