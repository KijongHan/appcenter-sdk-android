/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.distribute;

import android.app.DownloadManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.os.Handler;
import android.os.SystemClock;
import android.support.annotation.NonNull;

import com.microsoft.appcenter.utils.HandlerUtils;
import com.microsoft.appcenter.utils.storage.SharedPreferencesManager;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;

import java.util.concurrent.Semaphore;

import static com.microsoft.appcenter.distribute.DistributeConstants.CHECK_PROGRESS_TIME_INTERVAL_IN_MILLIS;
import static com.microsoft.appcenter.distribute.DistributeConstants.DOWNLOAD_STATE_AVAILABLE;
import static com.microsoft.appcenter.distribute.DistributeConstants.DOWNLOAD_STATE_INSTALLING;
import static com.microsoft.appcenter.distribute.DistributeConstants.HANDLER_TOKEN_CHECK_PROGRESS;
import static com.microsoft.appcenter.distribute.DistributeConstants.MEBIBYTE_IN_BYTES;
import static com.microsoft.appcenter.distribute.DistributeConstants.PREFERENCE_KEY_DOWNLOAD_STATE;
import static com.microsoft.appcenter.distribute.DistributeConstants.PREFERENCE_KEY_RELEASE_DETAILS;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.powermock.api.mockito.PowerMockito.doAnswer;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.when;
import static org.powermock.api.mockito.PowerMockito.whenNew;

@PrepareForTest(SystemClock.class)
public class DistributeMandatoryDownloadTest extends AbstractDistributeAfterDownloadTest {

    @Mock
    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    private android.app.ProgressDialog mProgressDialog;

    @Mock
    private Handler mHandler;

    @Before
    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    public void setUpDownload() throws Exception {

        /* Mock some dialog methods. */
        whenNew(android.app.ProgressDialog.class).withAnyArguments().thenReturn(mProgressDialog);
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                when(mProgressDialog.isIndeterminate()).thenReturn((Boolean) invocation.getArguments()[0]);
                return null;
            }
        }).when(mProgressDialog).setIndeterminate(anyBoolean());
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                Mockito.when(mDialog.isShowing()).thenReturn(true);
                return null;
            }
        }).when(mDialog).show();
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                Mockito.when(mDialog.isShowing()).thenReturn(false);
                return null;
            }
        }).when(mDialog).hide();

        /* Mock time for Handler.post. */
        mockStatic(SystemClock.class);
        when(SystemClock.uptimeMillis()).thenReturn(1L);

        /* Mock Handler. */
        when(HandlerUtils.getMainHandler()).thenReturn(mHandler);

        /* Set up common download test. */
        setUpDownload(true);
    }

    @Test
    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    public void disabledBeforeClickOnDialogInstall() throws Exception {

        /* Unblock download. */
        prepareAndStartDownload(true);

        /* Complete download. */
        completeDownload();

        Intent installIntent = mockInstallIntent();
        verify(mContext).startActivity(installIntent);

        /* Cancel install to go back to app. */
        Distribute.getInstance().onActivityPaused(mActivity);
        Distribute.getInstance().onActivityResumed(mActivity);

        /* Verify install dialog shown. */
        ArgumentCaptor<DialogInterface.OnClickListener> clickListener = ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mDialogBuilder).setPositiveButton(eq(R.string.appcenter_distribute_install), clickListener.capture());

        /* Disable SDK. */
        Distribute.setEnabled(false);

        /* Click. */
        clickListener.getValue().onClick(mDialog, DialogInterface.BUTTON_POSITIVE);

        /* Verify disabled. */
        verify(mReleaseDownloader).cancel();
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOAD_STATE);
    }

    @Test
    public void jsonCorruptedWhenRestarting() throws Exception {

        /* Simulate async task. */
        //FIXME: waitDownloadTask();

        /* Make JSON parsing fail. */
        when(ReleaseDetails.parse(anyString())).thenThrow(new JSONException("mock"));
        verifyWithInvalidOrMissingCachedJson();
    }

    @Test
    public void jsonMissingWhenRestarting() throws Exception {

        /* Simulate async task. */
        //FIXME: waitDownloadTask();

        /* Make JSON disappear for some reason (should not happen for real). */
        SharedPreferencesManager.remove(PREFERENCE_KEY_RELEASE_DETAILS);
        verifyWithInvalidOrMissingCachedJson();
    }

    private void verifyWithInvalidOrMissingCachedJson() throws Exception {
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mActivity);
        //FIXME: mockProgressCursor(-1);

        /* Unblock the mock task before restart sdk (unit test limitation). */
        //FIXME: waitCheckDownloadTask();

        /* Unblock the task that is scheduled after restart to check sanity. */
        //FIXME: waitCheckDownloadTask();

        /* Verify JSON removed. */
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_RELEASE_DETAILS);

        /* In that case the SDK will think its not mandatory but anyway this case never happens. */
        //FIXME: mockSuccessCursor();
        Intent intent = mockInstallIntent();
        completeDownload();
        //FIXME: waitCheckDownloadTask();
        verify(mContext).startActivity(intent);
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOAD_STATE);
    }

    @Test
    public void newOptionalUpdateWhileInstallingMandatory() throws Exception {

        /* Mock mandatory download and showing install U.I. */
        prepareAndStartDownload(true);
        
        completeDownload();
        verify(mContext).startActivity(mInstallIntent);
        verifyStatic();
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_AVAILABLE);
        verifyStatic();
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_INSTALLING);
        verifyNoMoreInteractions(mNotificationManager);

        /* Restart will restore install. */
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mActivity);
        //FIXME: waitCheckDownloadTask();
        verify(mContext, times(2)).startActivity(installIntent);
        verifyStatic(times(2));
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_INSTALLING);
        Distribute.getInstance().onActivityPaused(mActivity);

        /* Change what the next detected release will be: a more recent mandatory one. */
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(5);
        when(releaseDetails.getVersion()).thenReturn(8);
        when(releaseDetails.getDownloadUrl()).thenReturn(mDownloadUrl);
        when(releaseDetails.isMandatoryUpdate()).thenReturn(false);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);
        Distribute.getInstance().onActivityResumed(mActivity);
        verifyStatic(times(2));
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_AVAILABLE);

        /* Check no more going to installed state. I.e. still happened twice. */
        verifyStatic(times(2));
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_INSTALLING);

        /* Check next restart will use new release. */
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mActivity);

        /* Verify postpone-able dialog displayed. */
        verify(mDialogBuilder).setNegativeButton(anyInt(), any(DialogInterface.OnClickListener.class));
    }

    @Test
    public void newMandatoryUpdateWhileInstallingMandatory() throws Exception {

        /* Mock mandatory download and showing install U.I. */
        prepareAndStartDownload(true);
        completeDownload();
        //FIXME: mockSuccessCursor();
        Intent installIntent = mockInstallIntent();
        //FIXME: waitCheckDownloadTask();
        //FIXME: waitCheckDownloadTask();
        verify(mContext).startActivity(installIntent);
        verifyStatic();
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_AVAILABLE);
        verifyStatic();
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_INSTALLING);
        verifyNoMoreInteractions(mNotificationManager);

        /* Restart will restore install. */
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mActivity);
        //FIXME: waitCheckDownloadTask();
        verify(mContext, times(2)).startActivity(installIntent);
        verifyStatic(times(2));
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_INSTALLING);
        Distribute.getInstance().onActivityPaused(mActivity);

        /* Change what the next detected release will be: a more recent mandatory one. */
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(5);
        when(releaseDetails.getVersion()).thenReturn(8);
        when(releaseDetails.getShortVersion()).thenReturn("4.5.6");
        when(releaseDetails.getDownloadUrl()).thenReturn(mDownloadUrl);
        when(releaseDetails.isMandatoryUpdate()).thenReturn(true);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);
        Distribute.getInstance().onActivityResumed(mActivity);
        verifyStatic(times(2));
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_AVAILABLE);

        /* Check no more going to installed state. I.e. still happened twice. */
        verifyStatic(times(2));
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_INSTALLING);

        /* Check next restart will use new release. */
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mActivity);

        /* Verify new dialog displayed. */
        verify(mDialogBuilder).setMessage("unit-test-app4.5.68");
    }
}
