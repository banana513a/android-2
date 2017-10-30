package com.owncloud.android.services;

import android.accounts.Account;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.RequiresApi;
import android.util.Log;

import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.files.services.TransferRequester;
import com.owncloud.android.lib.common.operations.OnRemoteOperationListener;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.files.RemoteFile;
import com.owncloud.android.operations.GetFolderFilesOperation;
import com.owncloud.android.operations.UploadFileOperation;
import com.owncloud.android.utils.Extras;
import com.owncloud.android.utils.FileStorageUtils;
import com.owncloud.android.utils.MimetypeIconUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class SyncCameraFolderJobService extends JobService implements OnRemoteOperationListener {

    private static final String TAG = SyncCameraFolderJobService.class.getName();

    // To enqueue an action to be performed on a different thread than the current one
    private final Handler mHandler = new Handler();
    private ServiceConnection mOperationsServiceConnection = null;
    private OperationsService.OperationsServiceBinder mOperationsServiceBinder = null;

    // Identifier of operation in progress which result shouldn't be lost
    private long mWaitingForOpId = Long.MAX_VALUE;

    private JobParameters mJobParameters;
    private Account mAccount;

    private String mUploadedPicturesPath;
    private String mUploadedVideosPath;

    private boolean mGetRemotePicturesCompleted;
    private boolean mGetRemoteVideosCompleted;

    private static int MAX_RECENTS = 30;
    private static Set<String> sRecentlyUploadedFilePaths = new HashSet<>(MAX_RECENTS);

    @Override
    public boolean onStartJob(JobParameters jobParameters) {

        Log.d(TAG, "Starting job to sync camera folder");

        mJobParameters = jobParameters;

        String accountName = mJobParameters.getExtras().getString(Extras.EXTRA_ACCOUNT_NAME);

        mAccount = AccountUtils.getOwnCloudAccountByName(this, accountName);

        mGetRemotePicturesCompleted = false;
        mGetRemoteVideosCompleted = false;

        // bind to Operations Service
        mOperationsServiceConnection = new OperationsServiceConnection();
        bindService(new Intent(this, OperationsService.class), mOperationsServiceConnection,
                Context.BIND_AUTO_CREATE);

        return true; // True because we have a thread still running requesting stuff to the server
    }

    @Override
    /**
     * Called by the system if the job is cancelled before being finished
     */
    public boolean onStopJob(JobParameters jobParameters) {

        if (mOperationsServiceConnection != null) {
            unbindService(mOperationsServiceConnection);
            mOperationsServiceBinder = null;
        }

        return true;
    }

    /**
     * Get remote pictures and videos contained in upload folders
     */
    private void getUploadedPicturesAndVideos() {

        // Registering to listen for operation callbacks
        mOperationsServiceBinder.addOperationListener(this, mHandler);

        if (mWaitingForOpId <= Integer.MAX_VALUE) {
            mOperationsServiceBinder.dispatchResultIfFinished((int) mWaitingForOpId, this);
        }

        mUploadedPicturesPath = mJobParameters.getExtras().getString(Extras.
                EXTRA_UPLOAD_PICTURES_PATH);

        mUploadedVideosPath = mJobParameters.getExtras().getString(Extras.
                EXTRA_UPLOAD_VIDEOS_PATH);

        if (mUploadedPicturesPath != null) {
            // Get remote pictures
            Intent getUploadedPicturesIntent = new Intent();
            getUploadedPicturesIntent.setAction(OperationsService.ACTION_GET_FOLDER_FILES);
            getUploadedPicturesIntent.putExtra(OperationsService.EXTRA_REMOTE_PATH, mUploadedPicturesPath);
            getUploadedPicturesIntent.putExtra(OperationsService.EXTRA_ACCOUNT, mAccount);
            mWaitingForOpId = mOperationsServiceBinder.queueNewOperation(getUploadedPicturesIntent);
        }

        if (mUploadedVideosPath != null) {
            // Get remote videos
            Intent getUploadedVideosIntent = new Intent();
            getUploadedVideosIntent.setAction(OperationsService.ACTION_GET_FOLDER_FILES);
            getUploadedVideosIntent.putExtra(OperationsService.EXTRA_REMOTE_PATH, mUploadedVideosPath);
            getUploadedVideosIntent.putExtra(OperationsService.EXTRA_ACCOUNT, mAccount);
            mWaitingForOpId = mOperationsServiceBinder.queueNewOperation(getUploadedVideosIntent);
        }
    }

    /**
     * Implements callback methods for service binding.
     */
    private class OperationsServiceConnection implements ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName component, IBinder service) {
            if (component.equals(
                    new ComponentName(SyncCameraFolderJobService.this, OperationsService.class)
            )) {
                mOperationsServiceBinder = (OperationsService.OperationsServiceBinder) service;

                getUploadedPicturesAndVideos();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName component) {
            if (component.equals(
                    new ComponentName(SyncCameraFolderJobService.this, OperationsService.class)
            )) {
                Log_OC.e(TAG, "Operations service crashed");
                mOperationsServiceBinder = null;
            }
        }
    }

    @Override
    public void onRemoteOperationFinish(RemoteOperation operation, RemoteOperationResult result) {

        //Get local folder images
        String localCameraPath = mJobParameters.getExtras().getString(Extras.EXTRA_LOCAL_CAMERA_PATH);

        File localFiles[] = new File[0];

        if (localCameraPath != null) {
            File cameraFolder = new File(localCameraPath);
            localFiles = cameraFolder.listFiles();
        }

        if (!result.isSuccess()) {

            Log.d(TAG, "Remote folder does not exist yet, trying to upload the files for the " +
                    "first time");

            // Remote camera folder doesn't exist yet, first local files upload
            if (result.getCode() == RemoteOperationResult.ResultCode.FILE_NOT_FOUND) {

                for (File localFile : localFiles) {
                    handleNewFile(localFile);
                }
            }

        } else {

            ArrayList<Object> remoteObjects = result.getData();

            // Casting to remote files
            // TODO Move to a utils class
            ArrayList<RemoteFile> remoteFiles = new ArrayList<>(remoteObjects.size());
            for (Object object : remoteObjects) {
                remoteFiles.add((RemoteFile) object);
            }

            String remotePath = ((GetFolderFilesOperation) operation).getRemotePath();

            // Result contains remote pictures
            if (mUploadedPicturesPath != null && mUploadedPicturesPath.equals(remotePath)) {

                Log.d(TAG, "Receiving pictures uploaded");

                compareWithLocalFiles(localFiles, remoteFiles);
            }

            // Result contains remote videos
            if (mUploadedVideosPath != null && mUploadedVideosPath.equals(remotePath)) {

                Log.d(TAG, "Receiving videos uploaded");

            }
        }

        if (mOperationsServiceBinder != null) {
            mOperationsServiceBinder.removeOperationListener(this);
        }

        if (mOperationsServiceConnection != null) {
            unbindService(mOperationsServiceConnection);
            mOperationsServiceBinder = null;
        }

        jobFinished(mJobParameters, false);

        // We have to unbind the service to get remote images/videos and finish the job when
        // requested operations finish

        // User only requests pictures upload
//        boolean mOnlyGetPicturesFinished = mGetRemotePicturesCompleted && mUploadedVideosPath == null;
//
//        // User only requests videos upload
//        boolean mOnlyGetVideosFinished = mGetRemoteVideosCompleted && mUploadedPicturesPath == null ;
//
//        // User requests pictures & videos upload
//        boolean mGetPicturesVideosFinished = mGetRemotePicturesCompleted && mGetRemoteVideosCompleted;
//
//        if (mOnlyGetPicturesFinished || mOnlyGetVideosFinished || mGetPicturesVideosFinished) {
//
//            Log.d(TAG, "Finishing camera folder sync job");
//
//            if (mOperationsServiceBinder != null) {
//                mOperationsServiceBinder.removeOperationListener(this);
//            }
//
//            if (mOperationsServiceConnection != null) {
//                unbindService(mOperationsServiceConnection);
//                mOperationsServiceBinder = null;
//            }
//
//            jobFinished(mJobParameters, false);
//        }
    }

    private void compareWithLocalFiles(File[] localFiles, ArrayList<RemoteFile> remoteFiles) {

        ArrayList<OCFile> remoteFolderFiles = FileStorageUtils.
                createOCFilesFromRemoteFilesList(remoteFiles);

        for (File localFile : localFiles) {

            boolean isAlreadyUpdated = false;

            for (OCFile ocFile : remoteFolderFiles) {

                if (localFile.getName().equals(ocFile.getFileName())) {

                    isAlreadyUpdated = true;

                    break;
                }
            }

            if (!isAlreadyUpdated) {

                // Upload file
                handleNewFile(localFile);

            }
        }
    }

    private synchronized void handleNewFile(File localFile) {

        String fileName = localFile.getName();

        String mimeType = MimetypeIconUtil.getBestMimeTypeByFilename(fileName);
        boolean isImage = mimeType.startsWith("image/");
        boolean isVideo = mimeType.startsWith("video/");

        if (!isImage && !isVideo) {
            Log_OC.d(TAG, "Ignoring " + fileName);
            return;
        }

        if (isImage && mUploadedPicturesPath == null) {
            Log_OC.d(TAG, "Instant upload disabled for images, ignoring " + fileName);
            return;
        }

        if (isVideo && mUploadedVideosPath == null) {
            Log_OC.d(TAG, "Instant upload disabled for videos, ignoring " + fileName);
            return;
        }

        String remotePath = (isImage ? mUploadedPicturesPath : mUploadedVideosPath) + fileName;

        int createdBy = isImage ? UploadFileOperation.CREATED_AS_INSTANT_PICTURE :
                UploadFileOperation.CREATED_AS_INSTANT_VIDEO;

        String localPath = mJobParameters.getExtras().getString(Extras.EXTRA_LOCAL_CAMERA_PATH)
                + File.separator + fileName;

        /// check duplicated detection
        if (sRecentlyUploadedFilePaths.contains(localPath)) {
            Log_OC.i(TAG, "Duplicate detection of " + localPath + ", ignoring");
            return;
        }

        TransferRequester requester = new TransferRequester();
        requester.uploadNewFile(
                this,
                mAccount,
                localPath,
                remotePath,
                mJobParameters.getExtras().getInt(Extras.EXTRA_BEHAVIOR_AFTER_UPLOAD),
                mimeType,
                true,           // create parent folder if not existent
                createdBy
        );

        if (sRecentlyUploadedFilePaths.size() >= MAX_RECENTS) {
            // remove first path inserted
            sRecentlyUploadedFilePaths.remove(sRecentlyUploadedFilePaths.iterator().next());
        }
        sRecentlyUploadedFilePaths.add(localPath);

        Log_OC.i(
                TAG,
                String.format(
                        "Requested upload of %1s to %2s in %3s",
                        localPath,
                        remotePath,
                        mAccount.name
                )
        );
    }
}