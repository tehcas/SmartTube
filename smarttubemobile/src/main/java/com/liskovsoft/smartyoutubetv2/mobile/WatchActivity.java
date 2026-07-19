package com.liskovsoft.smartyoutubetv2.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

public final class WatchActivity extends Activity {
    private static final String EXTRA_VIDEO_ID = "video_id";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_AUTHOR = "author";
    private static final String EXTRA_IMAGE = "image";

    public static Intent createIntent(Context context, Video video) {
        return new Intent(context, WatchActivity.class)
                .putExtra(EXTRA_VIDEO_ID, video.videoId)
                .putExtra(EXTRA_TITLE, video.getTitle())
                .putExtra(EXTRA_AUTHOR, video.getAuthor())
                .putExtra(EXTRA_IMAGE, video.getCardImageUrl());
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch);

        String videoId = getIntent().getStringExtra(EXTRA_VIDEO_ID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String author = getIntent().getStringExtra(EXTRA_AUTHOR);
        String image = getIntent().getStringExtra(EXTRA_IMAGE);

        ImageButton back = findViewById(R.id.watch_back);
        ImageView thumbnail = findViewById(R.id.watch_thumbnail);
        TextView titleView = findViewById(R.id.watch_title);
        TextView authorView = findViewById(R.id.watch_author);
        TextView idView = findViewById(R.id.watch_video_id);
        Button copyLink = findViewById(R.id.copy_watch_link);

        back.setOnClickListener(view -> finish());
        titleView.setText(TextUtils.isEmpty(title) ? getString(R.string.untitled_video) : title);
        authorView.setText(TextUtils.isEmpty(author) ? getString(R.string.smarttube_video) : author);
        idView.setText(getString(R.string.video_id_value, videoId));

        Glide.with(getApplicationContext())
                .load(image)
                .placeholder(R.drawable.ic_video_placeholder)
                .error(R.drawable.ic_video_placeholder)
                .centerCrop()
                .into(thumbnail);

        copyLink.setOnClickListener(view -> {
            String link = "https://youtu.be/" + videoId;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.video_link), link));
            Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
        });
    }
}
