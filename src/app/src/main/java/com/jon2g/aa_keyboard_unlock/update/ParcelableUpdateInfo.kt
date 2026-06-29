package com.jon2g.aa_keyboard_unlock.update

import android.os.Parcel
import android.os.Parcelable

/** Parcelable wrapper for passing [UpdateInfo] into a [DialogFragment]. */
data class ParcelableUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val releaseUrl: String,
    val apkDownloadUrl: String,
) : Parcelable {

    fun toUpdateInfo() = UpdateInfo(
        versionName = versionName,
        versionCode = versionCode,
        changelog = changelog,
        releaseUrl = releaseUrl,
        apkDownloadUrl = apkDownloadUrl,
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(versionName)
        dest.writeInt(versionCode)
        dest.writeString(changelog)
        dest.writeString(releaseUrl)
        dest.writeString(apkDownloadUrl)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ParcelableUpdateInfo> {
        fun from(info: UpdateInfo) = ParcelableUpdateInfo(
            versionName = info.versionName,
            versionCode = info.versionCode,
            changelog = info.changelog,
            releaseUrl = info.releaseUrl,
            apkDownloadUrl = info.apkDownloadUrl,
        )

        override fun createFromParcel(source: Parcel) = ParcelableUpdateInfo(
            versionName = source.readString().orEmpty(),
            versionCode = source.readInt(),
            changelog = source.readString().orEmpty(),
            releaseUrl = source.readString().orEmpty(),
            apkDownloadUrl = source.readString().orEmpty(),
        )

        override fun newArray(size: Int): Array<ParcelableUpdateInfo?> = arrayOfNulls(size)
    }
}
