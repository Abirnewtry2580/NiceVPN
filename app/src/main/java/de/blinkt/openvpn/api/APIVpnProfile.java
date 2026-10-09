/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * This file is used for implementing the external API and this file like the AIDL and is exempted
 * from the GPLv2.
 */
package de.blinkt.openvpn.api;

import android.os.Parcel;
import android.os.Parcelable;

public class APIVpnProfile implements Parcelable {
    public final String mUUID;
    public final String mName;
    public final boolean mUserEditable;

    public APIVpnProfile(Parcel in) {
        mUUID = in.readString();
        mName = in.readString();
        mUserEditable = in.readInt() != 0;
    }

    public APIVpnProfile(String uuid, String name, boolean userEditable, String profileCreator) {
        mUUID = uuid;
        mName = name;
        mUserEditable = userEditable;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mUUID);
        dest.writeString(mName);
        dest.writeInt(mUserEditable ? 0 : 1);
    }

    public static final Creator<APIVpnProfile> CREATOR = new Creator<APIVpnProfile>() {
        @Override
        public APIVpnProfile createFromParcel(Parcel in) {
            return new APIVpnProfile(in);
        }

        @Override
        public APIVpnProfile[] newArray(int size) {
            return new APIVpnProfile[size];
        }
    };
}
