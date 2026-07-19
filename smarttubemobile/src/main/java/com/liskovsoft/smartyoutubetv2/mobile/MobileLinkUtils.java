package com.liskovsoft.smartyoutubetv2.mobile;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

final class MobileLinkUtils {
    private MobileLinkUtils() { }

    static void copyLink(Context context, Video video) {
        String link = linkFor(video);
        if (link == null) {
            Toast.makeText(context, R.string.link_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.video_link), link));
        Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show();
    }

    static String linkFor(Video video) {
        if (video == null) return null;
        if (!TextUtils.isEmpty(video.videoId)) return "https://youtu.be/" + video.videoId;
        if (!TextUtils.isEmpty(video.playlistId)) return "https://www.youtube.com/playlist?list=" + video.playlistId;
        if (!TextUtils.isEmpty(video.channelId)) return "https://www.youtube.com/channel/" + video.channelId;
        return null;
    }
}
