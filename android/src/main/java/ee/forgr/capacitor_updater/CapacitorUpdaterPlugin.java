package ee.forgr.capacitor_updater;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.getcapacitor.CapConfig;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.plugin.WebView;

import io.github.g00fy2.versioncompare.Version;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;

@CapacitorPlugin(name = "CapacitorUpdater")
public class CapacitorUpdaterPlugin extends Plugin implements Application.ActivityLifecycleCallbacks {
    private final String TAG = "Capacitor-updater";

    private static final String autoUpdateUrlDefault = "https://capgo.app/api/auto_update";
    private static final String statsUrlDefault = "https://capgo.app/api/stats";
    private static final String DELAY_UPDATE = "delayUpdate";

    private CapacitorUpdater implementation;

    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;

    private Integer appReadyTimeout = 10000;
    private String autoUpdateUrl = "";
    private Version currentVersionNative;
    private Boolean autoDeleteFailed = true;
    private Boolean autoDeletePrevious = true;
    private Boolean autoUpdate = false;
    private Boolean resetWhenUpdate = true;

    private volatile Thread appReadyCheck;

    @Override
    public void load() {
        super.load();
        this.prefs = this.getContext().getSharedPreferences(WebView.WEBVIEW_PREFS_NAME, Activity.MODE_PRIVATE);
        this.editor = this.prefs.edit();

        try {
            this.implementation = new CapacitorUpdater(this.getContext(), new CapacitorUpdaterEvents() {
                @Override
                public void notifyDownload(final int percent) {
                    CapacitorUpdaterPlugin.this.notifyDownload(percent);
                }
            });
            final PackageInfo pInfo = this.getContext().getPackageManager().getPackageInfo(this.getContext().getPackageName(), 0);
            this.currentVersionNative = new Version(pInfo.versionName);
        } catch (final PackageManager.NameNotFoundException e) {
            Log.e(this.TAG, "Error instantiating implementation", e);
            return;
        } catch (final Exception e) {
            Log.e(this.TAG, "Error getting current native app version", e);
            return;
        }

        final CapConfig config = CapConfig.loadDefault(this.getActivity());
        this.implementation.setAppId(config.getString("appId", ""));
        this.implementation.setStatsUrl(this.getConfig().getString("statsUrl", statsUrlDefault));

        this.autoDeleteFailed = this.getConfig().getBoolean("autoDeleteFailed", true);
        this.autoDeletePrevious = this.getConfig().getBoolean("autoDeletePrevious", true);
        this.autoUpdateUrl = this.getConfig().getString("autoUpdateUrl", autoUpdateUrlDefault);
        this.autoUpdate = this.getConfig().getBoolean("autoUpdate", false);
        this.appReadyTimeout = this.getConfig().getInt("appReadyTimeout", 10000);
        this.resetWhenUpdate = this.getConfig().getBoolean("resetWhenUpdate", true);

        this.cleanupObsoleteVersions();

        if (!this.autoUpdate || this.autoUpdateUrl.equals("")) return;
        final Application application = (Application) this.getContext().getApplicationContext();
        application.registerActivityLifecycleCallbacks(this);
        this.onActivityStarted(this.getActivity());
    }

    private void cleanupObsoleteVersions() {
        if (this.resetWhenUpdate) {
            try {
                final Version previous = new Version(this.prefs.getString("LatestVersionNative", ""));
                try {
                    if (!"".equals(previous.getOriginalString()) && this.currentVersionNative.getMajor() > previous.getMajor()) {
                        this.implementation.reset(true);
                        final ArrayList<VersionInfo> installed = this.implementation.list();
                        for (final VersionInfo version: installed) {
                            try {
                                Log.i(this.TAG, "Deleting obsolete version: " + version);
                                this.implementation.delete(version.getVersion());
                            } catch (final Exception e) {
                                Log.e(this.TAG, "Failed to delete: " + version, e);
                            }
                        }
                    }
                } catch (final Exception e) {
                    Log.e(this.TAG, "Could not determine the current version", e);
                }
            } catch(final Exception e) {
                Log.e(this.TAG, "Error calculating previous native version", e);
            }
        }

        this.editor.putString("LatestVersionNative", this.currentVersionNative.toString());
        this.editor.commit();
    }

    public void notifyDownload(final int percent) {
        try {
            final JSObject ret = new JSObject();
            ret.put("percent", percent);
            this.notifyListeners("download", ret);
        } catch (final Exception e) {
            Log.e(this.TAG, "Could not notify listeners", e);
        }
    }


    @PluginMethod
    public void getId(final PluginCall call) {
        try {
            final JSObject ret = new JSObject();
            ret.put("id", this.implementation.getDeviceID());
            call.resolve(ret);
        } catch (final Exception e) {
            Log.e(this.TAG, "Could not get device id", e);
            call.reject("Could not get device id", e);
        }
    }

    @PluginMethod
    public void download(final PluginCall call) {
        final String url = call.getString("url");
        try {
            Log.i(this.TAG, "Downloading " + url);

            new Thread(new Runnable(){
                @Override
                public void run() {
                    try {
                        final String versionName = call.getString("versionName");
                        final VersionInfo downloaded = CapacitorUpdaterPlugin.this.implementation.download(url, versionName);
                        call.resolve(downloaded.toJSON());
                    } catch (final IOException e) {
                        Log.e(CapacitorUpdaterPlugin.this.TAG, "download failed", e);
                        call.reject("download failed", e);
                    }
                }
            }).start();
        } catch (final Exception e) {
            Log.e(this.TAG, "Failed to download " + url, e);
            call.reject("Failed to download " + url, e);
        }
    }

    private boolean _reload() {
        final String path = this.implementation.getCurrentBundlePath();
        Log.i(this.TAG, "Reloading: " + path);
        if(this.implementation.isUsingBuiltin()) {
            this.bridge.setServerAssetPath(path);
        } else {
            this.bridge.setServerBasePath(path);
        }
        this.checkAppReady();
        return true;
    }
    
    @PluginMethod
    public void reload(final PluginCall call) {
        try {
            if (this._reload()) {
                call.resolve();
            } else {
                call.reject("reload failed");
            }
        } catch(final Exception e) {
            Log.e(this.TAG, "Could not reload", e);
            call.reject("Could not reload", e);
        }
    }

    @PluginMethod
    public void next(final PluginCall call) {
        final String version = call.getString("version");
        final String versionName = call.getString("versionName", "");

        try {
            Log.i(this.TAG, "Setting next active version " + version);
            if (!this.implementation.setNextVersion(version)) {
                call.reject("Set next version failed. Version " + version + " does not exist.");
            } else {
                if(!"".equals(versionName)) {
                    this.implementation.setVersionName(version, versionName);
                }
                call.resolve(this.implementation.getVersionInfo(version).toJSON());
            }
        } catch (final Exception e) {
            Log.e(this.TAG, "Could not set next version " + version, e);
            call.reject("Could not set next version " + version, e);
        }
    }

    @PluginMethod
    public void set(final PluginCall call) {
        final String version = call.getString("version");
        final String versionName = call.getString("versionName", "");

        try {
            Log.i(this.TAG, "Setting active bundle " + version);
            if (!this.implementation.set(version)) {
                Log.i(this.TAG, "No such bundle " + version);
                call.reject("Update failed, version " + version + " does not exist.");
            } else {
                Log.i(this.TAG, "Bundle successfully set to" + version);
                if(!"".equals(versionName)) {
                    this.implementation.setVersionName(version, versionName);
                }
                this.reload(call);
            }
        } catch(final Exception e) {
            Log.e(this.TAG, "Could not set version " + version, e);
            call.reject("Could not set version " + version, e);
        }
    }

    @PluginMethod
    public void delete(final PluginCall call) {
        final String version = call.getString("version");
        Log.i(this.TAG, "Deleting version: " + version);
        try {
            final Boolean res = this.implementation.delete(version);
            if (res) {
                call.resolve();
            } else {
                call.reject("Delete failed, version " + version + " does not exist");
            }
        } catch(final Exception e) {
            Log.e(this.TAG, "Could not delete version " + version, e);
            call.reject("Could not delete version " + version, e);
        }
    }


    @PluginMethod
    public void list(final PluginCall call) {
        try {
            final ArrayList<VersionInfo> res = this.implementation.list();
            final JSObject ret = new JSObject();
            final JSArray values = new JSArray();
            for (final VersionInfo version : res) {
                values.put(version.toJSON());
            }
            ret.put("versions", values);
            call.resolve(ret);
        }
        catch(final Exception e) {
            Log.e(this.TAG, "Could not list versions", e);
            call.reject("Could not list versions", e);
        }
    }

    private boolean _reset(final Boolean toLastSuccessful) {
        final VersionInfo fallback = this.implementation.getFallbackVersion();
        this.implementation.reset();

        if (toLastSuccessful && !fallback.isBuiltin()) {
            Log.i(this.TAG, "Resetting to: " + fallback);
            return this.implementation.set(fallback) && this._reload();
        }

        Log.i(this.TAG, "Resetting to native.");
        return this._reload();
    }

    @PluginMethod
    public void reset(final PluginCall call) {
        try {
            final Boolean toLastSuccessful = call.getBoolean("toLastSuccessful", false);
            if (this._reset(toLastSuccessful)) {
                call.resolve();
                return;
            }
            call.reject("Reset failed");
        }
        catch(final Exception e) {
            Log.e(this.TAG, "Reset failed", e);
            call.reject("Reset failed", e);
        }
    }

    @PluginMethod
    public void current(final PluginCall call) {
        try {
            final JSObject ret = new JSObject();
            final VersionInfo bundle = this.implementation.getCurrentBundle();
            ret.put("bundle", bundle.toJSON());
            ret.put("native", this.currentVersionNative);
            call.resolve(ret);
        }
        catch(final Exception e) {
            Log.e(this.TAG, "Could not get current bundle version", e);
            call.reject("Could not get current bundle version", e);
        }
    }

    @PluginMethod
    public void notifyAppReady(final PluginCall call) {
        try {
            Log.i(this.TAG, "Current bundle loaded successfully.");
            final VersionInfo version = this.implementation.getCurrentBundle();
            this.implementation.commit(version);
            call.resolve();
        }
        catch(final Exception e) {
            Log.e(this.TAG, "Failed to notify app ready state", e);
            call.reject("Failed to notify app ready state", e);
        }
    }

    @PluginMethod
    public void delayUpdate(final PluginCall call) {
        try {
            Log.i(this.TAG, "Delay update.");
            this.editor.putBoolean(this.DELAY_UPDATE, true);
            this.editor.commit();
            call.resolve();
        }
        catch(final Exception e) {
            Log.e(this.TAG, "Failed to delay update", e);
            call.reject("Failed to delay update", e);
        }
    }

    @PluginMethod
    public void cancelDelay(final PluginCall call) {
        try {
            Log.i(this.TAG, "Cancel update delay.");
            this.editor.putBoolean(this.DELAY_UPDATE, false);
            this.editor.commit();
            call.resolve();
        }
        catch(final Exception e) {
            Log.e(this.TAG, "Failed to cancel update delay", e);
            call.reject("Failed to cancel update delay", e);
        }
    }

    @Override
    public void onActivityStarted(@NonNull final Activity activity) {
        if (CapacitorUpdaterPlugin.this.isAutoUpdateEnabled()) {
            new Thread(new Runnable(){
                @Override
                public void run() {

                        Log.i(CapacitorUpdaterPlugin.this.TAG, "Check for update via: " + CapacitorUpdaterPlugin.this.autoUpdateUrl);
                        CapacitorUpdaterPlugin.this.implementation.getLatest(CapacitorUpdaterPlugin.this.autoUpdateUrl, (res) -> {
                            try {
                                if (res.has("message")) {
                                    Log.i(CapacitorUpdaterPlugin.this.TAG, "message: " + res.get("message"));
                                    if (res.has("major") && res.getBoolean("major") && res.has("version")) {
                                        final JSObject majorAvailable = new JSObject();
                                        majorAvailable.put("version", (String) res.get("version"));
                                        CapacitorUpdaterPlugin.this.notifyListeners("majorAvailable", majorAvailable);
                                    }
                                    return;
                                }
                                final VersionInfo current = CapacitorUpdaterPlugin.this.implementation.getCurrentBundle();
                                final String newVersion = (String) res.get("version");

                                // FIXME: What is failingVersion actually doing? Seems redundant with VersionStatus
                                final String failingVersion = CapacitorUpdaterPlugin.this.prefs.getString("failingVersion", "");
                                if (!"".equals(newVersion) && !newVersion.equals(current.getVersion()) && !newVersion.equals(failingVersion)) {
                                    new Thread(new Runnable(){
                                        @Override
                                        public void run() {
                                            try {
                                                final String url = (String) res.get("url");
                                                final VersionInfo next = CapacitorUpdaterPlugin.this.implementation.download(url, newVersion);
                                                Log.i(CapacitorUpdaterPlugin.this.TAG, "New version: " + newVersion + " found. Current is " + (current.getName().equals("") ? "builtin" : current.getName()) + ", next backgrounding will trigger update");

                                                CapacitorUpdaterPlugin.this.implementation.setNextVersion(next.getVersion());

                                                this.notifyUpdateAvailable(next.getVersion());
                                            } catch (final Exception e) {
                                                Log.e(CapacitorUpdaterPlugin.this.TAG, "error downloading file", e);
                                            }
                                        }

                                        private void notifyUpdateAvailable(final String version) {
                                            final JSObject updateAvailable = new JSObject();
                                            updateAvailable.put("version", version);
                                            CapacitorUpdaterPlugin.this.notifyListeners("updateAvailable", updateAvailable);
                                        }
                                    }).start();
                                } else {
                                    Log.i(CapacitorUpdaterPlugin.this.TAG, "No need to update, " + current + " is the latest version.");
                                }
                            } catch (final JSONException e) {
                                Log.e(CapacitorUpdaterPlugin.this.TAG, "error parsing JSON", e);
                            }
                        });
                    }
            }).start();
        }

        this.checkAppReady();
    }

    @Override
    public void onActivityStopped(@NonNull final Activity activity) {
        Log.i(this.TAG, "Checking for pending update");
        try {

            final Boolean delayUpdate = this.prefs.getBoolean(this.DELAY_UPDATE, false);
            this.editor.putBoolean(this.DELAY_UPDATE, false);
            this.editor.commit();

            if (delayUpdate) {
                Log.i(this.TAG, "Update delayed to next backgrounding");
                return;
            }

            final VersionInfo fallback = this.implementation.getFallbackVersion();
            final VersionInfo current = this.implementation.getCurrentBundle();
            final VersionInfo next = this.implementation.getNextVersion();

            final Boolean success = current.getStatus() == VersionStatus.SUCCESS;

            if (next != null && !next.isErrorStatus() && (next.getVersion() != current.getVersion())) {
                // There is a next version waiting for activation

                if (this.implementation.set(next) && this._reload()) {
                    Log.i(this.TAG, "Updated to version: " + next);
                    this.implementation.setNextVersion(null);
                } else {
                    Log.e(this.TAG, "Update to version: " + next + " Failed!");
                }
            } else if (!success) {
                // There is a no next version, and the current version has failed

                if(!current.isBuiltin()) {
                    // Don't try to roll back the builtin version. Nothing we can do.

                    this.implementation.rollback(current);

                    Log.i(this.TAG, "Update failed: 'notifyAppReady()' was never called.");
                    Log.i(this.TAG, "Version: " + current + ", is in error state.");
                    Log.i(this.TAG, "Will fallback to: " + fallback + " on application restart.");
                    Log.i(this.TAG, "Did you forget to call 'notifyAppReady()' in your Capacitor App code?");

                    if (!fallback.isBuiltin() && !fallback.equals(current)) {
                        final Boolean res = this.implementation.set(fallback);
                        if (res && this._reload()) {
                            Log.i(this.TAG, "Revert to version: " + fallback);
                        } else {
                            Log.e(this.TAG, "Revert to version: " + fallback + " Failed!");
                        }
                    } else {
                        if (this._reset(false)) {
                            Log.i(this.TAG, "Reverted to 'builtin' bundle.");
                        }
                    }

                    if (this.autoDeleteFailed) {
                        Log.i(this.TAG, "Deleting failing version: " + current);
                        try {
                            final Boolean res = this.implementation.delete(current.getVersion());
                            if (res) {
                                Log.i(this.TAG, "Failed version deleted: " + current);
                            }
                        } catch (final IOException e) {
                            Log.e(this.TAG, "Failed to delete failed version: " + current, e);
                        }
                    }
                } else {
                    // Nothing we can/should do by default if the 'builtin' bundle fails to call 'notifyAppReady()'.
                }

            } else if (!fallback.isBuiltin()) {
                // There is a no next version, and the current version has succeeded
                this.implementation.commit(current);

                if(this.autoDeletePrevious) {
                    Log.i(this.TAG, "Version successfully loaded: " + current);
                    try {
                        final Boolean res = this.implementation.delete(fallback.getVersion());
                        if (res) {
                            Log.i(this.TAG, "Deleted previous version: " + fallback);
                        }
                    } catch (final IOException e) {
                        Log.e(this.TAG, "Failed to delete previous version: " + fallback, e);
                    }
                }
            }
        }
        catch(final Exception e) {
            Log.e(this.TAG, "Error during onActivityStopped", e);
        }
    }

    private Boolean isAutoUpdateEnabled() {
        return !"".equals(CapacitorUpdaterPlugin.this.autoUpdateUrl);
    }

    // not use but necessary here to remove warnings
    @Override
    public void onActivityResumed(@NonNull final Activity activity) {
        // TODO: Implement background updating based on `backgroundUpdate` and `backgroundUpdateDelay` capacitor.config.ts settings
    }

    @Override
    public void onActivityPaused(@NonNull final Activity activity) {
        // TODO: Implement background updating based on `backgroundUpdate` and `backgroundUpdateDelay` capacitor.config.ts settings
    }
    @Override
    public void onActivityCreated(@NonNull final Activity activity, @Nullable final Bundle savedInstanceState) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull final Activity activity, @NonNull final Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull final Activity activity) {
    }

    private void checkAppReady() {
        try {
            if(this.appReadyCheck != null) {
                this.appReadyCheck.interrupt();
            }
            this.appReadyCheck = new Thread(new DeferredNotifyAppReadyCheck());
            this.appReadyCheck.start();
        } catch (final Exception e) {
            Log.e(CapacitorUpdaterPlugin.this.TAG, "Failed to start " + DeferredNotifyAppReadyCheck.class.getName(), e);
        }
    }

    private class DeferredNotifyAppReadyCheck implements Runnable {
        @Override
        public void run() {
            try {
                Log.i(CapacitorUpdaterPlugin.this.TAG, "Wait for " + CapacitorUpdaterPlugin.this.appReadyTimeout + "ms, then check for notifyAppReady");
                Thread.sleep(CapacitorUpdaterPlugin.this.appReadyTimeout);
                // Automatically roll back to fallback version if notifyAppReady has not been called yet
                final VersionInfo current = CapacitorUpdaterPlugin.this.implementation.getCurrentBundle();
                if(current.isBuiltin()) {
                    Log.i(CapacitorUpdaterPlugin.this.TAG, "Built-in bundle is active. Nothing to do.");
                    return;
                }

                if(VersionStatus.SUCCESS != current.getStatus()) {
                    Log.e(CapacitorUpdaterPlugin.this.TAG, "notifyAppReady was not called, roll back current version: " + current);
                    CapacitorUpdaterPlugin.this.implementation.rollback(current);
                    CapacitorUpdaterPlugin.this._reset(true);
                } else {
                    Log.i(CapacitorUpdaterPlugin.this.TAG, "notifyAppReady was called. This is fine: " + current);
                }

                CapacitorUpdaterPlugin.this.appReadyCheck = null;
            } catch (final InterruptedException e) {
                Log.e(CapacitorUpdaterPlugin.this.TAG, DeferredNotifyAppReadyCheck.class.getName() + " was interrupted.");
            }
        }
    }
}
