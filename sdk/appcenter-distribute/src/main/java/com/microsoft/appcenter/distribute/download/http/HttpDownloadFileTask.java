/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.distribute.download.http;

import android.content.Context;
import android.net.TrafficStats;
import android.os.AsyncTask;

import com.microsoft.appcenter.distribute.ReleaseDetails;
import com.microsoft.appcenter.distribute.download.DownloadProgress;
import com.microsoft.appcenter.http.TLS1_2SocketFactory;
import com.microsoft.appcenter.utils.AppCenterLog;
import com.microsoft.appcenter.utils.storage.SharedPreferencesManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;

import javax.net.ssl.HttpsURLConnection;

import static com.microsoft.appcenter.distribute.DistributeConstants.getDownloadFilesPath;
import static com.microsoft.appcenter.distribute.download.DownloadUtils.PREFERENCE_KEY_DOWNLOADED_FILE;
import static com.microsoft.appcenter.distribute.download.ReleaseDownloader.Listener;
import static com.microsoft.appcenter.http.HttpUtils.CONNECT_TIMEOUT;
import static com.microsoft.appcenter.http.HttpUtils.READ_TIMEOUT;
import static com.microsoft.appcenter.http.HttpUtils.THREAD_STATS_TAG;
import static com.microsoft.appcenter.http.HttpUtils.WRITE_BUFFER_SIZE;

/**
 * <h3>Description</h3>
 * <p>
 * Internal helper class. Downloads an .apk from AppCenter and stores
 * it on external storage. If the download was successful, the file
 * is then opened to trigger the installation.
 **/
public class HttpDownloadFileTask extends AsyncTask<Void, Integer, Long> {

    /**
     * Maximal number of allowed redirects.
     */
    private static final int MAX_REDIRECTS = 6;

    /**
     * Download callback.
     */
    private Listener mListener;

    /**
     * Path to the downloading apk.
     */
    private File mApkFilePath;

    /**
     * Path to the application specific "Downloads" folder.
     */
    private File mDownloadFilesPath;

    /**
     * Information about the downloading release.
     */
    private ReleaseDetails mReleaseDetails;

    HttpDownloadFileTask(ReleaseDetails releaseDetails, Listener listener, Context context) {
        mReleaseDetails = releaseDetails;
        mListener = listener;
        mDownloadFilesPath = getDownloadFilesPath(context);
        mApkFilePath = resolveApkFilePath();
    }

    @Override
    protected Long doInBackground(Void... args) {
        try {
            URL url = new URL(mReleaseDetails.getDownloadUrl().toString());
            TrafficStats.setThreadStatsTag(THREAD_STATS_TAG);
            URLConnection connection = createConnection(url, MAX_REDIRECTS);
            connection.connect();
            String contentType = connection.getContentType();
            if (contentType != null && contentType.contains("text")) {

                /* This is not the expected APK file. Maybe the redirect could not be resolved. */
                if (mListener != null) {
                    mListener.onError("The requested download does not appear to be a file.");
                }
                return 0L;
            }
            boolean result = mDownloadFilesPath.mkdirs();
            if (!result && !mDownloadFilesPath.exists()) {
                throw new IOException("Could not create the dir(s):" + mDownloadFilesPath.getAbsolutePath());
            }
            if (mApkFilePath.exists()) {
                mApkFilePath.delete();
            }

            /* Download the release file. */
            return downloadReleaseFile(connection);
        } catch (IOException e) {
            AppCenterLog.error("Failed to download " + mReleaseDetails.getDownloadUrl().toString(), e.getMessage());
            return 0L;
        } finally {
            TrafficStats.clearThreadStatsTag();
        }
    }

    @Override
    protected void onPostExecute(Long result) {
        if (result > 0L && mListener != null) {
            SharedPreferencesManager.putString(PREFERENCE_KEY_DOWNLOADED_FILE, mApkFilePath.getAbsolutePath());
            mListener.onComplete("file://" + mApkFilePath.getAbsolutePath());
        }
    }

    /**
     * Performs IO operation to download .apk file through HttpConnection.
     * Saves .apk file to the mApkFilePath.
     *
     * @param connection URLConnection instance
     * @return total number of downloaded bytes.
     * @throws IOException if connection fails
     */
    private Long downloadReleaseFile(URLConnection connection) throws IOException {
        InputStream input = null;
        OutputStream output = null;
        int lengthOfFile = connection.getContentLength();
        try {
            input = new BufferedInputStream(connection.getInputStream());
            output = new FileOutputStream(mApkFilePath);
            byte[] data = new byte[WRITE_BUFFER_SIZE];
            int count;
            long totalBytesDownloaded = 0;
            while ((count = input.read(data)) != -1) {
                totalBytesDownloaded += count;
                if (mListener != null) {
                    mListener.onProgress(new DownloadProgress(totalBytesDownloaded, lengthOfFile));
                }
                output.write(data, 0, count);
                if (isCancelled()) {
                    break;
                }
            }
            output.flush();
            return totalBytesDownloaded;
        } finally {
            try {
                if (output != null) {
                    output.close();
                }
                if (input != null) {
                    input.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    private File resolveApkFilePath() {
        String apkFileName = mReleaseDetails.getReleaseHash() + ".apk";
        return new File(mDownloadFilesPath, apkFileName);
    }

    /**
     * Recursive method for resolving redirects. Resolves at most MAX_REDIRECTS times.
     *
     * @param url                a URL
     * @param remainingRedirects loop counter
     * @return instance of URLConnection
     * @throws IOException if connection fails
     */
    private URLConnection createConnection(URL url, int remainingRedirects) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setSSLSocketFactory(new TLS1_2SocketFactory());
        connection.setInstanceFollowRedirects(true);

        /* Configure connection timeouts. */
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);

        int code = connection.getResponseCode();
        if (code == HttpsURLConnection.HTTP_MOVED_PERM ||
                code == HttpsURLConnection.HTTP_MOVED_TEMP ||
                code == HttpsURLConnection.HTTP_SEE_OTHER) {
            if (remainingRedirects == 0) {

                /*  Stop redirecting. */
                return connection;
            }
            URL movedUrl = new URL(connection.getHeaderField("Location"));
            if (!url.getProtocol().equals(movedUrl.getProtocol())) {

                /*  HttpsURLConnection doesn't handle redirects across schemes, so handle it manually, see
                    http://code.google.com/p/android/issues/detail?id=41651 */
                connection.disconnect();
                return createConnection(movedUrl, --remainingRedirects); // Recursion
            }
        }
        return connection;
    }
}
