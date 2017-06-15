/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.tools.offlineImageViewer;

import static org.apache.hadoop.fs.permission.AclEntryScope.ACCESS;
import static org.apache.hadoop.fs.permission.AclEntryType.GROUP;
import static org.apache.hadoop.fs.permission.AclEntryType.OTHER;
import static org.apache.hadoop.fs.permission.AclEntryType.USER;
import static org.apache.hadoop.fs.permission.FsAction.ALL;
import static org.apache.hadoop.fs.permission.FsAction.EXECUTE;
import static org.apache.hadoop.fs.permission.FsAction.READ_EXECUTE;
import static org.apache.hadoop.hdfs.server.namenode.AclTestHelpers.aclEntry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileSystemTestHelper;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.server.namenode.FSImageTestUtil;
import org.apache.hadoop.hdfs.server.namenode.NameNodeLayoutVersion;
import org.apache.hadoop.hdfs.web.WebHdfsFileSystem;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.log4j.Level;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class TestOfflineImageViewer {
  private static final Log LOG = LogFactory.getLog(OfflineImageViewerPB.class);
  private static final int NUM_DIRS = 3;
  private static final int FILES_PER_DIR = 4;
  private static final String TEST_RENEWER = "JobTracker";
  private static File originalFsimage = null;

  // namespace as written to dfs, to be compared with viewer's output
  final static HashMap<String, FileStatus> writtenFiles = Maps.newHashMap();
  static int dirCount = 0;

  private static File tempDir;

  // Create a populated namespace for later testing. Save its contents to a
  // data structure and store its fsimage location.
  // We only want to generate the fsimage file once and use it for
  // multiple tests.
  @BeforeClass
  public static void createOriginalFSImage() throws IOException {
    File[] nnDirs = MiniDFSCluster.getNameNodeDirectory(
        MiniDFSCluster.getBaseDirectory(), 0, 0);
    tempDir = nnDirs[0];

    MiniDFSCluster cluster = null;
    try {
      Configuration conf = new Configuration();
      conf.setLong(
          DFSConfigKeys.DFS_NAMENODE_DELEGATION_TOKEN_MAX_LIFETIME_KEY, 10000);
      conf.setLong(
          DFSConfigKeys.DFS_NAMENODE_DELEGATION_TOKEN_RENEW_INTERVAL_KEY, 5000);
      conf.setBoolean(
          DFSConfigKeys.DFS_NAMENODE_DELEGATION_TOKEN_ALWAYS_USE_KEY, true);
      conf.setBoolean(DFSConfigKeys.DFS_NAMENODE_ACLS_ENABLED_KEY, true);
      conf.set(CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTH_TO_LOCAL,
          "RULE:[2:$1@$0](JobTracker@.*FOO.COM)s/@.*//" + "DEFAULT");
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
      cluster.waitActive();
      DistributedFileSystem hdfs = cluster.getFileSystem();

      // Create a reasonable namespace
      for (int i = 0; i < NUM_DIRS; i++, dirCount++) {
        Path dir = new Path("/dir" + i);
        hdfs.mkdirs(dir);
        writtenFiles.put(dir.toString(), pathToFileEntry(hdfs, dir.toString()));
        for (int j = 0; j < FILES_PER_DIR; j++) {
          Path file = new Path(dir, "file" + j);
          FSDataOutputStream o = hdfs.create(file);
          o.write(23);
          o.close();

          writtenFiles.put(file.toString(),
              pathToFileEntry(hdfs, file.toString()));
        }
      }

      // Create an empty directory
      Path emptydir = new Path("/emptydir");
      hdfs.mkdirs(emptydir);
      dirCount++;
      writtenFiles.put(emptydir.toString(), hdfs.getFileStatus(emptydir));

      //Create a directory whose name should be escaped in XML
      Path invalidXMLDir = new Path("/dirContainingInvalidXMLChar\u0000here");
      hdfs.mkdirs(invalidXMLDir);
      dirCount++;

      //Create a directory with sticky bits
      Path stickyBitDir = new Path("/stickyBit");
      hdfs.mkdirs(stickyBitDir);
      hdfs.setPermission(stickyBitDir, new FsPermission(FsAction.ALL,
          FsAction.ALL, FsAction.ALL, true));
      dirCount++;
      writtenFiles.put(stickyBitDir.toString(),
          hdfs.getFileStatus(stickyBitDir));

      // Get delegation tokens so we log the delegation token op
      Token<?>[] delegationTokens = hdfs
          .addDelegationTokens(TEST_RENEWER, null);
      for (Token<?> t : delegationTokens) {
        LOG.debug("got token " + t);
      }

      // Create INodeReference
      final Path src = new Path("/src");
      hdfs.mkdirs(src);
      dirCount++;
      writtenFiles.put(src.toString(), hdfs.getFileStatus(src));

      // Create snapshot and snapshotDiff.
      final Path orig = new Path("/src/orig");
      hdfs.mkdirs(orig);
      final Path file1 = new Path("/src/file");
      FSDataOutputStream o = hdfs.create(file1);
      o.write(23);
      o.write(45);
      o.close();
      hdfs.allowSnapshot(src);
      hdfs.createSnapshot(src, "snapshot");
      final Path dst = new Path("/dst");
      // Rename a directory in the snapshot directory to add snapshotCopy
      // field to the dirDiff entry.
      hdfs.rename(orig, dst);
      dirCount++;
      writtenFiles.put(dst.toString(), hdfs.getFileStatus(dst));
      // Truncate a file in the snapshot directory to add snapshotCopy and
      // blocks fields to the fileDiff entry.
      hdfs.truncate(file1, 1);
      writtenFiles.put(file1.toString(), hdfs.getFileStatus(file1));

      // Set XAttrs so the fsimage contains XAttr ops
      final Path xattr = new Path("/xattr");
      hdfs.mkdirs(xattr);
      dirCount++;
      hdfs.setXAttr(xattr, "user.a1", new byte[]{ 0x31, 0x32, 0x33 });
      hdfs.setXAttr(xattr, "user.a2", new byte[]{ 0x37, 0x38, 0x39 });
      // OIV should be able to handle empty value XAttrs
      hdfs.setXAttr(xattr, "user.a3", null);
      // OIV should be able to handle XAttr values that can't be expressed
      // as UTF8
      hdfs.setXAttr(xattr, "user.a4", new byte[]{ -0x3d, 0x28 });
      writtenFiles.put(xattr.toString(), hdfs.getFileStatus(xattr));
      // Set ACLs
      hdfs.setAcl(
          xattr,
          Lists.newArrayList(aclEntry(ACCESS, USER, ALL),
              aclEntry(ACCESS, USER, "foo", ALL),
              aclEntry(ACCESS, GROUP, READ_EXECUTE),
              aclEntry(ACCESS, GROUP, "bar", READ_EXECUTE),
              aclEntry(ACCESS, OTHER, EXECUTE)));

      // Write results to the fsimage file
      hdfs.setSafeMode(SafeModeAction.SAFEMODE_ENTER, false);
      hdfs.saveNamespace();

      // Determine location of fsimage file
      originalFsimage = FSImageTestUtil.findLatestImageFile(FSImageTestUtil
          .getFSImage(cluster.getNameNode()).getStorage().getStorageDir(0));
      if (originalFsimage == null) {
        throw new RuntimeException("Didn't generate or can't find fsimage");
      }
      LOG.debug("original FS image file is " + originalFsimage);
    } finally {
      if (cluster != null)
        cluster.shutdown();
    }
  }

  @AfterClass
  public static void deleteOriginalFSImage() throws IOException {
    FileUtils.deleteQuietly(tempDir);
    if (originalFsimage != null && originalFsimage.exists()) {
      originalFsimage.delete();
    }
  }

  // Convenience method to generate a file status from file system for
  // later comparison
  private static FileStatus pathToFileEntry(FileSystem hdfs, String file)
      throws IOException {
    return hdfs.getFileStatus(new Path(file));
  }

  @Test(expected = IOException.class)
  public void testTruncatedFSImage() throws IOException {
    File truncatedFile = new File(tempDir, "truncatedFsImage");
    PrintStream output = new PrintStream(NullOutputStream.NULL_OUTPUT_STREAM);
    copyPartOfFile(originalFsimage, truncatedFile);
    new FileDistributionCalculator(new Configuration(), 0, 0, false, output)
        .visit(new RandomAccessFile(truncatedFile, "r"));
  }

  private void copyPartOfFile(File src, File dest) throws IOException {
    FileInputStream in = null;
    FileOutputStream out = null;
    final int MAX_BYTES = 700;
    try {
      in = new FileInputStream(src);
      out = new FileOutputStream(dest);
      in.getChannel().transferTo(0, MAX_BYTES, out.getChannel());
    } finally {
      IOUtils.cleanup(null, in);
      IOUtils.cleanup(null, out);
    }
  }

  @Test
  public void testFileDistributionCalculator() throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream o = new PrintStream(output);
    new FileDistributionCalculator(new Configuration(), 0, 0, false, o)
        .visit(new RandomAccessFile(originalFsimage, "r"));
    o.close();

    String outputString = output.toString();
    Pattern p = Pattern.compile("totalFiles = (\\d+)\n");
    Matcher matcher = p.matcher(outputString);
    assertTrue(matcher.find() && matcher.groupCount() == 1);
    int totalFiles = Integer.parseInt(matcher.group(1));
    assertEquals(NUM_DIRS * FILES_PER_DIR + 1, totalFiles);

    p = Pattern.compile("totalDirectories = (\\d+)\n");
    matcher = p.matcher(outputString);
    assertTrue(matcher.find() && matcher.groupCount() == 1);
    int totalDirs = Integer.parseInt(matcher.group(1));
    // totalDirs includes root directory
    assertEquals(dirCount + 1, totalDirs);

    FileStatus maxFile = Collections.max(writtenFiles.values(),
        new Comparator<FileStatus>() {
      @Override
      public int compare(FileStatus first, FileStatus second) {
        return first.getLen() < second.getLen() ? -1 :
            ((first.getLen() == second.getLen()) ? 0 : 1);
      }
    });
    p = Pattern.compile("maxFileSize = (\\d+)\n");
    matcher = p.matcher(output.toString("UTF-8"));
    assertTrue(matcher.find() && matcher.groupCount() == 1);
    assertEquals(maxFile.getLen(), Long.parseLong(matcher.group(1)));
  }

  @Test
  public void testFileDistributionCalculatorWithOptions() throws Exception {
    int status = OfflineImageViewerPB.run(new String[] {"-i",
        originalFsimage.getAbsolutePath(), "-o", "-", "-p", "FileDistribution",
        "-maxSize", "512", "-step", "8"});
    assertEquals(0, status);
  }

  @Test
  public void testPBImageXmlWriter() throws IOException, SAXException,
      ParserConfigurationException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream o = new PrintStream(output);
    PBImageXmlWriter v = new PBImageXmlWriter(new Configuration(), o);
    v.visit(new RandomAccessFile(originalFsimage, "r"));
    SAXParserFactory spf = SAXParserFactory.newInstance();
    SAXParser parser = spf.newSAXParser();
    final String xml = output.toString();
    parser.parse(new InputSource(new StringReader(xml)), new DefaultHandler());
  }

  @Test
  public void testWebImageViewer() throws Exception {
    WebImageViewer viewer = new WebImageViewer(
        NetUtils.createSocketAddr("localhost:0"));
    try {
      viewer.initServer(originalFsimage.getAbsolutePath());
      int port = viewer.getPort();

      // create a WebHdfsFileSystem instance
      URI uri = new URI("webhdfs://localhost:" + String.valueOf(port));
      Configuration conf = new Configuration();
      WebHdfsFileSystem webhdfs = (WebHdfsFileSystem)FileSystem.get(uri, conf);

      // verify the number of directories
      FileStatus[] statuses = webhdfs.listStatus(new Path("/"));
      assertEquals(dirCount, statuses.length);

      // verify the number of files in the directory
      statuses = webhdfs.listStatus(new Path("/dir0"));
      assertEquals(FILES_PER_DIR, statuses.length);

      // compare a file
      FileStatus status = webhdfs.listStatus(new Path("/dir0/file0"))[0];
      FileStatus expected = writtenFiles.get("/dir0/file0");
      compareFile(expected, status);

      // LISTSTATUS operation to an empty directory
      statuses = webhdfs.listStatus(new Path("/emptydir"));
      assertEquals(0, statuses.length);

      // LISTSTATUS operation to a invalid path
      URL url = new URL("http://localhost:" + port +
                    "/webhdfs/v1/invalid/?op=LISTSTATUS");
      verifyHttpResponseCode(HttpURLConnection.HTTP_NOT_FOUND, url);

      // LISTSTATUS operation to a invalid prefix
      url = new URL("http://localhost:" + port + "/foo");
      verifyHttpResponseCode(HttpURLConnection.HTTP_NOT_FOUND, url);

      // GETFILESTATUS operation
      status = webhdfs.getFileStatus(new Path("/dir0/file0"));
      compareFile(expected, status);

      // GETFILESTATUS operation to a invalid path
      url = new URL("http://localhost:" + port +
                    "/webhdfs/v1/invalid/?op=GETFILESTATUS");
      verifyHttpResponseCode(HttpURLConnection.HTTP_NOT_FOUND, url);

      // invalid operation
      url = new URL("http://localhost:" + port + "/webhdfs/v1/?op=INVALID");
      verifyHttpResponseCode(HttpURLConnection.HTTP_BAD_REQUEST, url);

      // invalid method
      url = new URL("http://localhost:" + port + "/webhdfs/v1/?op=LISTSTATUS");
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("POST");
      connection.connect();
      assertEquals(HttpURLConnection.HTTP_BAD_METHOD,
          connection.getResponseCode());
    } finally {
      // shutdown the viewer
      viewer.close();
    }
  }

  @Test
  public void testPBDelimitedWriter() throws IOException, InterruptedException {
    testPBDelimitedWriter("");  // Test in memory db.
    testPBDelimitedWriter(
        new FileSystemTestHelper().getTestRootDir() + "/delimited.db");
  }

  @Test
  public void testInvalidProcessorOption() throws Exception {
    int status =
        OfflineImageViewerPB.run(new String[] { "-i",
            originalFsimage.getAbsolutePath(), "-o", "-", "-p", "invalid" });
    assertTrue("Exit code returned for invalid processor option is incorrect",
        status != 0);
  }

  @Test
  public void testOfflineImageViewerHelpMessage() throws Throwable {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(bytes);
    final PrintStream oldOut = System.out;
    try {
      System.setOut(out);
      int status = OfflineImageViewerPB.run(new String[] { "-h" });
      assertTrue("Exit code returned for help option is incorrect", status == 0);
      Assert.assertFalse(
          "Invalid Command error displayed when help option is passed.", bytes
              .toString().contains("Error parsing command-line options"));
      status =
          OfflineImageViewerPB.run(new String[] { "-h", "-i",
              originalFsimage.getAbsolutePath(), "-o", "-", "-p",
              "FileDistribution", "-maxSize", "512", "-step", "8" });
      Assert.assertTrue(
          "Exit code returned for help with other option is incorrect",
          status == -1);
    } finally {
      System.setOut(oldOut);
      IOUtils.closeStream(out);
    }
  }
  private void testPBDelimitedWriter(String db)
      throws IOException, InterruptedException {
    final String DELIMITER = "\t";
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    try (PrintStream o = new PrintStream(output)) {
      PBImageDelimitedTextWriter v =
          new PBImageDelimitedTextWriter(o, DELIMITER, db);
      v.visit(new RandomAccessFile(originalFsimage, "r"));
    }

    Set<String> fileNames = new HashSet<>();
    try (
        ByteArrayInputStream input =
            new ByteArrayInputStream(output.toByteArray());
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(input))) {
      String line;
      boolean header = true;
      while ((line = reader.readLine()) != null) {
        System.out.println(line);
        String[] fields = line.split(DELIMITER);
        assertEquals(12, fields.length);
        if (!header) {
          fileNames.add(fields[0]);
        }
        header = false;
      }
    }

    // writtenFiles does not contain root directory and "invalid XML char" dir.
    for (Iterator<String> it = fileNames.iterator(); it.hasNext(); ) {
      String filename = it.next();
      if (filename.startsWith("/dirContainingInvalidXMLChar")) {
        it.remove();
      } else if (filename.equals("/")) {
        it.remove();
      }
    }
    assertEquals(writtenFiles.keySet(), fileNames);
  }

  private static void compareFile(FileStatus expected, FileStatus status) {
    assertEquals(expected.getAccessTime(), status.getAccessTime());
    assertEquals(expected.getBlockSize(), status.getBlockSize());
    assertEquals(expected.getGroup(), status.getGroup());
    assertEquals(expected.getLen(), status.getLen());
    assertEquals(expected.getModificationTime(),
        status.getModificationTime());
    assertEquals(expected.getOwner(), status.getOwner());
    assertEquals(expected.getPermission(), status.getPermission());
    assertEquals(expected.getReplication(), status.getReplication());
    assertEquals(expected.isDirectory(), status.isDirectory());
  }

  private void verifyHttpResponseCode(int expectedCode, URL url)
      throws IOException {
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("GET");
    connection.connect();
    assertEquals(expectedCode, connection.getResponseCode());
  }

  /**
   * Tests the ReverseXML processor.
   *
   * 1. Translate fsimage -> reverseImage.xml
   * 2. Translate reverseImage.xml -> reverseImage
   * 3. Translate reverseImage -> reverse2Image.xml
   * 4. Verify that reverseImage.xml and reverse2Image.xml match
   *
   * @throws Throwable
   */
  @Test
  public void testReverseXmlRoundTrip() throws Throwable {
    GenericTestUtils.setLogLevel(OfflineImageReconstructor.LOG,
        Level.TRACE);
    File reverseImageXml = new File(tempDir, "reverseImage.xml");
    File reverseImage =  new File(tempDir, "reverseImage");
    File reverseImage2Xml =  new File(tempDir, "reverseImage2.xml");
    LOG.info("Creating reverseImage.xml=" + reverseImageXml.getAbsolutePath() +
        ", reverseImage=" + reverseImage.getAbsolutePath() +
        ", reverseImage2Xml=" + reverseImage2Xml.getAbsolutePath());
    if (OfflineImageViewerPB.run(new String[] { "-p", "XML",
         "-i", originalFsimage.getAbsolutePath(),
         "-o", reverseImageXml.getAbsolutePath() }) != 0) {
      throw new IOException("oiv returned failure creating first XML file.");
    }
    if (OfflineImageViewerPB.run(new String[] { "-p", "ReverseXML",
          "-i", reverseImageXml.getAbsolutePath(),
          "-o", reverseImage.getAbsolutePath() }) != 0) {
      throw new IOException("oiv returned failure recreating fsimage file.");
    }
    if (OfflineImageViewerPB.run(new String[] { "-p", "XML",
        "-i", reverseImage.getAbsolutePath(),
        "-o", reverseImage2Xml.getAbsolutePath() }) != 0) {
      throw new IOException("oiv returned failure creating second " +
          "XML file.");
    }
    // The XML file we wrote based on the re-created fsimage should be the
    // same as the one we dumped from the original fsimage.
    Assert.assertEquals("",
      GenericTestUtils.getFilesDiff(reverseImageXml, reverseImage2Xml));
  }

  /**
   * Tests that the ReverseXML processor doesn't accept XML files with the wrong
   * layoutVersion.
   */
  @Test
  public void testReverseXmlWrongLayoutVersion() throws Throwable {
    File imageWrongVersion = new File(tempDir, "imageWrongVersion.xml");
    PrintWriter writer = new PrintWriter(imageWrongVersion, "UTF-8");
    try {
      writer.println("<?xml version=\"1.0\"?>");
      writer.println("<fsimage>");
      writer.println("<version>");
      writer.println(String.format("<layoutVersion>%d</layoutVersion>",
          NameNodeLayoutVersion.CURRENT_LAYOUT_VERSION + 1));
      writer.println("<onDiskVersion>1</onDiskVersion>");
      writer.println("<oivRevision>" +
          "545bbef596c06af1c3c8dca1ce29096a64608478</oivRevision>");
      writer.println("</version>");
      writer.println("</fsimage>");
    } finally {
      writer.close();
    }
    try {
      OfflineImageReconstructor.run(imageWrongVersion.getAbsolutePath(),
          imageWrongVersion.getAbsolutePath() + ".out"); 
      Assert.fail("Expected OfflineImageReconstructor to fail with " +
          "version mismatch.");
    } catch (Throwable t) {
      GenericTestUtils.assertExceptionContains("Layout version mismatch.", t);
    }
  }

  @Test
  public void testFileDistributionCalculatorForException() throws Exception {
    File fsimageFile = null;
    Configuration conf = new Configuration();
    HashMap<String, FileStatus> files = Maps.newHashMap();

    // Create a initial fsimage file
    try (MiniDFSCluster cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(1).build()) {
      cluster.waitActive();
      DistributedFileSystem hdfs = cluster.getFileSystem();

      // Create a reasonable namespace
      Path dir = new Path("/dir");
      hdfs.mkdirs(dir);
      files.put(dir.toString(), pathToFileEntry(hdfs, dir.toString()));
      // Create files with byte size that can't be divided by step size,
      // the byte size for here are 3, 9, 15, 21.
      for (int i = 0; i < FILES_PER_DIR; i++) {
        Path file = new Path(dir, "file" + i);
        DFSTestUtil.createFile(hdfs, file, 6 * i + 3, (short) 1, 0);

        files.put(file.toString(),
            pathToFileEntry(hdfs, file.toString()));
      }

      // Write results to the fsimage file
      hdfs.setSafeMode(SafeModeAction.SAFEMODE_ENTER, false);
      hdfs.saveNamespace();
      // Determine location of fsimage file
      fsimageFile =
          FSImageTestUtil.findLatestImageFile(FSImageTestUtil
              .getFSImage(cluster.getNameNode()).getStorage().getStorageDir(0));
      if (fsimageFile == null) {
        throw new RuntimeException("Didn't generate or can't find fsimage");
      }
    }

    // Run the test with params -maxSize 23 and -step 4, it will not throw
    // ArrayIndexOutOfBoundsException with index 6 when deals with
    // 21 byte size file.
    int status =
        OfflineImageViewerPB.run(new String[] {"-i",
            fsimageFile.getAbsolutePath(), "-o", "-", "-p",
            "FileDistribution", "-maxSize", "23", "-step", "4"});
    assertEquals(0, status);
  }

  @Test
  public void testOfflineImageViewerMaxSizeAndStepOptions() throws Exception {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(bytes);
    final PrintStream oldOut = System.out;
    try {
      System.setOut(out);
      // Add the -h option to make the test only for option parsing,
      // and don't need to do the following operations.
      OfflineImageViewer.main(new String[] {"-i", "-", "-o", "-", "-p",
          "FileDistribution", "-maxSize", "512", "-step", "8", "-h"});
      Assert.assertFalse(bytes.toString().contains(
          "Error parsing command-line options: "));
    } finally {
      System.setOut(oldOut);
      IOUtils.closeStream(out);
    }
  }

  @Test
  public void testOfflineImageViewerWithFormatOption() throws Exception {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(bytes);
    final PrintStream oldOut = System.out;
    try {
      System.setOut(out);
      int status =
          OfflineImageViewerPB.run(new String[] {"-i",
              originalFsimage.getAbsolutePath(), "-o", "-", "-p",
              "FileDistribution", "-maxSize", "512", "-step", "8",
              "-format"});
      assertEquals(0, status);
      Assert.assertTrue(bytes.toString().contains("(0 B, 8 B]"));
    } finally {
      System.setOut(oldOut);
      IOUtils.closeStream(out);
    }
  }
}
