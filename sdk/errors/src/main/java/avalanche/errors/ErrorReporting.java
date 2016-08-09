package avalanche.errors;

import android.support.annotation.NonNull;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import avalanche.core.AbstractAvalancheFeature;
import avalanche.core.Avalanche;
import avalanche.core.channel.AvalancheChannel;
import avalanche.core.ingestion.models.Device;
import avalanche.core.ingestion.models.json.LogFactory;
import avalanche.core.utils.AvalancheLog;
import avalanche.core.utils.DeviceInfoHelper;
import avalanche.core.utils.StorageHelper;
import avalanche.errors.ingestion.models.ErrorLog;
import avalanche.errors.ingestion.models.json.ErrorLogFactory;
import avalanche.errors.utils.ErrorLogHelper;


public class ErrorReporting extends AbstractAvalancheFeature {

    public static final String ERROR_GROUP = "group_error";

    private static ErrorReporting sInstance = null;

    private final Map<String, LogFactory> mFactories;

    private long mInitializeTimestamp;
    private UncaughtExceptionHandler mUncaughtExceptionHandler;

    private ErrorReporting() {
        mFactories = new HashMap<>();
        mFactories.put(ErrorLog.TYPE, ErrorLogFactory.getInstance());
    }

    @NonNull
    public static ErrorReporting getInstance() {
        if (sInstance == null) {
            sInstance = new ErrorReporting();
        }
        return sInstance;
    }

    public static void setEnabled(boolean enabled) {
        getInstance().setInstanceEnabled(enabled);
    }

    public static boolean isEnabled() {
        return getInstance().isInstanceEnabled();
    }

    @Override
    public synchronized void setInstanceEnabled(boolean enabled) {
        super.setInstanceEnabled(enabled);
        initialize();
    }

    @Override
    public synchronized void onChannelReady(AvalancheChannel channel) {
        super.onChannelReady(channel);

        initialize();

        if (isInstanceEnabled() && mChannel != null) {
            queuePendingCrashes();
        }
    }

    @Override
    public Map<String, LogFactory> getLogFactories() {
        return mFactories;
    }

    @Override
    protected String getGroupName() {
        return ERROR_GROUP;
    }

    private void initialize() {
        boolean enabled = isInstanceEnabled();
        mInitializeTimestamp = enabled ? System.currentTimeMillis() : -1;

        if (!enabled) {
            if (mUncaughtExceptionHandler != null) {
                mUncaughtExceptionHandler.unregister();
            }
            return;
        }

        mUncaughtExceptionHandler = new UncaughtExceptionHandler();
    }

    private void queuePendingCrashes() {
        for (File logfile : ErrorLogHelper.getStoredErrorLogFiles()) {
            ErrorLog log = ErrorLogHelper.deserializeErrorLog(logfile.getAbsolutePath());
            if (log != null) {
                mChannel.enqueue(log, ERROR_GROUP);
            }
            AvalancheLog.info("Deleting error log file " + logfile.getName());
            StorageHelper.InternalStorage.delete(logfile);
        }
    }

    protected long getInitializeTimestamp() {
        return mInitializeTimestamp;
    }

}
