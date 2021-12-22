package com.chengsoft

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.mov.QuickTimeDirectory
import com.drew.metadata.mp4.Mp4Directory
import com.google.common.base.Stopwatch
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.vavr.control.Option
import io.vavr.control.Try
import net.jpountz.xxhash.XXHashFactory
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.slf4j.LoggerFactory
import rx.Observable
import rx.Single
import rx.observables.GroupedObservable
import rx.schedulers.Schedulers
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.Objects.isNull
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Stream

/**
 * Created by Tim on 8/8/2016.
 *
 * @author tcheng
 */
class MediaCopier(private val inputFolder: String,
                  private val outputFolder: String,
                  private val media: Media = Media.IMAGE,
                  private val ignoreFolders: List<String> = emptyList(),
                  private var maxTransferLimitMB: Long? = null,
                  mediaTypeResolver: ((Path) -> String)? = null) {

    private val tikaConfig = initTika()

    private var filteredPaths: Observable<Path>? = null

    private val pool = Executors.newFixedThreadPool(10, ThreadFactoryBuilder()
            .setNameFormat("Fixed Pool-%d")
            .build())

    private val mediaTypeResolver: (Path) -> String

    private val beanCache = CacheBuilder.newBuilder()
            .build(
                    object : CacheLoader<Pair<String, Path>, FileCopyBean>() {
                        override fun load(key: Pair<String, Path>): FileCopyBean {
                            log.info("Save FileCopyBean to cache path={}", key)
                            return createFileCopyBean(key, outputFolder)
                        }
                    }
            )

    private val checksumCache = CacheBuilder.newBuilder()
            .build(
                    object : CacheLoader<ChecksumKey, Boolean>() {
                        override fun load(key: ChecksumKey): Boolean? {
                            return checksumMatches(key.source, key.destination)
                        }
                    }
            )

    private val fileSizeCounter: AtomicLong = AtomicLong()

    // Get the paths if it hasn't been retrieved during the dry run
    // Hack to retrieve the media type in an async way
    internal fun pathsGroupedByMediaType(): Observable<GroupedObservable<String, Path>> {
        if (isNull(filteredPaths)) {
            filteredPaths = getFilteredPaths()
        }

        return filteredPaths!!
                .flatMap { p -> { Pair(mediaTypeResolver.invoke(p), p) }.toAsyncObservable() }
                .groupBy({ it.first }, { it.second })
    }

    init {
        this.mediaTypeResolver = mediaTypeResolver ?: defaultMediaTypeResolver()
    }

    // TODO consolidate media type extraction with getDateTaken()
    private fun defaultMediaTypeResolver(): (Path) -> String {
        val mediaTypeCache = CacheBuilder.newBuilder()
                .build(
                        object : CacheLoader<Path, String>() {
                            override fun load(key: Path): String {
                                log.info("Save media type to cache path={}", key)
                                // Close stream after detecting media type
                                val stopWatch = Stopwatch.createStarted()
                                try {
                                    TikaInputStream.get(key).use { inputStream -> return tikaConfig.detector.detect(inputStream, org.apache.tika.metadata.Metadata()).type }
                                } catch (e: IOException) {
                                    throw RuntimeException("Error getting media type of path=$key", e)
                                } finally {
                                    stopWatch.stop()
                                    log.info("Took time={} to get media type of {}", stopWatch.toString(), key.fileName)
                                }
                            }
                        }
                )

        return { path: Path ->
            Try.of { mediaTypeCache.get(path) }
                    .onFailure { e -> log.warn("Failed to extract media type for path=$path", e) }
                    .getOrElse("unknown")
        }
    }

    fun transferFiles(transferMode: TransferMode, dryRun: Boolean): Observable<Path> {

        // Choose which media type to filter by
        val filterByMediaType: (GroupedObservable<String, Path>) -> Boolean =
                when (media) {
                    Media.ALL -> { _ -> true }
                    else -> { g -> g.key == media.value }
                }

        return pathsGroupedByMediaType()
                .filter(filterByMediaType)
                .flatMap { groupedObservable -> groupedObservable.flatMap { path -> { beanCache.get(Pair(groupedObservable.key, path)) }.toAsyncObservable() } }
                .flatMap { this.filterAlreadyCopiedFiles(it) }
                .takeUntil { this.exceedMaxTransferLimit(it) }
                .flatMap {
                    transferSingleFile(it, transferMode, dryRun)
                            .onErrorResumeNext { throwable ->
                                log.error("Failed to copy file", throwable)
                                Observable.empty()
                            }
                }

    }

    private fun <T> Callable<T>.toAsyncObservable() = Observable.from(pool.submit(this))
    private fun <T> (() -> T).toAsyncObservable() = Observable.from(pool.submit(this))

    private fun exceedMaxTransferLimit(fileCopyBean: FileCopyBean): Boolean? {
        if (isNull(maxTransferLimitMB)) {
            return false
        }
        val fileLength = fileCopyBean.sourcePath.toFile().length()
        val currentTransferSize = fileSizeCounter.get()
        val futureSize = currentTransferSize + fileLength
        val exceedsLimit = futureSize >= maxTransferLimitMB!! * 1024 * 1024

        if (exceedsLimit) {
            log.info("Stopping transfer. Current file=[{}] to transfer has size={} MB. Current transfer size={} MB. Max Limit={} MB",
                    fileCopyBean.sourcePath.fileName.toString(),
                    fileLength / 1024 / 1024,
                    currentTransferSize / 1024 / 1024,
                    maxTransferLimitMB)
        }

        return exceedsLimit
    }

    private fun filterAlreadyCopiedFiles(bean: FileCopyBean): Observable<FileCopyBean> {
        val destImagePath = bean.destinationPath
        val srcImagePath = bean.sourcePath

        // If the file exists
        if (Files.exists(destImagePath) && Files.isRegularFile(destImagePath)) {

            // Check that the source and destination file checksum match
            // If not, delete the destination file
            val checksumMatches = Try.of { checksumCache.get(ChecksumKey(srcImagePath, destImagePath)) }
                    .onFailure { log.warn("Failed to delete corrupted file=$destImagePath", it) }
                    .getOrElse(false)
            if (!checksumMatches) {
                Try.of { Files.deleteIfExists(destImagePath) }
                        .orElseRun { e -> log.warn("Failed to delete corrupted file=$destImagePath", e) }
                log.warn("Destination file={} is corrupted. Deleting file.", destImagePath.fileName)
            } else {
                log.info("Destination File={} already exists. Skipping.", destImagePath.fileName)
                return Observable.empty()
            }
        }
        return Observable.just(bean)
    }

    internal fun getFilteredPaths(): Observable<Path> {
        // Create ignore folder filter predicate
        val caseInsensitiveIgnoreFolders = ignoreFolders
                .map { it.lowercase(Locale.US) }

        val pathStream: Stream<Path>
        try {
            pathStream = Files.walk(Paths.get(inputFolder))
                    .filter { Files.isRegularFile(it) }
                    .filter { p -> Try.of { !Files.isHidden(p) }.getOrElse(false) }
                    .filter { p ->
                        caseInsensitiveIgnoreFolders.none { ignoreFolder -> p.toString().lowercase(Locale.US).contains(ignoreFolder) }
                    }
        } catch (e: IOException) {
            throw RuntimeException("Error loading paths from inputFolder=$inputFolder", e)
        }

        return Observable.from(Iterable { pathStream.iterator() })
                .cache() // Make sure it's cached since streams are not reusable
    }

    internal fun transferSingleFile(bean: FileCopyBean, transferMode: TransferMode, dryRun: Boolean): Observable<Path> {
        val destFolderPath = bean.destinationFolder
        val destImagePath = bean.destinationPath
        val srcImagePath = bean.sourcePath

        try {
            // Create directory if necessary
            if (!dryRun && Files.notExists(destFolderPath)) {
                Files.createDirectories(destFolderPath)
                log.info("Directory={} not found. Creating automatically.", destFolderPath)
            }

            // Decide which transfer mode to use
            val transferCallable: Callable<Path> = when (transferMode) {
                TransferMode.COPY -> Callable { Files.copy(srcImagePath, destImagePath, StandardCopyOption.COPY_ATTRIBUTES) }
                TransferMode.MOVE -> Callable { Files.move(srcImagePath, destImagePath, StandardCopyOption.ATOMIC_MOVE) }
            }

            val action = transferMode.toString()
            if (!dryRun) {
                return transferCallable.toAsyncObservable()
                        .doOnCompleted {
                            fileSizeCounter.addAndGet(destImagePath.toFile().length())
                            log.info("{} [srcImage={}, destImagePath={}]", action, srcImagePath.fileName, destImagePath)
                        }
            } else {
                log.info("Simulated {} [srcImage={}, destImagePath={}]", action, srcImagePath.fileName, destImagePath)
            }

            return Observable.just(destImagePath)
        } catch (ex: Exception) {
            return Observable.error(RuntimeException(
                    String.format("Error while copying [srcImage=%s, destImagePath=%s]", srcImagePath.fileName, destImagePath), ex))
        }

    }

    /**
     * Calculates the MD5 checksum of each file and compares them
     *
     * @param source      the source
     * @param destination the destination
     * @return checksum matches
     */
    private fun checksumMatches(source: Path, destination: Path): Boolean {
        val stopWatch = Stopwatch.createStarted()
        val srcChecksumObservable = Observable.fromCallable { hashFile(source.toFile()) }.subscribeOn(Schedulers.io())
        val destChecksumObservable = Observable.fromCallable { hashFile(destination.toFile()) }.subscribeOn(Schedulers.io())
        val result = Single.zip<Int, Int, Boolean>(srcChecksumObservable.toSingle(), destChecksumObservable.toSingle(), Int::equals).toBlocking().value()
        stopWatch.stop()
        log.info("Took time={} to compare checksum of {}", stopWatch.toString(), source.fileName)
        return result!!
    }

    private fun hashFile(fileToHash: File): Int {
        val stopWatch = Stopwatch.createStarted()
        var randomAccessFile: RandomAccessFile? = null
        var channel: FileChannel? = null
        try {
            val factory = XXHashFactory.fastestInstance()

            randomAccessFile = RandomAccessFile(fileToHash, "r")
            channel = randomAccessFile.channel
            val buffer = ByteBuffer.allocate(8192)

            val seed = -0x68b84d74 // used to initialize the hash value, use whatever
            // value you want, but always the same
            val hash32 = factory.newStreamingHash32(seed)
            while (true) {
                val read = channel!!.read(buffer)
                if (read == -1) {
                    break
                }
                hash32.update(buffer.array(), 0, read)
                buffer.clear()
            }
            return hash32.value
        } catch (ex: IOException) {
            throw RuntimeException("Failed to hash file=$fileToHash", ex)
        } finally {
            randomAccessFile?.run { this.close() }
            channel?.run { this.close() }
            stopWatch.stop()
            log.info("Took time={} to calculate hash of {}", stopWatch.toString(), fileToHash.absolutePath)
        }
    }

    /**
     * Create the [FileCopyBean] by pulling the EXIF data and creating the destination path and folder
     *
     * @param mediaTypePath the media type and source Path
     * @param outputFolder the output folder
     * @return the [FileCopyBean]
     */
    private fun createFileCopyBean(mediaTypePath: Pair<String, Path>, outputFolder: String): FileCopyBean {
        val (mediaType, path) = mediaTypePath
        // Try get the original date
        val dateTaken = getDateTaken(path).map(Date::toInstant)

        // Use the last modified date as a last resort
        val lastModifiedDate = Date(path.toFile().lastModified()).toInstant()

        log.debug("[dateTaken={}, lastModified={}, path={}]", dateTaken, lastModifiedDate, path)

        val folderName = dateTaken.map(FOLDER_DATE_FORMAT::format)
                .orElse("lastModifiedDate/" + FOLDER_DATE_FORMAT.format(lastModifiedDate))

        val destFolderPath = when (media) {
            Media.ALL -> Paths.get(outputFolder).resolve(mediaType).resolve(folderName)
            else -> Paths.get(outputFolder).resolve(folderName)
        }
        val destImagePath = destFolderPath.resolve(path.fileName)

        return FileCopyBean(path, destFolderPath, destImagePath)
    }

    /**
     * Get the date taken of the media type by using Apache Tika to extract it
     *
     * @param path the file path of the media
     * @return the optional date
     */
    internal fun getDateTaken(path: Path): Optional<Date> {
        // Find the original date
        val stopWatch = Stopwatch.createStarted()
        val extractedMetadata = Try.of { ImageMetadataReader.readMetadata(path.toFile()) }
                .onFailure { e -> log.warn("Failed to extract metadata to get date taken for path=$path", e) }
                .getOrElse { Metadata() }
        stopWatch.stop()
        log.info("Took time={} to get date taken of {}", stopWatch.toString(), path.fileName)

        return this.getExifOriginalDate(extractedMetadata)
                .orElse { getMovCreationDate(extractedMetadata) }
                .orElse { getMp4CreationDate(extractedMetadata) }
                .toJavaOptional()
    }

    private fun getMovCreationDate(metadata: Metadata): Option<Date> {
        return Option.of(metadata.getFirstDirectoryOfType(QuickTimeDirectory::class.java))
                .map { d -> d.getDate(QuickTimeDirectory.TAG_CREATION_TIME, UTC) }
    }

    private fun getMp4CreationDate(metadata: Metadata): Option<Date> {
        return Option.of(metadata.getFirstDirectoryOfType(Mp4Directory::class.java))
                .map { d -> d.getDate(Mp4Directory.TAG_CREATION_TIME, UTC) }
    }

    private fun getExifOriginalDate(metadata: Metadata): Option<Date> {
        return Option.of(metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java))
                .map { d -> d.getDateOriginal(UTC) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MediaCopier::class.java)
        private val FOLDER_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/yyyy_MM_dd").withZone(ZoneOffset.UTC)
        private val UTC = TimeZone.getTimeZone("UTC")

        private fun initTika(): TikaConfig {
            try {
                return TikaConfig()
            } catch (e: Exception) {
                throw RuntimeException(e)
            }

        }
    }
}
