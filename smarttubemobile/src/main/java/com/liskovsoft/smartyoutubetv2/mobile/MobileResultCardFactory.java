package com.liskovsoft.smartyoutubetv2.mobile;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

final class MobileResultCardFactory {
    interface Listener {
        void onOpen(Video video);
        void onMore(Video video);
    }

    private MobileResultCardFactory() { }

    static View create(Activity activity, Video video, Listener listener) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        card.setBackground(background(activity));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(type(activity, video) + ", " + video.getTitle());

        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new LinearLayout.LayoutParams(dp(activity, 156), dp(activity, 92)));
        Glide.with(activity.getApplicationContext()).load(video.getCardImageUrl())
                .placeholder(R.drawable.ic_video_placeholder)
                .error(R.drawable.ic_video_placeholder)
                .centerCrop().into(image);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(activity, 14), 0, dp(activity, 8), 0);
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView type = new TextView(activity);
        type.setText(type(activity, video));
        type.setTextColor(color(activity, R.color.smarttube_accent));
        type.setTextSize(11);
        type.setAllCaps(true);
        text.addView(type);

        TextView title = new TextView(activity);
        title.setText(TextUtils.isEmpty(video.getTitle()) ? activity.getString(R.string.untitled_video) : video.getTitle());
        title.setTextColor(color(activity, R.color.smarttube_text_primary));
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title);

        TextView metadata = new TextView(activity);
        metadata.setText(video.getAuthor());
        metadata.setTextColor(color(activity, R.color.smarttube_text_secondary));
        metadata.setTextSize(13);
        metadata.setMaxLines(1);
        text.addView(metadata);

        TextView overflow = new TextView(activity);
        overflow.setText("⋮");
        overflow.setTextColor(color(activity, R.color.smarttube_text_primary));
        overflow.setTextSize(25);
        overflow.setGravity(Gravity.CENTER);
        overflow.setContentDescription(activity.getString(R.string.more_actions_for, video.getTitle()));
        overflow.setOnClickListener(view -> listener.onMore(video));
        card.addView(overflow, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 56)));

        card.setOnClickListener(view -> listener.onOpen(video));
        card.setOnLongClickListener(view -> {
            listener.onMore(video);
            return true;
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(activity, 12), dp(activity, 5), dp(activity, 12), dp(activity, 5));
        card.setLayoutParams(params);
        return card;
    }

    private static String type(Activity activity, Video video) {
        if (video.isPlaylistAsChannel() || (!video.hasVideo() && video.hasPlaylist())) return activity.getString(R.string.result_playlist);
        if (video.isChannel()) return activity.getString(R.string.result_channel);
        if (video.isShorts) return activity.getString(R.string.result_shorts);
        if (video.isLive) return activity.getString(R.string.result_live);
        return activity.getString(R.string.result_video);
    }

    private static GradientDrawable background(Activity activity) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(activity, R.color.smarttube_surface));
        drawable.setCornerRadius(dp(activity, 14));
        return drawable;
    }

    private static int color(Activity activity, int resource) {
        return ContextCompat.getColor(activity, resource);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
