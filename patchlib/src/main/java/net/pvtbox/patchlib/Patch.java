package net.pvtbox.patchlib;

import android.util.Log;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;

import org.xeustechnologies.jtar.TarEntry;
import org.xeustechnologies.jtar.TarInputStream;
import org.xeustechnologies.jtar.TarOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
*  
*  Pvtbox. Fast and secure file transfer & sync directly across your devices. 
*  Copyright © 2020  Pb Private Cloud Solutions Ltd. 
*  
*  Licensed under the Apache License, Version 2.0 (the "License");
*  you may not use this file except in compliance with the License.
*  You may obtain a copy of the License at
*     http://www.apache.org/licenses/LICENSE-2.0
*  
*  Unless required by applicable law or agreed to in writing, software
*  distributed under the License is distributed on an "AS IS" BASIS,
*  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*  See the License for the specific language governing permissions and
*  limitations under the License.
*  
**/

@SuppressWarnings("WeakerAccess")
public final class Patch {
    public static final int defaultBlockSize = 1024 * 1024;
    private static final String TAG = "PvtboxPatch";

    public static String hashFromBlocksHashes(TreeMap<Long, String> blockHashes)
            throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");

        for (String hash : blockHashes.values()) {
            digest.update(hash.getBytes());
        }
        byte[] md5sum = digest.digest();
        BigInteger bigInt = new BigInteger(1, md5sum);
        String output = bigInt.toString(16);
        // Fill to 32 chars
        output = String.format("%32s", output).replace(' ', '0');

        return output;
    }

    public static TreeMap<Long, String> blocksHashes(String filePath, int blocksize) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Exception while getting digest", e);
            return null;
        }

        InputStream is;
        try {
            is = new FileInputStream(filePath);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Exception while getting FileInputStream", e);
            return null;
        }


        TreeMap<Long, String> result = new TreeMap<>();
        byte[] buffer = new byte[blocksize];
        int read = 0;
        long totalRead = 0;
        long size = new File(filePath).length();
        long offset = 0L;
        try {
            while ((read += is.read(buffer, read, blocksize - read)) > 0) {
                if (read < blocksize && totalRead + read != size) continue;
                totalRead += read;
                digest.update(buffer, 0, read);
                byte[] md5sum = digest.digest();
                BigInteger bigInt = new BigInteger(1, md5sum);
                String output = bigInt.toString(16);
                // Fill to 32 chars
                output = String.format("%32s", output).replace(' ', '0');
                result.put(offset, output);
                offset += read;
                read = 0;
                digest.reset();
            }

            return result;
        } catch (IOException e) {
            throw new RuntimeException("Unable to process file for MD5", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                Log.e(TAG, "Exception on closing MD5 input stream", e);
            }
        }
    }

    public static Map createPatch(String filePath, String patchFilePath,
                                  String fileHash, TreeMap<Long, String> blocksHashes,
                                  String oldFileHash, TreeMap<Long, String> oldBlocksHashes,
                                  int blocksize)
            throws IOException, NoSuchAlgorithmException {
        if (blocksHashes == null) {
            blocksHashes = blocksHashes(filePath, blocksize);
        }
        if (fileHash == null) {
            assert blocksHashes != null;
            fileHash = hashFromBlocksHashes(blocksHashes);
        }

        File patchDataFile = File.createTempFile("data", null);

        Map patchBlocks = createPatchBlocks(
                filePath, patchDataFile, blocksHashes, oldBlocksHashes, blocksize);

        FileOutputStream patchFile = new FileOutputStream(patchFilePath);
        TarOutputStream out = new TarOutputStream(new BufferedOutputStream(patchFile));
        TarEntry dataEntry = new TarEntry(patchDataFile, "data");
        out.putNextEntry(dataEntry);

        FileInputStream patchDataStream = new FileInputStream(patchDataFile);
        int count;
        byte[] data = new byte[defaultBlockSize];
        while ((count = patchDataStream.read(data, 0, defaultBlockSize)) != -1) {
            out.write(data, 0, count);
        }

        out.flush();

        //noinspection ResultOfMethodCallIgnored
        patchDataFile.delete();

        HashMap<String, Object> patch = new HashMap<>();
        patch.put("blocks", patchBlocks);
        patch.put("new_hash", fileHash);
        patch.put("blocksize", blocksize);

        File file = new File(filePath);
        patch.put("size", file.length());
        patch.put("time_modify", file.lastModified());
        if (oldFileHash != null) {
            patch.put("old_hash", oldFileHash);
        }

        Moshi moshi = new Moshi.Builder().build();
        JsonAdapter<Map> jsonAdapter = moshi.adapter(Map.class);

        String json = jsonAdapter.toJson(patch);

        TarEntry infoEntry = new TarEntry(new File(""), "info");
        infoEntry.setSize(json.getBytes().length);
        out.putNextEntry(infoEntry);

        out.write(json.getBytes());
        out.flush();
        out.close();
        return patch;
    }

    private static Map createPatchBlocks(String filePath, File patchDataFile,
                                         TreeMap<Long, String> blocksHashes, TreeMap<Long, String> oldBlocksHashes,
                                         int blocksize)
            throws IOException {
        RandomAccessFile file = new RandomAccessFile(filePath, "r");
        LinkedHashMap<String, Long> oldBlocksSearch = new LinkedHashMap<>();
        if (oldBlocksHashes != null) {
            for (Object entry : oldBlocksHashes.entrySet())
                oldBlocksSearch.put(
                        (String) ((Map.Entry) entry).getValue(),
                        (Long) ((Map.Entry) entry).getKey());
        }
        LinkedHashMap<String, Long> patchBlocksSearch = new LinkedHashMap<>();

        TreeMap<Long, Object> patch = new TreeMap<>();

        long dataFileOffset = 0;

        FileOutputStream dataStream = new FileOutputStream(patchDataFile, false);

        for (Object entry : blocksHashes.entrySet()) {
            Long offset = (Long) ((Map.Entry) entry).getKey();
            String hash = (String) ((Map.Entry) entry).getValue();

            HashMap<String, Object> patchEntry = new HashMap<>();
            patchEntry.put("hash", hash);
            if (patchBlocksSearch.containsKey(hash)) {
                patchEntry.put("new", false);
                patchEntry.put("from_patch", true);
                patchEntry.put("offset", patchBlocksSearch.get(hash));
            } else if (oldBlocksSearch.containsKey(hash)) {
                patchEntry.put("new", false);
                patchEntry.put("from_patch", false);
                patchEntry.put("offset", oldBlocksSearch.get(hash));
            } else {
                patchEntry.put("new", true);
                patchEntry.put("offset", dataFileOffset);
                file.seek(offset);
                byte[] buffer = new byte[blocksize];
                int data_size = 0;
                int read = 0;
                while (data_size <= blocksize &&
                        (read = file.read(buffer, read, blocksize - read)) > 0){
                    data_size += read;
                }
                dataFileOffset += data_size;
                dataStream.write(buffer, 0, data_size);
                patchEntry.put("data_size", data_size);
                patchBlocksSearch.put(hash, offset);
            }
            patch.put(offset, patchEntry);
        }
        dataStream.flush();
        dataStream.close();
        return patch;
    }

    public static ArrayList acceptPatch(String filePath, String resultPath, String patchFilePath, String fileHash)
            throws IOException {
        TarInputStream tis = new TarInputStream(
                new BufferedInputStream(
                        new FileInputStream(patchFilePath)));
        TarEntry entry;
        Map<String, Object> patchInfo = null;
        File patchDataFile = File.createTempFile("data", null);

        while ((entry = tis.getNextEntry()) != null) {
            int count;
            byte[] data = new byte[defaultBlockSize];

            if (entry.getName().equals("info")) {
                StringBuilder info = new StringBuilder();
                while ((count = tis.read(data, 0, defaultBlockSize)) != -1) {
                    info.append(new String(data, 0, count));
                }

                Moshi moshi = new Moshi.Builder().build();
                JsonAdapter<Map> jsonAdapter = moshi.adapter(Map.class);

                //noinspection unchecked
                patchInfo = jsonAdapter.fromJson(info.toString());
            } else if (entry.getName().equals("data")) {
                BufferedOutputStream os = new BufferedOutputStream(
                        new FileOutputStream(patchDataFile, false));
                while ((count = tis.read(data, 0, defaultBlockSize)) != -1) {
                    os.write(data, 0, count);
                }
                os.flush();
                os.close();
            }
        }
        tis.close();

        File patchedTempFile;
        try {
            patchedTempFile = File.createTempFile("data", null);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            patchDataFile.delete();
            throw e;
        }
        try {
            assert patchInfo != null;
            return acceptPatch(filePath, resultPath, fileHash, patchInfo, patchDataFile, patchedTempFile);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            patchedTempFile.delete();
            //noinspection ResultOfMethodCallIgnored
            patchDataFile.delete();
        }
    }

    private static ArrayList acceptPatch(String filePath, String resultFilePath, String fileHash,
                                         Map<String, Object> patchInfo, File patchDataFile,
                                         File patchedTempFile)
            throws IOException {
        if (patchInfo.containsKey("old_hash") && !fileHash.equals(patchInfo.get("old_hash"))) {
            throw new IOException("Trying to apply patch for wrong file");
        }

        Double blocksizeD = (Double) patchInfo.get("blocksize");
        int blocksize = blocksizeD == null ? defaultBlockSize : blocksizeD.intValue();
        @SuppressWarnings("unchecked")
        TreeMap<Long, Object> blocks = new TreeMap((Map<Long, Object>) patchInfo.get("blocks"));

        RandomAccessFile patchedFile = new RandomAccessFile(patchedTempFile, "rw");
        RandomAccessFile patchData = new RandomAccessFile(patchDataFile, "r");

        File file = new File(filePath);

        RandomAccessFile originalFile = null;
        try {
            originalFile = new RandomAccessFile(file, "r");
        } catch (FileNotFoundException ignored) {
        }

        TreeMap<Long, String> blocksHashes = new TreeMap<>();

        for (Object entry : blocks.entrySet()) {
            Long offset = Long.valueOf((String) ((Map.Entry) entry).getKey());
            @SuppressWarnings("unchecked")
            Map<String, Object> blockEntry = (Map<String, Object>) ((Map.Entry) entry).getValue();
            Double blockOffset = Objects.requireNonNull((Double) blockEntry.get("offset"));
            Boolean is_new = (Boolean) blockEntry.get("new");
            byte[] data = new byte[blocksize];
            Double dataSizeD;
            int dataSize;
            if (is_new != null && is_new) {
                patchData.seek(blockOffset.longValue());
                dataSizeD = (Double) blockEntry.get("data_size");
                dataSize = dataSizeD == null ?
                        blocksize : dataSizeD.intValue();
                int readLast;
                int read = 0;
                while (read < dataSize && (readLast = patchData.read(data, read, dataSize - read)) > 0) {
                    read += readLast;
                }
                dataSize = read;
            } else {
                Boolean fromPatch = (Boolean) blockEntry.get("from_patch");
                if (fromPatch != null && fromPatch) {
                    //noinspection SuspiciousMethodCalls,unchecked
                    Map<String, Object> patchBlock = Objects.requireNonNull((Map<String, Object>) blocks.get(
                            Long.toString(blockOffset.longValue())));
                    Double patchOffset = Objects.requireNonNull((Double) patchBlock.get("offset"));
                    dataSizeD = (Double) patchBlock.get("data_size");
                    dataSize = dataSizeD == null ?
                            blocksize : dataSizeD.intValue();
                    patchData.seek(patchOffset.longValue());
                    int readLast;
                    int read = 0;
                    while (read < dataSize && (readLast = patchData.read(data, read, dataSize - read)) > 0) {
                        read += readLast;
                    }
                    dataSize = read;
                } else {
                    if (originalFile == null) {
                        patchedTempFile.deleteOnExit();
                        throw new IOException("Original file not found");
                    }
                    dataSizeD = (Double) blockEntry.get("data_size");
                    dataSize = dataSizeD == null ?
                            blocksize : dataSizeD.intValue();
                    originalFile.seek(blockOffset.longValue());
                    int readLast;
                    int read = 0;
                    while (read < dataSize && (readLast = originalFile.read(data, read, dataSize - read)) > 0) {
                        read += readLast;
                    }
                    dataSize = read;
                }
            }

            patchedFile.seek(offset);
            patchedFile.write(data, 0, dataSize);

            blocksHashes.put(offset, (String) blockEntry.get("hash"));
        }

        if (originalFile != null) {
            originalFile.close();
        }
        patchedFile.close();

        TreeMap<Long, String> patchedFileBlocksHashes = blocksHashes(
                patchedTempFile.getPath(), blocksize);
        if (!Objects.equals(patchedFileBlocksHashes, blocksHashes)) {
            throw new IOException(String.format(
                    "Invalid patch result, expected signature: %s, actual: %s",
                    blocksHashes, patchedFileBlocksHashes));
        }
        try {

            File fileResult = new File(resultFilePath);
            if (fileResult.exists()) {
                //noinspection ResultOfMethodCallIgnored
                fileResult.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            patchedTempFile.renameTo(fileResult);
        } catch (Exception e) {
            throw new IOException(String.format(
                    "Unable to rename file '%s' to '%s'",
                    patchedTempFile.getName(),
                    file.getPath()));
        }
        ArrayList<Object> res = new ArrayList<>();
        res.add(patchInfo.get("new_hash"));
        res.add(blocksHashes);
        return res;
    }
}
