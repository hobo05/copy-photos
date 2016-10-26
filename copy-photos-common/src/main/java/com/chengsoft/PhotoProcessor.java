package com.chengsoft;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.io.TikaInputStream;
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
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by Tim on 8/8/2016.
 *
 * @author Tim
 */
public class PhotoProcessor {
    private static final Logger log = LoggerFactory.getLogger(PhotoProcessor.class);
    private static final SimpleDateFormat FOLDER_DATE_FORMAT = new SimpleDateFormat("yyyy/yyyy_MM_dd");
    private static final SimpleDateFormat TIKE_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public static enum Media {
        IMAGE("image"),
        VIDEO("video"),
        ALL("all");

        private String value;

        Media(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }

        public String getValue() {
            return value;
        }
    }

    public static Observable<Path> copyFiles(String inputFolder,
                                             String outputFolder,
                                             List<String> ignoreFolders,
                                             Media media,
                                             boolean dryRun) {


        // Choose which media type to filter by
        Func1<GroupedObservable<String, Path>, Boolean> filterByMediaType = go -> go.getKey().equals(media.getValue());
        if (Media.ALL == media) {
            filterByMediaType = p -> true;
        }

        return getPathsGroupedByMediaType(inputFolder, ignoreFolders)
                .filter(filterByMediaType)
                .flatMap(a -> a)// unwrap the observable
                .map(p -> PhotoProcessor.createFileCopyBean(p, outputFolder, dryRun))
                .flatMap(b -> PhotoProcessor.copySingleFile(b)
                        .onErrorResumeNext(throwable -> {
                            log.error("Failed to copy file", throwable);
                            return Observable.empty();
                        }));

    }

    private static Observable<GroupedObservable<String, Path>> getPathsGroupedByMediaType(String inputFolder, List<String> ignoreFolders) {
        TikaConfig tika;
        Stream<Path> pathStream;
        try {
            tika = new TikaConfig();
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
                .groupBy(p -> PhotoProcessor.getMediaType(tika, p))
                .cache();
    }

    private static String getMediaType(TikaConfig tika, Path path) {
        try {
            return tika.getDetector().detect(TikaInputStream.get(path), new org.apache.tika.metadata.Metadata()).getType();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Observable<Path> copySingleFile(FileCopyBean bean) {
        Path destFolderPath = bean.getDestinationFolder();
        Path destImagePath = bean.getDestinationPath();
        Path srcImagePath = bean.getSourcePath();

        try {
            // Create directory if necessary
            if (!bean.isDryRun() && Files.notExists(destFolderPath)) {
                Files.createDirectories(destFolderPath);
                log.info("Directory={} not found. Creating automatically.", destFolderPath);
            }


            // If the file exists
            if (Files.exists(destImagePath)) {

                // Check that the source and destination file checksum match
                // If not, delete the destination file
                String srcChecksum = DigestUtils.md5Hex(Files.readAllBytes(srcImagePath));
                String destChecksum = DigestUtils.md5Hex(Files.readAllBytes(destImagePath));
                if (!srcChecksum.equals(destChecksum)) {
                    Files.delete(destImagePath);
                    log.warn("Destination file={} is corrupted. Overwriting file.", destImagePath.getFileName());
                } else {
                    log.warn("Destination File={} already exists. Skipping.", destImagePath.getFileName());
                    return Observable.empty();
                }
            }

            String action = "Simulated Copy";
            if (!bean.isDryRun()) {
                action = "Copied";
                Files.copy(srcImagePath, destImagePath, StandardCopyOption.COPY_ATTRIBUTES);
            }

            log.info("{} [srcImage={}, destImagePath={}]", action, srcImagePath.getFileName(), destImagePath);
            return Observable.just(destImagePath);
        } catch (IOException ex) {
            return Observable.error(new RuntimeException(
                    String.format("Error while copying [srcImage=%s, destImagePath=%s]", srcImagePath.getFileName(), destImagePath), ex));
        }
    }

    /**
     * Create the {@link FileCopyBean} by pulling the EXIF data and creating the destination path and folder
     *
     * @param path the source Path
     * @param outputFolder the output folder
     * @param dryRun
     * @return the {@link FileCopyBean}
     */
    private static FileCopyBean createFileCopyBean(Path path, String outputFolder, boolean dryRun) {
        // Try get the original photo date
        Optional<Date> dateTaken = getPhotoDateTaken(path);
        // Try to get the original video date
        dateTaken = dateTaken.isPresent() ? dateTaken : getVideoDateTaken(path);


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
                .dryRun(dryRun)
                .build();
    }

    @Data
    @Builder
    private static class FileCopyBean {
        private Path sourcePath;
        private Path destinationFolder;
        private Path destinationPath;
        private boolean dryRun;
    }

    private static Optional<Date> getVideoDateTaken(Path videoPath) {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler();
        org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();
        try (InputStream stream = Files.newInputStream(videoPath)) {
            parser.parse(stream, handler, metadata);
            return Optional.ofNullable(metadata.get(TikaCoreProperties.CREATED))
                    .map(s -> {
                        try {
                            return TIKE_DATE_FORMAT.parse(s);
                        } catch (ParseException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (Exception e) {
            log.warn("[path={}, exception={}]", videoPath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Bean that holds info to copy one file to a destination
     */
    private static Optional<Date> getPhotoDateTaken(Path srcImagePath) {
        Optional<ExifSubIFDDirectory> directory = getExifSubIFDDirectory(srcImagePath);
        return directory.map(d -> Optional.ofNullable(d.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)))
                .orElse(directory.flatMap(d -> Optional.ofNullable(d.getDate(ExifSubIFDDirectory.TAG_DATETIME))));
    }

    /**
     * Get the EXIF directory from the image path
     *
     * @param srcImagePath the image path
     * @return the EXIF directory if any
     */
    private static Optional<ExifSubIFDDirectory> getExifSubIFDDirectory(Path srcImagePath) {
        Optional<ExifSubIFDDirectory> directory = Optional.empty();
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(srcImagePath.toFile());
            // obtain the Exif directory
            directory = Optional.ofNullable(metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class));
        } catch (Exception e) {
            log.warn("[path={}, exception={}]", srcImagePath, e.getMessage());
        }
        return directory;
    }
}
