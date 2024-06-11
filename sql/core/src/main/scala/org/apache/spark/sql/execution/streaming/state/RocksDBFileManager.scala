/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.streaming.state

import java.io.{File, FileInputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.{ZipEntry, ZipOutputStream}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.module.scala.{ClassTagExtensions, DefaultScalaModule}
import org.apache.commons.io.{FilenameUtils, IOUtils}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path, PathFilter}
import org.json4s.{Formats, NoTypeHints}
import org.json4s.jackson.Serialization

import org.apache.spark.{SparkConf, SparkEnv}
import org.apache.spark.internal.{Logging, LogKeys, MDC, MessageWithContext}
import org.apache.spark.io.CompressionCodec
import org.apache.spark.sql.errors.QueryExecutionErrors
import org.apache.spark.sql.execution.streaming.CheckpointFileManager
import org.apache.spark.util.ArrayImplicits._
import org.apache.spark.util.Utils

/**
 * Class responsible for syncing RocksDB checkpoint files from local disk to DFS.
 * For each version, checkpoint is saved in specific directory structure that allows successive
 * versions to reuse to SST data files and archived log files. This allows each commit to be
 * incremental, only new SST files and archived log files generated by RocksDB will be uploaded.
 * The directory structures on local disk and in DFS are as follows.
 *
 * Local checkpoint dir structure
 * ------------------------------
 * RocksDB generates a bunch of files in the local checkpoint directory. The most important among
 * them are the SST files; they are the actual log structured data files. Rest of the files contain
 * the metadata necessary for RocksDB to read the SST files and start from the checkpoint.
 * Note that the SST files are hard links to files in the RocksDB's working directory, and therefore
 * successive checkpoints can share some of the SST files. So these SST files have to be copied to
 * DFS in shared directory such that different committed versions can save them.
 *
 * We consider both SST files and archived log files as immutable files which can be shared between
 * different checkpoints.
 *
 *    localCheckpointDir
 *                  |
 *                  +-- OPTIONS-000005
 *                  +-- MANIFEST-000008
 *                  +-- CURRENT
 *                  +-- 00007.sst
 *                  +-- 00011.sst
 *                  +-- archive
 *                  |     +-- 00008.log
 *                  |     +-- 00013.log
 *                  ...
 *
 *
 * DFS directory structure after saving to DFS as version 10
 * -----------------------------------------------------------
 * The SST and archived log files are given unique file names and copied to the shared subdirectory.
 * Every version maintains a mapping of local immutable file name to the unique file name in DFS.
 * This mapping is saved in a JSON file (named `metadata`), which is zipped along with other
 * checkpoint files into a single file `[version].zip`.
 *
 *    dfsRootDir
 *           |
 *           +-- SSTs
 *           |      +-- 00007-[uuid1].sst
 *           |      +-- 00011-[uuid2].sst
 *           +-- logs
 *           |      +-- 00008-[uuid3].log
 *           |      +-- 00013-[uuid4].log
 *           +-- 10.zip
 *           |      +-- metadata         <--- contains mapping between 00007.sst and [uuid1].sst,
 *                                            and the mapping between 00008.log and [uuid3].log
 *           |      +-- OPTIONS-000005
 *           |      +-- MANIFEST-000008
 *           |      +-- CURRENT
 *           |      ...
 *           |
 *           +-- 9.zip
 *           +-- 8.zip
 *           ...
 *
 * Note the following.
 * - Each [version].zip is a complete description of all the data and metadata needed to recover
 *   a RocksDB instance at the corresponding version. The SST files and log files are not included
 *   in the zip files, they can be shared cross different versions. This is unlike the
 *   [version].delta files of HDFSBackedStateStore where previous delta files needs to be read
 *   to be recovered.
 * - This is safe wrt speculatively executed tasks running concurrently in different executors
 *   as each task would upload a different copy of the generated immutable files and
 *   atomically update the [version].zip.
 * - Immutable files are identified uniquely based on their file name and file size.
 * - Immutable files can be reused only across adjacent checkpoints/versions.
 * - This class is thread-safe. Specifically, it is safe to concurrently delete old files from a
 *   different thread than the task thread saving files.
 *
 * @param dfsRootDir  Directory where the [version].zip files will be stored
 * @param localTempDir Local directory for temporary work
 * @param hadoopConf   Hadoop configuration for talking to DFS
 * @param loggingId    Id that will be prepended in logs for isolating concurrent RocksDBs
 */
class RocksDBFileManager(
    dfsRootDir: String,
    localTempDir: File,
    hadoopConf: Configuration,
    codecName: String = CompressionCodec.ZSTD,
    loggingId: String = "")
  extends Logging {

  import RocksDBImmutableFile._

  private val versionToRocksDBFiles = new ConcurrentHashMap[Long, Seq[RocksDBImmutableFile]]

  // used to keep a mapping of the exact Dfs file that was used to create a local SST file.
  // The reason this is a separate map because versionToRocksDBFiles can contain multiple similar
  // SST files to a particular local file (for example 1.sst can map to 1-UUID1.sst in v1 and
  // 1-UUID2.sst in v2). We need to capture the exact file used to ensure Version ID compatibility
  // across SST files and RocksDB manifest.
  private[sql] val localFilesToDfsFiles = new ConcurrentHashMap[String, RocksDBImmutableFile]

  private lazy val fm = CheckpointFileManager.create(new Path(dfsRootDir), hadoopConf)
  private val fs = new Path(dfsRootDir).getFileSystem(hadoopConf)
  private val onlyZipFiles = new PathFilter {
    override def accept(path: Path): Boolean = path.toString.endsWith(".zip")
  }
  private val onlyChangelogFiles = new PathFilter {
    override def accept(path: Path): Boolean = path.toString.endsWith(".changelog")
  }

  private lazy val sparkConf = Option(SparkEnv.get).map(_.conf).getOrElse(new SparkConf)

  private def codec = CompressionCodec.createCodec(sparkConf, codecName)

  @volatile private var rootDirChecked: Boolean = false

  def deepCopy(): RocksDBFileManager = {
    val newFileManager = new RocksDBFileManager(
      dfsRootDir,
      localTempDir,
      hadoopConf,
      codecName,
      loggingId
    )

    newFileManager.versionToRocksDBFiles.putAll(versionToRocksDBFiles)
    newFileManager.localFilesToDfsFiles.putAll(localFilesToDfsFiles)

    newFileManager
  }

  def getChangeLogWriter(
      version: Long,
      useColumnFamilies: Boolean = false): StateStoreChangelogWriter = {
    val changelogFile = dfsChangelogFile(version)
    if (!rootDirChecked) {
      val rootDir = new Path(dfsRootDir)
      if (!fm.exists(rootDir)) fm.mkdirs(rootDir)
      rootDirChecked = true
    }
    val changelogWriter = if (useColumnFamilies) {
      new StateStoreChangelogWriterV2(fm, changelogFile, codec)
    } else {
      new StateStoreChangelogWriterV1(fm, changelogFile, codec)
    }
    changelogWriter
  }

  // Get the changelog file at version
  def getChangelogReader(
      version: Long,
      useColumnFamilies: Boolean = false): StateStoreChangelogReader = {
    val changelogFile = dfsChangelogFile(version)
    if (useColumnFamilies) {
      new StateStoreChangelogReaderV2(fm, changelogFile, codec)
    } else {
      new StateStoreChangelogReaderV1(fm, changelogFile, codec)
    }
  }

  /**
   * Metrics for loading checkpoint from DFS. Every loadCheckpointFromDFS call will update this
   * metrics, so this effectively records the latest metrics.
   */
  @volatile private var loadCheckpointMetrics = RocksDBFileManagerMetrics.EMPTY_METRICS

  /**
   * Metrics for saving checkpoint to DFS. Every saveCheckpointToDFS call will update this
   * metrics, so this effectively records the latest metrics.
   */
  @volatile private var saveCheckpointMetrics = RocksDBFileManagerMetrics.EMPTY_METRICS

  def latestLoadCheckpointMetrics: RocksDBFileManagerMetrics = loadCheckpointMetrics

  def latestSaveCheckpointMetrics: RocksDBFileManagerMetrics = saveCheckpointMetrics

  /** Save all the files in given local checkpoint directory as a committed version in DFS */
  def saveCheckpointToDfs(checkpointDir: File, version: Long, numKeys: Long): Unit = {
    logFilesInDir(checkpointDir, log"Saving checkpoint files " +
      log"for version ${MDC(LogKeys.VERSION_NUM, version)}")
    val (localImmutableFiles, localOtherFiles) = listRocksDBFiles(checkpointDir)
    val rocksDBFiles = saveImmutableFilesToDfs(version, localImmutableFiles)
    val metadata = RocksDBCheckpointMetadata(rocksDBFiles, numKeys)
    val metadataFile = localMetadataFile(checkpointDir)
    metadata.writeToFile(metadataFile)
    logInfo(log"Written metadata for version ${MDC(LogKeys.VERSION_NUM, version)}:\n" +
      log"${MDC(LogKeys.METADATA_JSON, metadata.prettyJson)}")

    if (version <= 1 && numKeys <= 0) {
      // If we're writing the initial version and there's no data, we have to explicitly initialize
      // the root directory. Normally saveImmutableFilesToDfs will do this initialization, but
      // when there's no data that method won't write any files, and zipToDfsFile uses the
      // CheckpointFileManager.createAtomic API which doesn't auto-initialize parent directories.
      // Moreover, once we disable to track the number of keys, in which the numKeys is -1, we
      // still need to create the initial dfs root directory anyway.
      if (!rootDirChecked) {
        val path = new Path(dfsRootDir)
        if (!fm.exists(path)) fm.mkdirs(path)
        rootDirChecked = true
      }
    }
    zipToDfsFile(localOtherFiles :+ metadataFile, dfsBatchZipFile(version))
    logInfo(log"Saved checkpoint file for version ${MDC(LogKeys.VERSION_NUM, version)}")
  }

  /**
   * Load all necessary files for specific checkpoint version from DFS to given local directory.
   * If version is 0, then it will delete all files in the directory. For other versions, it
   * ensures that only the exact files generated during checkpointing will be present in the
   * local directory.
   */
  def loadCheckpointFromDfs(version: Long, localDir: File): RocksDBCheckpointMetadata = {
    logInfo(log"Loading checkpoint files for version ${MDC(LogKeys.VERSION_NUM, version)}")
    // The unique ids of SST files are checked when opening a rocksdb instance. The SST files
    // in larger versions can't be reused even if they have the same size and name because
    // they belong to another rocksdb instance.
    versionToRocksDBFiles.keySet().removeIf(_ >= version)
    val metadata = if (version == 0) {
      if (localDir.exists) Utils.deleteRecursively(localDir)
      localFilesToDfsFiles.clear()
      localDir.mkdirs()
      RocksDBCheckpointMetadata(Seq.empty, 0)
    } else {
      // Delete all non-immutable files in local dir, and unzip new ones from DFS commit file
      listRocksDBFiles(localDir)._2.foreach(_.delete())
      Utils.unzipFilesFromFile(fs, dfsBatchZipFile(version), localDir)

      // Copy the necessary immutable files
      val metadataFile = localMetadataFile(localDir)
      val metadata = RocksDBCheckpointMetadata.readFromFile(metadataFile)
      logInfo(log"Read metadata for version ${MDC(LogKeys.VERSION_NUM, version)}:\n" +
        log"${MDC(LogKeys.METADATA_JSON, metadata.prettyJson)}")
      loadImmutableFilesFromDfs(metadata.immutableFiles, localDir)
      versionToRocksDBFiles.put(version, metadata.immutableFiles)
      metadataFile.delete()
      metadata
    }
    logFilesInDir(localDir, log"Loaded checkpoint files " +
      log"for version ${MDC(LogKeys.VERSION_NUM, version)}")
    metadata
  }

  // Get latest snapshot version <= version
  def getLatestSnapshotVersion(version: Long): Long = {
    val path = new Path(dfsRootDir)
    if (fm.exists(path)) {
      // If the latest version snapshot exists, we avoid listing.
      if (fm.exists(dfsBatchZipFile(version))) {
        return version
      }
      fm.list(path, onlyZipFiles)
        .map(_.getPath.getName.stripSuffix(".zip"))
        .map(_.toLong)
        .filter(_ <= version)
        .foldLeft(0L)(math.max)
    } else {
      0
    }
  }


  /** Get the latest version available in the DFS directory. If no data present, it returns 0. */
  def getLatestVersion(): Long = {
    val path = new Path(dfsRootDir)
    if (fm.exists(path)) {
      val files = fm.list(path).map(_.getPath)
      val changelogFileVersions = files
        .filter(onlyChangelogFiles.accept(_))
        .map(_.getName.stripSuffix(".changelog"))
        .map(_.toLong)
      val snapshotFileVersions = files
        .filter(onlyZipFiles.accept(_))
        .map(_.getName.stripSuffix(".zip"))
        .map(_.toLong)
      val versions = changelogFileVersions ++ snapshotFileVersions
      versions.foldLeft(0L)(math.max)
    } else {
      0
    }
  }

  /**
   * Find orphan files which are not tracked by zip files.
   * Both sst files and log files can be orphan files.
   * They are uploaded separately before the zip file of that version is uploaded.
   * When the zip file of a version get overwritten, the referenced sst and log files become orphan.
   * Be careful here since sst and log files of the ongoing version
   * also appear to be orphan before their zip file is uploaded.
   *
   * @param trackedFiles files tracked by metadata in versioned zip file
   * @param allFiles all sst or log files in the directory.
   * @return filenames of orphan files
   */
  def findOrphanFiles(trackedFiles: Seq[String], allFiles: Seq[FileStatus]): Seq[String] = {
    val fileModificationTimes = allFiles.map(file =>
      file.getPath.getName -> file.getModificationTime).toMap
    if (trackedFiles.nonEmpty && allFiles.size > trackedFiles.size) {
      // Some tracked files may not be in the directory when listing.
      val oldestTrackedFileModificationTime = trackedFiles.flatMap(fileModificationTimes.get(_)).min
      // If this immutable file is older than any tracked file,
      // then it can't belong to the ongoing version and it should be safe to clean it up.
      val orphanFiles = fileModificationTimes
        .filter(_._2 < oldestTrackedFileModificationTime).keys.toSeq
      if (orphanFiles.nonEmpty) {
        logInfo(log"Found ${MDC(LogKeys.NUM_FILES, orphanFiles.size)} orphan files: " +
          log"${MDC(LogKeys.FILE_MODIFICATION_TIME, orphanFiles.take(20).mkString(", "))}" +
          log"... (display at most 20 filenames) that should be deleted.")
      }
      orphanFiles
    } else {
      Seq.empty
    }
  }

  private def deleteChangelogFiles(versionsToDelete: Array[Long]): Unit = {
    versionsToDelete.foreach { version =>
      try {
        fm.delete(dfsChangelogFile(version))
        logInfo(log"Deleted changelog file ${MDC(LogKeys.VERSION_NUM, version)}")
      } catch {
        case e: Exception =>
          logWarning(
            log"Error deleting changelog file for version ${MDC(LogKeys.FILE_VERSION, version)}", e)
      }
    }
  }

  /**
   * Delete old versions by deleting the associated version and SST files.
   * At a high-level, this method finds which versions to delete, and which SST files that were
   * last used in those versions. It's safe to delete these SST files because a SST file can
   * be reused only in successive versions. Therefore, if a SST file F was last used in version
   * V, then it won't be used in version V+1 or later, and if version V can be deleted, then
   * F can safely be deleted as well.
   *
   * To find old files, it does the following.
   * - List all the existing [version].zip files
   * - Find the min version that needs to be retained based on the given `numVersionsToRetain`.
   * - Accordingly decide which versions should be deleted.
   * - Resolve all SSTs files of all the existing versions, if not already resolved.
   * - Find the files that were last used in the to-be-deleted versions as we will not
   *   need those files any more.
   * - Find the orphan sst and log files whose zip files are not uploaded successfully
   *   or have been overwritten. To avoid deleting files of ongoing tasks, only delete orphan files
   *   that are older than all tracked files when there are at least 2 versions.
   * - Delete sst and log files in to-be-deleted versions.
   * - Delete orphan files.
   * - Delete changelog files of to-be-deleted versions.
   *
   * Note that it only deletes files that it knows are safe to delete.
   * It may not delete the following files.
   * - Partially written SST files
   * - SST files that were used in a version, but that version got overwritten with a different
   *   set of SST files.
   */
  def deleteOldVersions(numVersionsToRetain: Int): Unit = {
    val path = new Path(dfsRootDir)
    val allFiles = fm.list(path).map(_.getPath)
    val snapshotFiles = allFiles.filter(file => onlyZipFiles.accept(file))
    val changelogFiles = allFiles.filter(file => onlyChangelogFiles.accept(file))
    // All versions present in DFS, sorted
    val sortedSnapshotVersions = snapshotFiles
      .map(_.getName.stripSuffix(".zip"))
      .map(_.toLong)
      .sorted

    // Return if no versions generated yet
    if (sortedSnapshotVersions.isEmpty) return

    // Find the versions to delete
    val maxSnapshotVersionPresent = sortedSnapshotVersions.last

    // In order to reconstruct numVersionsToRetain version, retain the latest snapshot
    // that satisfies (version <= maxSnapshotVersionPresent - numVersionsToRetain + 1).
    // If none of the snapshots satisfy the condition, minVersionToRetain will be 0 and
    // no version gets deleted.
    val minVersionToRetain = sortedSnapshotVersions
      .filter(_ <= maxSnapshotVersionPresent - numVersionsToRetain + 1)
      .foldLeft(0L)(math.max)

    // When snapshotVersionToDelete is non-empty, there are at least 2 snapshot versions.
    // We only delete orphan files when there are at least 2 versions,
    // which avoid deleting files for running tasks.
    val snapshotVersionsToDelete = sortedSnapshotVersions.filter(_ < minVersionToRetain)
    if (snapshotVersionsToDelete.isEmpty) return


    // Resolve RocksDB files for all the versions and find the max version each file is used
    val fileToMaxUsedVersion = new mutable.HashMap[String, Long]
    sortedSnapshotVersions.foreach { version =>
      val files = Option(versionToRocksDBFiles.get(version)).getOrElse {
        val newResolvedFiles = getImmutableFilesFromVersionZip(version)
        versionToRocksDBFiles.put(version, newResolvedFiles)
        newResolvedFiles
      }
      files.foreach(f => fileToMaxUsedVersion(f.dfsFileName) =
        math.max(version, fileToMaxUsedVersion.getOrElse(f.dfsFileName, version)))
    }

    // Best effort attempt to delete SST files that were last used in to-be-deleted versions
    val filesToDelete = fileToMaxUsedVersion.filter {
      case (_, v) => snapshotVersionsToDelete.contains(v)
    }

    val sstDir = new Path(dfsRootDir, RocksDBImmutableFile.SST_FILES_DFS_SUBDIR)
    val logDir = new Path(dfsRootDir, RocksDBImmutableFile.LOG_FILES_DFS_SUBDIR)
    val allSstFiles = if (fm.exists(sstDir)) fm.list(sstDir).toImmutableArraySeq else Seq.empty
    val allLogFiles = if (fm.exists(logDir)) fm.list(logDir).toImmutableArraySeq else Seq.empty
    filesToDelete ++= findOrphanFiles(fileToMaxUsedVersion.keys.toSeq, allSstFiles ++ allLogFiles)
      .map(_ -> -1L)
    logInfo(log"Deleting ${MDC(LogKeys.NUM_FILES, filesToDelete.size)} " +
      log"files not used in versions >= ${MDC(LogKeys.VERSION_NUM, minVersionToRetain)}")
    var failedToDelete = 0
    filesToDelete.foreach { case (dfsFileName, maxUsedVersion) =>
      try {
        val dfsFile = dfsFilePath(dfsFileName)
        fm.delete(dfsFile)
        if (maxUsedVersion == -1) {
          logDebug(s"Deleted orphan file $dfsFileName")
        } else {
          logDebug(s"Deleted file $dfsFileName that was last used in version $maxUsedVersion")
        }
      } catch {
        case e: Exception =>
          failedToDelete += 1
          if (maxUsedVersion == -1) {
            logWarning(log"Error deleting orphan file ${MDC(LogKeys.PATH, dfsFileName)}", e)
          } else {
            logWarning(log"Error deleting file ${MDC(LogKeys.PATH, dfsFileName)}, " +
              log"last used in version ${MDC(LogKeys.MAX_FILE_VERSION, maxUsedVersion)}", e)
          }
      }
    }

    // Delete the version files and forget about them
    snapshotVersionsToDelete.foreach { version =>
      val versionFile = dfsBatchZipFile(version)
      try {
        fm.delete(versionFile)
        versionToRocksDBFiles.remove(version)
        logDebug(s"Deleted version $version")
      } catch {
        case e: Exception =>
          logWarning(log"Error deleting version file ${MDC(LogKeys.PATH, versionFile)} for " +
            log"version ${MDC(LogKeys.FILE_VERSION, version)}", e)
      }
    }
    logInfo(log"Deleted ${MDC(LogKeys.NUM_FILES, filesToDelete.size - failedToDelete)} files " +
      log"(failed to delete" +
      log"${MDC(LogKeys.NUM_FILES_FAILED_TO_DELETE, failedToDelete)} files) " +
      log"not used in versions >= ${MDC(LogKeys.MIN_VERSION_NUM, minVersionToRetain)}")
    val changelogVersionsToDelete = changelogFiles
      .map(_.getName.stripSuffix(".changelog")).map(_.toLong)
      .filter(_ < minVersionToRetain)
    deleteChangelogFiles(changelogVersionsToDelete)
  }

  /** Save immutable files to DFS directory */
  private def saveImmutableFilesToDfs(
      version: Long,
      localFiles: Seq[File]): Seq[RocksDBImmutableFile] = {
    // Get the immutable files used in previous versions, as some of those uploaded files can be
    // reused for this version
    logInfo(log"Saving RocksDB files to DFS for ${MDC(LogKeys.VERSION_NUM, version)}")

    var bytesCopied = 0L
    var filesCopied = 0L
    var filesReused = 0L

    val immutableFiles = localFiles.map { localFile =>
      val existingDfsFile = localFilesToDfsFiles.asScala.get(localFile.getName)
      if (existingDfsFile.isDefined && existingDfsFile.get.sizeBytes == localFile.length()) {
        val dfsFile = existingDfsFile.get
        filesReused += 1
        logInfo(log"reusing file ${MDC(LogKeys.DFS_FILE, dfsFile)} for " +
          log"${MDC(LogKeys.FILE_NAME, localFile)}")
        RocksDBImmutableFile(localFile.getName, dfsFile.dfsFileName, dfsFile.sizeBytes)
      } else {
        val localFileName = localFile.getName
        val dfsFileName = newDFSFileName(localFileName)
        val dfsFile = dfsFilePath(dfsFileName)
        // Note: The implementation of copyFromLocalFile() closes the output stream when there is
        // any exception while copying. So this may generate partial files on DFS. But that is
        // okay because until the main [version].zip file is written, those partial files are
        // not going to be used at all. Eventually these files should get cleared.
        fs.copyFromLocalFile(
          new Path(localFile.getAbsoluteFile.toURI), dfsFile)
        val localFileSize = localFile.length()
        logInfo(log"Copied ${MDC(LogKeys.FILE_NAME, localFile)} to " +
          log"${MDC(LogKeys.DFS_FILE, dfsFile)} - ${MDC(LogKeys.NUM_BYTES, localFileSize)} bytes")
        filesCopied += 1
        bytesCopied += localFileSize

        val immutableDfsFile = RocksDBImmutableFile(localFile.getName, dfsFileName, localFileSize)
        localFilesToDfsFiles.put(localFileName, immutableDfsFile)

        immutableDfsFile
      }
    }
    logInfo(log"Copied ${MDC(LogKeys.NUM_FILES_COPIED, filesCopied)} files " +
      log"(${MDC(LogKeys.NUM_BYTES, bytesCopied)} bytes) from local to" +
      log" DFS for version ${MDC(LogKeys.VERSION_NUM, version)}. " +
      log"${MDC(LogKeys.NUM_FILES_REUSED, filesReused)} files reused without copying.")
    versionToRocksDBFiles.put(version, immutableFiles)

    // Cleanup locally deleted files from the localFilesToDfsFiles map
    // Locally, SST Files can be deleted due to RocksDB compaction. These files need
    // to be removed rom the localFilesToDfsFiles map to ensure that if a older version
    // regenerates them and overwrites the version.zip, SST files from the conflicting
    // version (previously committed) are not reused.
    removeLocallyDeletedSSTFilesFromDfsMapping(localFiles)

    saveCheckpointMetrics = RocksDBFileManagerMetrics(
      bytesCopied = bytesCopied,
      filesCopied = filesCopied,
      filesReused = filesReused)

    immutableFiles
  }

  /**
   * Copy files from DFS directory to a local directory. It will figure out which
   * existing files are needed, and accordingly, unnecessary SST files are deleted while
   * necessary and non-existing files are copied from DFS.
   */
  private def loadImmutableFilesFromDfs(
      immutableFiles: Seq[RocksDBImmutableFile], localDir: File): Unit = {
    val requiredFileNameToFileDetails = immutableFiles.map(f => f.localFileName -> f).toMap

    val localImmutableFiles = listRocksDBFiles(localDir)._1

    // Cleanup locally deleted files from the localFilesToDfsFiles map
    // Locally, SST Files can be deleted due to RocksDB compaction. These files need
    // to be removed rom the localFilesToDfsFiles map to ensure that if a older version
    // regenerates them and overwrites the version.zip, SST files from the conflicting
    // version (previously committed) are not reused.
    removeLocallyDeletedSSTFilesFromDfsMapping(localImmutableFiles)

    // Delete unnecessary local immutable files
    localImmutableFiles
      .foreach { existingFile =>
        val existingFileSize = existingFile.length()
        val requiredFile = requiredFileNameToFileDetails.get(existingFile.getName)
        val prevDfsFile = localFilesToDfsFiles.asScala.get(existingFile.getName)
        val isSameFile = if (requiredFile.isDefined && prevDfsFile.isDefined) {
          requiredFile.get.dfsFileName == prevDfsFile.get.dfsFileName &&
            existingFile.length() == requiredFile.get.sizeBytes
        } else {
          false
        }

        if (!isSameFile) {
          existingFile.delete()
          localFilesToDfsFiles.remove(existingFile.getName)
          logInfo(log"Deleted local file ${MDC(LogKeys.FILE_NAME, existingFile)} " +
            log"with size ${MDC(LogKeys.NUM_BYTES, existingFileSize)} mapped" +
            log" to previous dfsFile ${MDC(LogKeys.DFS_FILE, prevDfsFile.getOrElse("null"))}")
        } else {
          logInfo(log"reusing ${MDC(LogKeys.DFS_FILE, prevDfsFile)} present at " +
            log"${MDC(LogKeys.EXISTING_FILE, existingFile)} " +
            log"for ${MDC(LogKeys.FILE_NAME, requiredFile)}")
        }
      }

    var filesCopied = 0L
    var bytesCopied = 0L
    var filesReused = 0L
    immutableFiles.foreach { file =>
      val localFileName = file.localFileName
      val localFile = localFilePath(localDir, localFileName)
      if (!localFile.exists) {
        val dfsFile = dfsFilePath(file.dfsFileName)
        // Note: The implementation of copyToLocalFile() closes the output stream when there is
        // any exception while copying. So this may generate partial files on DFS. But that is
        // okay because until the main [version].zip file is written, those partial files are
        // not going to be used at all. Eventually these files should get cleared.
        fs.copyToLocalFile(dfsFile, new Path(localFile.getAbsoluteFile.toURI))
        val localFileSize = localFile.length()
        val expectedSize = file.sizeBytes
        if (localFileSize != expectedSize) {
          throw QueryExecutionErrors.unexpectedFileSize(dfsFile, localFile, expectedSize,
            localFileSize)
        }
        filesCopied += 1
        bytesCopied += localFileSize
        localFilesToDfsFiles.put(localFileName, file)
        logInfo(log"Copied ${MDC(LogKeys.DFS_FILE, dfsFile)} to " +
          log"${MDC(LogKeys.FILE_NAME, localFile)} - " +
          log"${MDC(LogKeys.NUM_BYTES, localFileSize)} bytes")
      } else {
        filesReused += 1
      }
    }
    logInfo(log"Copied ${MDC(LogKeys.NUM_FILES_COPIED, filesCopied)} files " +
      log"(${MDC(LogKeys.NUM_BYTES, bytesCopied)} bytes) from DFS to local with " +
      log"${MDC(LogKeys.NUM_FILES_REUSED, filesReused)} files reused.")

    loadCheckpointMetrics = RocksDBFileManagerMetrics(
      bytesCopied = bytesCopied,
      filesCopied = filesCopied,
      filesReused = filesReused)
  }

  private def removeLocallyDeletedSSTFilesFromDfsMapping(localFiles: Seq[File]): Unit = {
    // clean up deleted SST files from the localFilesToDfsFiles Map
    val currentLocalFiles = localFiles.map(_.getName).toSet
    val mappingsToClean = localFilesToDfsFiles.asScala
      .keys
      .filterNot(currentLocalFiles.contains)

    mappingsToClean.foreach { f =>
      logInfo(log"cleaning ${MDC(LogKeys.FILE_NAME, f)} from the localFilesToDfsFiles map")
      localFilesToDfsFiles.remove(f)
    }
  }

  /** Get the SST files required for a version from the version zip file in DFS */
  private def getImmutableFilesFromVersionZip(version: Long): Seq[RocksDBImmutableFile] = {
    Utils.deleteRecursively(localTempDir)
    localTempDir.mkdirs()
    Utils.unzipFilesFromFile(fs, dfsBatchZipFile(version), localTempDir)
    val metadataFile = localMetadataFile(localTempDir)
    val metadata = RocksDBCheckpointMetadata.readFromFile(metadataFile)
    metadata.immutableFiles
  }

  /**
   * Compress files to a single zip file in DFS. Only the file names are embedded in the zip.
   * Any error while writing will ensure that the file is not written.
   */
  private def zipToDfsFile(files: Seq[File], dfsZipFile: Path): Unit = {
    lazy val filesStr = s"$dfsZipFile\n\t${files.mkString("\n\t")}"
    var in: InputStream = null
    val out = fm.createAtomic(dfsZipFile, overwriteIfPossible = true)
    var totalBytes = 0L
    val zout = new ZipOutputStream(out)
    try {
      files.foreach { file =>
        zout.putNextEntry(new ZipEntry(file.getName))
        in = new FileInputStream(file)
        val bytes = IOUtils.copy(in, zout)
        in.close()
        zout.closeEntry()
        totalBytes += bytes
      }
      zout.close()  // so that any error in closing also cancels the output stream
      logInfo(log"Zipped ${MDC(LogKeys.NUM_BYTES, totalBytes)} bytes (before compression) to " +
        log"${MDC(LogKeys.FILE_NAME, filesStr)}")
      // The other fields saveCheckpointMetrics should have been filled
      saveCheckpointMetrics =
        saveCheckpointMetrics.copy(zipFileBytesUncompressed = Some(totalBytes))
    } catch {
      case e: Exception =>
        // Cancel the actual output stream first, so that zout.close() does not write the file
        out.cancel()
        logError(s"Error zipping to $filesStr", e)
        throw e
    } finally {
      // Close everything no matter what happened
      IOUtils.closeQuietly(in)
      IOUtils.closeQuietly(zout)
    }
  }

  /** Log the files present in a directory. This is useful for debugging. */
  private def logFilesInDir(dir: File, msg: MessageWithContext): Unit = {
    lazy val files = Option(Utils.recursiveList(dir)).getOrElse(Array.empty).map { f =>
      s"${f.getAbsolutePath} - ${f.length()} bytes"
    }
    logInfo(msg + log" - ${MDC(LogKeys.NUM_FILES, files.length)} files\n\t" +
      log"${MDC(LogKeys.FILE_NAME, files.mkString("\n\t"))}")
  }

  private def newDFSFileName(localFileName: String): String = {
    val baseName = FilenameUtils.getBaseName(localFileName)
    val extension = FilenameUtils.getExtension(localFileName)
    s"$baseName-${UUID.randomUUID}.$extension"
  }

  private def dfsBatchZipFile(version: Long): Path = new Path(s"$dfsRootDir/$version.zip")
  // We use changelog suffix intentionally so that we can tell the difference from changelog file of
  // HDFSBackedStateStore which is named version.delta.
  private def dfsChangelogFile(version: Long): Path = new Path(s"$dfsRootDir/$version.changelog")

  private def localMetadataFile(parentDir: File): File = new File(parentDir, "metadata")

  override protected def logName: String = s"${super.logName} $loggingId"

  private def dfsFilePath(fileName: String): Path = {
    if (isSstFile(fileName)) {
      new Path(new Path(dfsRootDir, SST_FILES_DFS_SUBDIR), fileName)
    } else if (isLogFile(fileName)) {
      new Path(new Path(dfsRootDir, LOG_FILES_DFS_SUBDIR), fileName)
    } else {
      new Path(dfsRootDir, fileName)
    }
  }

  private def localFilePath(localDir: File, fileName: String): File = {
    if (isLogFile(fileName)) {
      new File(new File(localDir, LOG_FILES_LOCAL_SUBDIR), fileName)
    } else {
      new File(localDir, fileName)
    }
  }

  /**
   * List all the RocksDB files that need be synced or recovered.
   */
  private def listRocksDBFiles(localDir: File): (Seq[File], Seq[File]) = {
    val topLevelFiles = localDir.listFiles.filter(!_.isDirectory)
    val archivedLogFiles =
      Option(new File(localDir, LOG_FILES_LOCAL_SUBDIR).listFiles())
        .getOrElse(Array[File]())
        // To ignore .log.crc files
        .filter(file => isLogFile(file.getName))
    val (topLevelSstFiles, topLevelOtherFiles) = topLevelFiles.partition(f => isSstFile(f.getName))
    ((topLevelSstFiles ++ archivedLogFiles).toImmutableArraySeq,
      topLevelOtherFiles.toImmutableArraySeq)
  }
}

/**
 * Metrics regarding RocksDB file sync between local and DFS.
 */
case class RocksDBFileManagerMetrics(
    filesCopied: Long,
    bytesCopied: Long,
    filesReused: Long,
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    zipFileBytesUncompressed: Option[Long] = None)

/**
 * Metrics to return when requested but no operation has been performed.
 */
object RocksDBFileManagerMetrics {
  val EMPTY_METRICS = RocksDBFileManagerMetrics(0L, 0L, 0L, None)
}

/**
 * Classes to represent metadata of checkpoints saved to DFS. Since this is converted to JSON, any
 * changes to this MUST be backward-compatible.
 */
case class RocksDBCheckpointMetadata(
    sstFiles: Seq[RocksDBSstFile],
    logFiles: Seq[RocksDBLogFile],
    numKeys: Long) {
  import RocksDBCheckpointMetadata._

  def json: String = {
    // We turn this field into a null to avoid write a empty logFiles field in the json.
    val nullified = if (logFiles.isEmpty) this.copy(logFiles = null) else this
    mapper.writeValueAsString(nullified)
  }

  def prettyJson: String = Serialization.writePretty(this)(RocksDBCheckpointMetadata.format)

  def writeToFile(metadataFile: File): Unit = {
    val writer = Files.newBufferedWriter(metadataFile.toPath, UTF_8)
    try {
      writer.write(s"v$VERSION\n")
      writer.write(this.json)
    } finally {
      writer.close()
    }
  }

  def immutableFiles: Seq[RocksDBImmutableFile] = sstFiles ++ logFiles
}

/** Helper class for [[RocksDBCheckpointMetadata]] */
object RocksDBCheckpointMetadata {
  val VERSION = 1

  implicit val format: Formats = Serialization.formats(NoTypeHints)

  /** Used to convert between classes and JSON. */
  lazy val mapper = {
    val _mapper = new ObjectMapper with ClassTagExtensions
    _mapper.setSerializationInclusion(Include.NON_ABSENT)
    _mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    _mapper.registerModule(DefaultScalaModule)
    _mapper
  }

  def readFromFile(metadataFile: File): RocksDBCheckpointMetadata = {
    val reader = Files.newBufferedReader(metadataFile.toPath, UTF_8)
    try {
      val versionLine = reader.readLine()
      if (versionLine != s"v$VERSION") {
        throw QueryExecutionErrors.cannotReadCheckpoint(versionLine, s"v$VERSION")
      }
      Serialization.read[RocksDBCheckpointMetadata](reader)
    } finally {
      reader.close()
    }
  }

  def apply(rocksDBFiles: Seq[RocksDBImmutableFile], numKeys: Long): RocksDBCheckpointMetadata = {
    val sstFiles = rocksDBFiles.collect { case file: RocksDBSstFile => file }
    val logFiles = rocksDBFiles.collect { case file: RocksDBLogFile => file }

    RocksDBCheckpointMetadata(sstFiles, logFiles, numKeys)
  }
}

/**
 * A RocksDBImmutableFile maintains a mapping between a local RocksDB file name and the name of
 * its copy on DFS. Since these files are immutable, their DFS copies can be reused.
 */
sealed trait RocksDBImmutableFile {
  def localFileName: String
  def dfsFileName: String
  def sizeBytes: Long

  /**
   * Whether another local file is same as the file described by this class.
   * A file is same only when the name and the size are same.
   */
  def isSameFile(otherFile: File): Boolean = {
    otherFile.getName == localFileName && otherFile.length() == sizeBytes
  }
}

/**
 * Class to represent a RocksDB SST file. Since this is converted to JSON,
 * any changes to these MUST be backward-compatible.
 */
private[sql] case class RocksDBSstFile(
    localFileName: String,
    dfsSstFileName: String,
    sizeBytes: Long) extends RocksDBImmutableFile {

  override def dfsFileName: String = dfsSstFileName
}

/**
 * Class to represent a RocksDB Log file. Since this is converted to JSON,
 * any changes to these MUST be backward-compatible.
 */
private[sql] case class RocksDBLogFile(
    localFileName: String,
    dfsLogFileName: String,
    sizeBytes: Long) extends RocksDBImmutableFile {

  override def dfsFileName: String = dfsLogFileName
}

object RocksDBImmutableFile {
  val SST_FILES_DFS_SUBDIR = "SSTs"
  val LOG_FILES_DFS_SUBDIR = "logs"
  val LOG_FILES_LOCAL_SUBDIR = "archive"

  def apply(localFileName: String, dfsFileName: String, sizeBytes: Long): RocksDBImmutableFile = {
    if (isSstFile(localFileName)) {
      RocksDBSstFile(localFileName, dfsFileName, sizeBytes)
    } else if (isLogFile(localFileName)) {
      RocksDBLogFile(localFileName, dfsFileName, sizeBytes)
    } else {
      null
    }
  }

  def isSstFile(fileName: String): Boolean = fileName.endsWith(".sst")

  def isLogFile(fileName: String): Boolean = fileName.endsWith(".log")

  private def isArchivedLogFile(file: File): Boolean =
    isLogFile(file.getName) && file.getParentFile.getName == LOG_FILES_LOCAL_SUBDIR

  def isImmutableFile(file: File): Boolean = isSstFile(file.getName) || isArchivedLogFile(file)
}
