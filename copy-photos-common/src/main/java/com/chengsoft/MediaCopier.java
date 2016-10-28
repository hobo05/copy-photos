package com.chengsoft;

import com.google.common.base.Stopwatch;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.NonNull;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.functions.Func1;
import rx.observables.GroupedObservable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;

/**
 * Created by Tim on 8/8/2016.
 *
 * @author tcheng
 */
//@Data
public class MediaCopier {
    private static final Logger log = LoggerFactory.getLogger(MediaCopier.class);
    private static final SimpleDateFormat FOLDER_DATE_FORMAT = new SimpleDateFormat("yyyy/yyyy_MM_dd");
    private static final SimpleDateFormat TIKA_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    private @NonNull String inputFolder;
    private @NonNull String outputFolder;
    private @NonNull Media media;
    private List<String> ignoreFolders = ImmutableList.of();

    private TikaConfig tikaConfig = initTika();
    private AutoDetectParser autoDetectParser = new AutoDetectParser(tikaConfig);

    private Observable<Path> filteredPaths;

    private ExecutorService pool = Executors.newFixedThreadPool(10, new ThreadFactoryBuilder()
            .setNameFormat("Fixed Pool-%d")
            .build());

    private LoadingCache<Path, String> mediaTypeCache = CacheBuilder.newBuilder()
            .build(
                    new CacheLoader<Path, String>() {
                        @Override
                        public String load(Path key) throws Exception {
                            log.info("Save media type to cache path={}", key);
                            return getMediaType(key);
                        }
                    }
            );

    private LoadingCache<Path, FileCopyBean> beanCache = CacheBuilder.newBuilder()
            .build(
                    new CacheLoader<Path, FileCopyBean>() {
                        @Override
                        public FileCopyBean load(Path key) throws Exception {
                            log.info("Save FileCopyBean to cache path={}", key);
                            return createFileCopyBean(key, outputFolder);
                        }
                    }
            );

    public MediaCopier(String inputFolder, String outputFolder, Media media, List<String> ignoreFolders) {
        this.inputFolder = inputFolder;
        this.outputFolder = outputFolder;
        this.media = media;
        this.ignoreFolders = ignoreFolders;
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
                .flatMap(b -> transferSingleFile(b, transferMode, dryRun)
                        .onErrorResumeNext(throwable -> {
                            log.error("Failed to copy file", throwable);
                            return Observable.empty();
                        }));

    }

    private Observable<GroupedObservable<String, Path>> getPathsGroupedByMediaType() {
        // Get the paths if it hasn't been retrieved during the dry run
        if (isNull(filteredPaths)) {
            filteredPaths = getFilteredPaths();
        }
        return filteredPaths.
                // Hack to retrieve the media type in an async way
                        flatMap(p -> Observable.from(pool.submit(
                        () -> new AbstractMap.SimpleEntry<>(mediaTypeCache.get(p), p))))
                .groupBy(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue);
    }

    private Observable<Path> getFilteredPaths() {
        Stream<Path> pathStream;
        try {
            pathStream = Files.walk(Paths.get(inputFolder));
        } catch (Exception e) {
            throw new RuntimeException(String.format("Error loading paths from inputFolder=%s", inputFolder), e);
        }

        // Create ignore folder filter predicate
        List<String> caseInsensitiveIgnoreFolders = ignoreFolders.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList());

        Func1<Path, Boolean> filterOutIgnoredFolder = p -> caseInsensitiveIgnoreFolders.stream()
                .noneMatch(ignoreFolder -> p.toString().toLowerCase().contains(ignoreFolder));


        return Observable.from(pathStream::iterator)
                .filter(filterOutIgnoredFolder)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    try {
                        return !Files.isHidden(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .cache(); // Make sure it's cached since streams are not reusable
    }

    /**
     * Detect the media type of the file and return it as a string
     *
     * @param path the file path
     * @return the media type
     */
    private String getMediaType(Path path) {
        // Close stream after detecting media type
        Stopwatch stopWatch = Stopwatch.createStarted();
        try (TikaInputStream inputStream = TikaInputStream.get(path)) {
            return tikaConfig.getDetector().detect(inputStream, new Metadata()).getType();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            stopWatch.stop();
            log.info("Took time={} to get media type of {}", stopWatch.toString(), path.getFileName());
        }
    }

    private Observable<Path> transferSingleFile(FileCopyBean bean, TransferMode transferMode, boolean dryRun) {
        Path destFolderPath = bean.getDestinationFolder();
        Path destImagePath = bean.getDestinationPath();
        Path srcImagePath = bean.getSourcePath();

        try {
            // Create directory if necessary
            if (!dryRun && Files.notExists(destFolderPath)) {
                Files.createDirectories(destFolderPath);
                log.info("Directory={} not found. Creating automatically.", destFolderPath);
            }


            // If the file exists
            if (Files.exists(destImagePath)) {

                // Check that the source and destination file checksum match
                // If not, delete the destination file
                Stopwatch stopWatch = Stopwatch.createStarted();
                String srcChecksum = DigestUtils.md5Hex(Files.readAllBytes(srcImagePath));
                String destChecksum = DigestUtils.md5Hex(Files.readAllBytes(destImagePath));
                stopWatch.stop();
                log.info("Took time={} to get checksum of {}", stopWatch.toString(), srcImagePath.getFileName());
                if (!srcChecksum.equals(destChecksum)) {
                    Files.delete(destImagePath);
                    log.warn("Destination file={} is corrupted. Overwriting file.", destImagePath.getFileName());
                } else {
                    log.warn("Destination File={} already exists. Skipping.", destImagePath.getFileName());
                    return Observable.empty();
                }
            }

            // Decide which transfer mode to use
            Callable<Path> transferCallable;
            switch (transferMode) {
                case COPY:
                    transferCallable = () ->
                            Files.copy(srcImagePath, destImagePath, StandardCopyOption.COPY_ATTRIBUTES);
                    break;
                case MOVE:
                    transferCallable = () ->
                            Files.move(srcImagePath, destImagePath, StandardCopyOption.ATOMIC_MOVE);
                    break;
                default:
                    return Observable.error(new RuntimeException("Did not recognize transferMode=%s"+transferMode));
            }

            String action = transferMode.toString();
            if (!dryRun) {
                return Observable.from(pool.submit(transferCallable))
                        .doOnCompleted(() -> log.info("{} [srcImage={}, destImagePath={}]", action, srcImagePath.getFileName(), destImagePath));
            } else {
                log.info("Simulated {} [srcImage={}, destImagePath={}]", action, srcImagePath.getFileName(), destImagePath);
            }

            return Observable.just(destImagePath);
        } catch (IOException ex) {
            return Observable.error(new RuntimeException(
                    String.format("Error while copying [srcImage=%s, destImagePath=%s]", srcImagePath.getFileName(), destImagePath), ex));
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
    private Optional<Date> getDateTaken(Path path) {
        BodyContentHandler handler = new BodyContentHandler();
        Metadata metadata = new Metadata();
        try (InputStream stream = Files.newInputStream(path)) {
            Stopwatch stopWatch = Stopwatch.createStarted();
            autoDetectParser.parse(stream, handler, metadata);
            stopWatch.stop();
            log.info("Took time={} to get date taken of {}", stopWatch.toString(), path.getFileName());

            // Find the created and original date
            Optional<String> createdOptional = Optional.ofNullable(metadata.get(TikaCoreProperties.CREATED));
            Optional<String> originalOptional = Optional.ofNullable(metadata.get(Metadata.ORIGINAL_DATE));

            // Give precedence to the created date, then try the original one
            return (createdOptional.isPresent() ? createdOptional : originalOptional)
                    .map(s -> uncheckedParse(TIKA_DATE_FORMAT, s));  // Parse to date object
        } catch (Exception e) {
            log.warn("[path={}, exception={}]", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Unchecked version of {@link DateFormat#parse(String)}
     *
     * @param dateFormat the date format
     * @param string     the parseable string
     * @return the {@link Date}
     */
    private Date uncheckedParse(DateFormat dateFormat, String string) {
        try {
            return dateFormat.parse(string);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    private static TikaConfig initTika() {
        try {
            return new TikaConfig();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
