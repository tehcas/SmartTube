package com.liskovsoft.smartyoutubetv2.mobile;

import android.app.Activity;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

final class MobileActionDialog {
    interface OpenAction { void open(Video video); }

    private MobileActionDialog() { }

    static void show(Activity activity, Video video, OpenAction opener) {
        String[] actions = {
                activity.getString(R.string.open_result),
                activity.getString(R.string.copy_video_link),
                activity.getString(R.string.save_to_playlist_account_required),
                activity.getString(R.string.subscribe_account_required)
        };
        new android.app.AlertDialog.Builder(activity)
                .setTitle(video.getTitle())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) opener.open(video);
                    else if (which == 1) MobileLinkUtils.copyLink(activity, video);
                    else Toast.makeText(activity, R.string.account_action_blocked, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
