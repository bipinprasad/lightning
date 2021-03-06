/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The ASF licenses this file to you under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package org.apache.storm.hdfs.spout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.storm.Config;
import org.apache.storm.hdfs.common.HdfsUtils;
import org.apache.storm.hdfs.common.HdfsUtils.Pair;
import org.apache.storm.hdfs.testing.MiniDFSClusterExtensionClassLevel;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

public class TestHdfsSpout {

    private static final Configuration conf = new Configuration();
    @RegisterExtension
    public static final MiniDFSClusterExtensionClassLevel DFS_CLUSTER_EXTENSION = new MiniDFSClusterExtensionClassLevel();
    private static DistributedFileSystem fs;
    @TempDir
    public File tempFolder;
    public File baseFolder;
    private Path source;
    private Path archive;
    private Path badfiles;

    @BeforeAll
    public static void setupClass() throws IOException {
        fs = DFS_CLUSTER_EXTENSION.getDfscluster().getFileSystem();
    }

    @AfterAll
    public static void teardownClass() throws IOException {
        fs.close();
    }

    private static <T> T getField(HdfsSpout spout, String fieldName) throws NoSuchFieldException, IllegalAccessException {
        Field readerFld = HdfsSpout.class.getDeclaredField(fieldName);
        readerFld.setAccessible(true);
        return (T) readerFld.get(spout);
    }

    private static boolean getBoolField(HdfsSpout spout, String fieldName) throws NoSuchFieldException, IllegalAccessException {
        Field readerFld = HdfsSpout.class.getDeclaredField(fieldName);
        readerFld.setAccessible(true);
        return readerFld.getBoolean(spout);
    }

    private static List<String> readTextFile(FileSystem fs, String f) throws IOException {
        Path file = new Path(f);
        FSDataInputStream x = fs.open(file);
        BufferedReader reader = new BufferedReader(new InputStreamReader(x));
        String line = null;
        ArrayList<String> result = new ArrayList<>();
        while ((line = reader.readLine()) != null) {
            result.add(line);
        }
        return result;
    }

    private static void createSeqFile(FileSystem fs, Path file, int rowCount) throws IOException {

        Configuration conf = new Configuration();
        try {
            if (fs.exists(file)) {
                fs.delete(file, false);
            }

            SequenceFile.Writer w = SequenceFile.createWriter(fs, conf, file, IntWritable.class, Text.class);
            for (int i = 0; i < rowCount; i++) {
                w.append(new IntWritable(i), new Text("line " + i));
            }
            w.close();
            System.out.println("done");
        } catch (IOException e) {
            e.printStackTrace();

        }
    }

    @BeforeEach
    public void setup() throws Exception {
        baseFolder = new File(tempFolder, "hdfsspout");
        baseFolder.mkdir();
        source = new Path(baseFolder.toString() + "/source");
        fs.mkdirs(source);
        archive = new Path(baseFolder.toString() + "/archive");
        fs.mkdirs(archive);
        badfiles = new Path(baseFolder.toString() + "/bad");
        fs.mkdirs(badfiles);
    }

    @AfterEach
    public void shutDown() throws IOException {
        fs.delete(new Path(baseFolder.toString()), true);
    }

    @Test
    public void testSimpleText_noACK() throws Exception {
        Path file1 = new Path(source.toString() + "/file1.txt");
        createTextFile(file1, 5);

        Path file2 = new Path(source.toString() + "/file2.txt");
        createTextFile(file2, 5);

        try (AutoCloseableHdfsSpout closeableSpout = makeSpout(Configs.TEXT, TextFileReader.defaultFields)) {
            HdfsSpout spout = closeableSpout.spout;
            spout.setCommitFrequencyCount(1);
            spout.setCommitFrequencySec(1);

            Map<String, Object> conf = getCommonConfigs();
            openSpout(spout, 0, conf);

            runSpout(spout, "r11");

            Path arc1 = new Path(archive.toString() + "/file1.txt");
            Path arc2 = new Path(archive.toString() + "/file2.txt");
            checkCollectorOutput_txt((MockCollector) spout.getCollector(), arc1, arc2);
        }
    }

    @Test
    public void testSimpleText_ACK() throws Exception {
        Path file1 = new Path(source.toString() + "/file1.txt");
        createTextFile(file1, 5);

        Path file2 = new Path(source.toString() + "/file2.txt");
        createTextFile(file2, 5);

        try (AutoCloseableHdfsSpout closeableSpout = makeSpout(Configs.TEXT, TextFileReader.defaultFields)) {
            HdfsSpout spout = closeableSpout.spout;
            spout.setCommitFrequencyCount(1);
            spout.setCommitFrequencySec(1);

            Map<String, Object> conf = getCommonConfigs();
            conf.put(Config.TOPOLOGY_ACKER_EXECUTORS, "1"); // enable ACKing
            openSpout(spout, 0, conf);

            // consume file 1
            runSpout(spout, "r6", "a0", "a1", "a2", "a3", "a4");
            Path arc1 = new Path(archive.toString() + "/file1.txt");
            checkCollectorOutput_txt((MockCollector) spout.getCollector(), arc1);

            // consume file 2
            runSpout(spout, "r6", "a5", "a6", "a7", "a8", "a9");
            Path arc2 = new Path(archive.toString() + "/file2.txt");
            checkCollectorOutput_txt((MockCollector) spout.getCollector(), arc1, arc2);
        }
    }

    @Test
    public void testEmptySimpleText_ACK() throws Exception {
        Path file1 = new Path(source.toString() + "/file_empty.txt");
        createTextFile(file1, 0);

        //Ensure the second file has a later modified timestamp, as the spout should pick the first file first.
        Thread.sleep(2);

        Path file2 = new Path(source.toString() + "/file.txt");
        createTextFile(file2, 5);

        try (AutoCloseableHdfsSpout closeableSpout = makeSpout(Configs.TEXT, TextFileReader.defaultFields)) {
            HdfsSpout spout = closeableSpout.spout;
            spout.setCommitFrequencyCount(1);

            Map<String, Object> conf = getCommonConfigs();
            conf.put(Config.TOPOLOGY_ACKER_EXECUTORS, "1"); // enable ACKing
            openSpout(spout, 0, conf);

            // Read once. Since the first file is empty, the spout should continue with file 2
            runSpout(spout, "r6", "a0", "a1", "a2", "a3", "a4");
            //File 1 should be moved to archive
            assertThat(fs.isFile(new Path(archive.toString() + "/file_empty.txt")), is(true));
            //File 2 should be read
            Path arc2 = new Path(archive.toString() + "/file.txt");
            checkCollectorOutput_txt((MockCollector) spout.getCollector(), arc2);
        }
    }

    @Test
    public void testResumeAbandoned_Text_NoAck() throws Exception {
        Path file1 = new Path(source.toString() + "/file1.txt");
        createTextFile(file1, 6);

        final Integer lockExpirySec = 1;

        try (AutoCloseableHdfsSpout closeableSpout = makeSpout(Configs.TEXT, TextFileReader.defaultFields)) {
            HdfsSpout spout = closeableSpout.spout;
            spout.setCommitFrequencyCount(1);
            spout.setCommitFrequencySec(1000);  // effectively disable commits based on time
            spout.setLockTimeoutSec(lockExpirySec);

            try (AutoCloseableHdfsSpout closeableSpout2 = makeSpout(Configs.TEXT, TextFileReader.defaultFields)) {
                HdfsSpout spout2 = closeableSpout2.spout;
                spout2.setCommitFrequencyCount(1);
                spout2.setCommitFrequencySec(1000);  // effectively disable commits based on time
                spout2.setLockTimeoutSec(lockExpirySec);

                Map<String, Object> conf = getCommonConfigs();
                openSpout(spout, 0, conf);
                openSpout(spout2, 1, conf);

                // consume file 1 partially
                List<String> res = runSpout(spout, "r2");
                assertEquals(2, res.size());

                // abandon file
                FileLock lock = getField(spout, "lock");
                TestFileLock.closeUnderlyingLockFile(lock);
                Thread.sleep(lockExpirySec * 2 * 1000);

                // check lock file presence
                assertTrue(fs.exists(lock.getLockFile()));

                // create another spout to take over processing and read a few lines
                List<String> res2 = runSpout(spout2, "r3");
                assertEquals(3, res2.size());

                // check lock file presence
                assertTrue(fs.exists(lock.getLockFile()));

                // check lock file contents
                List<String> contents = readTextFile(fs, lock.getLockFile().toString());
                assertFalse(contents.isEmpty());

                // finish up reading the file
                res2 = runSpout(spout2, "r2");
                assertEquals(4, res2.size());

                // check lock file is gone
                assertFalse(fs.exists(lock.getLockFile()));
                FileReader rdr = getField(spout2, "reader");
                assertNull(rdr);
                assertTrue(getBoolField(spout2, "fileReadCompletely"));
            }
        }
    }

    @Test
    public void testResumeAbandoned_Seq_NoAck() throws Exception {
        Path file1 = new Path(source.toString() + "/file1.seq");
        createSeqFile(fs, file1, 6);

        final Integer lockExpirySec = 1;

        try (AutoCloseableHdfsSpout closeableSpout = makeSpout(Configs.SEQ, SequenceFileReader.defaultFields)) {
            HdfsSpout spout = closeableSpout.spout;
            spout.setCommitFrequencyCount(1);
            spout.setCommitFrequencySec(1000); // effectively disable commits based on time
            spout.setLockTimeoutSec(lockExpirySec);

            try (AutoCloseableHdfsSpout closeableSpout2 = makeSpout(Configs.SEQ, SequenceFileReader.defaultFields)) {
                HdfsSpout spout2 = closeableSpout2.spout;
                spout2.setCommitFrequencyCount(1);
                spout2.setCommitFrequencySec(1000); // effectively disable commits based on time
                spout2.setLockTimeoutSec(lockExpirySec);

                Map<String, Object> conf = getCommonConfigs();
                openSpout(spout, 0, conf);
                openSpout(spout2, 1, conf);

                // consume file 1 partially
                List<String> res = runSpout(spout, "r2");
                assertEquals(2, res.size());
                // abandon file
                FileLock lock = getField(spout, "lock");
                TestFileLock.closeUnderlyingLockFile(lock);
                Thread.sleep(lockExpirySec * 2 * 1000);

                // check lock file presence
                assertTrue(fs.exists(lock.getLockFile()));

                // create another spout to take over processing and read a few lines
                List<String> res2 = runSpout(spout2, "r3");
                assertEquals(3, res2.size());

                // check lock file presence
                assertTrue(fs.exists(lock.getLockFile()));

                // check lock file contents
                List<String> contents = getTextFileContents(fs, lock.getLockFile());
                assertFalse(contents.isEmpty());

                // finish up reading the file
                res2 = runSpout(spout2, "r3");
                assertEquals(4, res2.size());

                // check lock file is gone
                assertFalse(fs.exists(lock.getLockFile()));
                FileReader rdr = getField(spout2, "reader");
                assertNull(rdr);
                assertTrue(getBoolField(spout2, "fileReadCompletely"));
            }
        }
    }

    private void checkCollectorOutput_txt(MockCollector collector, Path... txtFiles) throws IOException {
        ArrayList<String> expected = new ArrayList<>();
        for (Path txtFile : txtFiles) {
            List<String> lines = getTextFileContents(fs, txtFile);
            expected.addAll(lines);
        }

        List<String> actual = new ArrayList<>();
        for (Pair<HdfsSpout.MessageId, List<Object>> item : collector.items) {
            actual.add(item.getValue().get(0).toString());
        }
        assertEquals(expected, actual);
    }

    private List<String> getTextFileContents(FileSystem fs, Path txtFile) throws IOException {
        ArrayList<String> result = new ArrayList<>();
        FSDataInputStream istream = fs.open(txtFile);
        InputStreamReader isreader = new InputStreamReader(istream, "UTF-8");
        BufferedReader reader = new BufferedReader(isreader);

        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            result.add(line);
        }
        isreader.close();
        return result;
    }

    private void checkCollectorOutput_seq(MockCollector collector, Path... seqFiles) throws IOException {
        ArrayList<String> expected = new ArrayList<>();
        for (Path seqFile : seqFiles) {
            List<String> lines = getSeqFileContents(fs, seqFile);
            expected.addAll(lines);
        }
        assertTrue(expected.equals(collector.lines));
    }

    private List<String> getSeqFileContents(FileSystem fs, Path... seqFiles) throws IOException {
        ArrayList<String> result = new ArrayList<>();

        for (Path seqFile : seqFiles) {
            Path file = new Path(fs.getUri().toString() + seqFile.toString());
            SequenceFile.Reader reader = new SequenceFile.Reader(conf, SequenceFile.Reader.file(file));
            try {
                Writable key = (Writable) ReflectionUtils.newInstance(reader.getKeyClass(), conf);
                Writable value = (Writable) ReflectionUtils.newInstance(reader.getValueClass(), conf);
                while (reader.next(key, value)) {
                    String keyValStr = Arrays.asList(key, value).toString();
                    result.add(keyValStr);
                }
            } finally {
                reader.close();
            }
        }// for
        return result;
    }

    private List<String> listDir(Path p) throws IOException {
        ArrayList<String> result = new ArrayList<>();
        RemoteIterator<LocatedFileStatus> fileNames = fs.listFiles(p, false);
        while (fileNames.hasNext()) {
            LocatedFileStatus fileStatus = fileNames.next();
            result.add(Path.getPathWithoutSchemeAndAuthority(fileStatus.getPath()).toString());
        }
        return result;
    }

    @Test
    public void testMultipleFileConsumption_Ack() throws Exception {
        Path file1 = new Path(source.toString() + "/file1.txt");
        createTextFile(file1, 5);

        try (AutoCloseableHdfsSpout closeableSpout = makeSpout(Configs.TEXT, TextFileReader.defaultFields)) {
            HdfsSpout spout = closeableSpout.spout;
            spout.setCommitFrequencyCount(1);
            spout.setCommitFrequencySec(1);

            Map<String, Object> conf = getCommonConfigs();
            conf.put(Config.TOPOLOGY_ACKER_EXECUTORS, "1"); // enable ACKing
            openSpout(spout, 0, conf);

            // read few lines from file1 dont ack
            runSpout(spout, "r3");
            FileReader reader = getField(spout, "reader");
            assertNotNull(reader);
            assertFalse(getBoolField(spout, "fileReadCompletely"));

            // read remaining lines
            runSpout(spout, "r3");
            reader = getField(spout, "reader");
            assertNotNull(reader);
            assertTrue(getBoolField(spout, "fileReadCompletely"));

            // ack few
            runSpout(spout, "a0", "a1", "a2");
            reader = getField(spout, "reader");
            assertNotNull(reader);
            assertTrue(getBoolField(spout, "fileReadCompletely"));

            //ack rest
            runSpout(spout, "a3", "a4");
            reader = getField(spout, "reader");
            assertNull(reader);
            assertTrue(getBoolField(spout, "fileReadCompletely"));

            // go to next file
            Path file2 = new Path(source.toString() + "/file2.txt");
            createTextFile(file2, 5);

            // Read 1 line
            runSpout(spout, "r1");
            assertNotNull(getField(spout, "reader"));
            assertFalse(getBoolField(spout, "fileReadCompletely"));

            // ack 1 tuple
            runSpout(spout, "a5");
            assertNotNull(getField(spout, "reader"));
            assertFalse(getBoolField(spout, "fileReadCompletely"));

            // read and ack remaining lines
            runSpout(spout, "r5", "a6", "a7", "a8", "a9");
            assertNull(getField(spout, "reader"));
            assertTrue(getBoolField(spout, "fileReadCompletely"));
        }
    }

    @Test
    public void testSimpleSequenceFile() throws Exception {
        //1) create a couple files to consume
        source = new Path("/tmp/hdfsspout/source");
        fs.mkdirs(source);
        archive = new Path("/tmp/hdfsspout/archive");
        fs.mkdirs(archive);

        Path file1 = new Path(source + "/file1.seq");
        createSeqFile(fs, file1, 5);

        Path file2 = new Path(source + "/file2.seq");
        createSeqFile(fs, file2, 5);

        try (AutoCloseableHdfsSpout closeableSpout = makeSpout(Configs.SEQ, SequenceFileReader.defaultFields)) {
            HdfsSpout spout = closeableSpout.spout;
            Map<String, Object> conf = getCommonConfigs();
            openSpout(spout, 0, conf);

            // consume both files
            List<String> res = runSpout(spout, "r11");
            assertEquals(10, res.size());

            assertEquals(2, listDir(archive).size());

            Path f1 = new Path(archive + "/file1.seq");
            Path f2 = new Path(archive + "/file2.seq");

            checkCollectorOutput_seq((MockCollector) spout.getCollector(), f1, f2);
        }
    }

    @Test
    public void testReadFailures() throws Exception {
        // 1) create couple of input files to read
        Path file1 = new Path(source.toString() + "/file1.txt");
        Path file2 = new Path(source.toString() + "/file2.txt");

        createTextFile(file1, 6);
        createTextFile(file2, 7);
        assertEquals(2, listDir(source).size());

        // 2) run spout
        try (
            AutoCloseableHdfsSpout closeableSpout = makeSpout(MockTextFailingReader.class.getName(), MockTextFailingReader.defaultFields)) {
            HdfsSpout spout = closeableSpout.spout;
            Map<String, Object> conf = getCommonConfigs();
            openSpout(spout, 0, conf);

            List<String> res = runSpout(spout, "r11");
            String[] expected = new String[]{ "[line 0]", "[line 1]", "[line 2]", "[line 0]", "[line 1]", "[line 2]" };
            assertArrayEquals(expected, res.toArray());

            // 3) make sure 6 lines (3 from each file) were read in all
            assertEquals(((MockCollector) spout.getCollector()).lines.size(), 6);
            ArrayList<Path> badFiles = HdfsUtils.listFilesByModificationTime(fs, badfiles, 0);
            assertEquals(badFiles.size(), 2);
        }
    }

    // check lock creation/deletion and contents
    @Test
    public void testLocking() throws Exception {
        Path file1 = new Path(source.toString() + "/file1.txt");
        createTextFile(file1, 10);

        // 0) config spout to log progress in lock file for each tuple
        try (AutoCloseableHdfsSpout closeableSpout = makeSpout(Configs.TEXT, TextFileReader.defaultFields)) {
            HdfsSpout spout = closeableSpout.spout;
            spout.setCommitFrequencyCount(1);
            spout.setCommitFrequencySec(1000);  // effectively disable commits based on time

            Map<String, Object> conf = getCommonConfigs();
            openSpout(spout, 0, conf);

            // 1) read initial lines in file, then check if lock exists
            List<String> res = runSpout(spout, "r5");
            assertEquals(5, res.size());
            List<String> lockFiles = listDir(spout.getLockDirPath());
            assertEquals(1, lockFiles.size());

            // 2) check log file content line count == tuples emitted + 1
            List<String> lines = readTextFile(fs, lockFiles.get(0));
            assertEquals(lines.size(), res.size() + 1);

            // 3) read remaining lines in file, then ensure lock is gone
            runSpout(spout, "r6");
            lockFiles = listDir(spout.getLockDirPath());
            assertEquals(0, lockFiles.size());

            // 4)  --- Create another input file and reverify same behavior ---
            Path file2 = new Path(source.toString() + "/file2.txt");
            createTextFile(file2, 10);

            // 5) read initial lines in file, then check if lock exists
            res = runSpout(spout, "r5");
            assertEquals(15, res.size());
            lockFiles = listDir(spout.getLockDirPath());
            assertEquals(1, lockFiles.size());

            // 6) check log file content line count == tuples emitted + 1
            lines = readTextFile(fs, lockFiles.get(0));
            assertEquals(6, lines.size());

            // 7) read remaining lines in file, then ensure lock is gone
            runSpout(spout, "r6");
            lockFiles = listDir(spout.getLockDirPath());
            assertEquals(0, lockFiles.size());
        }
    }

    @Test
    public void testLockLoggingFreqCount() throws Exception {
        Path file1 = new Path(source.toString() + "/file1.txt");
        createTextFile(file1, 10);

        // 0) config spout to log progress in lock file for each tuple
        try (AutoCloseableHdfsSpout closeableSpout = makeSpout(Configs.TEXT, TextFileReader.defaultFields)) {
            HdfsSpout spout = closeableSpout.spout;
            spout.setCommitFrequencyCount(2);   // 1 lock log entry every 2 tuples
            spout.setCommitFrequencySec(1000);  // Effectively disable commits based on time

            Map<String, Object> conf = getCommonConfigs();
            openSpout(spout, 0, conf);

            // 1) read 5 lines in file,
            runSpout(spout, "r5");

            // 2) check log file contents
            String lockFile = listDir(spout.getLockDirPath()).get(0);
            List<String> lines = readTextFile(fs, lockFile);
            assertEquals(lines.size(), 3);

            // 3) read 6th line and see if another log entry was made
            runSpout(spout, "r1");
            lines = readTextFile(fs, lockFile);
            assertEquals(lines.size(), 4);
        }
    }

    @Test
    public void testLockLoggingFreqSec() throws Exception {
        Path file1 = new Path(source.toString() + "/file1.txt");
        createTextFile(file1, 10);

        // 0) config spout to log progress in lock file for each tuple
        try (AutoCloseableHdfsSpout closeableSpout = makeSpout(Configs.TEXT, TextFileReader.defaultFields)) {
            HdfsSpout spout = closeableSpout.spout;
            spout.setCommitFrequencyCount(0); // disable it
            spout.setCommitFrequencySec(2);   // log every 2 sec

            Map<String, Object> conf = getCommonConfigs();
            openSpout(spout, 0, conf);

            // 1) read 5 lines in file
            runSpout(spout, "r5");

            // 2) check log file contents
            String lockFile = listDir(spout.getLockDirPath()).get(0);
            List<String> lines = readTextFile(fs, lockFile);
            assertEquals(lines.size(), 1);
            Thread.sleep(3000); // allow freq_sec to expire

            // 3) read another line and see if another log entry was made
            runSpout(spout, "r1");
            lines = readTextFile(fs, lockFile);
            assertEquals(2, lines.size());
        }
    }

    private Map<String, Object> getCommonConfigs() {
        Map<String, Object> topoConf = new HashMap<>();
        topoConf.put(Config.TOPOLOGY_ACKER_EXECUTORS, "0");
        return topoConf;
    }

    private AutoCloseableHdfsSpout makeSpout(String readerType, String[] outputFields) {
        HdfsSpout spout = new HdfsSpout().withOutputFields(outputFields)
                                         .setReaderType(readerType)
                                         .setHdfsUri(DFS_CLUSTER_EXTENSION.getDfscluster().getURI().toString())
                                         .setSourceDir(source.toString())
                                         .setArchiveDir(archive.toString())
                                         .setBadFilesDir(badfiles.toString());

        return new AutoCloseableHdfsSpout(spout);
    }

    private void openSpout(HdfsSpout spout, int spoutId, Map<String, Object> topoConf) {
        MockCollector collector = new MockCollector();
        spout.open(topoConf, new MockTopologyContext(spoutId, topoConf), collector);
    }

    /**
     * Execute a sequence of calls on HdfsSpout.
     *
     * @param cmds: set of commands to run, e.g. "r,r,r,r,a1,f2,...". The commands are: r[N] - receive() called N times aN - ack, item
     * number: N fN - fail, item number: N
     */
    private List<String> runSpout(HdfsSpout spout, String... cmds) {
        MockCollector collector = (MockCollector) spout.getCollector();
        for (String cmd : cmds) {
            if (cmd.startsWith("r")) {
                int count = 1;
                if (cmd.length() > 1) {
                    count = Integer.parseInt(cmd.substring(1));
                }
                for (int i = 0; i < count; ++i) {
                    spout.nextTuple();
                }
            } else if (cmd.startsWith("a")) {
                int n = Integer.parseInt(cmd.substring(1));
                Pair<HdfsSpout.MessageId, List<Object>> item = collector.items.get(n);
                spout.ack(item.getKey());
            } else if (cmd.startsWith("f")) {
                int n = Integer.parseInt(cmd.substring(1));
                Pair<HdfsSpout.MessageId, List<Object>> item = collector.items.get(n);
                spout.fail(item.getKey());
            }
        }
        return collector.lines;
    }

    private void createTextFile(Path file, int lineCount) throws IOException {
        FSDataOutputStream os = fs.create(file);
        for (int i = 0; i < lineCount; i++) {
            os.writeBytes("line " + i + System.lineSeparator());
        }
        os.close();
    }

    private static class AutoCloseableHdfsSpout implements AutoCloseable {

        private final HdfsSpout spout;

        public AutoCloseableHdfsSpout(HdfsSpout spout) {
            this.spout = spout;
        }

        @Override
        public void close() throws Exception {
            spout.close();
        }
    }

    static class MockCollector extends SpoutOutputCollector {
        //comma separated offsets

        public ArrayList<String> lines;
        public ArrayList<Pair<HdfsSpout.MessageId, List<Object>>> items;

        public MockCollector() {
            super(null);
            lines = new ArrayList<>();
            items = new ArrayList<>();
        }

        @Override
        public List<Integer> emit(List<Object> tuple, Object messageId) {
            lines.add(tuple.toString());
            items.add(HdfsUtils.Pair.of(messageId, tuple));
            return null;
        }

        @Override
        public List<Integer> emit(String streamId, List<Object> tuple, Object messageId) {
            return emit(tuple, messageId);
        }

        @Override
        public void emitDirect(int arg0, String arg1, List<Object> arg2, Object arg3) {
            throw new UnsupportedOperationException("NOT Implemented");
        }

        @Override
        public void reportError(Throwable arg0) {
            throw new UnsupportedOperationException("NOT Implemented");
        }

        @Override
        public long getPendingCount() {
            return 0;
        }
    } // class MockCollector

    // Throws IOExceptions for 3rd & 4th call to next(), succeeds on 5th, thereafter
    // throws ParseException. Effectively produces 3 lines (1,2 & 3) from each file read
    static class MockTextFailingReader extends TextFileReader {

        public static final String[] defaultFields = { "line" };
        int readAttempts = 0;

        public MockTextFailingReader(FileSystem fs, Path file, Map<String, Object> conf) throws IOException {
            super(fs, file, conf);
        }

        @Override
        public List<Object> next() throws IOException, ParseException {
            readAttempts++;
            if (readAttempts == 3 || readAttempts == 4) {
                throw new IOException("mock test exception");
            } else if (readAttempts > 5) {
                throw new ParseException("mock test exception", null);
            }
            return super.next();
        }
    }

    static class MockTopologyContext extends TopologyContext {

        private final int componentId;

        public MockTopologyContext(int componentId, Map<String, Object> topoConf) {
            super(null, topoConf, null, null, null, null, null, null, null, 0, 0, null, null, null, null, null, null, null);
            this.componentId = componentId;
        }

        @Override
        public String getThisComponentId() {
            return Integer.toString(componentId);
        }

    }

}
