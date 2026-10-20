// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package android.content;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * A hand-written copy of the platform's hidden {@code android.content.IOnPrimaryClipChangedListener}.
 *
 * <p>The real interface is not in the public SDK, so it cannot be compiled against
 * directly. This copy exists only so that {@link com.paras.tailbridge.clip.ClipBoard}
 * can compile: at run time the boot class path wins for anything in {@code android.*},
 * so the class actually used is the platform's own, and the transaction code and
 * descriptor below are the ones the clipboard service calls back on.
 *
 * <p>Both must match the platform exactly:
 *
 * <pre>
 *   DESCRIPTOR = "android.content.IOnPrimaryClipChangedListener"
 *   TRANSACTION_dispatchPrimaryClipChanged = IBinder.FIRST_CALL_TRANSACTION + 0
 * </pre>
 *
 * <p>Modelled on ClipShare's copy of the same interface.
 */
public interface IOnPrimaryClipChangedListener extends IInterface {

	/** Called by the clipboard service when the primary clip changes. */
	void dispatchPrimaryClipChanged() throws RemoteException;

	abstract class Stub extends Binder implements IOnPrimaryClipChangedListener {

		public static final String DESCRIPTOR = "android.content.IOnPrimaryClipChangedListener";

		static final int TRANSACTION_dispatchPrimaryClipChanged =
				(IBinder.FIRST_CALL_TRANSACTION + 0);

		public Stub() {
			attachInterface(this, DESCRIPTOR);
		}

		public static IOnPrimaryClipChangedListener asInterface(IBinder obj) {
			if (obj == null) {
				return null;
			}
			IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
			if (iin instanceof IOnPrimaryClipChangedListener) {
				return (IOnPrimaryClipChangedListener) iin;
			}
			return new Proxy(obj);
		}

		@Override
		public IBinder asBinder() {
			return this;
		}

		@Override
		public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
				throws RemoteException {
			switch (code) {
				case INTERFACE_TRANSACTION: {
					reply.writeString(DESCRIPTOR);
					return true;
				}
				case TRANSACTION_dispatchPrimaryClipChanged: {
					data.enforceInterface(DESCRIPTOR);
					dispatchPrimaryClipChanged();
					reply.writeNoException();
					return true;
				}
				default:
					return super.onTransact(code, data, reply, flags);
			}
		}

		private static class Proxy implements IOnPrimaryClipChangedListener {
			private final IBinder mRemote;

			Proxy(IBinder remote) {
				mRemote = remote;
			}

			@Override
			public IBinder asBinder() {
				return mRemote;
			}

			@Override
			public void dispatchPrimaryClipChanged() throws RemoteException {
				Parcel _data = Parcel.obtain();
				Parcel _reply = Parcel.obtain();
				try {
					_data.writeInterfaceToken(DESCRIPTOR);
					mRemote.transact(TRANSACTION_dispatchPrimaryClipChanged, _data, _reply, 0);
					_reply.readException();
				} finally {
					_reply.recycle();
					_data.recycle();
				}
			}
		}
	}
}
