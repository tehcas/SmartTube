package com.liskovsoft.smartyoutubetv2.mobile;

import androidx.multidex.MultiDexApplication;

import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;

public final class MobileApplication extends MultiDexApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        System.setProperty("http.keepAlive", "false");
        GlobalPreferences.instance(this);

        ViewManager viewManager = ViewManager.instance(this);
        viewManager.setRoot(MainActivity.class);
        viewManager.register(BrowseView.class, MainActivity.class);
    }
}
