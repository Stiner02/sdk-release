package com.tune.ma.playlist;

import android.util.Log;

import com.tune.ma.TuneManager;
import com.tune.ma.configuration.TuneConfigurationManager;
import com.tune.ma.eventbus.TuneEventBus;
import com.tune.ma.eventbus.event.TuneAppBackgrounded;
import com.tune.ma.eventbus.event.TuneAppForegrounded;
import com.tune.ma.eventbus.event.TunePlaylistManagerCurrentPlaylistChanged;
import com.tune.ma.model.TuneCallback;
import com.tune.ma.model.TuneCallbackHolder;
import com.tune.ma.playlist.model.TunePlaylist;
import com.tune.ma.utils.TuneDebugLog;
import com.tune.ma.utils.TuneJsonUtils;
import com.tune.ma.utils.TuneStringUtils;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by gowie on 1/27/16.
 */
public class TunePlaylistManager {

    private TuneCallbackHolder onFirstPlaylistDownloadCallbackHolder;
    private boolean firstPlaylistCallbackExecuted;

    private TunePlaylist currentPlaylist;
    private boolean started;
    private boolean isUpdating;
    private boolean receivedFirstPlaylistDownload;

    private ScheduledThreadPoolExecutor scheduler;
    private ExecutorService executorService;

    private static final String TAG = "PlaylistManager";

    public TunePlaylistManager() {
        this.started = false;
        this.isUpdating = false;
        this.receivedFirstPlaylistDownload = false;
        this.executorService = Executors.newSingleThreadExecutor();
        this.loadPlaylistFromDisk();
    }

    //  EventBus Events
    ////////////////////
    public synchronized void onEvent(TuneAppForegrounded event) {
        if (TuneManager.getInstance() == null || TuneManager.getInstance().getConfigurationManager().isTMADisabled()) {
            return;
        }

        // If there's a callback holder that was canceled on app background, resume it
        if (onFirstPlaylistDownloadCallbackHolder != null && onFirstPlaylistDownloadCallbackHolder.isCanceled()) {
            // If playlist is downloaded, execute callback immediately
            if (receivedFirstPlaylistDownload) {
                onFirstPlaylistDownloadCallbackHolder.executeBlock();
            } else {
                long timeout = onFirstPlaylistDownloadCallbackHolder.getTimeout();
                if (timeout > 0) {
                    // Restart the canceled callback with timeout
                    onFirstPlaylistDownloadCallbackHolder.setTimeout(timeout);
                }
            }
        }

        if (TuneManager.getInstance().getConfigurationManager().getPollForPlaylist() && !this.started) {
            this.startPlaylistRetriever();
            started = true;
        } else {
            executorService.execute(new PlaylistRetrievalTask());
        }
    }

    public synchronized void onEvent(TuneAppBackgrounded event) {
        if (started) {
            this.stopPlaylistRetriever();
            started = false;
        }

        // If there's a currently pending callback holder, stop it upon background
        if (onFirstPlaylistDownloadCallbackHolder != null) {
            onFirstPlaylistDownloadCallbackHolder.stopTimer();
        }
    }

    // Start/Stop Retriever Task
    /////////////////////////////
    private void startPlaylistRetriever() {
        TuneDebugLog.i(TAG, "Starting PlaylistRetriever Schedule.");
        scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.scheduleAtFixedRate(new PlaylistRetrievalTask(), 0, TuneManager.getInstance().getConfigurationManager().getPlaylistRequestPeriod(), TimeUnit.SECONDS);
    }

    private void stopPlaylistRetriever() {
        if (scheduler != null) {
            TuneDebugLog.i(TAG, "Stopping PlaylistRetriever Schedule.");
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private boolean loadPlaylistFromDisk() {
        JSONObject playlistFromDisk = null;
        if (TuneManager.getInstance().getConfigurationManager().usePlaylistPlayer()) {
            playlistFromDisk = TuneManager.getInstance().getPlaylistPlayer().getNext();
        } else {
            playlistFromDisk = TuneManager.getInstance().getFileManager().readPlaylist();
        }

        if (playlistFromDisk != null) {
            TunePlaylist playlist = new TunePlaylist(playlistFromDisk);
            playlist.setFromDisk(true);
            setCurrentPlaylist(playlist);
            return true;
        } else {
            return false;
        }
    }

    public TunePlaylist getCurrentPlaylist() {
        return currentPlaylist;
    }

    protected synchronized void setCurrentPlaylist(TunePlaylist newPlaylist) {
        // If TMA is disabled, the new playlist is always blank.
        if (TuneManager.getInstance() == null || TuneManager.getInstance().getConfigurationManager().isTMADisabled()) {
            newPlaylist = new TunePlaylist();
        }

        if (currentPlaylist == null || !currentPlaylist.equals(newPlaylist)) {
            this.currentPlaylist = newPlaylist;

            // Current playlist changed and not from disk, save new playlist to disk
            if (TuneManager.getInstance() != null && !newPlaylist.isFromDisk()) {
                TuneDebugLog.i("Saving New Playlist to Disk");
                TuneManager.getInstance().getFileManager().writePlaylist(currentPlaylist.toJson());
            }

            TuneEventBus.post(new TunePlaylistManagerCurrentPlaylistChanged(newPlaylist));
        }

        if (!newPlaylist.isFromDisk()) {
            checkTriggerOnFirstPlaylistDownloadedCallback();
        }
    }

    private class PlaylistRetrievalTask implements Runnable {

        @Override
        public void run() {
            // If we are off then don't bother requesting anymore playlists
            if (TuneManager.getInstance() == null || TuneManager.getInstance().getConfigurationManager().isTMADisabled()) {
                return;
            }

            TuneDebugLog.i("Retrieving Playlist from Server");

            if (isUpdating) {
                return;
            }

            isUpdating = true;

            try {
                TuneConfigurationManager configurationManager = TuneManager.getInstance().getConfigurationManager();

                JSONObject response = null;
                if (configurationManager.usePlaylistPlayer()) {
                    response = TuneManager.getInstance().getPlaylistPlayer().getNext();
                } else {
                    response = TuneManager.getInstance().getApi().getPlaylist();
                }

                TunePlaylist newPlaylist = null;
                if (response == null) {
                    TuneDebugLog.w("Playlist response did not have any JSON");
                    // If we failed to get the playlist (or if it is empty) then trigger the 'onFirstPlaylistDownloaded' callback immediately
                    // So that we don't hit the timeout.
                    checkTriggerOnFirstPlaylistDownloadedCallback();
                } else if (response.length() == 0) {
                    /*
                     *  IMPORTANT:
                     *     An empty playlist is a signal from the server to not process anything
                     */

                    TuneDebugLog.w("Received empty playlist from the server -- not updating");
                    checkTriggerOnFirstPlaylistDownloadedCallback();
                } else {
                    if (configurationManager.echoPlaylists()) {
                        TuneDebugLog.alwaysLog("Got Playlist:\n" + TuneJsonUtils.getPrettyJson(response));
                    }
                    newPlaylist = new TunePlaylist(response);
                }

                if (newPlaylist != null) {
                    setCurrentPlaylist(newPlaylist);
                }
            } catch (Exception e) {
                e.printStackTrace();
                TuneDebugLog.e(TAG, "Failed to download new playlist.");
            } finally {
                isUpdating = false;
            }
        }
    }

    // On First Playlist Downloaded Callback
    //////////////////////////////////////////

    public synchronized void onFirstPlaylistDownloaded(final TuneCallback callback, long timeout) {
        if (callback == null) {
            TuneDebugLog.IAMConfigError("You passed a null TuneCallback for the onFirstPlaylistDownloaded callback.");
            return;
        }

        // If there's a currently pending callback holder, stop it
        if (onFirstPlaylistDownloadCallbackHolder != null) {
            onFirstPlaylistDownloadCallbackHolder.stopTimer();
        }

        // Initialize callback executed to false, since this method is registering it
        setFirstPlaylistCallbackExecuted(false);
        if (TuneManager.getInstance() == null || TuneManager.getInstance().getConfigurationManager().isTMADisabled()) {
            TuneDebugLog.i("TMA is Disabled, executing firstPlaylistDownload callback");
            setFirstPlaylistCallbackExecuted(true);
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    callback.execute();
                }
            });
        } else if (receivedFirstPlaylistDownload) {
            TuneDebugLog.i("Playlist already downloaded upon callback registration, executing firstPlaylistDownload callback");
            setFirstPlaylistCallbackExecuted(true);
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    callback.execute();
                }
            });
        } else {
            onFirstPlaylistDownloadCallbackHolder = new TuneCallbackHolder(callback);

            if (timeout > 0) {
                TuneDebugLog.i("Playlist not downloaded, executing firstPlaylistDownload callback after timeout " + timeout);
                onFirstPlaylistDownloadCallbackHolder.setTimeout(timeout);
            }
        }
    }

    public boolean hasFirstPlaylistCallbackExecuted() {
        return firstPlaylistCallbackExecuted;
    }

    public void setFirstPlaylistCallbackExecuted(boolean firstPlaylistCallbackExecuted) {
        this.firstPlaylistCallbackExecuted = firstPlaylistCallbackExecuted;
    }

    private synchronized void checkTriggerOnFirstPlaylistDownloadedCallback() {
        if (!receivedFirstPlaylistDownload) {
            receivedFirstPlaylistDownload = true;

            // If we have a pending callback that hasn't been executed, execute it
            if (!firstPlaylistCallbackExecuted && onFirstPlaylistDownloadCallbackHolder != null) {
                TuneDebugLog.i("Playlist downloaded, executing firstPlaylistDownload callback");
                try {
                    executorService.execute(new Runnable() {
                        @Override
                        public void run() {
                            onFirstPlaylistDownloadCallbackHolder.executeBlock();
                        }
                    });
                } catch (Exception e) {
                    TuneDebugLog.e(TuneStringUtils.format("Exception in executing firstPlaylistDownload callback. %s", Log.getStackTraceString(e)));
                }
            }
        }
    }
}