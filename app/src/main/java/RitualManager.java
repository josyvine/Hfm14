package com.hfm.app;

import android.content.ContentResolver;
import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class RitualManager {

    private static final String TAG = "RitualManager";
    private static final String RITUAL_FILE_NAME = "rituals.dat";
    private static final String HIDDEN_DIR_NAME = "hidden";

    // --- Data Class to represent a single Ritual ---
    public static class Ritual implements Serializable {
        private static final long serialVersionUID = 1L;

        public final int tapCount;
        public final int shakeCount;
        public final float[] magnetometerData;
        public final double latitude;
        public final double longitude;

        // --- NEW: FIELDS FOR MAP FALLBACK ---
        public Double fallbackLatitude;
        public Double fallbackLongitude;

        public List<HiddenFile> hiddenFiles;

        public Ritual(int tapCount, int shakeCount, float[] magnetometerData, Location location) {
            this.tapCount = tapCount;
            this.shakeCount = shakeCount;
            this.magnetometerData = magnetometerData.clone();
            this.latitude = location.getLatitude();
            this.longitude = location.getLongitude();
            this.hiddenFiles = new ArrayList<>();
            // Initialize new fields to null
            this.fallbackLatitude = null;
            this.fallbackLongitude = null;
        }

        public void addHiddenFile(HiddenFile file) {
            this.hiddenFiles.add(file);
        }
    }

    // --- Data Class to store info about a hidden file ---
    public static class HiddenFile implements Serializable {
        private static final long serialVersionUID = 2L;

        public final String originalPath;
        public final String encryptedFileName; // The new random name in the hidden directory

        public HiddenFile(String originalPath, String encryptedFileName) {
            this.originalPath = originalPath;
            this.encryptedFileName = encryptedFileName;
        }
    }

    // --- Public Method to Start the Hiding Process ---
    public void createAndSaveRitual(Context context, int taps, int shakes, float[] magnetometer, Location location, List<File> filesToHide) {
        Ritual newRitual = new Ritual(taps, shakes, magnetometer, location);
        new HideFilesTask(context, newRitual, filesToHide, -1).execute();
    }

    // --- NEW Public Method to add files to an existing Ritual ---
    public void addFilesToRitual(Context context, int ritualIndex, List<File> filesToHide) {
        List<Ritual> rituals = loadRituals(context);
        if (rituals != null && ritualIndex >= 0 && ritualIndex < rituals.size()) {
            Ritual existingRitual = rituals.get(ritualIndex);
            new HideFilesTask(context, existingRitual, filesToHide, ritualIndex).execute();
        } else {
            Toast.makeText(context, "Error: Could not find the specified ritual to update.", Toast.LENGTH_LONG).show();
        }
    }

    // --- Public Method to Start the Unhiding Process ---
    public void verifyAndDecryptRitual(Context context, Ritual ritual, int ritualIndex) {
        new UnhideFilesTask(context, ritual, ritualIndex).execute();
    }


    // --- File Persistence Methods (NOW PUBLIC) ---
    public List<Ritual> loadRituals(Context context) {
        File ritualFile = new File(context.getFilesDir(), RITUAL_FILE_NAME);
        if (!ritualFile.exists()) {
            return new ArrayList<>();
        }
        try {
            FileInputStream fis = new FileInputStream(ritualFile);
            ObjectInputStream ois = new ObjectInputStream(fis);
            List<Ritual> rituals = (List<Ritual>) ois.readObject();
            ois.close();
            fis.close();
            return rituals;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load rituals", e);
            return new ArrayList<>(); // Return empty list on failure
        }
    }

    public void saveRituals(Context context, List<Ritual> rituals) {
        File ritualFile = new File(context.getFilesDir(), RITUAL_FILE_NAME);
        try {
            FileOutputStream fos = new FileOutputStream(ritualFile);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(rituals);
            oos.close();
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save rituals", e);
        }
    }

    // --- Cryptography and Key Generation ---
    private SecretKeySpec generateKey(Ritual ritual) throws Exception {
        // --- ROBUST FIX ---
        // 1. Create a consistent string "password" from the ritual data using the raw bit representations
        // of floating-point numbers. This is far more robust than using string formatting (e.g., "%.6f"),
        // which can have subtle inconsistencies.
        StringBuilder passwordBuilder = new StringBuilder();
        passwordBuilder.append("taps:").append(ritual.tapCount);
        passwordBuilder.append("-shakes:").append(ritual.shakeCount);
        passwordBuilder.append("-mag:");
        passwordBuilder.append(Float.floatToIntBits(ritual.magnetometerData[0])).append(",");
        passwordBuilder.append(Float.floatToIntBits(ritual.magnetometerData[1])).append(",");
        passwordBuilder.append(Float.floatToIntBits(ritual.magnetometerData[2]));
        passwordBuilder.append("-loc:");
        passwordBuilder.append(Double.doubleToLongBits(ritual.latitude)).append(",");
        passwordBuilder.append(Double.doubleToLongBits(ritual.longitude));

        String passwordString = passwordBuilder.toString();
        Log.d(TAG, "Generated Key String: " + passwordString); // For debugging purposes

        // 2. Use a standard Key Derivation Function (PBKDF2) to create a strong key
        String salt = "hfm_secure_salt"; // A static salt is acceptable here as the passwordString is highly unique
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        KeySpec spec = new PBEKeySpec(passwordString.toCharArray(), salt.getBytes(), 65536, 256); // 256-bit key
        SecretKey tmp = factory.generateSecret(spec);

        // 3. Return the key in a format suitable for AES
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    // --- MODIFIED AsyncTask to handle file HIDING for both new and existing rituals ---
    private class HideFilesTask extends AsyncTask<Void, String, Boolean> {
        private final Context context;
        private final Ritual ritual;
        private final List<File> filesToHide;
        private final SecretKeySpec secretKey;
        private final int ritualIndex; // -1 for new ritual, >= 0 for existing

        HideFilesTask(Context context, Ritual ritual, List<File> filesToHide, int ritualIndex) {
            this.context = context;
            this.ritual = ritual;
            this.filesToHide = filesToHide;
            this.ritualIndex = ritualIndex;
            SecretKeySpec key = null;
            try {
                key = generateKey(ritual);
            } catch (Exception e) {
                Log.e(TAG, "Key generation failed!", e);
            }
            this.secretKey = key;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            Toast.makeText(context, "Starting encryption process...", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            if (secretKey == null) {
                return false;
            }

            File hiddenDir = new File(context.getFilesDir(), HIDDEN_DIR_NAME);
            if (!hiddenDir.exists()) {
                hiddenDir.mkdir();
            }

            ContentResolver resolver = context.getContentResolver();

            for (int i = 0; i < filesToHide.size(); i++) {
                File originalFile = filesToHide.get(i);
                String path = originalFile.getAbsolutePath();
                publishProgress("Encrypting: " + originalFile.getName() + " (" + (i + 1) + "/" + filesToHide.size() + ")");

                String encryptedFileName = UUID.randomUUID().toString() + ".hfm";
                File encryptedFile = new File(hiddenDir, encryptedFileName);

                try {
                    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    IvParameterSpec iv = new IvParameterSpec("hfm_static_iv_16".getBytes(StandardCharsets.UTF_8));
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv);

                    FileInputStream fis = new FileInputStream(originalFile);
                    FileOutputStream fos = new FileOutputStream(encryptedFile);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        byte[] output = cipher.update(buffer, 0, bytesRead);
                        if (output != null) {
                            fos.write(output);
                        }
                    }
                    byte[] finalBytes = cipher.doFinal();
                    if (finalBytes != null) {
                        fos.write(finalBytes);
                    }
                    fis.close();
                    fos.flush();
                    fos.close();

                    ritual.addHiddenFile(new HiddenFile(path, encryptedFileName));

                    // --- STEALTH REMOVAL: Stop Android Trash Popup ---
                    // Wipe the file from the system database so the OS doesn't "catch" the delete
                    try {
                        Uri filesUri = MediaStore.Files.getContentUri("external");
                        resolver.delete(filesUri, MediaStore.MediaColumns.DATA + "=?", new String[]{path});
                    } catch (Exception e) {
                        Log.e(TAG, "Stealth DB removal failed", e);
                    }

                    // Physical deletion
                    if (!originalFile.delete()) {
                        StorageUtils.deleteFile(context, originalFile);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Encryption failed for " + originalFile.getName(), e);
                    return false;
                }
            }
            return true;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            Toast.makeText(context, values[0], Toast.LENGTH_SHORT).show();
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            if (success) {
                List<Ritual> rituals = loadRituals(context);
                if (ritualIndex == -1) {
                    // This is a new ritual, add it to the list
                    rituals.add(ritual);
                } else {
                    // This is an existing ritual, update it in the list
                    rituals.set(ritualIndex, ritual);
                }
                saveRituals(context, rituals);
                Toast.makeText(context, "All files successfully hidden!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "A critical error occurred. Hiding process failed.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- AsyncTask to handle file UNHIDING ---
    private class UnhideFilesTask extends AsyncTask<Void, String, Boolean> {
        private final Context context;
        private final Ritual ritual;
        private final int ritualIndex;
        private final SecretKeySpec secretKey;

        UnhideFilesTask(Context context, Ritual ritual, int ritualIndex) {
            this.context = context;
            this.ritual = ritual;
            this.ritualIndex = ritualIndex;
            SecretKeySpec key = null;
            try {
                key = generateKey(ritual);
            } catch (Exception e) {
                Log.e(TAG, "Key re-generation failed for decryption!", e);
            }
            this.secretKey = key;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            Toast.makeText(context, "Starting decryption process...", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            if (secretKey == null) {
                return false;
            }

            File hiddenDir = new File(context.getFilesDir(), HIDDEN_DIR_NAME);
            if (!hiddenDir.exists()) {
                Log.e(TAG, "Hidden directory does not exist. Cannot decrypt.");
                return false;
            }

            for (int i = 0; i < ritual.hiddenFiles.size(); i++) {
                HiddenFile hiddenFile = ritual.hiddenFiles.get(i);
                File encryptedFile = new File(hiddenDir, hiddenFile.encryptedFileName);
                File restoredFile = new File(hiddenFile.originalPath);

                // Ensure parent directory of restored file exists
                File parentDir = restoredFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                publishProgress("Decrypting: " + restoredFile.getName() + " (" + (i + 1) + "/" + ritual.hiddenFiles.size() + ")");

                try {
                    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    IvParameterSpec iv = new IvParameterSpec("hfm_static_iv_16".getBytes(StandardCharsets.UTF_8));
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, iv);

                    FileInputStream fis = new FileInputStream(encryptedFile);
                    FileOutputStream fos = new FileOutputStream(restoredFile);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        byte[] output = cipher.update(buffer, 0, bytesRead);
                        if (output != null) {
                            fos.write(output);
                        }
                    }
                    byte[] finalBytes = cipher.doFinal();
                    if (finalBytes != null) {
                        fos.write(finalBytes);
                    }
                    fis.close();
                    fos.flush();
                    fos.close();

                    // If decryption is successful, delete the encrypted file
                    encryptedFile.delete();

                } catch (Exception e) {
                    Log.e(TAG, "Decryption failed for " + encryptedFile.getName(), e);
                    return false;
                }
            }
            return true;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            Toast.makeText(context, values[0], Toast.LENGTH_SHORT).show();
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            if (success) {
                // Load all rituals, remove the one we just decrypted, and save the list back
                List<Ritual> rituals = loadRituals(context);
                if (rituals.size() > ritualIndex) {
                    rituals.remove(ritualIndex);
                }
                saveRituals(context, rituals);
                Toast.makeText(context, "All files have been restored successfully!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "A critical error occurred. Decryption process failed.", Toast.LENGTH_LONG).show();
            }
        }
    }
}