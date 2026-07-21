package com.liskovsoft.smartyoutubetv2.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.liskovsoft.mediaserviceinterfaces.SignInService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.BrowseProcessorManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.DeArrowData;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

public final class MainActivity extends Activity implements BrowseView, MediaServiceManager.AccountChangeListener {
    private static boolean sResumeAccountSelectionAfterConfigurationChange;
    private static final Object ACCOUNT_FINGERPRINT_LOCK = new Object();
    private static final String ACCOUNT_FINGERPRINT_PREFS = "mobile_account_fingerprint";
    private static final String ACCOUNT_FINGERPRINT_SALT = "salt";
    private static final String ACCOUNT_TRANSACTION_PREFS = "mobile_account_selection_transaction";
    private static final String ACCOUNT_TRANSACTION_PENDING = "pending";
    private static final String ACCOUNT_TRANSACTION_PREVIOUS_NULL = "previous_null";
    private static final String ACCOUNT_TRANSACTION_PREVIOUS_FINGERPRINT = "previous_fingerprint";
    private static final String ACCOUNT_TRANSACTION_TARGET_NULL = "target_null";
    private static final String ACCOUNT_TRANSACTION_TARGET_FINGERPRINT = "target_fingerprint";
    private static final String ACCOUNT_HISTORY_PREFS = "mobile_account_history";
    private static final String ACCOUNT_HISTORY_KEY_PREFIX = "history_";
    private static final String ACCOUNT_HISTORY_MIGRATION_COMPLETE = "migration_complete";
    private static final String STATE_SECTION_ID = "mobile_section_id";
    private static final String STATE_SCROLL_Y = "mobile_scroll_y";
    private static final int MAX_CARDS_PER_SHELF = 12;

    private final List<BrowseSection> sections = new ArrayList<>();
    private final List<VideoGroup> visibleGroups = new ArrayList<>();

    private BrowsePresenter presenter;
    private LinearLayout navigationItems;
    private LinearLayout navigationPanel;
    private LinearLayout shelvesContainer;
    private LinearLayout stateContainer;
    private TextView screenTitle;
    private TextView screenSubtitle;
    private TextView stateMessage;
    private Button stateAction;
    private ProgressBar progressBar;
    private ScrollView contentScroll;
    private ImageButton menuButton;
    private ImageButton accountButton;
    private boolean isTablet;
    private boolean progressShowing;
    private boolean hasContent;
    private boolean initialSelectionHandled;
    private boolean homeFallbackStarted;
    private int selectedSectionId = -1;
    private int requestedSectionId = -1;
    private int pendingScrollY;
    private Disposable homeFallbackAction;
    private Disposable signInAction;
    private Disposable accountSelectionAction;
    private Disposable historyReadbackAction;
    private AlertDialog signInDialog;
    private AlertDialog accountSelectionProgress;
    private AlertDialog historyReadbackProgress;
    private SignInService signInService;
    private BrowseProcessorManager fallbackBrowseProcessor;
    private boolean lastDeArrowTitles;
    private boolean lastDeArrowThumbnails;
    private long accountSelectionSequence;
    private String accountSelectionReadback = "not_verified";
    private int accountCount;
    private Account pendingPreviousAccount;
    private Account pendingTargetAccount;
    private boolean pendingAccountSelectionChanged;
    private long historyReadbackSequence;
    private long historyReadbackProgressSequence;
    private String historyReadback = "not_verified";
    private int historyItemCount = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        fallbackBrowseProcessor = new BrowseProcessorManager(this,
                video -> runUi(this::renderShelves));
        DeArrowData deArrowData = DeArrowData.instance(this);
        lastDeArrowTitles = deArrowData.isReplaceTitlesEnabled();
        lastDeArrowThumbnails = deArrowData.isReplaceThumbnailsEnabled();

        isTablet = getResources().getBoolean(R.bool.is_tablet);
        configureAdaptiveNavigation();

        if (savedInstanceState != null) {
            selectedSectionId = savedInstanceState.getInt(STATE_SECTION_ID, -1);
            pendingScrollY = savedInstanceState.getInt(STATE_SCROLL_Y, 0);
            initialSelectionHandled = selectedSectionId != -1;
        }

        signInService = YouTubeServiceManager.instance().getSignInService();
        presenter = BrowsePresenter.instance(this);
        presenter.setView(this);
        recoverInterruptedAccountSelection();
        applyAccountHistoryPreference(signInService.getSelectedAccount());
        presenter.onViewInitialized();
        MediaServiceManager.instance().addAccountListener(this);
        updateBadge();
        handleDeepLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        presenter.setView(this);
        presenter.onViewResumed();
        DeArrowData data = DeArrowData.instance(this);
        if (lastDeArrowTitles != data.isReplaceTitlesEnabled()
                || lastDeArrowThumbnails != data.isReplaceThumbnailsEnabled()) {
            refreshVisibleDeArrowCards();
        }
    }

    @Override
    protected void onPause() {
        presenter.onViewPaused();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        disposeHomeFallback();
        fallbackBrowseProcessor.dispose();
        if (signInAction != null) signInAction.dispose();
        ++historyReadbackSequence;
        if (historyReadbackAction != null) historyReadbackAction.dispose();
        if (historyReadbackProgress != null) historyReadbackProgress.dismiss();
        if (isChangingConfigurations() && pendingAccountSelectionChanged) {
            suspendAccountSelectionForConfigurationChange();
        } else {
            rollbackPendingAccountSelection();
        }
        if (signInDialog != null) signInDialog.dismiss();
        if (accountSelectionProgress != null) accountSelectionProgress.dismiss();
        MediaServiceManager.instance().removeAccountListener(this);
        presenter.onViewDestroyed();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_SECTION_ID, selectedSectionId);
        outState.putInt(STATE_SCROLL_Y, contentScroll.getScrollY());
        super.onSaveInstanceState(outState);
    }

    private void bindViews() {
        navigationItems = findViewById(R.id.navigation_items);
        navigationPanel = findViewById(R.id.navigation_panel);
        shelvesContainer = findViewById(R.id.shelves_container);
        stateContainer = findViewById(R.id.state_container);
        screenTitle = findViewById(R.id.screen_title);
        screenSubtitle = findViewById(R.id.screen_subtitle);
        stateMessage = findViewById(R.id.state_message);
        stateAction = findViewById(R.id.state_action);
        progressBar = findViewById(R.id.loading_indicator);
        contentScroll = findViewById(R.id.content_scroll);
        menuButton = findViewById(R.id.menu_button);
        accountButton = findViewById(R.id.account_button);
        accountButton.setOnClickListener(view -> showAccountDialog());
        findViewById(R.id.search_button).setOnClickListener(view ->
                startActivity(new Intent(this, SearchActivity.class)));
    }

    private void configureAdaptiveNavigation() {
        if (isTablet) {
            menuButton.setVisibility(View.GONE);
            navigationPanel.setVisibility(View.VISIBLE);
        } else {
            menuButton.setOnClickListener(view -> toggleNavigation());
            navigationPanel.setVisibility(View.GONE);
            navigationPanel.setOnClickListener(view -> { });
        }
    }

    private void toggleNavigation() {
        navigationPanel.setVisibility(navigationPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void closePhoneNavigation() {
        if (!isTablet) {
            navigationPanel.setVisibility(View.GONE);
        }
    }

    @Override
    public void addSection(int index, BrowseSection section) {
        runUi(() -> {
            int safeIndex = Math.max(0, Math.min(index, sections.size()));
            sections.remove(section);
            sections.add(safeIndex, section);
            renderNavigation();
        });
    }

    @Override
    public void removeSection(BrowseSection section) {
        runUi(() -> {
            sections.remove(section);
            renderNavigation();
        });
    }

    @Override
    public void removeAllSections() {
        runUi(() -> {
            sections.clear();
            navigationItems.removeAllViews();
        });
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        runUi(() -> {
            if (sections.isEmpty()) {
                return;
            }

            int targetIndex = Math.max(0, Math.min(index, sections.size() - 1));
            boolean explicitNavigation = requestedSectionId == sections.get(targetIndex).getId();
            if (!initialSelectionHandled) {
                int homeIndex = findSectionIndex(MediaGroup.TYPE_HOME);
                if (homeIndex >= 0) {
                    targetIndex = homeIndex;
                }
                initialSelectionHandled = true;
            } else if (!explicitNavigation && selectedSectionId != -1) {
                int restoredIndex = findSectionIndex(selectedSectionId);
                if (restoredIndex >= 0 && visibleGroups.isEmpty()) {
                    targetIndex = restoredIndex;
                }
            }

            BrowseSection section = sections.get(targetIndex);
            requestedSectionId = -1;
            disposeHomeFallback();
            homeFallbackStarted = false;
            selectedSectionId = section.getId();
            screenTitle.setText(section.getTitle());
            screenSubtitle.setText(sectionSubtitle(section.getId()));
            visibleGroups.clear();
            hasContent = false;
            shelvesContainer.removeAllViews();
            hideState();
            renderNavigation();
            presenter.onSectionFocused(section.getId());
            closePhoneNavigation();

            if (focusOnContent) {
                contentScroll.requestFocus();
            }
        });
    }

    @Override
    public void updateSection(VideoGroup group) {
        if (group == null) {
            return;
        }
        runUi(() -> {
            BrowseSection section = group.getSection();
            if (homeFallbackStarted && section != null && section.getId() == MediaGroup.TYPE_HOME) {
                return;
            }
            applyVideoGroup(group);
        });
    }

    @Override
    public void updateSection(SettingsGroup group) {
        runUi(() -> renderSettings(group));
    }

    @Override
    public void clearSection(BrowseSection section) {
        runUi(() -> {
            if (section != null && section.getId() == selectedSectionId) {
                if (homeFallbackStarted && section.getId() == MediaGroup.TYPE_HOME) {
                    return;
                }
                visibleGroups.clear();
                hasContent = false;
                renderShelves();
            }
        });
    }

    @Override
    public void selectSectionItem(int index) {
        // Vertical position restoration is handled by ScrollView state in this mobile adapter.
    }

    @Override
    public void selectSectionItem(Video item) {
        // Item-level restoration will move to RecyclerView stable IDs as M2 is expanded.
    }

    @Override
    public void showError(ErrorFragmentData data) {
        runUi(() -> {
            progressShowing = false;
            progressBar.setVisibility(View.GONE);
            if (selectedSectionId == MediaGroup.TYPE_HOME && !hasContent) {
                if (!homeFallbackStarted) {
                    loadSignedOutHomeFallback();
                }
                return;
            }
            boolean accountBlocked = isLibrarySignInState(data);
            showState(libraryStateMessage(data),
                    accountBlocked ? getString(R.string.account_sign_in_m6) : data != null ? data.getActionText() : null,
                    accountBlocked
                            ? this::showAccountDialog
                            : data != null ? data::onAction : null);
        });
    }

    private String sectionSubtitle(int sectionId) {
        if (sectionId == MediaGroup.TYPE_SUBSCRIPTIONS) return getString(R.string.subscriptions_subtitle);
        if (sectionId == MediaGroup.TYPE_HISTORY) return getString(R.string.history_subtitle);
        if (sectionId == MediaGroup.TYPE_USER_PLAYLISTS) return getString(R.string.playlists_subtitle);
        return getString(R.string.real_smarttube_data);
    }

    private String libraryStateMessage(ErrorFragmentData data) {
        String fallback = data != null ? data.getMessage() : getString(R.string.empty_section);
        if (!isLibrarySignInState(data)) return fallback;
        if (selectedSectionId == MediaGroup.TYPE_SUBSCRIPTIONS) return getString(R.string.subscriptions_sign_in_state);
        if (selectedSectionId == MediaGroup.TYPE_HISTORY) return getString(R.string.history_sign_in_state);
        if (selectedSectionId == MediaGroup.TYPE_USER_PLAYLISTS) return getString(R.string.playlists_sign_in_state);
        return fallback;
    }

    private boolean isLibrarySignInState(ErrorFragmentData data) {
        boolean librarySection = selectedSectionId == MediaGroup.TYPE_SUBSCRIPTIONS
                || selectedSectionId == MediaGroup.TYPE_HISTORY
                || selectedSectionId == MediaGroup.TYPE_USER_PLAYLISTS;
        return librarySection && data != null
                && TextUtils.equals(data.getActionText(), getString(R.string.action_signin));
    }

    @Override
    public void showProgressBar(boolean show) {
        runUi(() -> {
            progressShowing = show;
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && !hasContent) {
                showState(getString(R.string.loading_section, screenTitle.getText()), null, null);
            } else if (!show && hasContent) {
                hideState();
            } else if (!show && !hasContent && stateContainer.getVisibility() != View.VISIBLE) {
                showState(getString(R.string.empty_section), getString(R.string.retry), presenter::refresh);
            }
        });
    }

    @Override
    public boolean isProgressBarShowing() {
        return progressShowing;
    }

    @Override
    public void focusOnContent() {
        runUi(contentScroll::requestFocus);
    }

    @Override
    public boolean isEmpty() {
        return !hasContent;
    }

    @Override
    public void updateBadge() {
        if (accountButton == null || signInService == null) return;
        Account selected = signInService.getSelectedAccount();
        boolean signed = selected != null;
        accountButton.setColorFilter(color(signed ? R.color.smarttube_accent : R.color.smarttube_text_primary));
        accountButton.setContentDescription(signed && !TextUtils.isEmpty(selected.getName())
                ? getString(R.string.account_name_email, selected.getName(), safeText(selected.getEmail()))
                : getString(R.string.account));
    }

    @Override
    public void onAccountChanged(Account account) {
        runUi(() -> {
            ++historyReadbackSequence;
            if (historyReadbackAction != null) historyReadbackAction.dispose();
            historyReadbackAction = null;
            dismissHistoryReadbackProgress();
            historyReadback = "not_verified";
            historyItemCount = -1;
            applyAccountHistoryPreference(account);
            updateBadge();
            if (presenter != null) presenter.refresh();
        });
    }

    private void showAccountDialog() {
        if (signInService == null) return;
        List<Account> accounts = signInService.getAccounts();
        if (accounts == null || accounts.isEmpty()) {
            startDeviceSignIn();
            return;
        }
        accountCount = accounts.size();
        String[] labels = new String[accounts.size() + 3];
        int checked = accounts.size() + 1;
        for (int i = 0; i < accounts.size(); i++) {
            Account account = accounts.get(i);
            labels[i] = getString(R.string.account_name_email,
                    safeText(account.getName()), safeText(account.getEmail()));
            if (account.isSelected()) checked = i;
        }
        labels[accounts.size()] = getString(R.string.add_account);
        labels[accounts.size() + 1] = getString(R.string.use_without_account);
        labels[accounts.size() + 2] = getString(R.string.history_synchronization);
        final AlertDialog[] holder = new AlertDialog[1];
        holder[0] = new AlertDialog.Builder(this)
                .setTitle(R.string.account)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which < accounts.size()) selectAccountWithReadback(accounts.get(which));
                    else if (which == accounts.size()) startDeviceSignIn();
                    else if (which == accounts.size() + 1) selectAccountWithReadback(null);
                    else showHistorySyncDialog();
                })
                .setNeutralButton(R.string.account_status, (dialog, which) -> showAccountStatusDialog())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0].show();
    }

    private void selectAccountWithReadback(@Nullable Account target) {
        if (signInService == null) return;
        rollbackPendingAccountSelection();
        final long sequence = ++accountSelectionSequence;
        final Account previous = signInService.getSelectedAccount();
        final boolean changed = !sameAccount(previous, target);
        pendingPreviousAccount = previous;
        pendingTargetAccount = target;
        pendingAccountSelectionChanged = changed;
        if (changed && !writeAccountSelectionTransaction(previous, target)) {
            clearAccountSelectionTransaction();
            clearPendingAccountSelection();
            accountSelectionReadback = "failed_preserved";
            Toast.makeText(this, R.string.account_selection_failed_preserved, Toast.LENGTH_LONG).show();
            updateBadge();
            presenter.refresh();
            return;
        }
        accountSelectionReadback = "verifying";
        showAccountSelectionProgress();
        if (changed) {
            applySharedAccountSelection(target);
        } else {
            YouTubeSignInService.instance().invalidateCache();
        }

        startAccountProviderProbe(sequence, target);
    }

    private void startAccountProviderProbe(long sequence, @Nullable Account target) {
        Observable<Boolean> providerProbe = target == null
                ? YouTubeServiceManager.instance().getContentService()
                        .getSearchObserve(getString(R.string.signed_out_discovery_query))
                        .map(result -> result != null && !result.isEmpty()).defaultIfEmpty(false)
                : YouTubeServiceManager.instance().getContentService().getSubscriptionsObserve()
                        .map(result -> result != null).defaultIfEmpty(false);
        accountSelectionAction = providerProbe
                .timeout(20, TimeUnit.SECONDS)
                .subscribe(providerVerified -> runUi(() -> finishAccountSelection(sequence, target,
                                providerVerified && sameAccount(target, signInService.getSelectedAccount()))),
                        error -> runUi(() -> finishAccountSelection(sequence, target, false)));
    }

    private void applySharedAccountSelection(@Nullable Account account) {
        signInService.selectAccount(account);
        YouTubeSignInService.instance().invalidateCache();
        Utils.updateChannels(this);
        applyAccountHistoryPreference(account);
    }

    private void finishAccountSelection(long sequence, @Nullable Account target, boolean verified) {
        if (sequence != accountSelectionSequence || isFinishing() || isDestroyed()) return;
        if (accountSelectionProgress != null) accountSelectionProgress.dismiss();
        accountSelectionProgress = null;
        accountSelectionAction = null;
        if (verified) {
            if (pendingAccountSelectionChanged && !clearAccountSelectionTransaction()) {
                boolean restored = sameAccount(target, signInService.getSelectedAccount());
                if (restored) applySharedAccountSelection(pendingPreviousAccount);
                clearAccountSelectionTransaction();
                clearPendingAccountSelection();
                accountSelectionReadback = restored ? "failed_restored" : "failed_preserved";
                Toast.makeText(this, restored ? R.string.account_selection_failed_restored
                        : R.string.account_selection_failed_preserved, Toast.LENGTH_LONG).show();
                updateBadge();
                presenter.refresh();
                return;
            }
            clearPendingAccountSelection();
            accountSelectionReadback = "verified";
            Toast.makeText(this, R.string.account_selection_verified, Toast.LENGTH_LONG).show();
            updateBadge();
            presenter.refresh();
            return;
        }

        boolean restored = pendingAccountSelectionChanged
                && sameAccount(target, signInService.getSelectedAccount());
        if (restored) applySharedAccountSelection(pendingPreviousAccount);
        clearAccountSelectionTransaction();
        clearPendingAccountSelection();
        accountSelectionReadback = restored ? "failed_restored" : "failed_preserved";
        Toast.makeText(this, restored ? R.string.account_selection_failed_restored
                : R.string.account_selection_failed_preserved, Toast.LENGTH_LONG).show();
        updateBadge();
        presenter.refresh();
    }

    private void suspendAccountSelectionForConfigurationChange() {
        sResumeAccountSelectionAfterConfigurationChange = true;
        ++accountSelectionSequence;
        if (accountSelectionAction != null) accountSelectionAction.dispose();
        accountSelectionAction = null;
    }

    private void rollbackPendingAccountSelection() {
        ++accountSelectionSequence;
        if (accountSelectionAction != null) accountSelectionAction.dispose();
        accountSelectionAction = null;
        if (pendingAccountSelectionChanged
                && sameAccount(pendingTargetAccount, signInService != null ? signInService.getSelectedAccount() : null)) {
            applySharedAccountSelection(pendingPreviousAccount);
        }
        clearAccountSelectionTransaction();
        clearPendingAccountSelection();
    }

    private void clearPendingAccountSelection() {
        pendingPreviousAccount = null;
        pendingTargetAccount = null;
        pendingAccountSelectionChanged = false;
    }

    private boolean writeAccountSelectionTransaction(@Nullable Account previous, @Nullable Account target) {
        String previousFingerprint = previous != null ? accountFingerprint(previous) : null;
        String targetFingerprint = target != null ? accountFingerprint(target) : null;
        if ((previous != null && previousFingerprint == null)
                || (target != null && targetFingerprint == null)) return false;
        SharedPreferences.Editor editor = accountTransactionPreferences().edit()
                .putBoolean(ACCOUNT_TRANSACTION_PENDING, true)
                .putBoolean(ACCOUNT_TRANSACTION_PREVIOUS_NULL, previous == null)
                .putBoolean(ACCOUNT_TRANSACTION_TARGET_NULL, target == null);
        if (previous != null) editor.putString(ACCOUNT_TRANSACTION_PREVIOUS_FINGERPRINT,
                previousFingerprint);
        else editor.remove(ACCOUNT_TRANSACTION_PREVIOUS_FINGERPRINT);
        if (target != null) editor.putString(ACCOUNT_TRANSACTION_TARGET_FINGERPRINT,
                targetFingerprint);
        else editor.remove(ACCOUNT_TRANSACTION_TARGET_FINGERPRINT);
        return editor.commit();
    }

    private void recoverInterruptedAccountSelection() {
        SharedPreferences preferences = accountTransactionPreferences();
        if (!preferences.getBoolean(ACCOUNT_TRANSACTION_PENDING, false)) {
            sResumeAccountSelectionAfterConfigurationChange = false;
            return;
        }
        Account current = signInService.getSelectedAccount();
        boolean targetNull = preferences.getBoolean(ACCOUNT_TRANSACTION_TARGET_NULL, true);
        String targetFingerprint = preferences.getString(ACCOUNT_TRANSACTION_TARGET_FINGERPRINT, null);
        if (matchesStoredAccount(current, targetNull, targetFingerprint)) {
            boolean previousNull = preferences.getBoolean(ACCOUNT_TRANSACTION_PREVIOUS_NULL, true);
            String previousFingerprint = preferences.getString(
                    ACCOUNT_TRANSACTION_PREVIOUS_FINGERPRINT, null);
            Account previous = previousNull ? null : findUniqueAccountByFingerprint(previousFingerprint);
            if (sResumeAccountSelectionAfterConfigurationChange) {
                sResumeAccountSelectionAfterConfigurationChange = false;
                pendingPreviousAccount = previous;
                pendingTargetAccount = current;
                pendingAccountSelectionChanged = !sameAccount(previous, current);
                accountSelectionReadback = "verifying";
                showAccountSelectionProgress();
                YouTubeSignInService.instance().invalidateCache();
                startAccountProviderProbe(++accountSelectionSequence, current);
                return;
            }
            applySharedAccountSelection(previous);
        }
        sResumeAccountSelectionAfterConfigurationChange = false;
        clearAccountSelectionTransaction();
    }

    @Nullable
    private Account findUniqueAccountByFingerprint(@Nullable String fingerprint) {
        List<Account> accounts = signInService.getAccounts();
        if (accounts == null || fingerprint == null) return null;
        Account match = null;
        for (Account account : accounts) {
            if (account != null && TextUtils.equals(fingerprint, accountFingerprint(account))) {
                if (match != null) return null;
                match = account;
            }
        }
        return match;
    }

    private boolean matchesStoredAccount(@Nullable Account account, boolean storedNull,
                                                @Nullable String storedFingerprint) {
        return storedNull ? account == null
                : account != null && storedFingerprint != null
                        && TextUtils.equals(storedFingerprint, accountFingerprint(account));
    }

    @Nullable
    private String accountFingerprint(Account account) {
        String value = account.getId() + "\u001f" + fingerprintPart(account.getName()) + "\u001f"
                + fingerprintPart(account.getEmail()) + "\u001f"
                + fingerprintPart(account.getAvatarImageUrl());
        if (account instanceof YouTubeAccount) {
            YouTubeAccount youtubeAccount = (YouTubeAccount) account;
            value += "\u001f" + fingerprintPart(youtubeAccount.getPageIdToken())
                    + "\u001f" + fingerprintPart(youtubeAccount.getChannelName());
        }
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] salt = accountFingerprintSalt();
            if (salt == null) return null;
            messageDigest.update(salt);
            byte[] digest = messageDigest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    @Nullable
    private byte[] accountFingerprintSalt() {
        synchronized (ACCOUNT_FINGERPRINT_LOCK) {
            SharedPreferences preferences = getSharedPreferences(ACCOUNT_FINGERPRINT_PREFS, MODE_PRIVATE);
            String encoded = preferences.getString(ACCOUNT_FINGERPRINT_SALT, null);
            if (encoded == null) {
                byte[] generated = new byte[32];
                new SecureRandom().nextBytes(generated);
                encoded = Base64.encodeToString(generated, Base64.NO_WRAP);
                if (!preferences.edit().putString(ACCOUNT_FINGERPRINT_SALT, encoded).commit()) {
                    preferences.edit().remove(ACCOUNT_FINGERPRINT_SALT).apply();
                    return null;
                }
            }
            return Base64.decode(encoded, Base64.NO_WRAP);
        }
    }

    private static String fingerprintPart(@Nullable String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private SharedPreferences accountTransactionPreferences() {
        return getSharedPreferences(ACCOUNT_TRANSACTION_PREFS, MODE_PRIVATE);
    }

    private boolean clearAccountSelectionTransaction() {
        return accountTransactionPreferences().edit().clear().commit();
    }

    private void applyAccountHistoryPreference(@Nullable Account account) {
        if (account == null) return;
        String fingerprint = accountFingerprint(account);
        GeneralData generalData = GeneralData.instance(this);
        if (fingerprint == null) {
            generalData.setHistoryState(GeneralData.HISTORY_AUTO);
            return;
        }
        SharedPreferences preferences = getSharedPreferences(ACCOUNT_HISTORY_PREFS, MODE_PRIVATE);
        String key = ACCOUNT_HISTORY_KEY_PREFIX + fingerprint;
        int state;
        if (preferences.contains(key)) {
            state = preferences.getInt(key, GeneralData.HISTORY_AUTO);
            if (!preferences.getBoolean(ACCOUNT_HISTORY_MIGRATION_COMPLETE, false)
                    && !preferences.edit().putBoolean(ACCOUNT_HISTORY_MIGRATION_COMPLETE, true).commit()) {
                generalData.setHistoryState(GeneralData.HISTORY_AUTO);
                Toast.makeText(this, R.string.history_setting_not_saved, Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            boolean migrateExistingState = !preferences.getBoolean(
                    ACCOUNT_HISTORY_MIGRATION_COMPLETE, false);
            state = migrateExistingState ? generalData.getHistoryState() : GeneralData.HISTORY_AUTO;
            SharedPreferences.Editor editor = preferences.edit().putInt(key, state);
            if (migrateExistingState) editor.putBoolean(ACCOUNT_HISTORY_MIGRATION_COMPLETE, true);
            if (!editor.commit()) {
                generalData.setHistoryState(GeneralData.HISTORY_AUTO);
                Toast.makeText(this, R.string.history_setting_not_saved, Toast.LENGTH_LONG).show();
                return;
            }
        }
        generalData.setHistoryState(state);
        if (state == GeneralData.HISTORY_ENABLED) {
            MediaServiceManager.instance().enableHistory(true);
        }
    }

    private void showHistorySyncDialog() {
        Account selected = signInService != null ? signInService.getSelectedAccount() : null;
        if (selected == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.history_synchronization)
                    .setMessage(R.string.history_account_required)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        int state = GeneralData.instance(this).getHistoryState();
        String itemCount = historyItemCount >= 0 ? String.valueOf(historyItemCount)
                : getString(R.string.history_items_unavailable);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(4), dp(24), dp(8));
        TextView status = new TextView(this);
        status.setText(getString(R.string.history_status_value,
                historyModeLabel(state), historyReadbackLabel(), itemCount));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setTextColor(color(R.color.smarttube_text_primary));
        status.setPadding(0, 0, 0, dp(8));
        content.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        RadioGroup modes = new RadioGroup(this);
        int[] states = {GeneralData.HISTORY_AUTO, GeneralData.HISTORY_ENABLED, GeneralData.HISTORY_DISABLED};
        int[] labels = {R.string.history_mode_automatic, R.string.history_mode_enabled,
                R.string.history_mode_paused};
        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < states.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(labels[i]);
            option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            option.setTextColor(color(R.color.smarttube_text_primary));
            option.setPadding(0, dp(8), 0, dp(8));
            option.setChecked(state == states[i]);
            option.setTag(states[i]);
            modes.addView(option, new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            if (checked == null || checked.getTag() == null) return;
            if (holder[0] != null) holder[0].dismiss();
            setAccountHistoryState(selected, (int) checked.getTag());
        });
        content.addView(modes, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder[0] = new AlertDialog.Builder(this)
                .setTitle(R.string.history_synchronization)
                .setView(content)
                .setPositiveButton(R.string.verify_provider_history,
                        (selectionDialog, which) -> startHistoryProviderReadback())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0].show();
    }

    private void setAccountHistoryState(Account account, int state) {
        if (!sameAccount(account, signInService.getSelectedAccount())) return;
        String fingerprint = accountFingerprint(account);
        if (fingerprint == null || !getSharedPreferences(ACCOUNT_HISTORY_PREFS, MODE_PRIVATE).edit()
                .putInt(ACCOUNT_HISTORY_KEY_PREFIX + fingerprint, state).commit()) {
            Toast.makeText(this, R.string.history_setting_not_saved, Toast.LENGTH_LONG).show();
            return;
        }
        GeneralData.instance(this).setHistoryState(state);
        if (state == GeneralData.HISTORY_ENABLED) {
            MediaServiceManager.instance().enableHistory(true);
        }
        historyReadback = "not_verified";
        historyItemCount = -1;
        startHistoryProviderReadback();
    }

    private void startHistoryProviderReadback() {
        Account target = signInService != null ? signInService.getSelectedAccount() : null;
        if (target == null) {
            showHistorySyncDialog();
            return;
        }
        final long sequence = ++historyReadbackSequence;
        if (historyReadbackAction != null) historyReadbackAction.dispose();
        historyReadback = "verifying";
        dismissHistoryReadbackProgress();
        historyReadbackProgressSequence = sequence;
        historyReadbackProgress = new AlertDialog.Builder(this)
                .setTitle(R.string.history_synchronization)
                .setMessage(R.string.history_provider_verifying)
                .setCancelable(false)
                .create();
        historyReadbackProgress.show();
        YouTubeSignInService.instance().invalidateCache();
        historyReadbackAction = YouTubeServiceManager.instance().getContentService().getHistoryObserve()
                .map(group -> group.getMediaItems() != null ? group.getMediaItems().size() : 0)
                .defaultIfEmpty(0)
                .timeout(20, TimeUnit.SECONDS)
                .subscribe(count -> runUi(() -> finishHistoryProviderReadback(sequence, target, count, true)),
                        error -> runUi(() -> finishHistoryProviderReadback(sequence, target, -1, false)));
    }

    private void finishHistoryProviderReadback(long sequence, Account target, int count, boolean verified) {
        if (sequence == historyReadbackProgressSequence) dismissHistoryReadbackProgress();
        if (sequence != historyReadbackSequence || isFinishing() || isDestroyed()
                || !sameAccount(target, signInService.getSelectedAccount())) return;
        historyReadbackAction = null;
        historyReadback = verified ? "verified" : "failed";
        historyItemCount = verified ? count : -1;
        Toast.makeText(this, verified ? R.string.history_provider_verified
                : R.string.history_provider_failed, Toast.LENGTH_LONG).show();
        if (selectedSectionId == MediaGroup.TYPE_HISTORY && presenter != null) presenter.refresh();
        showHistorySyncDialog();
    }

    private void dismissHistoryReadbackProgress() {
        if (historyReadbackProgress != null) historyReadbackProgress.dismiss();
        historyReadbackProgress = null;
        historyReadbackProgressSequence = 0;
    }

    private String historyModeLabel(int state) {
        if (state == GeneralData.HISTORY_ENABLED) return getString(R.string.history_mode_enabled);
        if (state == GeneralData.HISTORY_DISABLED) return getString(R.string.history_mode_paused);
        return getString(R.string.history_mode_automatic);
    }

    private String historyReadbackLabel() {
        if (TextUtils.equals("verified", historyReadback)) return getString(R.string.account_readback_verified);
        if (TextUtils.equals("verifying", historyReadback)) return getString(R.string.account_readback_verifying);
        if (TextUtils.equals("failed", historyReadback)) return getString(R.string.history_provider_failed_short);
        return getString(R.string.account_readback_not_verified);
    }

    private void showAccountSelectionProgress() {
        if (accountSelectionProgress != null) accountSelectionProgress.dismiss();
        accountSelectionProgress = new AlertDialog.Builder(this)
                .setTitle(R.string.account)
                .setMessage(R.string.account_selection_verifying)
                .setCancelable(false)
                .create();
        accountSelectionProgress.show();
    }

    private void showAccountStatusDialog() {
        Account selected = signInService != null ? signInService.getSelectedAccount() : null;
        new AlertDialog.Builder(this)
                .setTitle(R.string.account_status)
                .setMessage(getString(R.string.account_status_value, accountCount,
                        getString(selected != null ? R.string.account_selection_selected
                                : R.string.account_selection_without_account),
                        accountSelectionReadbackLabel()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String accountSelectionReadbackLabel() {
        if (TextUtils.equals("verified", accountSelectionReadback)) {
            return getString(R.string.account_readback_verified);
        }
        if (TextUtils.equals("verifying", accountSelectionReadback)) {
            return getString(R.string.account_readback_verifying);
        }
        if (TextUtils.equals("failed_restored", accountSelectionReadback)) {
            return getString(R.string.account_readback_failed_restored);
        }
        if (TextUtils.equals("failed_preserved", accountSelectionReadback)) {
            return getString(R.string.account_readback_failed_preserved);
        }
        return getString(R.string.account_readback_not_verified);
    }

    private boolean sameAccount(@Nullable Account first, @Nullable Account second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        String fingerprint = accountFingerprint(first);
        String secondFingerprint = accountFingerprint(second);
        return fingerprint != null && secondFingerprint != null
                && TextUtils.equals(fingerprint, secondFingerprint)
                && isUniqueAccountFingerprint(fingerprint);
    }

    private boolean isUniqueAccountFingerprint(String fingerprint) {
        List<Account> accounts = signInService != null ? signInService.getAccounts() : null;
        if (accounts == null) return false;
        int matches = 0;
        for (Account account : accounts) {
            if (account != null && TextUtils.equals(fingerprint, accountFingerprint(account)) && ++matches > 1) {
                return false;
            }
        }
        return matches == 1;
    }

    private void startDeviceSignIn() {
        if (signInAction != null) signInAction.dispose();
        showSignInDialog(R.string.sign_in_youtube, getString(R.string.requesting_sign_in_code), null);
        signInAction = signInService.signInObserve().subscribe(
                code -> runUi(() -> showActivationCode(code)),
                error -> runUi(() -> showSignInDialog(R.string.sign_in_youtube,
                        getString(R.string.sign_in_error, safeText(error.getMessage())), null)),
                () -> runUi(() -> {
                    signInAction = null;
                    if (signInDialog != null) signInDialog.dismiss();
                    Toast.makeText(this, R.string.sign_in_complete, Toast.LENGTH_LONG).show();
                    updateBadge();
                    presenter.refresh();
                }));
    }

    private void showActivationCode(String code) {
        String activationUrl = "https://yt.be/activate";
        String qrUrl = "https://youtube.com/qr/activate/" + code.replace(" ", "-");
        ImageView qrView = createQrView(qrUrl);
        showSignInDialog(R.string.activate_account_title,
                getString(R.string.activate_account_message, activationUrl, code), qrView);
    }

    private void showSignInDialog(int title, String message, @Nullable View view) {
        if (signInDialog != null) signInDialog.dismiss();
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    if (signInAction != null) signInAction.dispose();
                    signInAction = null;
                });
        if (view != null) builder.setView(view);
        signInDialog = builder.create();
        signInDialog.show();
    }

    @Nullable
    private ImageView createQrView(String value) {
        try {
            int size = 560;
            BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
            ImageView view = new ImageView(this);
            int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                    getResources().getDisplayMetrics());
            view.setPadding(padding, padding, padding, padding);
            view.setBackgroundColor(Color.WHITE);
            view.setImageBitmap(bitmap);
            view.setContentDescription(value);
            return view;
        } catch (WriterException error) {
            return null;
        }
    }

    private void applyVideoGroup(VideoGroup incoming) {
        BrowseSection section = incoming.getSection();
        if (section != null && section.getId() != selectedSectionId) {
            return;
        }

        int action = incoming.getAction();
        if (action == VideoGroup.ACTION_REPLACE) {
            visibleGroups.clear();
        }

        if (action == VideoGroup.ACTION_REMOVE || action == VideoGroup.ACTION_REMOVE_AUTHOR) {
            removeVideos(incoming, action == VideoGroup.ACTION_REMOVE_AUTHOR);
        } else if (action == VideoGroup.ACTION_SYNC) {
            syncVideos(incoming);
        } else if (!incoming.isEmpty()) {
            int existingIndex = findGroupIndex(incoming);
            if (existingIndex >= 0) {
                visibleGroups.set(existingIndex, incoming);
            } else if (action == VideoGroup.ACTION_PREPEND) {
                visibleGroups.add(0, incoming);
            } else {
                visibleGroups.add(incoming);
            }
        }

        renderShelves();
    }

    private void loadSignedOutHomeFallback() {
        BrowseSection homeSection = findSection(MediaGroup.TYPE_HOME);
        if (homeSection == null) {
            showState(getString(R.string.empty_section), getString(R.string.retry), presenter::refresh);
            return;
        }

        disposeHomeFallback();
        homeFallbackStarted = true;
        progressShowing = true;
        progressBar.setVisibility(View.VISIBLE);
        screenSubtitle.setText(R.string.signed_out_explore_feed);
        showState(getString(R.string.loading_signed_out_feed), null, null);

        homeFallbackAction = YouTubeServiceManager.instance()
                .getContentService()
                .getSearchObserve(getString(R.string.signed_out_discovery_query))
                .subscribe(
                        mediaGroups -> runUi(() -> {
                            visibleGroups.clear();
                            boolean first = true;
                            for (MediaGroup mediaGroup : mediaGroups) {
                                if (mediaGroup == null || mediaGroup.isEmpty()) {
                                    continue;
                                }
                                VideoGroup group = VideoGroup.from(mediaGroup, homeSection);
                                if (first) {
                                    group.setAction(VideoGroup.ACTION_REPLACE);
                                    first = false;
                                }
                                applyVideoGroup(group);
                                fallbackBrowseProcessor.process(group);
                            }
                            progressShowing = false;
                            progressBar.setVisibility(View.GONE);
                            if (!hasContent) {
                                showState(getString(R.string.empty_section), getString(R.string.retry), this::loadSignedOutHomeFallback);
                            }
                        }),
                        error -> runUi(() -> {
                            progressShowing = false;
                            progressBar.setVisibility(View.GONE);
                            showState(getString(R.string.load_failed, error.getMessage()), getString(R.string.retry), this::loadSignedOutHomeFallback);
                        }));
    }

    private void disposeHomeFallback() {
        if (homeFallbackAction != null && !homeFallbackAction.isDisposed()) {
            homeFallbackAction.dispose();
        }
        homeFallbackAction = null;
    }

    private void removeVideos(VideoGroup incoming, boolean byAuthor) {
        for (VideoGroup group : visibleGroups) {
            List<Video> removals = new ArrayList<>();
            for (Video candidate : group.getVideos()) {
                for (Video removed : incoming.getVideos()) {
                    boolean match = byAuthor
                            ? TextUtils.equals(candidate.getAuthor(), removed.getAuthor())
                            : candidate.equals(removed);
                    if (match) {
                        removals.add(candidate);
                    }
                }
            }
            for (Video removal : removals) {
                group.remove(removal);
            }
        }
    }

    private void syncVideos(VideoGroup incoming) {
        for (VideoGroup group : visibleGroups) {
            for (Video replacement : incoming.getVideos()) {
                int index = group.indexOf(replacement);
                if (index >= 0) {
                    group.remove(group.get(index));
                    group.add(index, replacement);
                }
            }
        }
    }

    private int findGroupIndex(VideoGroup target) {
        for (int i = 0; i < visibleGroups.size(); i++) {
            VideoGroup candidate = visibleGroups.get(i);
            if (candidate.getId() == target.getId()
                    || (!TextUtils.isEmpty(candidate.getTitle()) && TextUtils.equals(candidate.getTitle(), target.getTitle()))) {
                return i;
            }
        }
        return -1;
    }

    private void renderNavigation() {
        navigationItems.removeAllViews();
        for (BrowseSection section : sections) {
            TextView item = new TextView(this);
            item.setText(section.getTitle());
            item.setTextSize(isTablet ? 15 : 17);
            item.setTextColor(section.getId() == selectedSectionId
                    ? color(R.color.smarttube_text_primary)
                    : color(R.color.smarttube_text_secondary));
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setTypeface(Typeface.DEFAULT, section.getId() == selectedSectionId ? Typeface.BOLD : Typeface.NORMAL);
            item.setPadding(dp(18), dp(14), dp(18), dp(14));
            item.setBackground(createNavigationBackground(section.getId() == selectedSectionId));
            item.setContentDescription(getString(R.string.navigate_to_section, section.getTitle()));
            item.setOnClickListener(view -> {
                int selectedIndex = findSectionIndex(section.getId());
                if (selectedIndex >= 0) {
                    requestedSectionId = section.getId();
                    selectSection(selectedIndex, true);
                }
            });
            item.setOnLongClickListener(view -> {
                Toast.makeText(this, getString(R.string.section_customization_m8), Toast.LENGTH_SHORT).show();
                return true;
            });
            navigationItems.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private GradientDrawable createNavigationBackground(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selected ? color(R.color.smarttube_surface_selected) : Color.TRANSPARENT);
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    private void renderShelves() {
        shelvesContainer.removeAllViews();
        hasContent = false;

        for (VideoGroup group : visibleGroups) {
            if (group == null || group.isEmpty()) {
                continue;
            }
            hasContent = true;
            shelvesContainer.addView(createShelf(group));
        }

        if (hasContent) {
            hideState();
            screenSubtitle.setText(getResources().getQuantityString(
                    R.plurals.loaded_shelves, visibleGroups.size(), visibleGroups.size()));
            if (pendingScrollY > 0) {
                contentScroll.post(() -> {
                    contentScroll.scrollTo(0, pendingScrollY);
                    pendingScrollY = 0;
                });
            }
        } else if (!progressShowing) {
            showState(getString(R.string.empty_section), getString(R.string.retry), presenter::refresh);
        }
    }

    private View createShelf(VideoGroup group) {
        LinearLayout shelf = new LinearLayout(this);
        shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.setPadding(0, 0, 0, dp(24));

        TextView title = new TextView(this);
        title.setText(TextUtils.isEmpty(group.getTitle()) ? getString(R.string.recommended) : group.getTitle());
        title.setTextColor(color(R.color.smarttube_text_primary));
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(18), dp(8), dp(18), dp(12));
        shelf.addView(title);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setPadding(dp(12), 0, dp(12), 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int count = Math.min(MAX_CARDS_PER_SHELF, group.getVideos().size());
        for (int i = 0; i < count; i++) {
            row.addView(createVideoCard(group.get(i), group.isShorts()));
        }
        scroller.addView(row);
        shelf.addView(scroller);
        return shelf;
    }

    private View createVideoCard(Video video, boolean shorts) {
        int cardWidth = dp(shorts ? 168 : 292);
        int imageHeight = dp(shorts ? 272 : 164);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(8));
        card.setBackground(createCardBackground());
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(buildCardDescription(video));

        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(color(R.color.smarttube_surface));
        card.addView(thumbnail, new LinearLayout.LayoutParams(cardWidth - dp(12), imageHeight));
        Glide.with(getApplicationContext())
                .load(video.getCardImageUrl())
                .placeholder(R.drawable.ic_video_placeholder)
                .error(R.drawable.ic_video_placeholder)
                .centerCrop()
                .into(thumbnail);

        TextView title = new TextView(this);
        title.setText(TextUtils.isEmpty(video.getTitle()) ? getString(R.string.untitled_video) : video.getTitle());
        title.setTextColor(color(R.color.smarttube_text_primary));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(4), dp(9), dp(4), 0);
        card.addView(title, new LinearLayout.LayoutParams(cardWidth - dp(12), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout metadataRow = new LinearLayout(this);
        metadataRow.setOrientation(LinearLayout.HORIZONTAL);
        metadataRow.setGravity(Gravity.CENTER_VERTICAL);
        metadataRow.setPadding(dp(4), dp(5), dp(2), 0);

        TextView metadata = new TextView(this);
        metadata.setText(buildMetadata(video));
        metadata.setTextColor(color(R.color.smarttube_text_secondary));
        metadata.setTextSize(13);
        metadata.setMaxLines(1);
        metadata.setEllipsize(TextUtils.TruncateAt.END);
        metadataRow.addView(metadata, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView overflow = new TextView(this);
        overflow.setText("⋮");
        overflow.setTextColor(color(R.color.smarttube_text_primary));
        overflow.setTextSize(24);
        overflow.setGravity(Gravity.CENTER);
        overflow.setContentDescription(getString(R.string.more_actions_for, video.getTitle()));
        overflow.setOnClickListener(view -> showVideoActions(video));
        metadataRow.addView(overflow, new LinearLayout.LayoutParams(dp(40), dp(40)));
        card.addView(metadataRow, new LinearLayout.LayoutParams(cardWidth - dp(12), ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(view -> openWatch(video));
        card.setOnLongClickListener(view -> {
            showVideoActions(video);
            return true;
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(6), 0, dp(6), 0);
        card.setLayoutParams(params);
        return card;
    }

    private GradientDrawable createCardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(R.color.smarttube_surface));
        drawable.setCornerRadius(dp(14));
        return drawable;
    }

    private String buildMetadata(Video video) {
        String author = video.getAuthor();
        CharSequence second = video.getSecondTitle();
        if (!TextUtils.isEmpty(author)) {
            return author;
        }
        return second != null ? second.toString() : "";
    }

    private String buildCardDescription(Video video) {
        String title = TextUtils.isEmpty(video.getTitle()) ? getString(R.string.untitled_video) : video.getTitle();
        String metadata = buildMetadata(video);
        return TextUtils.isEmpty(metadata) ? title : title + ", " + metadata;
    }

    private void showVideoActions(Video video) {
        String link = video.videoId != null ? "https://youtu.be/" + video.videoId : null;
        List<String> actions = new ArrayList<>();
        actions.add(getString(R.string.open_watch_route));
        if (link != null) {
            actions.add(getString(R.string.copy_video_link));
        }

        new AlertDialog.Builder(this)
                .setTitle(video.getTitle())
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        openWatch(video);
                    } else if (link != null) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.video_link), link));
                        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openWatch(Video video) {
        if (video == null || TextUtils.isEmpty(video.videoId)) {
            Toast.makeText(this, R.string.non_video_route_m3, Toast.LENGTH_SHORT).show();
            return;
        }

        presenter.onVideoItemSelected(video);
        startActivity(WatchActivity.createIntent(this, video));
    }

    private void renderSettings(SettingsGroup group) {
        shelvesContainer.removeAllViews();
        visibleGroups.clear();
        hasContent = group != null && !group.isEmpty();
        if (!hasContent) {
            showState(getString(R.string.empty_section), null, null);
            return;
        }

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (SettingsItem item : group.getItems()) {
            TextView row = new TextView(this);
            row.setText(item.title);
            row.setTextColor(color(R.color.smarttube_text_primary));
            row.setTextSize(17);
            row.setPadding(dp(20), dp(18), dp(20), dp(18));
            row.setBackground(createCardBackground());
            row.setOnClickListener(view -> {
                if (TextUtils.equals(item.title,
                        getString(com.liskovsoft.smartyoutubetv2.common.R.string.dearrow_provider))) {
                    showMobileDeArrowSettings();
                } else if (item.onClick != null) {
                    item.onClick.run();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(16), dp(6), dp(16), dp(6));
            grid.addView(row, params);
        }
        shelvesContainer.addView(grid);
        hideState();
    }

    private void showMobileDeArrowSettings() {
        DeArrowData data = DeArrowData.instance(this);
        String[] choices = {
                getString(R.string.dearrow_titles,
                        getString(data.isReplaceTitlesEnabled() ? R.string.state_on : R.string.state_off)),
                getString(R.string.dearrow_thumbnails,
                        getString(data.isReplaceThumbnailsEnabled() ? R.string.state_on : R.string.state_off))
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.dearrow_settings)
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) data.setReplaceTitlesEnabled(!data.isReplaceTitlesEnabled());
                    if (which == 1) data.setReplaceThumbnailsEnabled(!data.isReplaceThumbnailsEnabled());
                    refreshVisibleDeArrowCards();
                    showMobileDeArrowSettings();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshVisibleDeArrowCards() {
        DeArrowData data = DeArrowData.instance(this);
        lastDeArrowTitles = data.isReplaceTitlesEnabled();
        lastDeArrowThumbnails = data.isReplaceThumbnailsEnabled();
        for (VideoGroup group : visibleGroups) {
            for (Video video : group.getVideos()) {
                video.deArrowTitle = null;
                video.altCardImageUrl = null;
                video.deArrowProcessed = false;
            }
            fallbackBrowseProcessor.process(group);
        }
        renderShelves();
    }

    private void showState(String message, @Nullable String actionText, @Nullable Runnable action) {
        stateMessage.setText(message);
        if (!TextUtils.isEmpty(actionText) && action != null) {
            stateAction.setText(actionText);
            stateAction.setVisibility(View.VISIBLE);
            stateAction.setOnClickListener(view -> action.run());
        } else {
            stateAction.setVisibility(View.GONE);
            stateAction.setOnClickListener(null);
        }
        stateContainer.setVisibility(View.VISIBLE);
    }

    private void hideState() {
        stateContainer.setVisibility(View.GONE);
    }

    private int findSectionIndex(int sectionId) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).getId() == sectionId) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private BrowseSection findSection(int sectionId) {
        int index = findSectionIndex(sectionId);
        return index >= 0 ? sections.get(index) : null;
    }

    private void handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        Uri uri = intent.getData();
        String videoId = uri.getQueryParameter("v");
        if (TextUtils.isEmpty(videoId) && "youtu.be".equals(uri.getHost()) && uri.getPathSegments().size() > 0) {
            videoId = uri.getPathSegments().get(0);
        }
        if (!TextUtils.isEmpty(videoId)) {
            Video video = Video.from(videoId);
            video.title = getString(R.string.shared_video);
            startActivity(WatchActivity.createIntent(this, video));
        }
    }

    private void runUi(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            runOnUiThread(action);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safeText(String value) {
        return TextUtils.isEmpty(value) ? "—" : value;
    }

    private int color(@ColorRes int resourceId) {
        return ContextCompat.getColor(this, resourceId);
    }
}
