package net.pvtbox.patchlib;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

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
/**
 * Instrumentation test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@SuppressWarnings("ALL")
@RunWith(AndroidJUnit4.class)
public class PvtboxPatchLibraryInstrumentedTest {

    private Context appContext;
    private final ArrayList<File> files = new ArrayList<>();

    @Before
    public void setUp() {
        appContext = InstrumentationRegistry.getTargetContext();
    }

    @After
    public void tearDown() {
        for (File f : files) {
            f.delete();
        }
    }

    private File createFile(String name) {
        File file = new File(appContext.getFilesDir(), name);
        file.delete();
        files.add(file);
        return file;
    }

    private File fillFile(File file, int fill, long repeat) throws IOException {
        if (!file.exists()) file.createNewFile();
        OutputStream fo = new FileOutputStream(file, true);
        while (repeat > 0) {
            fo.write(fill);
            repeat--;
        }
        fo.flush();
        fo.close();
        return file;
    }

    private boolean fileContentsEq(File lhs, File rhs) throws IOException {
        FileInputStream lhss = new FileInputStream(lhs);
        FileInputStream rhss = new FileInputStream(rhs);
        while (true) {
            int lhssbyte = lhss.read();
            int rhssbyte = rhss.read();
            if (lhssbyte != rhssbyte) return false;
            if (lhssbyte == -1) return true;
        }

    }

    @Test
    public void patch0k() throws Exception {
        File origin = fillFile(createFile("0.orig"), 0, 0);
        File patch = createFile("0.patch");
        File patched = createFile("0.patched");

        File result = createFile("0.result");

        Map patchInfo = Patch.createPatch(origin.getPath(), patch.getPath(), null, null, null, null, Patch.defaultBlockSize);
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", patchInfo.get("new_hash"));
        assertEquals(Patch.defaultBlockSize, patchInfo.get("blocksize"));
        assertEquals(0L, patchInfo.get("size"));
        assertEquals(0, ((Map) patchInfo.get("blocks")).size());

        Map blocks = (Map) Patch.acceptPatch(patched.getPath(), result.getPath(), patch.getPath(), null).get(1);
        if(patched.exists()){
            patched.delete();
        }
        result.renameTo(patched);
        assertEquals(0, blocks.size());
        assertEquals(Patch.blocksHashes(origin.getPath(), Patch.defaultBlockSize), blocks);

        assertTrue(fileContentsEq(origin, patched));
    }

    @Test
    public void patch1k() throws Exception {
        File origin = fillFile(createFile("0.orig"), 42, 1024);
        File patch = createFile("0.patch");
        File patched = createFile("0.patched");
        File result = createFile("0.result");
        Map patchInfo = Patch.createPatch(origin.getPath(), patch.getPath(), null, null, null, null, Patch.defaultBlockSize);
        assertEquals("368826d9001ff5f9365790ce1b9dad5f", patchInfo.get("new_hash"));
        assertEquals(Patch.defaultBlockSize, patchInfo.get("blocksize"));
        assertEquals(1024L, patchInfo.get("size"));
        assertEquals(1, ((Map) patchInfo.get("blocks")).size());

        Map blocks = (Map) Patch.acceptPatch(patched.getPath(), result.getPath(), patch.getPath(), null).get(1);
        assertEquals(1, blocks.size());
        assertEquals(Patch.blocksHashes(origin.getPath(), Patch.defaultBlockSize), blocks);
        assertTrue(fileContentsEq(origin, result));
    }

    @Test
    public void patch3_different_blocks() throws Exception {
        File origin =
                fillFile(
                        fillFile(
                                fillFile(
                                        createFile("0.orig"),
                                        42, Patch.defaultBlockSize),
                                43, Patch.defaultBlockSize),
                        44, Patch.defaultBlockSize);
        File patch = createFile("0.patch");
        File patched = createFile("0.patched");
        File result = createFile("0.result");

        Map patchInfo = Patch.createPatch(origin.getPath(), patch.getPath(), null, null, null, null, Patch.defaultBlockSize);
        assertEquals("a3b48438a106106074da37be30fe21b1", patchInfo.get("new_hash"));
        assertEquals(Patch.defaultBlockSize, patchInfo.get("blocksize"));
        assertEquals(Patch.defaultBlockSize * 3L, patchInfo.get("size"));
        Map blocks = (Map) patchInfo.get("blocks");
        assertEquals(3, blocks.size());

        Map block0 = (Map) blocks.get(0L);
        Map block1 = (Map) blocks.get((long) Patch.defaultBlockSize);
        Map block2 = (Map) blocks.get((long) Patch.defaultBlockSize * 2);

        assertEquals(true, block0.get("new"));
        assertEquals(0L, block0.get("offset"));
        assertEquals("5b99234ef23ac445057d3f6ba4dd4f8c", block0.get("hash"));
        assertEquals(Patch.defaultBlockSize, block0.get("data_size"));

        assertEquals(true, block1.get("new"));
        assertEquals((long) Patch.defaultBlockSize, block1.get("offset"));
        assertEquals("3a7c3c35eee529587f401af6c8646d79", block1.get("hash"));
        assertEquals(Patch.defaultBlockSize, block1.get("data_size"));

        assertEquals(true, block2.get("new"));
        assertEquals((long) Patch.defaultBlockSize * 2, block2.get("offset"));
        assertEquals("36287664b9006f578d81f7f382fe34f6", block2.get("hash"));
        assertEquals(Patch.defaultBlockSize, block2.get("data_size"));

        blocks = (Map) Patch.acceptPatch(patched.getPath(), result.getPath(), patch.getPath(), null).get(1);
        assertEquals(3, blocks.size());
        assertEquals(Patch.blocksHashes(origin.getPath(), Patch.defaultBlockSize), blocks);
        assertTrue(fileContentsEq(origin, result));
    }

    @Test
    public void patch3_same_blocks() throws Exception {
        File origin =
                fillFile(
                        fillFile(
                                fillFile(
                                        createFile("0.orig"),
                                        42, (long) Patch.defaultBlockSize),
                                42, (long) Patch.defaultBlockSize),
                        42, (long) Patch.defaultBlockSize);
        File patch = createFile("0.patch");
        File patched = createFile("0.patched");
        File result = createFile("0.result");

        Map patchInfo = Patch.createPatch(origin.getPath(), patch.getPath(), null, null, null, null, Patch.defaultBlockSize);
        assertEquals("010df07339d2aa72bd408b7489c25958", patchInfo.get("new_hash"));
        assertEquals(Patch.defaultBlockSize, patchInfo.get("blocksize"));
        assertEquals(Patch.defaultBlockSize * 3L, patchInfo.get("size"));
        Map blocks = (Map) patchInfo.get("blocks");
        assertEquals(3, blocks.size());

        Map block0 = (Map) blocks.get(0L);
        Map block1 = (Map) blocks.get((long) Patch.defaultBlockSize);
        Map block2 = (Map) blocks.get((long) Patch.defaultBlockSize * 2);

        assertEquals(true, block0.get("new"));
        assertEquals(0L, block0.get("offset"));
        assertEquals("5b99234ef23ac445057d3f6ba4dd4f8c", block0.get("hash"));
        assertEquals(Patch.defaultBlockSize, block0.get("data_size"));

        assertEquals(false, block1.get("new"));
        assertEquals(true, block1.get("from_patch"));
        assertEquals(0L, block1.get("offset"));
        assertEquals("5b99234ef23ac445057d3f6ba4dd4f8c", block1.get("hash"));

        assertEquals(false, block2.get("new"));
        assertEquals(true, block2.get("from_patch"));
        assertEquals(0L, block2.get("offset"));
        assertEquals("5b99234ef23ac445057d3f6ba4dd4f8c", block2.get("hash"));


        blocks = (Map) Patch.acceptPatch(patched.getPath(), result.getPath(), patch.getPath(), null).get(1);
        assertEquals(3, blocks.size());
        assertEquals(Patch.blocksHashes(origin.getPath(), Patch.defaultBlockSize), blocks);
        assertTrue(fileContentsEq(origin, result));
    }

    @Test
    public void continious_patch() throws Exception {
        File origin = fillFile(createFile("0.orig"), 0, 0);
        File patch = createFile("0.patch");
        File patched = createFile("0.patched");
        File result = createFile("0.result");

        TreeMap origBlocks = Patch.blocksHashes(origin.getPath(), Patch.defaultBlockSize);
        String origHash = Patch.hashFromBlocksHashes(origBlocks);
        Map patchInfo = Patch.createPatch(origin.getPath(), patch.getPath(), origHash, origBlocks, null, null, Patch.defaultBlockSize);
        TreeMap patchedBlocks = (TreeMap) Patch.acceptPatch(patched.getPath(), result.getPath(), patch.getPath(), null).get(1);
        String patchedHash = Patch.hashFromBlocksHashes(patchedBlocks);

        assertEquals(origHash, patchedHash);
        assertEquals(origBlocks, patchedBlocks);
        assertTrue(fileContentsEq(origin, result));

        origin = fillFile(origin, 42, 2048);
        origBlocks = Patch.blocksHashes(origin.getPath(), Patch.defaultBlockSize);
        origHash = Patch.hashFromBlocksHashes(origBlocks);
        patchInfo = Patch.createPatch(origin.getPath(), patch.getPath(), origHash, origBlocks, patchedHash, patchedBlocks, Patch.defaultBlockSize);

        assertEquals(patchedHash, patchInfo.get("old_hash"));

        patchedBlocks = (TreeMap) Patch.acceptPatch(patched.getPath(), result.getPath(), patch.getPath(), patchedHash).get(1);
        patchedHash = Patch.hashFromBlocksHashes(patchedBlocks);

        assertEquals(origHash, patchedHash);
        assertEquals(origBlocks, patchedBlocks);
        assertTrue(fileContentsEq(origin, result));

        origin = createFile("0.orig");
        origin = fillFile(origin, 43, Patch.defaultBlockSize * 2);
        origBlocks = Patch.blocksHashes(origin.getPath(), Patch.defaultBlockSize);
        origHash = Patch.hashFromBlocksHashes(origBlocks);
        patchInfo = Patch.createPatch(origin.getPath(), patch.getPath(), origHash, origBlocks, patchedHash, patchedBlocks, Patch.defaultBlockSize);

        assertEquals(patchedHash, patchInfo.get("old_hash"));

        patchedBlocks = (TreeMap) Patch.acceptPatch(patched.getPath(), result.getPath(), patch.getPath(), patchedHash).get(1);
        patchedHash = Patch.hashFromBlocksHashes(patchedBlocks);

        assertEquals(origHash, patchedHash);
        assertEquals(origBlocks, patchedBlocks);
        assertTrue(fileContentsEq(origin, result));

        origin = fillFile(origin, 43, Patch.defaultBlockSize);
        origBlocks = Patch.blocksHashes(origin.getPath(), Patch.defaultBlockSize);
        origHash = Patch.hashFromBlocksHashes(origBlocks);
        patchInfo = Patch.createPatch(origin.getPath(), patch.getPath(), origHash, origBlocks, patchedHash, patchedBlocks, Patch.defaultBlockSize);

        assertEquals(patchedHash, patchInfo.get("old_hash"));
        TreeMap blocks = (TreeMap) patchInfo.get("blocks");
        assertEquals(3, blocks.size());
        Map block0 = (Map) blocks.get(0L);
        Map block1 = (Map) blocks.get((long) Patch.defaultBlockSize);
        Map block2 = (Map) blocks.get((long) Patch.defaultBlockSize * 2);
        assertEquals(false, block0.get("new"));
        assertEquals(false, block0.get("from_patch"));
        assertEquals((long) Patch.defaultBlockSize, block0.get("offset"));
        assertEquals("3a7c3c35eee529587f401af6c8646d79", block0.get("hash"));
        assertEquals(false, block1.get("new"));
        assertEquals(false, block1.get("from_patch"));
        assertEquals((long) Patch.defaultBlockSize, block1.get("offset"));
        assertEquals("3a7c3c35eee529587f401af6c8646d79", block1.get("hash"));
        assertEquals(false, block2.get("new"));
        assertEquals(false, block2.get("from_patch"));
        assertEquals((long) Patch.defaultBlockSize, block2.get("offset"));
        assertEquals("3a7c3c35eee529587f401af6c8646d79", block2.get("hash"));

        patched.delete();
        result.renameTo(patched);


        patchedBlocks = (TreeMap) Patch.acceptPatch(patched.getPath(), result.getPath(), patch.getPath(), patchedHash).get(1);
        patchedHash = Patch.hashFromBlocksHashes(patchedBlocks);

        assertEquals(origHash, patchedHash);
        assertEquals(origBlocks, patchedBlocks);
        assertTrue(fileContentsEq(origin, result));

        origin = fillFile(origin, 44, Patch.defaultBlockSize);
        origin = fillFile(origin, 43, Patch.defaultBlockSize);
        origin = fillFile(origin, 42, Patch.defaultBlockSize / 2);
        origBlocks = Patch.blocksHashes(origin.getPath(), Patch.defaultBlockSize);
        origHash = Patch.hashFromBlocksHashes(origBlocks);
        patchInfo = Patch.createPatch(origin.getPath(), patch.getPath(), origHash, origBlocks, patchedHash, patchedBlocks, Patch.defaultBlockSize);

        assertEquals(patchedHash, patchInfo.get("old_hash"));


        patched.delete();
        result.renameTo(patched);

        patchedBlocks = (TreeMap) Patch.acceptPatch(patched.getPath(), result.getPath(), patch.getPath(), patchedHash).get(1);
        patchedHash = Patch.hashFromBlocksHashes(patchedBlocks);

        assertEquals(origHash, patchedHash);
        assertEquals(origBlocks, patchedBlocks);
        assertTrue(fileContentsEq(origin, result));

        origin = createFile("0.orig");
        origin = fillFile(origin, 42, Patch.defaultBlockSize / 2);
        origBlocks = Patch.blocksHashes(origin.getPath(), Patch.defaultBlockSize);
        origHash = Patch.hashFromBlocksHashes(origBlocks);
        patchInfo = Patch.createPatch(origin.getPath(), patch.getPath(), origHash, origBlocks, patchedHash, patchedBlocks, Patch.defaultBlockSize);

        assertEquals(patchedHash, patchInfo.get("old_hash"));


        patched.delete();
        result.renameTo(patched);

        patchedBlocks = (TreeMap) Patch.acceptPatch(patched.getPath(), result.getPath(), patch.getPath(), patchedHash).get(1);
        patchedHash = Patch.hashFromBlocksHashes(patchedBlocks);

        assertEquals(origHash, patchedHash);
        assertEquals(origBlocks, patchedBlocks);
        assertTrue(fileContentsEq(origin, result));


        origin = createFile("0.orig");
        origin = fillFile(origin, 0, 0);
        origBlocks = Patch.blocksHashes(origin.getPath(), Patch.defaultBlockSize);
        origHash = Patch.hashFromBlocksHashes(origBlocks);
        patchInfo = Patch.createPatch(origin.getPath(), patch.getPath(), origHash, origBlocks, patchedHash, patchedBlocks, Patch.defaultBlockSize);

        assertEquals(patchedHash, patchInfo.get("old_hash"));

        patched.delete();
        result.renameTo(patched);

        patchedBlocks = (TreeMap) Patch.acceptPatch(patched.getPath(), result.getPath(), patch.getPath(), patchedHash).get(1);
        patchedHash = Patch.hashFromBlocksHashes(patchedBlocks);

        assertEquals(origHash, patchedHash);
        assertEquals(origBlocks, patchedBlocks);
        assertTrue(fileContentsEq(origin, result));
    }
}
