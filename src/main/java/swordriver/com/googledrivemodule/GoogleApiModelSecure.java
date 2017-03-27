package swordriver.com.googledrivemodule;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentActivity;
import android.util.Base64;

import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveFolder;
import com.google.android.gms.drive.DriveResource;
import com.google.android.gms.drive.Metadata;
import com.google.android.gms.drive.metadata.CustomPropertyKey;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import timber.log.Timber;

/**
 * Created by jcli on 4/26/16.
 * Asset name is also encrypted
 * metadata contain the following
 *  - IV for the content
 *  - encrypted encryption key
 *  - IV for the encryption key
 *  - password salt (should be same for all asset)
 *
 */
public class GoogleApiModelSecure extends GoogleApiModel {

    private final int ITERATIONS = 10000;
    private final int KEYLENGTH = 256;
    private SecretKey mKeyEncryptionKey=null;  // must never be stored, and should be cleared on timeout.
    private String mPasswordString=null; // must never be stored, and should be cleared on timeout.
    private byte[] mSalt;               // should be the same for every asset
    private SecureRandom secureRandom;

    private Metadata mPasswordValidationData = null;

    private enum SecureProperties {
        ENCRYPTION_KEY("encryption_key"),
        ENCRYPTION_KEY_IV("encryption_key_iv"),
        ASSET_NAME("asset_name"),
        ASSET_NAME_IV("asset_name_iv"),
        CIPHER_TEXT("cipher_text"),
        CIPHER_TEXT_IV("cipher_text_iv"),
        VALIDATION_TEXT("validation_text"),
        VALIDATION_TEXT_IV("validation_text_iv"),
        SALT("salt");

        private String value;
        private SecureProperties(String value){this.value=value;}
        public String getValue(){return this.value;}
        @Override
        public String toString(){return this.value;}
    }

    public GoogleApiModelSecure(Context callerContext, FragmentActivity resolutionActivity, String tag, String serverClientID){
        super(callerContext, resolutionActivity, tag, serverClientID);
        secureRandom = new SecureRandom();
    }

    @Override
    public GoogleApiStatus listFolder(DriveFolder assetID, final ListFolderCallback callbackInstance){
        return super.listFolder(assetID, new ListFolderCallback() {
            @Override
            public void callback(FolderInfo info) {
                if (info.items!=null && mPasswordString!=null){
                    for (ItemInfo item : info.items) {
                        Map<CustomPropertyKey, String> properties = item.meta.getCustomProperties();
                        if (properties.get(new CustomPropertyKey(SecureProperties.ASSET_NAME_IV.toString(), CustomPropertyKey.PUBLIC)) == null){
                        } else {
                            // decrypt drive asset name
                            Map<String, String> encryptInfo = new HashMap<>();
                            for (Map.Entry<CustomPropertyKey, String> entry : properties.entrySet()) {
                                String key = entry.getKey().getKey();
                                String value = entry.getValue();
                                encryptInfo.put(key, value);
                            }
                            String clearTitle = decryptAssetString(item.meta.getTitle(), encryptInfo.get(SecureProperties.ASSET_NAME_IV.toString()), encryptInfo);
                            item.readableTitle = clearTitle;
                        }
                    }
                }
                callbackInstance.callback(info);
            }
        });
    }

    @Override
    public GoogleApiStatus createTxtFileInFolder(final String fileName, final DriveFolder assetID,
                                      final Map<String, String> metaInfo, final ListFolderCallback callbackInstance){
        if (mCurrentApiStatus!=GoogleApiStatus.INITIALIZED) return mCurrentApiStatus;
        Map<String, String> cipherData = encryptAssetName(fileName);
        String encryptedName = cipherData.remove(SecureProperties.ASSET_NAME.toString());
        if (metaInfo!=null) cipherData.putAll(metaInfo);
        return super.createTxtFileInFolder(encryptedName, assetID, cipherData, callbackInstance);
    }

    @Override
    public GoogleApiStatus readTxtFile(final ItemInfo assetInfo, final ReadTxtFileCallback callbackInstance){
        if (mCurrentApiStatus!=GoogleApiStatus.INITIALIZED) return mCurrentApiStatus;
        return super.readTxtFile(assetInfo, new ReadTxtFileCallback() {
            @Override
            public void callback(String fileContent) {
                Map<CustomPropertyKey, String> properties = assetInfo.meta.getCustomProperties();
                String cipherIV = (String) properties.get(new CustomPropertyKey(SecureProperties.CIPHER_TEXT_IV.toString(), CustomPropertyKey.PUBLIC));
                if (cipherIV == null || fileContent.length()==0) {
                    callbackInstance.callback(fileContent);
                }else{
                    Map<String, String> encryptInfo = new HashMap<>();
                    for (Map.Entry<CustomPropertyKey, String> entry : properties.entrySet()) {
                        String key = entry.getKey().getKey();
                        String value = entry.getValue();
                        encryptInfo.put(key, value);
                    }
                    String clearFileContent = decryptAssetString(fileContent, encryptInfo.get(SecureProperties.CIPHER_TEXT_IV.toString()), encryptInfo);
                    callbackInstance.callback(clearFileContent);
                }
            }
        });
    }

    @Override
    public GoogleApiStatus writeTxtFile(final ItemInfo assetInfo, final String contentStr, final WriteTxtFileCallback callbackInstance, final Map<String, String> metaInfo) {
        if (mCurrentApiStatus!=GoogleApiStatus.INITIALIZED) return mCurrentApiStatus;
        Map<CustomPropertyKey, String> properties = assetInfo.meta.getCustomProperties();
        String encryptedEncryptionKey = (String) properties.get(new CustomPropertyKey(SecureProperties.ENCRYPTION_KEY.toString(), CustomPropertyKey.PUBLIC));
        if (encryptedEncryptionKey == null) {
            // clear content
            return super.writeTxtFile(assetInfo, contentStr, callbackInstance, metaInfo);
        } else {
            // encrypt the content first
            Map<String, String> encryptInfo = new HashMap<>();
            for (Map.Entry<CustomPropertyKey, String> entry : properties.entrySet()) {
                String key = entry.getKey().getKey();
                String value = entry.getValue();
                encryptInfo.put(key, value);
            }
            String encryptedFileContent = encryptAssetString(contentStr, encryptInfo);
            if (metaInfo!=null) encryptInfo.putAll(metaInfo);
            return super.writeTxtFile(assetInfo, encryptedFileContent, callbackInstance, encryptInfo);
        }
    }

    @Override
    public GoogleApiStatus createFolderInFolder(final String name, final DriveFolder assetID, final boolean gotoFolder,
                                                final Map<String, String> metaInfo, final ListFolderCallback callbackInstance){
        if (mCurrentApiStatus!=GoogleApiStatus.INITIALIZED) return mCurrentApiStatus;
        Map<String, String> cipherData = encryptAssetName(name);
        String encryptedName = cipherData.remove(SecureProperties.ASSET_NAME.toString());
        if (metaInfo!=null) cipherData.putAll(metaInfo);
        return super.createFolderInFolder(encryptedName, assetID, gotoFolder, cipherData, callbackInstance);
    }

    @Override
    protected void initAppRoot(final ListFolderCallback callbackInstance){
        final DriveFolder driveRoot = Drive.DriveApi.getRootFolder(mGoogleApiClient);
        final String name = mParentContext.getString(R.string.app_name);
        super.createFolderInFolder(name, driveRoot, false, null, new ListFolderCallback(){
            @Override
            public void callback(FolderInfo info) {
                // init mAppRootFolder

                if (info.items.length!=0) {
                    for (ItemInfo item : info.items) {
                        if (item.readableTitle.equals(name) && item.meta.isFolder()) {
                            mAppRootFolder = item.meta.getDriveId().asDriveFolder();
                            Map<CustomPropertyKey, String> properties = item.meta.getCustomProperties();
                            String validationString = (String) properties.get(new CustomPropertyKey(SecureProperties.VALIDATION_TEXT.toString(), CustomPropertyKey.PUBLIC));
                            String validationStringIV = (String) properties.get(new CustomPropertyKey(SecureProperties.VALIDATION_TEXT_IV.toString(), CustomPropertyKey.PUBLIC));
                            if (validationString != null && validationStringIV != null) {
                                // found encrypted item
                                mPasswordValidationData = item.meta;
                                break;
                            }
                        }
                    }
                }else{
                    //TODO: error!
                }
                mCurrentApiStatus = GoogleApiStatus.CONNECTED_UNINITIALIZED;
                selfNotify();

                callbackInstance.callback(info);
            }
        });
    }

    public synchronized boolean needNewPassword(){
        if (mPasswordValidationData ==null){
            return true;
        }else{
            return false;
        }
    }

    public synchronized boolean setPassword(String password){
        mPasswordString=password;
        mKeyEncryptionKey=null;
        mSalt=null;
        if (password.equals("")) return false;

        if (mPasswordValidationData ==null){
            // new password
            // generate salt and create a validation string
            generateSalt();
            convertPassToKey(mPasswordString);
            //TODO: need to generate a random string
            Map<String, String> cipherData = encryptValidationString("random validation string");
            if (updateMetadata(mAppRootFolder.getDriveId(), cipherData, new ResultCallback<DriveResource.MetadataResult>() {
                @Override
                public void onResult(@NonNull DriveResource.MetadataResult metadataResult) {
                    if (metadataResult.getStatus().isSuccess()) {
                        mPasswordValidationData = metadataResult.getMetadata();
                        mCurrentApiStatus = GoogleApiStatus.INITIALIZED;
                        selfNotify();
                    }else{
                        // ERROR: shouldn't fail.
                    }
                }
            })!=GoogleApiStatus.DISCONNECTED){
                return true;
            }else{
                return false;
            }
        }else{
            // validate password
            if (passwordValidation()){
                mCurrentApiStatus=GoogleApiStatus.INITIALIZED;
                selfNotify();
                return true;
            }else{
                mPasswordString=null;
                mKeyEncryptionKey=null;
                mSalt=null;
                return false;
            }
        }
    }

    public synchronized void clearPassword(){
        mPasswordString=null;
        mSalt=null;
        mKeyEncryptionKey=null;
        mCurrentApiStatus=GoogleApiStatus.CONNECTED_UNINITIALIZED;
    }

    public void clearPasswordValidationData(){
        mPasswordValidationData=null;
    }
    //////////////////////// protected helper ///////////////////

    @Override
    protected boolean nameCompare(String name, Metadata item, Map<String, String> metaInfo){
        Map<CustomPropertyKey, String> properties = item.getCustomProperties();
//        String encryptedEncryptionKey = (String) properties.get(new CustomPropertyKey(SecureProperties.ENCRYPTION_KEY.toString(), CustomPropertyKey.PUBLIC));
//        if (encryptedEncryptionKey==null) {
        if(properties.get(new CustomPropertyKey(SecureProperties.ASSET_NAME_IV.toString(), CustomPropertyKey.PUBLIC))==null){
            return item.getTitle().equals(name);
        }else{
            // decrypt drive asset name
            Map<String, String> encryptInfo = new HashMap<>();
            for (Map.Entry<CustomPropertyKey, String> entry : properties.entrySet()) {
                String key = entry.getKey().getKey();
                String value = entry.getValue();
                encryptInfo.put(key, value);
            }
            String clearTitle = decryptAssetString(item.getTitle(), encryptInfo.get(SecureProperties.ASSET_NAME_IV.toString()), encryptInfo);

            // decrypt input name
            String clearInputTitle = decryptAssetString(name, metaInfo.get(SecureProperties.ASSET_NAME_IV.toString()), metaInfo);

            return clearTitle.equals(clearInputTitle);
        }
    }

    //////////////////////// private helper /////////////////////

    // validate password
    private boolean passwordValidation(){
        Map<CustomPropertyKey, String> properties = mPasswordValidationData.getCustomProperties();
        Map<String, String> encryptInfo = new HashMap<>();
        for (Map.Entry<CustomPropertyKey, String> entry : properties.entrySet()) {
            String key = entry.getKey().getKey();
            String value = entry.getValue();
            encryptInfo.put(key, value);
        }
        String clearValidationString = decryptAssetString(encryptInfo.get(SecureProperties.VALIDATION_TEXT.toString()),
                encryptInfo.get(SecureProperties.VALIDATION_TEXT_IV.toString()), encryptInfo);
        if (clearValidationString!=null){
            // success?
            return true;
        }else{
            // try again
            return false;
        }
    }

    // for the master key encryption key
    private void generateSalt(){
        int saltLength = KEYLENGTH / 8; // same size as key output
        mSalt = new byte[saltLength];
        secureRandom.nextBytes(mSalt);
    }
    private void convertPassToKey(String password){
        KeySpec keySpec = new PBEKeySpec(password.toCharArray(), mSalt,
                ITERATIONS, KEYLENGTH);
        SecretKeyFactory keyFactory = null;
        try {
            keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        } catch (NoSuchAlgorithmException e) {
            Timber.tag(mTAG).e(e, "exception!");
            //TODO: need to notify user
        }
        byte[] keyBytes = new byte[0];
        try {
            keyBytes = keyFactory.generateSecret(keySpec).getEncoded();
        } catch (InvalidKeySpecException e) {
            Timber.tag(mTAG).e(e, "exception!");
            //TODO: need to notify user
        }
        mKeyEncryptionKey = new SecretKeySpec(keyBytes, "AES");
    }

    // general encryption
    private Map<String, String> encryptThenBase64(byte[] input, SecretKey key){
        //Map<String, String> values = new HashMap<String, String>();
        Map<String, String> values = new HashMap<String, String>();

        // create cipher
        Cipher cipher = null;
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
        } catch (NoSuchAlgorithmException e) {
            Timber.tag(mTAG).e(e, "exception!");
            // TODO: notify user
        } catch (NoSuchPaddingException e) {
            Timber.tag(mTAG).e(e, "exception!");
            // TODO: notify user
        }
        final byte[] iv = new byte[cipher.getBlockSize()];
        secureRandom.nextBytes(iv);
        String ivString=Base64.encodeToString(iv, Base64.URL_SAFE);
        IvParameterSpec ivParams = new IvParameterSpec(iv);
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key, ivParams);
        } catch (InvalidKeyException e) {
            Timber.tag(mTAG).e(e, "exception!");
            // TODO: notify user
        } catch (InvalidAlgorithmParameterException e) {
            Timber.tag(mTAG).e(e, "exception!");
            // TODO: notify user
        }
        // encrypt
        byte[] ciphertext=null;
        try {
            ciphertext = cipher.doFinal(input);
        } catch (IllegalBlockSizeException e) {
            Timber.tag(mTAG).e(e, "exception!");
            // TODO: notify user
        } catch (BadPaddingException e) {
            Timber.tag(mTAG).e(e, "exception!");
            // TODO: notify user
        }
        final String encryptedText = Base64.encodeToString(ciphertext, Base64.URL_SAFE);

        values.put(SecureProperties.CIPHER_TEXT.toString(), encryptedText);
        values.put(SecureProperties.CIPHER_TEXT_IV.toString(), ivString);

        return  values;
    }
    private Map<String, String> encryptStringThenBase64(String input, SecretKey key){
        return encryptThenBase64(input.getBytes(), key);
    }
    private Map<String, String> encryptAssetName(String name){
        // generate encryption key
        KeyGenerator keyGen = null;
        try {
            keyGen = KeyGenerator.getInstance("AES");
        } catch (NoSuchAlgorithmException e) {
            Timber.tag(mTAG).e(e, "exception!");
            //TODO: notify user then exit
        }
        keyGen.init(KEYLENGTH);
        SecretKey secretKey = keyGen.generateKey();

        // encrypt name and get IV
        Map<String, String> cipherAndIV = encryptStringThenBase64(name, secretKey);

        // encrypt the key using the key encryption key
        Map<String, String> encryptedEncryptionKeyandIV = encryptThenBase64(secretKey.getEncoded(), mKeyEncryptionKey);

        Map<String, String> assetInfo = new HashMap<>();
        assetInfo.put(SecureProperties.ASSET_NAME.toString(), cipherAndIV.get(SecureProperties.CIPHER_TEXT.toString()));
        assetInfo.put(SecureProperties.ASSET_NAME_IV.toString(), cipherAndIV.get(SecureProperties.CIPHER_TEXT_IV.toString()));
        assetInfo.put(SecureProperties.ENCRYPTION_KEY.toString(), encryptedEncryptionKeyandIV.get(SecureProperties.CIPHER_TEXT.toString()));
        assetInfo.put(SecureProperties.ENCRYPTION_KEY_IV.toString(), encryptedEncryptionKeyandIV.get(SecureProperties.CIPHER_TEXT_IV.toString()));
        assetInfo.put(SecureProperties.SALT.toString(), Base64.encodeToString(mSalt, Base64.URL_SAFE));

        return assetInfo;
    }
    private Map<String, String> encryptValidationString(String name){
        Map<String, String> cipherData = encryptAssetName(name);
        cipherData.put(SecureProperties.VALIDATION_TEXT.toString(), cipherData.remove(SecureProperties.ASSET_NAME.toString()));
        cipherData.put(SecureProperties.VALIDATION_TEXT_IV.toString(), cipherData.remove(SecureProperties.ASSET_NAME_IV.toString()));
        return cipherData;
    }
    private String encryptAssetString(String clearAssetString, Map<String, String> encryptInfo){
        // check the salt
        String saltStr = encryptInfo.get(SecureProperties.SALT.toString());
        if (!Base64.encodeToString(mSalt, Base64.URL_SAFE).equals(saltStr)){
            // salt changed, regenerate master key encryption key
            mSalt = Base64.decode(saltStr, Base64.URL_SAFE);
            convertPassToKey(mPasswordString);
        }
        // decrypt the encryption key using master key
        byte[] keyIV = Base64.decode(encryptInfo.get(SecureProperties.ENCRYPTION_KEY_IV.toString()), Base64.URL_SAFE);
        byte[] encryptionKeyBytes = decryptStringToData(encryptInfo.get(SecureProperties.ENCRYPTION_KEY.toString()), mKeyEncryptionKey, keyIV);
        SecretKey encryptionKey = new SecretKeySpec(encryptionKeyBytes, "AES");

        // encrypt the asset string and get IV
        Map<String, String> cipherAndIV = encryptStringThenBase64(clearAssetString, encryptionKey);

        // add the asset IV to the encryptInfo map
        encryptInfo.put(SecureProperties.CIPHER_TEXT_IV.toString(), cipherAndIV.get(SecureProperties.CIPHER_TEXT_IV.toString()));

        // return the encrypted string
        return cipherAndIV.get(SecureProperties.CIPHER_TEXT.toString());
    }

    // general decryption
    private byte[] decryptData(byte[] input, SecretKey key, byte[] iv){
        Cipher cipher = null;
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
        } catch (NoSuchAlgorithmException e) {
            Timber.tag(mTAG).e(e, "exception!");
        } catch (NoSuchPaddingException e) {
            Timber.tag(mTAG).e(e, "exception!");
        }
        IvParameterSpec ivParams = new IvParameterSpec(iv);
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, ivParams);
        } catch (InvalidKeyException e) {
            Timber.tag(mTAG).e(e, "exception!");
        } catch (InvalidAlgorithmParameterException e) {
            Timber.tag(mTAG).e(e, "exception!");
        }
        byte[] plaintext=null;
        try {
            plaintext = cipher.doFinal(input);
        } catch (IllegalBlockSizeException e) {
            Timber.tag(mTAG).e(e, "exception!");
        } catch (BadPaddingException e) {
            Timber.tag(mTAG).e(e, "exception!");
        }
        return plaintext;
    }
    private byte[] decryptStringToData(String input, SecretKey key, byte[] iv){
        return decryptData(Base64.decode(input, Base64.URL_SAFE), key, iv);
    }
    private String decryptStringToString(String input, SecretKey key, byte[] iv){
        return new String(decryptStringToData(input, key, iv));
    }
    private String decryptAssetString(String encryptedString, String iv, Map<String, String> encryptInfo){
        // check the salt
        String saltStr = encryptInfo.get(SecureProperties.SALT.toString());
        if (mSalt==null || !Base64.encodeToString(mSalt, Base64.URL_SAFE).equals(saltStr)){
            // salt changed, regenerate master key encryption key
            mSalt = Base64.decode(saltStr, Base64.URL_SAFE);
            convertPassToKey(mPasswordString);
        }
        // decrypt the encryption key using master key
        byte[] keyIV = Base64.decode(encryptInfo.get(SecureProperties.ENCRYPTION_KEY_IV.toString()), Base64.URL_SAFE);
        byte[] encryptionKeyBytes = decryptStringToData(encryptInfo.get(SecureProperties.ENCRYPTION_KEY.toString()), mKeyEncryptionKey, keyIV);
        if (encryptionKeyBytes==null){
            return null;
        }
        SecretKey encryptionKey = new SecretKeySpec(encryptionKeyBytes, "AES");

        // decrypt the name
        byte[] assetStringIV=Base64.decode(iv, Base64.URL_SAFE);
        String assetString = decryptStringToString(encryptedString, encryptionKey, assetStringIV);
        return assetString;
    }
}
