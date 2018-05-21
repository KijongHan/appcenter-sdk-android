package com.microsoft.appcenter.channel;

import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import com.microsoft.appcenter.ingestion.models.Log;
import com.microsoft.appcenter.ingestion.models.json.LogSerializer;
import com.microsoft.appcenter.ingestion.models.one.CommonSchemaLog;
import com.microsoft.appcenter.utils.UUIDUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One Collector channel listener used to redirect selected traffic to One Collector.
 */
public class OneCollectorChannelListener extends AbstractChannelListener {

    /**
     * Maximum time interval in milliseconds after which a synchronize will be triggered, regardless of queue size.
     */
    @VisibleForTesting
    static final int ONE_COLLECTOR_TRIGGER_INTERVAL = 3 * 1000;

    /**
     * Number of metrics queue items which will trigger synchronization.
     */
    @VisibleForTesting
    static final int ONE_COLLECTOR_TRIGGER_COUNT = 50;

    /**
     * Maximum number of requests being sent for the group.
     */
    @VisibleForTesting
    static final int ONE_COLLECTOR_TRIGGER_MAX_PARALLEL_REQUESTS = 3;

    /**
     * Postfix for One Collector's groups.
     */
    @VisibleForTesting
    static final String ONE_COLLECTOR_GROUP_NAME_SUFFIX = "/one";

    @Override
    public void onPreparedLog(@NonNull Log log, @NonNull String groupName) {

        /* Convert logs to Common Schema. */
        Collection<CommonSchemaLog> commonSchemaLogs = mLogSerializer.toCommonSchemaLog(log);

        /* Add SDK extension part A fields. LibVer is already set. */
        for (CommonSchemaLog commonSchemaLog : commonSchemaLogs) {
            commonSchemaLog.getExt().getSdk().setInstallId(mInstallId);
            EpochAndSeq epochAndSeq = mEpochsAndSeqsByIKey.get(commonSchemaLog.getIKey());
            if (epochAndSeq == null) {
                epochAndSeq = new EpochAndSeq(UUIDUtils.randomUUID().toString(), 0L);
                mEpochsAndSeqsByIKey.put(commonSchemaLog.getIKey(), epochAndSeq);
            }
            commonSchemaLog.getExt().getSdk().setEpoch(epochAndSeq.epoch);
            commonSchemaLog.getExt().getSdk().setSeq(++epochAndSeq.seq);
        }
    }

    /**
     * Channel.
     */
    private final Channel mChannel;

    /**
     * Log serializer.
     */
    private final LogSerializer mLogSerializer;

    /**
     * Install id.
     */
    private final UUID mInstallId;

    /**
     * Epochs and sequences grouped by iKey.
     */
    private final Map<String, EpochAndSeq> mEpochsAndSeqsByIKey = new HashMap<>();

    /**
     * Init with channel.
     */
    public OneCollectorChannelListener(@NonNull Channel channel, @NonNull LogSerializer logSerializer, @NonNull UUID installId) {
        mChannel = channel;
        mLogSerializer = logSerializer;
        mInstallId = installId;
    }

    @Override
    public void onGroupRemoved(@NonNull String groupName) {
        if (isOneCollectorGroup(groupName)) {
            return;
        }
        String oneCollectorGroupName = getOneCollectorGroupName(groupName);
        mChannel.removeGroup(oneCollectorGroupName);

        /* TODO: We need to reset epoch and sequence in onEnabled(false) callback. */
    }

    @Override
    public void onGroupAdded(@NonNull String groupName) {
        if (isOneCollectorGroup(groupName)) {
            return;
        }
        String oneCollectorGroupName = getOneCollectorGroupName(groupName);
        mChannel.addGroup(oneCollectorGroupName, ONE_COLLECTOR_TRIGGER_COUNT, ONE_COLLECTOR_TRIGGER_INTERVAL, ONE_COLLECTOR_TRIGGER_MAX_PARALLEL_REQUESTS, null, null);
    }

    @Override
    public boolean shouldFilter(@NonNull Log log) {

        /* Don't send the logs to AppCenter if it is being sent to OneCollector. */
        return !log.getTransmissionTargetTokens().isEmpty() && !(log instanceof CommonSchemaLog);
    }

    /**
     * Epoch and sequence number for logs.
     */
    private static class EpochAndSeq {

        /**
         * Epoch.
         */
        String epoch;

        /**
         * Sequence number.
         */
        long seq;

        /**
         * Init.
         */
        EpochAndSeq(String epoch, long seq) {
            this.epoch = epoch;
            this.seq = seq;
        }
    }

    @Override
    public void onClear(@NonNull String groupName) {
        if (isOneCollectorGroup(groupName)) {
            return;
        }
        String oneCollectorGroupName = getOneCollectorGroupName(groupName);
        mChannel.clear(oneCollectorGroupName);
    }

    /**
     * Get One Collector's group name for original one.
     *
     * @param groupName The group name.
     * @return The One Collector's group name.
     */
    private String getOneCollectorGroupName(@NonNull String groupName) {
        return groupName + ONE_COLLECTOR_GROUP_NAME_SUFFIX;
    }

    /**
     * Checks if the group has One Collector's postfix.
     *
     * @param groupName The group name.
     * @return true if group has One Collector's postfix, false otherwise.
     */
    private boolean isOneCollectorGroup(@NonNull String groupName) {
        return groupName.endsWith(ONE_COLLECTOR_GROUP_NAME_SUFFIX);
    }
}
