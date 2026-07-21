package com.liskovsoft.smartyoutubetv2.mobile;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class MobileBackupManager {
    static final int REQUEST_EXPORT = 9201;
    static final int REQUEST_IMPORT = 9202;

    private static final String FILE_NAME = "SmartTube-Mobile-Settings.zip";
    private static final String FORMAT = "smarttube-mobile-portable-settings";
    private static final String FORMAT_VERSION = "1";
    private static final String MANIFEST_ENTRY = "manifest.properties";
    private static final String SETTINGS_ENTRY = "settings.properties";
    private static final String CONTROLS_PREFS = "mobile_player_controls";
    private static final int MAX_ENTRY_BYTES = 512 * 1024;
    private static final int MAX_ENTRIES = 2;

    private static final String PROFILE_GENERAL = "profile.general";
    private static final String PROFILE_PLAYER = "profile.player";
    private static final String PROFILE_PLAYER_TWEAKS = "profile.player_tweaks";
    private static final String PROFILE_MAIN_UI = "profile.main_ui";
    private static final String GLOBAL_SEARCH = "global.search";
    private static final String GLOBAL_SPONSOR_BLOCK = "global.sponsor_block";
    private static final String GLOBAL_DEARROW = "global.dearrow";
    private static final String MOBILE_CONTROL_ORDER = "mobile.control_order";
    private static final String MOBILE_CONTROL_PREFIX = "mobile.control.";

    private static final String GENERAL_DATA = "general_data";
    private static final String PLAYER_DATA = "video_player_data";
    private static final String PLAYER_TWEAKS_DATA = "video_player_tweaks_data";
    private static final String MAIN_UI_DATA = "main_ui_data";
    private static final String SEARCH_DATA = "search_data";
    private static final String SPONSOR_BLOCK_DATA = "content_block_data";
    private static final String DEARROW_DATA = "DeArrowData";

    // GeneralData.persistStateInt() indexes that must remain local: backup metadata,
    // content identifiers/queues, network/device switches, passwords/child mode,
    // History behavior, migration/changelog state, tooltips and backup schedules.
    private static final int[] PRIVATE_GENERAL_INDEXES = {
            0, 6, 10, 11, 13, 27, 28, 29, 30, 33, 34, 35, 39, 41, 47, 57, 63, 64, 65, 69, 70
    };
    // SponsorBlockData index 9 stores excluded channel identifiers.
    private static final int[] PRIVATE_SPONSOR_BLOCK_INDEXES = {9};
    // PlayerData indexes 54 and 57 store per-channel subtitle/speed identifiers.
    private static final int[] PRIVATE_PLAYER_INDEXES = {54, 57};
    private static final String[] CONTROL_KEYS = {
            "quality", "speed", "captions", "chapters", "pip", "queue"
    };
    private static final Set<String> ALLOWED_KEYS = new HashSet<>(Arrays.asList(
            PROFILE_GENERAL,
            PROFILE_PLAYER,
            PROFILE_PLAYER_TWEAKS,
            PROFILE_MAIN_UI,
            GLOBAL_SEARCH,
            GLOBAL_SPONSOR_BLOCK,
            GLOBAL_DEARROW,
            MOBILE_CONTROL_ORDER,
            MOBILE_CONTROL_PREFIX + "quality",
            MOBILE_CONTROL_PREFIX + "speed",
            MOBILE_CONTROL_PREFIX + "captions",
            MOBILE_CONTROL_PREFIX + "chapters",
            MOBILE_CONTROL_PREFIX + "pip",
            MOBILE_CONTROL_PREFIX + "queue"
    ));

    private final Context context;
    private final AppPrefs appPrefs;

    static final class Archive {
        private final Properties settings;
        private final String profileScope;

        private Archive(Properties settings, String profileScope) {
            this.settings = settings;
            this.profileScope = profileScope;
        }

        int itemCount() {
            return settings.size();
        }
    }

    MobileBackupManager(Context context) {
        this.context = context.getApplicationContext();
        appPrefs = AppPrefs.instance(context);
    }

    Intent createExportIntent() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, FILE_NAME);
        return intent;
    }

    Intent createImportIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"
        });
        return intent;
    }

    int exportTo(Uri uri) throws IOException {
        if (uri == null) {
            throw new IOException("No export destination selected");
        }

        String profileScope = appPrefs.getCurrentProfileScope();
        Properties settings = capturePortableSettings(profileScope);
        Properties manifest = new Properties();
        manifest.setProperty("format", FORMAT);
        manifest.setProperty("version", FORMAT_VERSION);
        manifest.setProperty("scope", "portable-settings-current-profile");
        manifest.setProperty("contains_accounts", "false");
        manifest.setProperty("contains_credentials", "false");
        manifest.setProperty("contains_history", "false");

        try (OutputStream raw = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (raw == null) {
                throw new IOException("Unable to open export destination");
            }
            try (ZipOutputStream zip = new ZipOutputStream(raw)) {
                writeProperties(zip, MANIFEST_ENTRY, manifest);
                writeProperties(zip, SETTINGS_ENTRY, settings);
            }
        }

        return settings.size();
    }

    Archive inspect(Uri uri) throws IOException {
        if (uri == null) {
            throw new IOException("No backup selected");
        }

        Properties manifest = null;
        Properties settings = null;
        Set<String> names = new HashSet<>();
        int entries = 0;

        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) {
                throw new IOException("Unable to open backup");
            }
            try (ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries++;
                    if (entries > MAX_ENTRIES || entry.isDirectory()) {
                        throw new IOException("Backup contains unexpected entries");
                    }
                    String name = entry.getName();
                    if ((!MANIFEST_ENTRY.equals(name) && !SETTINGS_ENTRY.equals(name)) || !names.add(name)) {
                        throw new IOException("Backup contains an invalid or duplicate entry");
                    }
                    Properties properties = readProperties(zip);
                    if (MANIFEST_ENTRY.equals(name)) {
                        manifest = properties;
                    } else {
                        settings = properties;
                    }
                    zip.closeEntry();
                }
            }
        }

        if (entries != MAX_ENTRIES || manifest == null || settings == null) {
            throw new IOException("Backup is incomplete");
        }
        if (!FORMAT.equals(manifest.getProperty("format"))
                || !FORMAT_VERSION.equals(manifest.getProperty("version"))
                || !"portable-settings-current-profile".equals(manifest.getProperty("scope"))
                || !"false".equals(manifest.getProperty("contains_accounts"))
                || !"false".equals(manifest.getProperty("contains_credentials"))
                || !"false".equals(manifest.getProperty("contains_history"))) {
            throw new IOException("Unsupported or unsafe backup format");
        }
        if (settings.isEmpty() || settings.size() > ALLOWED_KEYS.size()
                || !ALLOWED_KEYS.containsAll(settings.stringPropertyNames())) {
            throw new IOException("Backup contains unsupported settings");
        }
        validateSettings(settings);

        return new Archive(settings, appPrefs.getCurrentProfileScope());
    }

    int restore(Archive archive) throws IOException {
        if (archive == null) {
            throw new IOException("Backup was not validated");
        }

        assertCurrentProfile(archive.profileScope);
        Properties before = capturePortableSettings(archive.profileScope);
        try {
            applyPortableSettings(archive.settings, archive.profileScope);
            assertCurrentProfile(archive.profileScope);
            Properties after = capturePortableSettings(archive.profileScope);
            assertRestored(archive.settings, after);
            return archive.itemCount();
        } catch (Exception error) {
            try {
                rollbackPortableSettings(before, archive.settings, archive.profileScope);
                assertSnapshotRestored(before, capturePortableSettings(archive.profileScope));
            } catch (Exception rollbackError) {
                IOException failure = new IOException("Restore and rollback verification failed", error);
                failure.addSuppressed(rollbackError);
                throw failure;
            }
            throw error instanceof IOException ? (IOException) error
                    : new IOException("Unable to restore portable settings", error);
        }
    }

    private Properties capturePortableSettings(String profileScope) {
        Properties result = new Properties();
        put(result, PROFILE_GENERAL, redactMerged(
                appPrefs.getProfileDataForScope(profileScope, GENERAL_DATA), PRIVATE_GENERAL_INDEXES));
        put(result, PROFILE_PLAYER, redactMerged(
                appPrefs.getProfileDataForScope(profileScope, PLAYER_DATA), PRIVATE_PLAYER_INDEXES));
        put(result, PROFILE_PLAYER_TWEAKS,
                appPrefs.getProfileDataForScope(profileScope, PLAYER_TWEAKS_DATA));
        put(result, PROFILE_MAIN_UI,
                appPrefs.getProfileDataForScope(profileScope, MAIN_UI_DATA));
        put(result, GLOBAL_SEARCH, appPrefs.getData(SEARCH_DATA));
        put(result, GLOBAL_SPONSOR_BLOCK, redactMerged(appPrefs.getData(SPONSOR_BLOCK_DATA), PRIVATE_SPONSOR_BLOCK_INDEXES));
        put(result, GLOBAL_DEARROW, appPrefs.getData(DEARROW_DATA));

        SharedPreferences controls = context.getSharedPreferences(CONTROLS_PREFS, Context.MODE_PRIVATE);
        put(result, MOBILE_CONTROL_ORDER,
                controls.getString("control_order", "quality,speed,captions,chapters,pip"));
        for (String key : CONTROL_KEYS) {
            result.setProperty(MOBILE_CONTROL_PREFIX + key,
                    Boolean.toString(controls.getBoolean(key, true)));
        }
        return result;
    }

    private void applyPortableSettings(Properties values, String profileScope) throws IOException {
        applyProfileMerged(values, PROFILE_GENERAL, GENERAL_DATA, PRIVATE_GENERAL_INDEXES, profileScope);
        applyProfileMerged(values, PROFILE_PLAYER, PLAYER_DATA, PRIVATE_PLAYER_INDEXES, profileScope);
        applyProfile(values, PROFILE_PLAYER_TWEAKS, PLAYER_TWEAKS_DATA, profileScope);
        applyProfile(values, PROFILE_MAIN_UI, MAIN_UI_DATA, profileScope);
        applyGlobal(values, GLOBAL_SEARCH, SEARCH_DATA);
        applyGlobalMerged(values, GLOBAL_SPONSOR_BLOCK, SPONSOR_BLOCK_DATA, PRIVATE_SPONSOR_BLOCK_INDEXES);
        applyGlobal(values, GLOBAL_DEARROW, DEARROW_DATA);

        SharedPreferences controls = context.getSharedPreferences(CONTROLS_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = controls.edit();
        if (values.containsKey(MOBILE_CONTROL_ORDER)) {
            editor.putString("control_order", values.getProperty(MOBILE_CONTROL_ORDER));
        }
        for (String key : CONTROL_KEYS) {
            String property = MOBILE_CONTROL_PREFIX + key;
            if (values.containsKey(property)) {
                String value = values.getProperty(property);
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new IOException("Invalid mobile control value");
                }
                editor.putBoolean(key, Boolean.parseBoolean(value));
            }
        }
        if (!editor.commit()) {
            throw new IOException("Unable to persist mobile controls");
        }
    }

    private void applyProfile(Properties values, String property, String dataKey, String profileScope) {
        if (values.containsKey(property)
                && !appPrefs.writeProfileDataCheckedForScope(
                        profileScope, dataKey, values.getProperty(property))) {
            throw new IllegalStateException("Unable to persist profile settings");
        }
    }

    private void applyGlobal(Properties values, String property, String dataKey) {
        if (values.containsKey(property)
                && !appPrefs.writeDataChecked(dataKey, values.getProperty(property))) {
            throw new IllegalStateException("Unable to persist global settings");
        }
    }

    private void applyProfileMerged(Properties values, String property, String dataKey,
                                    int[] privateIndexes, String profileScope) {
        if (values.containsKey(property)) {
            if (!appPrefs.writeProfileDataCheckedForScope(profileScope, dataKey,
                    mergePrivate(values.getProperty(property),
                            appPrefs.getProfileDataForScope(profileScope, dataKey), privateIndexes))) {
                throw new IllegalStateException("Unable to persist merged profile settings");
            }
        }
    }

    private void applyGlobalMerged(Properties values, String property, String dataKey, int[] privateIndexes) {
        if (values.containsKey(property)) {
            if (!appPrefs.writeDataChecked(dataKey,
                    mergePrivate(values.getProperty(property), appPrefs.getData(dataKey), privateIndexes))) {
                throw new IllegalStateException("Unable to persist merged global settings");
            }
        }
    }

    private void rollbackPortableSettings(Properties before, Properties attempted,
                                          String profileScope) throws IOException {
        applyPortableSettings(before, profileScope);
        deleteProfileIfCreated(before, attempted, PROFILE_GENERAL, GENERAL_DATA, profileScope);
        deleteProfileIfCreated(before, attempted, PROFILE_PLAYER, PLAYER_DATA, profileScope);
        deleteProfileIfCreated(before, attempted, PROFILE_PLAYER_TWEAKS, PLAYER_TWEAKS_DATA, profileScope);
        deleteProfileIfCreated(before, attempted, PROFILE_MAIN_UI, MAIN_UI_DATA, profileScope);
        deleteGlobalIfCreated(before, attempted, GLOBAL_SEARCH, SEARCH_DATA);
        deleteGlobalIfCreated(before, attempted, GLOBAL_SPONSOR_BLOCK, SPONSOR_BLOCK_DATA);
        deleteGlobalIfCreated(before, attempted, GLOBAL_DEARROW, DEARROW_DATA);
    }

    private void deleteProfileIfCreated(Properties before, Properties attempted, String property,
                                        String dataKey, String profileScope) throws IOException {
        if (!before.containsKey(property) && attempted.containsKey(property)
                && !appPrefs.deleteProfileDataCheckedForScope(profileScope, dataKey)) {
            throw new IOException("Unable to remove restore-created profile settings");
        }
    }

    private void deleteGlobalIfCreated(Properties before, Properties attempted,
                                       String property, String dataKey) throws IOException {
        if (!before.containsKey(property) && attempted.containsKey(property)
                && !appPrefs.deleteDataChecked(dataKey)) {
            throw new IOException("Unable to remove restore-created global settings");
        }
    }

    private static void validateSettings(Properties settings) throws IOException {
        validateRedactedMerged(settings, PROFILE_GENERAL, PRIVATE_GENERAL_INDEXES, 73);
        validateRedactedMerged(settings, PROFILE_PLAYER, PRIVATE_PLAYER_INDEXES, 63);
        validateRedactedMerged(settings, GLOBAL_SPONSOR_BLOCK, PRIVATE_SPONSOR_BLOCK_INDEXES, 12);
        validateMergedMinimum(settings, PROFILE_PLAYER_TWEAKS, 61);
        validateMergedMinimum(settings, PROFILE_MAIN_UI, 23);
        validateMergedMinimum(settings, GLOBAL_SEARCH, 12);
        validateMergedMinimum(settings, GLOBAL_DEARROW, 3);

        for (String key : CONTROL_KEYS) {
            String property = MOBILE_CONTROL_PREFIX + key;
            if (settings.containsKey(property)) {
                String value = settings.getProperty(property);
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new IOException("Backup contains an invalid mobile control value");
                }
            }
        }
        if (settings.containsKey(MOBILE_CONTROL_ORDER)) {
            String order = settings.getProperty(MOBILE_CONTROL_ORDER);
            Set<String> seen = new HashSet<>();
            for (String key : order.split(",")) {
                if (!Arrays.asList("quality", "speed", "captions", "chapters", "pip").contains(key)
                        || !seen.add(key)) {
                    throw new IOException("Backup contains an invalid mobile control order");
                }
            }
            if (seen.size() != 5) {
                throw new IOException("Backup contains an incomplete mobile control order");
            }
        }
    }

    private static void validateRedactedMerged(Properties settings, String property,
                                                int[] privateIndexes, int minimumItems) throws IOException {
        if (!settings.containsKey(property)) return;
        String[] values = Helpers.splitData(settings.getProperty(property));
        if (values == null || values.length < minimumItems) {
            throw new IOException("Backup contains a truncated settings record");
        }
        for (int index : privateIndexes) {
            if (index >= values.length || !"null".equals(values[index])) {
                throw new IOException("Backup contains private or malformed settings fields");
            }
        }
    }

    private static void validateMergedMinimum(Properties settings, String property,
                                              int minimumItems) throws IOException {
        if (!settings.containsKey(property)) return;
        String[] values = Helpers.splitData(settings.getProperty(property));
        if (values == null || values.length < minimumItems) {
            throw new IOException("Backup contains a truncated settings record");
        }
    }

    private void assertRestored(Properties expected, Properties actual) throws IOException {
        for (String key : expected.stringPropertyNames()) {
            if (!expected.getProperty(key).equals(actual.getProperty(key))) {
                throw new IOException("Restore verification failed");
            }
        }
    }

    private void assertSnapshotRestored(Properties expected, Properties actual) throws IOException {
        for (String key : ALLOWED_KEYS) {
            if (expected.containsKey(key) != actual.containsKey(key)
                    || (expected.containsKey(key)
                    && !expected.getProperty(key).equals(actual.getProperty(key)))) {
                throw new IOException("Rollback verification failed");
            }
        }
    }

    private void assertCurrentProfile(String expectedScope) throws IOException {
        if (expectedScope == null || !expectedScope.equals(appPrefs.getCurrentProfileScope())) {
            throw new IOException("Selected profile changed during restore");
        }
    }

    private static String redactMerged(String data, int[] privateIndexes) {
        String[] values = Helpers.splitData(data);
        if (values == null) {
            return data;
        }
        for (int index : privateIndexes) {
            if (index < values.length) {
                values[index] = null;
            }
        }
        return Helpers.mergeData((Object[]) values);
    }

    private static String mergePrivate(String portable, String current, int[] privateIndexes) {
        String[] portableValues = Helpers.splitData(portable);
        String[] currentValues = Helpers.splitData(current);
        if (portableValues == null) {
            return portable;
        }
        if (currentValues != null) {
            for (int index : privateIndexes) {
                if (index < portableValues.length && index < currentValues.length) {
                    portableValues[index] = currentValues[index];
                }
            }
        }
        return Helpers.mergeData((Object[]) portableValues);
    }

    private static void put(Properties properties, String key, String value) {
        if (value != null) {
            properties.setProperty(key, value);
        }
    }

    private static void writeProperties(ZipOutputStream zip, String entryName, Properties properties) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        properties.store(buffer, null);
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > MAX_ENTRY_BYTES) {
            throw new IOException("Portable settings exceed the backup limit");
        }
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static Properties readProperties(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        int total = 0;
        while ((read = zip.read(chunk)) != -1) {
            total += read;
            if (total > MAX_ENTRY_BYTES) {
                throw new IOException("Backup entry exceeds the size limit");
            }
            buffer.write(chunk, 0, read);
        }
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(buffer.toByteArray()));
        return properties;
    }
}
