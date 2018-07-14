package com.chengsoft;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.google.common.base.Stopwatch;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.NonNull;
import net.jpountz.xxhash.StreamingXXHash32;
import net.jpountz.xxhash.XXHashFactory;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.io.TikaInputStream;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Single;
import rx.functions.Func1;
import rx.observables.GroupedObservable;
import rx.schedulers.Schedulers;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Created by Tim on 8/8/2016.
 *
 * @author tcheng
 */
public class MediaCopier {
    private static final Logger log = LoggerFactory.getLogger(MediaCopier.class);
    private static final SimpleDateFormat FOLDER_DATE_FORMAT = new SimpleDateFormat("yyyy/yyyy_MM_dd");
    static final SimpleDateFormat TIKA_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private @NonNull String inputFolder;
    private @NonNull String outputFolder;
    private @NonNull Media media;
    private List<String> ignoreFolders;

    private TikaConfig tikaConfig = initTika();

    private Observable<Path> filteredPaths;

    private ExecutorService pool = Executors.newFixedThreadPool(10, new ThreadFactoryBuilder()
            .setNameFormat("Fixed Pool-%d")
            .build());

    private Function<Path, String> mediaTypeResolver;

    private LoadingCache<Path, FileCopyBean> beanCache = CacheBuilder.newBuilder()
            .build(
                    new CacheLoader<Path, FileCopyBean>() {
                        @Override
                        public FileCopyBean load(Path key) {
                            log.info("Save FileCopyBean to cache path={}", key);
                            return createFileCopyBean(key, outputFolder);
                        }
                    }
            );

    private LoadingCache<ChecksumKey, Boolean> checksumCache = CacheBuilder.newBuilder()
            .build(
                    new CacheLoader<ChecksumKey, Boolean>() {
                        @Override
                        public Boolean load(ChecksumKey key) {
                            return checksumMatches(key.getSource(), key.getDestination());
                        }
                    }
            );

    private AtomicLong fileSizeCounter;
    private Long maxTransferLimitMB;

    public MediaCopier(String inputFolder,
                       String outputFolder,
                       Media media,
                       List<String> ignoreFolders,
                       Long maxTransferLimitMB,
                       Function<Path, String> mediaTypeResolver) {
        this.inputFolder = inputFolder;
        this.outputFolder = outputFolder;
        this.media = media;
        this.ignoreFolders = ignoreFolders;
        this.mediaTypeResolver = mediaTypeResolver;
        this.fileSizeCounter = new AtomicLong();
        this.maxTransferLimitMB = maxTransferLimitMB;
    }

    public MediaCopier(String inputFolder,
                       String outputFolder,
                       Media media,
                       Long maxTransferLimitMB,
                       List<String> ignoreFolders) {
        this.inputFolder = inputFolder;
        this.outputFolder = outputFolder;
        this.media = media;
        this.ignoreFolders = ignoreFolders;
        this.mediaTypeResolver = defaultMediaTypeResolver();
        this.fileSizeCounter = new AtomicLong();
        this.maxTransferLimitMB = maxTransferLimitMB;
    }

    // TODO consolidate media type extraction with getDateTaken()
    private Function<Path, String> defaultMediaTypeResolver() {
        LoadingCache<Path, String> mediaTypeCache = CacheBuilder.newBuilder()
                .build(
                        new CacheLoader<Path, String>() {
                            @Override
                            public String load(Path key) {
                                log.info("Save media type to cache path={}", key);
                                // Close stream after detecting media type
                                Stopwatch stopWatch = Stopwatch.createStarted();
                                try (TikaInputStream inputStream = TikaInputStream.get(key)) {
                                    return tikaConfig.getDetector().detect(inputStream, new org.apache.tika.metadata.Metadata()).getType();
                                } catch (IOException e) {
                                    throw new RuntimeException("Error getting media type of path=" + key, e);
                                } finally {
                                    stopWatch.stop();
                                    log.info("Took time={} to get media type of {}", stopWatch.toString(), key.getFileName());
                                }
                            }
                        }
                );

        return path -> Try.of(() -> mediaTypeCache.get(path))
                .onFailure(e -> log.warn("Failed to extract media type for path=" + path, e))
                .getOrElse("unknown");
    }

    public Observable<Path> transferFiles(TransferMode transferMode, boolean dryRun) {

        // Choose which media type to filter by
        Func1<GroupedObservable<String, Path>, Boolean> filterByMediaType = go -> go.getKey().equals(media.getValue());
        if (Media.ALL == media) {
            filterByMediaType = p -> true;
        }

        return getPathsGroupedByMediaType()
                .filter(filterByMediaType)
                .flatMap(a -> a)// unwrap the observable
                .flatMap(p -> Observable.from(pool.submit(() -> beanCache.get(p))))
                .flatMap(this::filterAlreadyCopiedFiles)
                .takeUntil(this::exceedMaxTransferLimit)
                .flatMap(b -> transferSingleFile(b, transferMode, dryRun)
                        .onErrorResumeNext(throwable -> {
                            log.error("Failed to copy file", throwable);
                            return Observable.empty();
                        }));

    }

    private Boolean exceedMaxTransferLimit(FileCopyBean fileCopyBean) {
        if (isNull(maxTransferLimitMB)) {
            return false;
        }
        long fileLength = fileCopyBean.getSourcePath().toFile().length();
        long currentTransferSize = fileSizeCounter.get();
        long futureSize = currentTransferSize + fileLength;
        boolean exceedsLimit = futureSize >= maxTransferLimitMB*1024*1024;

        if (exceedsLimit) {
            log.info("Stopping transfer. Current file=[{}] to transfer has size={} MB. Current transfer size={} MB. Max Limit={} MB",
                    fileCopyBean.getSourcePath().getFileName().toString(),
                    fileLength/1024/1024,
                    currentTransferSize/1024/1024,
                    maxTransferLimitMB);
        }

        return exceedsLimit;
    }

    private Observable<FileCopyBean> filterAlreadyCopiedFiles(FileCopyBean bean) {
        Path destImagePath = bean.getDestinationPath();
        Path srcImagePath = bean.getSourcePath();

        // If the file exists
        if (Files.exists(destImagePath) && Files.isRegularFile(destImagePath)) {

            // Check that the source and destination file checksum match
            // If not, delete the destination file
            Boolean checksumMatches = Try.of(() -> checksumCache.get(ChecksumKey.builder()
                    .source(srcImagePath)
                    .destination(destImagePath)
                    .build()))
                    .onFailure(e -> log.warn("Failed to delete corrupted file=" + destImagePath, e))
                    .getOrElse(false);
            if (!checksumMatches) {
                Try.of(() -> Files.deleteIfExists(destImagePath))
                        .orElseRun(e -> log.warn("Failed to delete corrupted file=" + destImagePath, e));
                log.warn("Destination file={} is corrupted. Deleting file.", destImagePath.getFileName());
            } else {
                log.warn("Destination File={} already exists. Skipping.", destImagePath.getFileName());
                return Observable.empty();
            }
        }
        return Observable.just(bean);
    }

    Observable<GroupedObservable<String, Path>> getPathsGroupedByMediaType() {
        // Get the paths if it hasn't been retrieved during the dry run
        if (isNull(filteredPaths)) {
            filteredPaths = getFilteredPaths();
        }
        return filteredPaths.
                // Hack to retrieve the media type in an async way
                        flatMap(p -> Observable.from(pool.submit(
                        () -> new AbstractMap.SimpleEntry<>(mediaTypeResolver.apply(p), p))))
                .groupBy(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue);
    }

    Observable<Path> getFilteredPaths() {
        // Create ignore folder filter predicate
        List<String> caseInsensitiveIgnoreFolders = ignoreFolders.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList());

        Stream<Path> pathStream;
        try {
            pathStream = Files.walk(Paths.get(inputFolder))
                    .filter(Files::isRegularFile)
                    .filter(p -> Try.of(() -> !Files.isHidden(p)).getOrElse(false))
                    .filter(p -> caseInsensitiveIgnoreFolders.stream()
                            .noneMatch(ignoreFolder -> p.toString().toLowerCase().contains(ignoreFolder)));
        } catch (IOException e) {
            throw new RuntimeException(String.format("Error loading paths from inputFolder=%s", inputFolder), e);
        }

        return Observable.from(pathStream::iterator)
                .cache(); // Make sure it's cached since streams are not reusable
    }

    Observable<Path> transferSingleFile(FileCopyBean bean, TransferMode transferMode, boolean dryRun) {
        Path destFolderPath = bean.getDestinationFolder();
        Path destImagePath = bean.getDestinationPath();
        Path srcImagePath = bean.getSourcePath();

        try {
            // Create directory if necessary
            if (!dryRun && Files.notExists(destFolderPath)) {
                Files.createDirectories(destFolderPath);
                log.info("Directory={} not found. Creating automatically.", destFolderPath);
            }

            // Decide which transfer mode to use
            Callable<Path> transferCallable = null;
            switch (transferMode) {
                case COPY:
                    transferCallable = () ->
                            Files.copy(srcImagePath, destImagePath, StandardCopyOption.COPY_ATTRIBUTES);
                    break;
                case MOVE:
                    transferCallable = () ->
                            Files.move(srcImagePath, destImagePath, StandardCopyOption.ATOMIC_MOVE);
                    break;
            }

            String action = transferMode.toString();
            if (!dryRun) {
                return Observable.from(pool.submit(transferCallable))
                        .doOnCompleted(() -> {
                            fileSizeCounter.addAndGet(destImagePath.toFile().length());
                            log.info("{} [srcImage={}, destImagePath={}]", action, srcImagePath.getFileName(), destImagePath);
                        });
            } else {
                log.info("Simulated {} [srcImage={}, destImagePath={}]", action, srcImagePath.getFileName(), destImagePath);
            }

            return Observable.just(destImagePath);
        } catch (Exception ex) {
            return Observable.error(new RuntimeException(
                    String.format("Error while copying [srcImage=%s, destImagePath=%s]", srcImagePath.getFileName(), destImagePath), ex));
        }
    }

    /**
     * Calculates the MD5 checksum of each file and compares them
     *
     * @param source      the source
     * @param destination the destination
     * @return checksum matches
     */
    private boolean checksumMatches(Path source, Path destination) {
        Stopwatch stopWatch = Stopwatch.createStarted();
        Observable<Integer> srcChecksumObservable = Observable.fromCallable(() -> hashFile(source.toFile())).subscribeOn(Schedulers.io());
        Observable<Integer> destChecksumObservable = Observable.fromCallable(() -> hashFile(destination.toFile())).subscribeOn(Schedulers.io());
        Boolean result = Single.zip(srcChecksumObservable.toSingle(), destChecksumObservable.toSingle(), Integer::equals).toBlocking().value();
        stopWatch.stop();
        log.info("Took time={} to compare checksum of {}", stopWatch.toString(), source.getFileName());
        return result;
    }

    private int hashFile(File fileToHash) {
        Stopwatch stopWatch = Stopwatch.createStarted();
        RandomAccessFile randomAccessFile = null;
        FileChannel channel = null;
        try {
            XXHashFactory factory = XXHashFactory.fastestInstance();

            randomAccessFile = new RandomAccessFile(fileToHash, "r");
            channel = randomAccessFile.getChannel();
            ByteBuffer buffer = ByteBuffer.allocate(8192);

            int seed = 0x9747b28c; // used to initialize the hash value, use whatever
            // value you want, but always the same
            StreamingXXHash32 hash32 = factory.newStreamingHash32(seed);
            for (; ; ) {
                int read = channel.read(buffer);
                if (read == -1) {
                    break;
                }
                hash32.update(buffer.array(), 0, read);
                buffer.clear();
            }
            return hash32.getValue();
        } catch (IOException ex) {
            throw new RuntimeException("Failed to hash file=" + fileToHash, ex);
        } finally {
            closeQuietly(randomAccessFile);
            closeQuietly(channel);
            stopWatch.stop();
            log.info("Took time={} to calculate hash of {}", stopWatch.toString(), fileToHash.getAbsolutePath());
        }
    }

    private void closeQuietly(RandomAccessFile randomAccessFile) {
        if (nonNull(randomAccessFile)) {
            try {
                randomAccessFile.close();
            } catch (IOException e) {
                log.warn("Could not close randomAccessFile=" + randomAccessFile);
            }
        }
    }

    private void closeQuietly(FileChannel fileChannel) {
        if (nonNull(fileChannel)) {
            try {
                fileChannel.close();
            } catch (IOException e) {
                log.warn("Could not close fileChanel=" + fileChannel);
            }
        }
    }

    /**
     * Create the {@link FileCopyBean} by pulling the EXIF data and creating the destination path and folder
     *
     * @param path         the source Path
     * @param outputFolder the output folder
     * @return the {@link FileCopyBean}
     */
    private FileCopyBean createFileCopyBean(Path path, String outputFolder) {
        // Try get the original date
        Optional<Date> dateTaken = getDateTaken(path);

        // Use the last modified date as a last resort
        Date lastModifiedDate = new Date(path.toFile().lastModified());

        log.debug("[dateTaken={}, lastModified={}, path={}]", dateTaken, lastModifiedDate, path);

        String folderName = dateTaken.map(FOLDER_DATE_FORMAT::format)
                .orElse("lastModifiedDate/" + FOLDER_DATE_FORMAT.format(lastModifiedDate));
        Path destFolderPath = Paths.get(outputFolder).resolve(folderName);
        Path destImagePath = destFolderPath.resolve(path.getFileName());

        return FileCopyBean.builder()
                .sourcePath(path)
                .destinationFolder(destFolderPath)
                .destinationPath(destImagePath)
                .build();
    }

    /**
     * Get the date taken of the media type by using Apache Tika to extract it
     *
     * @param path the file path of the media
     * @return the optional date
     */
    Optional<Date> getDateTaken(Path path) {
        // Find the original date
        Stopwatch stopWatch = Stopwatch.createStarted();
        Metadata extractedMetadata = Try.of(() -> ImageMetadataReader.readMetadata(path.toFile()))
                .onFailure(e -> log.warn("Failed to extract metadata to get date taken for path=" + path, e))
                .getOrElse(Metadata::new);
        stopWatch.stop();
        log.info("Took time={} to get date taken of {}", stopWatch.toString(), path.getFileName());

        return this.getExifOriginalDate(extractedMetadata)
                .orElse(() -> getMp4CreationDate(extractedMetadata))
                .toJavaOptional();
    }

    @NotNull
    private Option<Date> getMp4CreationDate(Metadata metadata) {
        return Option.of(metadata.getFirstDirectoryOfType(Mp4Directory.class))
                .map(d -> d.getDate(Mp4Directory.TAG_CREATION_TIME, UTC));
    }

    @NotNull
    private Option<Date> getExifOriginalDate(Metadata metadata) {
        return Option.of(metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class))
                .map(d -> d.getDateOriginal(UTC));
    }

    private static TikaConfig initTika() {
        try {
            return new TikaConfig();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
