package swordriver.com.googledrivemodule;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;

import com.google.android.gms.ads.internal.client.ThinAdSizeParcel;
import com.google.android.gms.appindexing.AppIndex;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveApi;
import com.google.android.gms.drive.DriveApi.DriveContentsResult;
import com.google.android.gms.drive.DriveContents;
import com.google.android.gms.drive.DriveFile;
import com.google.android.gms.drive.DriveFolder;
import com.google.android.gms.drive.DriveId;
import com.google.android.gms.drive.DriveResource;
import com.google.android.gms.drive.Metadata;
import com.google.android.gms.drive.MetadataBuffer;
import com.google.android.gms.drive.MetadataChangeSet;
import com.google.android.gms.drive.metadata.CustomPropertyKey;
import com.google.android.gms.plus.Plus;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.Map;
import java.util.Observable;
import java.util.concurrent.CountDownLatch;

import timber.log.Timber;

/**
 * Created by jcli on 4/7/16.
 */
public class GoogleApiModel extends Observable implements GoogleApiClient.ConnectionCallbacks,GoogleApiClient.OnConnectionFailedListener{

    public enum GoogleApiStatus{
        DISCONNECTED,
        CONNECTED_UNINITIALIZED,
        INITIALIZED
    }

    protected final String mTAG;
    protected GoogleApiClient mGoogleApiClient;
    public static final int REQUEST_CODE_CAPTURE_IMAGE = 1;
    public static final int REQUEST_CODE_CREATOR = 2;
    public static final int REQUEST_CODE_RESOLUTION = 3;
    public static final int REQUEST_CODE_SIGNIN = 4;
    protected Context mParentContext = null;
    protected FragmentActivity mResolutionActivity = null;
    protected DriveFolder mAppRootFolder;

    private String mIdToken;
    private String mUserEmail;
    private CountDownLatch writeCountDown;

    protected GoogleApiStatus mCurrentApiStatus = GoogleApiStatus.DISCONNECTED;

    public static class ItemInfo {
        public Metadata meta;
        public String readableTitle;
    }
    public static class FolderInfo {
        public DriveFolder parentFolder;
        public DriveFolder folder;
        public ItemInfo items[];
    }

    public static class AssetNode{
        public ItemInfo assetInfo;
        public AssetNode parentAsset;
        public ArrayList<AssetNode> children;
    }

    /////////////// constructor ////////////////////
    public GoogleApiModel(Context callerContext, FragmentActivity resolutionActivity, String tag, String serverClientID){
        mTAG=tag;
        mParentContext = callerContext;
        mResolutionActivity = resolutionActivity;

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Drive.SCOPE_FILE)
                .requestScopes(Plus.SCOPE_PLUS_LOGIN)
                .requestIdToken(serverClientID)
                .build();

        mGoogleApiClient = new GoogleApiClient.Builder(mParentContext)
//                .enableAutoManage(resolutionActivity /* FragmentActivity */, this /* OnConnectionFailedListener */)
                .addApi(Auth.GOOGLE_SIGN_IN_API, gso)
                .addApi(Drive.API)
                .addApi(Plus.API)
                .addApi(AppIndex.API).build();
        
    }

    /////////////////// public API that I will keep////////////////
    public void setResolutionActivity (FragmentActivity resolutionActivity){
        mResolutionActivity=resolutionActivity;
    }

    public void open(){
        Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(mGoogleApiClient);
        Timber.tag(mTAG).i("signing into Google.");
        mResolutionActivity.startActivityForResult(signInIntent, REQUEST_CODE_SIGNIN);
    }

    public void connect(){
        if (!mGoogleApiClient.isConnectionCallbacksRegistered(this))
            mGoogleApiClient.registerConnectionCallbacks(this);
        if (!mGoogleApiClient.isConnectionFailedListenerRegistered(this))
            mGoogleApiClient.registerConnectionFailedListener(this);
        Timber.tag(mTAG).i("calling google api client connect.");
        mGoogleApiClient.connect(GoogleApiClient.SIGN_IN_MODE_OPTIONAL);
    }

    public void processSignInResult(Intent data){
        Timber.tag(mTAG).i("processing signin result.");
        GoogleSignInResult result = Auth.GoogleSignInApi.getSignInResultFromIntent(data);
        if (result.isSuccess()) {
            GoogleSignInAccount acct = result.getSignInAccount();
            mIdToken=acct.getIdToken();
            mUserEmail=acct.getEmail();
            this.connect();
        }else{
            //TODO: handle sign in failure
            Timber.tag(mTAG).e("signin failed! " + result.getStatus());
        }
    }

    public void close()   {
        // can not re-enter
        initCountDown();
        if (mGoogleApiClient.isConnected()) {
            Auth.GoogleSignInApi.signOut(mGoogleApiClient).setResultCallback(
                    new ResultCallback<Status>() {
                        @Override
                        public void onResult(Status status) {
                            writeCountDown.countDown();
                            if (status.isSuccess()) {
                                mIdToken=null;
                            }
                            mGoogleApiClient.disconnect();
                            mCurrentApiStatus=GoogleApiStatus.DISCONNECTED;
                            selfNotify();
                        }
                    });
        }else{
            writeCountDown.countDown();
        }
    }

    public void signOut(){
        // can not re-enter
        initCountDown();
        try {
            Auth.GoogleSignInApi.revokeAccess(mGoogleApiClient).setResultCallback(
                    new ResultCallback<Status>() {
                        @Override
                        public void onResult(Status status) {
                            writeCountDown.countDown();
                            if (status.isSuccess()) {
                            } else {
                            }
                            mGoogleApiClient.disconnect();
                            mCurrentApiStatus = GoogleApiStatus.DISCONNECTED;
                            selfNotify();
                        }
                    });
        }catch (Exception e){
            writeCountDown.countDown();
        }
    }

    public GoogleApiStatus getStatus(){
        if (mCurrentApiStatus==null || !mGoogleApiClient.isConnected()) mCurrentApiStatus=GoogleApiStatus.DISCONNECTED;
        return mCurrentApiStatus;
    }

    public interface ListParentCallback {
        void callback (DriveFolder parent);
    }
    public GoogleApiStatus listParent(DriveFolder assetID, final ListParentCallback callbackInstance){
        if (mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        assetID.getDriveId().asDriveResource().listParents(mGoogleApiClient).
                setResultCallback(new ResultCallback<DriveApi.MetadataBufferResult>() {
                    @Override
                    public void onResult(DriveApi.MetadataBufferResult result) {
                        if (!result.getStatus().isSuccess()) {
                            if (callbackInstance!=null) callbackInstance.callback(null);
                            return;
                        }
                        MetadataBuffer buffer = result.getMetadataBuffer();
                        if (buffer.getCount()>0){
                            // have parents. return the first parent.
                            if (callbackInstance!=null) callbackInstance.callback(buffer.get(0).getDriveId().asDriveFolder());
                        }else{
                            if (callbackInstance!=null) callbackInstance.callback(null);
                        }
                        buffer.release();
                        result.release();
                    }
                });
        return mCurrentApiStatus;
    }

    public interface ListFolderCallback {
        void callback(FolderInfo info);
    }
    public class ListFolderCallbackNull implements ListFolderCallback {
        @Override
        public void callback(FolderInfo info) {
            // do nothing
        }
    }
    public GoogleApiStatus listFolder(DriveFolder assetID, final ListFolderCallback callbackInstance) {
        if (mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        final FolderInfo currentFolder = new FolderInfo();
        currentFolder.folder = assetID;
        // list parents first
        listParent(assetID, new ListParentCallback() {
            @Override
            public void callback(DriveFolder parent) {
                currentFolder.parentFolder = parent;
                // then list children
                currentFolder.folder.listChildren(mGoogleApiClient)
                        .setResultCallback(new ResultCallback<DriveApi.MetadataBufferResult>() {
                            @Override
                            public void onResult(DriveApi.MetadataBufferResult result) {
                                if (!result.getStatus().isSuccess()) {
                                    currentFolder.items = new ItemInfo[0];
                                    if (callbackInstance!=null) callbackInstance.callback(currentFolder);
                                    return;
                                }
                                MetadataBuffer buffer = result.getMetadataBuffer();
                                if (buffer.getCount()>0){
                                    currentFolder.items = new ItemInfo[buffer.getCount()];
                                    for (int i=0; i<buffer.getCount(); i++){
                                        currentFolder.items[i] = new ItemInfo();
                                        currentFolder.items[i].meta=buffer.get(i).freeze();
                                        currentFolder.items[i].readableTitle = currentFolder.items[i].meta.getTitle();
                                    }
                                    if (callbackInstance!=null) callbackInstance.callback(currentFolder);
                                }else{
                                    currentFolder.items = new ItemInfo[0];
                                    if (callbackInstance!=null) callbackInstance.callback(currentFolder);
                                }
                                buffer.release();
                                result.release();
                            }
                        });
            }
        });
        return mCurrentApiStatus;
    }

    public GoogleApiStatus createFolderInFolder(final String name, final DriveFolder assetID, final boolean gotoFolder,
                                                final Map<String, String> metaInfo, final ListFolderCallback callbackInstance){
        if (mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        // can not re-entry
        initCountDown();
        // check for naming conflict
        listFolder(assetID, new ListFolderCallback() {
            @Override
            public void callback(FolderInfo info) {
                for (int i=0; i<info.items.length; i++){
                    if (nameCompare(name, info.items[i].meta, metaInfo) && info.items[i].meta.isFolder()){
                        // naming conflict !!
                        writeCountDown.countDown();
                        if (gotoFolder){
                            // list conflicted folder
                            listFolder(info.items[i].meta.getDriveId().asDriveFolder(), callbackInstance);
                        }else{
                            // list current folder again
                            listFolder(assetID, callbackInstance);
                        }
                        return;
                    }
                }
                // no conflict if it gets to here
                MetadataChangeSet.Builder builder = new MetadataChangeSet.Builder();
                builder.setTitle(name);
                if (metaInfo!=null){
                    for (Map.Entry<String, String> entry : metaInfo.entrySet()){
                        CustomPropertyKey propertyKey = new CustomPropertyKey(entry.getKey(), CustomPropertyKey.PUBLIC);
                        builder.setCustomProperty(propertyKey, entry.getValue());
                    }
                }
                MetadataChangeSet changeSet = builder.build();

                assetID.createFolder(mGoogleApiClient, changeSet)
                        .setResultCallback(new ResultCallback<DriveFolder.DriveFolderResult>() {
                            @Override
                            public void onResult(@NonNull DriveFolder.DriveFolderResult result) {
                                writeCountDown.countDown();
                                if (!result.getStatus().isSuccess()) {
                                    if (callbackInstance!=null) callbackInstance.callback(null);
                                    return;
                                }else{
                                    if (gotoFolder){
                                        // list newly created folder
                                        listFolder(result.getDriveFolder(), callbackInstance);
                                    }else{
                                        // list current folder again
                                        listFolder(assetID, callbackInstance);
                                    }
                                }
                    }
                });
            }
        });
        return mCurrentApiStatus;
    }
    public GoogleApiStatus createFolderInFolder(final String name, final DriveFolder assetID, final boolean gotoFolder,
                                                final ListFolderCallback callbackInstance)   {
        return createFolderInFolder(name, assetID, gotoFolder, null, callbackInstance);
    }
    public GoogleApiStatus createFolderListInFolder(final Deque<String> names, final DriveFolder assetID,
                                                    final ListFolderCallback callbackInstance){
        if (names.size()==0 || mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        if (names.size()==1){
            return createFolderInFolder(names.pop(), assetID, true, null, callbackInstance);
        }else{
            String currentName = names.pop();
            return createFolderInFolder(currentName, assetID, true, null, new ListFolderCallback() {
                @Override
                public void callback(FolderInfo info) {
                    if (info!=null) {
                        createFolderListInFolder(names, info.folder, callbackInstance);
                    }
                }
            });
        }
    }

    public GoogleApiStatus createTxtFileInFolder(final String fileName, final DriveFolder assetID,
                                      final Map<String, String> metaInfo, final ListFolderCallback callbackInstance)   {
        if (mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        // can not re-entry
        initCountDown();
        // check for naming conflict
        listFolder(assetID, new ListFolderCallback() {
            @Override
                public void callback(FolderInfo info) {
                for (int i = 0; i < info.items.length; i++) {
                    if (nameCompare(fileName, info.items[i].meta, metaInfo) && !info.items[i].meta.isFolder()) {
                        // naming conflict !!
                        writeCountDown.countDown();
                        if (callbackInstance != null) callbackInstance.callback(info);
                        return;
                    }
                }
                // no conflict if it gets to here
                MetadataChangeSet.Builder builder = new MetadataChangeSet.Builder();
                builder.setTitle(fileName);
                builder.setMimeType("text/plain");
                if (metaInfo!=null){
                    for (Map.Entry<String, String> entry : metaInfo.entrySet()){
                        CustomPropertyKey propertyKey = new CustomPropertyKey(entry.getKey(), CustomPropertyKey.PUBLIC);
                        builder.setCustomProperty(propertyKey, entry.getValue());
                    }
                }
                MetadataChangeSet changeSet = builder.build();

                assetID.createFile(mGoogleApiClient, changeSet, null)
                        .setResultCallback(new ResultCallback<DriveFolder.DriveFileResult>() {
                            @Override
                            public void onResult(DriveFolder.DriveFileResult result) {
                                writeCountDown.countDown();
                                if (!result.getStatus().isSuccess()) {
                                    if (callbackInstance!=null) callbackInstance.callback(null);
                                    return;
                                }else{
                                    // list current folder again
                                    listFolder(assetID, callbackInstance);
                                }
                            }
                        });
            }
        });
        return mCurrentApiStatus;
    }
    public GoogleApiStatus createTxtFileInFolder(final String fileName, final DriveFolder assetID, final ListFolderCallback callbackInstance){
        return createTxtFileInFolder(fileName, assetID, null, callbackInstance);
    }

    public interface ReadTxtFileCallback {
        void callback(String fileContent);
    }
    public GoogleApiStatus readTxtFile(final ItemInfo assetInfo, final ReadTxtFileCallback callbackInstance){
        if (mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        String assetID = assetInfo.meta.getDriveId().encodeToString();
        final DriveFile file = DriveId.decodeFromString(assetID).asDriveFile();
        file.open(mGoogleApiClient, DriveFile.MODE_READ_ONLY, null)
                .setResultCallback(new ResultCallback<DriveContentsResult>() {
                    @Override
                    public void onResult(DriveContentsResult result) {
                        if (!result.getStatus().isSuccess()) {
                            // display an error saying file can't be opened
                            return;
                        }
                        // DriveContents object contains pointers
                        // to the actual byte stream
                        DriveContents contents = result.getDriveContents();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(contents.getInputStream()));
                        StringBuilder builder = new StringBuilder();
                        String line;
                        try {
                            while ((line = reader.readLine()) != null) {
                                builder.append(line);
                            }
                        } catch (IOException e) {
                            Timber.tag(mTAG).e(e, "exception!");
                        }
                        String contentsAsString = builder.toString();
                        contents.discard(mGoogleApiClient);
                        if (callbackInstance!=null) {
                            callbackInstance.callback(contentsAsString);
                        }
                    }
                });
        return mCurrentApiStatus;
    }

    public interface WriteTxtFileCallback {
        void callback(boolean success);
    }
    public GoogleApiStatus writeTxtFile(final ItemInfo assetInfo, final String contentStr, final WriteTxtFileCallback callbackInstance){
        return writeTxtFile(assetInfo, contentStr, callbackInstance, null);
    }
    public GoogleApiStatus writeTxtFile(final ItemInfo assetInfo, final String contentStr, final WriteTxtFileCallback callbackInstance, final Map<String, String> metaInfo){
        if (mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        String assetID = assetInfo.meta.getDriveId().encodeToString();
        DriveFile file = DriveId.decodeFromString(assetID).asDriveFile();
        file.open(mGoogleApiClient, DriveFile.MODE_WRITE_ONLY, null).setResultCallback(new ResultCallback<DriveContentsResult>() {
            @Override
            public void onResult(DriveContentsResult result) {
                if (!result.getStatus().isSuccess()) {
                    // Handle error
                    return;
                }
                DriveContents driveContents = result.getDriveContents();
                try{
                    ParcelFileDescriptor parcelFileDescriptor = driveContents.getParcelFileDescriptor();
                    FileOutputStream fileOutputStream = new FileOutputStream(parcelFileDescriptor
                            .getFileDescriptor());
                    Writer writer = new OutputStreamWriter(fileOutputStream);
                    writer.write(contentStr);
                    writer.flush();
                    writer.close();
                    fileOutputStream.close();
                } catch (IOException e) {
                    Timber.tag(mTAG).e(e, "exception!");
                }
                MetadataChangeSet.Builder builder = new MetadataChangeSet.Builder()
                        .setLastViewedByMeDate(new Date());
                if (metaInfo!=null){
                    for (Map.Entry<String, String> entry : metaInfo.entrySet()){
                        CustomPropertyKey propertyKey = new CustomPropertyKey(entry.getKey(), CustomPropertyKey.PUBLIC);
                        builder.setCustomProperty(propertyKey, entry.getValue());
                    }
                }
                MetadataChangeSet changeSet = builder.build();

                if (mGoogleApiClient==null){
                }else if (mGoogleApiClient.isConnected()==false){
                }else {
                    driveContents.commit(mGoogleApiClient, changeSet).setResultCallback(new ResultCallback<Status>() {
                        @Override
                        public void onResult(Status result) {
                            if (callbackInstance != null) {
                                callbackInstance.callback(result.isSuccess());
                            }
                        }
                    });
                }
            }
        });
        return mCurrentApiStatus;
    }

    public GoogleApiStatus deleteItem(DriveId assetID, ResultCallback<Status> callbackInstance){
        if (mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        DriveResource driveResource= assetID.asDriveResource();
        driveResource.delete(mGoogleApiClient).setResultCallback(callbackInstance);
        return mCurrentApiStatus;
    }
    public GoogleApiStatus deleteMultipleItems(final Deque<DriveId> items, final ResultCallback<Status> callbackInstance){
        if (mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        if (items.size()==0)
            callbackInstance.onResult(new Status(0));
        DriveId assetID = items.pop();
        deleteItem(assetID, new ResultCallback<Status>() {
            @Override
            public void onResult(@NonNull Status status) {
                if (!status.isSuccess()){
                    callbackInstance.onResult(status);
                }else {
                    if (items.size() == 0) {
                        //end case
                        callbackInstance.onResult(status);
                    } else {
                        deleteMultipleItems(items, callbackInstance);
                    }
                }
            }
        });
        return mCurrentApiStatus;
    }
    public GoogleApiStatus deleteEverythingInAppRoot(final ResultCallback<Status> callbackInstance){
        if (mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        listFolder(mAppRootFolder, new ListFolderCallback() {
            @Override
            public void callback(FolderInfo info) {
                if (info.items!=null && info.items.length>0){
                    Deque<DriveId> itemIDs = new ArrayDeque<>();
                    for (ItemInfo item: info.items){
                        itemIDs.push(item.meta.getDriveId());
                    }
                    deleteMultipleItems(itemIDs, callbackInstance);
                }else{
                    callbackInstance.onResult(new Status(0));
                }
            }
        });
        return mCurrentApiStatus;
    }
    public GoogleApiStatus deleteAllFolderInAppRoot(final ResultCallback<Status> callbackInstance){
        if (mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        listFolder(mAppRootFolder, new ListFolderCallback() {
            @Override
            public void callback(FolderInfo info) {
                if (info.items!=null){
                    Deque<DriveId> itemIDs = new ArrayDeque<>();
                    for (ItemInfo item: info.items){
                        if(item.meta.isFolder())
                            itemIDs.push(item.meta.getDriveId());
                    }
                    deleteMultipleItems(itemIDs, callbackInstance);
                }
            }
        });
        return mCurrentApiStatus;
    }
    public GoogleApiStatus deleteEverything(final ResultCallback<Status> callbackInstance){
        if (mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        listFolder(Drive.DriveApi.getRootFolder(mGoogleApiClient),
                new ListFolderCallback() {
            @Override
            public void callback(FolderInfo info) {
                if (info.items!=null && info.items.length>0){
                    Deque<DriveId> itemIDs = new ArrayDeque<>();
                    for (ItemInfo item: info.items){
                        itemIDs.push(item.meta.getDriveId());
                    }
                    deleteMultipleItems(itemIDs, callbackInstance);
                }else{
                    callbackInstance.onResult(new Status(0));
                }
            }
        });
        return mCurrentApiStatus;
    }

    public GoogleApiStatus updateMetadata(DriveId assetID, Map<String, String> metaInfo, final ResultCallback<DriveResource.MetadataResult> callback){
        if (mCurrentApiStatus==GoogleApiStatus.DISCONNECTED) return mCurrentApiStatus;
        MetadataChangeSet.Builder changeSetBuilder = new MetadataChangeSet.Builder();
        if (metaInfo!=null){
            for (Map.Entry<String, String> entry : metaInfo.entrySet()){
                CustomPropertyKey propertyKey = new CustomPropertyKey(entry.getKey(), CustomPropertyKey.PUBLIC);
                changeSetBuilder.setCustomProperty(propertyKey, entry.getValue());
            }
        }
        MetadataChangeSet changeSet = changeSetBuilder.build();
        assetID.asDriveResource().updateMetadata(mGoogleApiClient, changeSet).setResultCallback(callback);
        return mCurrentApiStatus;
    }

    public DriveFolder getAppRootFolder(){
        return mAppRootFolder;
    }

    public DriveFolder getRootFolder(){
        return Drive.DriveApi.getRootFolder(mGoogleApiClient);
    }

    /////// get info APIs
    public String getUsername() {
        return mUserEmail;
    }

    public String getIdToken(){
        return mIdToken;
    }

    //////////////////////////////////// Protected methods   /////////////////////////////////////////

    protected void initAppRoot(final ListFolderCallback callbackInstance)   {
        final DriveFolder driveRoot = Drive.DriveApi.getRootFolder(mGoogleApiClient);
        String name = mParentContext.getString(R.string.app_name);
        createFolderInFolder(name, driveRoot, true, new ListFolderCallback() {
            @Override
            public void callback(FolderInfo info) {
                mAppRootFolder = info.folder;
                mCurrentApiStatus=GoogleApiStatus.INITIALIZED;
                selfNotify();
                callbackInstance.callback(info);
            }
        });
    }

    // override this if you want put encryption data in folder title
    protected boolean nameCompare(String name, Metadata item, Map<String, String> metaInfo){
        return item.getTitle().equals(name);
    }

    ////////////////// callbacks //////////////////

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Timber.tag(mTAG).w("Google connection failed.");
        if (connectionResult.hasResolution()) {
            try {
                Timber.tag(mTAG).w("Auto resolve connection failure.");
                connectionResult.startResolutionForResult(mResolutionActivity, REQUEST_CODE_RESOLUTION);
            } catch (IntentSender.SendIntentException e) {
                // Unable to resolve, msgString user appropriately
            }
        } else {
            Timber.tag(mTAG).e("No resolution.");
            GooglePlayServicesUtil.getErrorDialog(connectionResult.getErrorCode(), mResolutionActivity, 0).show();
        }

    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Timber.tag(mTAG).i("connected.");
        if (mGoogleApiClient.isConnected()) {
            mCurrentApiStatus = GoogleApiStatus.CONNECTED_UNINITIALIZED;
            initAppRoot(new ListFolderCallbackNull());
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
    }

    //////////////////// protected helper ////////////////////////

    protected synchronized void selfNotify(){
        setChanged();
        notifyObservers(mCurrentApiStatus);
        clearChanged();
    }

    //////////////////// private helper ////////////////////////

    private synchronized void initCountDown()   {
        if (writeCountDown!=null) {
            try {
                writeCountDown.await();
            } catch (InterruptedException e) {
                Timber.tag(mTAG).e(e, "exception!");
            }
        }
        writeCountDown = new CountDownLatch(1);
    }

}
