package com.chengsoft;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.functions.Func1;

import javax.activation.MimetypesFileTypeMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
class PhotoProcessor {
    private static final Logger log = LoggerFactory.getLogger(PhotoProcessor.class);
    private static final SimpleDateFormat FOLDER_DATE_FORMAT = new SimpleDateFormat("yyyy/yyyy_MM_dd");

    static Observable<Path> copyPhotos(String inputFolder, String outputFolder, List<String> ignoreFolders, boolean dryRun) {
        List<String> caseInsensitiveIgnoreFolders = ignoreFolders.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList());

        Func1<Path, Boolean> filterOutIgnoredFolder = p -> caseInsensitiveIgnoreFolders.stream()
                .noneMatch(ignoreFolder -> p.toString().toLowerCase().contains(ignoreFolder));

        Stream<Path> pathStream;
        try {
            pathStream = Files.walk(Paths.get(inputFolder));
        } catch (IOException e) {
            throw new RuntimeException(String.format("Error loading paths from inputFolder=%s with exception: ", inputFolder), e);
        }
        return Observable.from(pathStream::iterator)
                .filter(PhotoProcessor::filterByImages)
                .filter(filterOutIgnoredFolder)
                .map(p -> PhotoProcessor.createFileCopyBean(p, outputFolder))
                .flatMap(b -> PhotoProcessor.copyFile(b, dryRun));
    }

    private static Observable<Path> copyFile(FileCopyBean bean, boolean dryRun) {
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
            if (!dryRun) {
                action = "Copied";
                Files.copy(srcImagePath, destImagePath, StandardCopyOption.COPY_ATTRIBUTES);
            }

            log.info("{} [srcImage={}, destImagePath={}]", action, srcImagePath.getFileName(), destImagePath);
            return Observable.just(destImagePath);
        } catch (IOException ex) {
            log.error("Error while copying [srcImage={}, destImagePath={}, exception={}]", srcImagePath.getFileName(), destImagePath, ex);
            return Observable.empty();
        }
    }

    /**
     * Create the {@link FileCopyBean} by pulling the EXIF data and creating the destination path and folder
     *
     * @param srcImagePath the source image Path
     * @param outputFolder the output folder
     * @return the {@link FileCopyBean}
     */
    private static FileCopyBean createFileCopyBean(Path srcImagePath, String outputFolder) {
        // Try 2 different methods to get the original photo date
        Optional<ExifSubIFDDirectory> directory = getExifSubIFDDirectory(srcImagePath);
        Optional<Date> dateTaken = directory.map(d -> Optional.ofNullable(d.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)))
                .orElse(directory.flatMap(d -> Optional.ofNullable(d.getDate(ExifSubIFDDirectory.TAG_DATETIME))));

        // Use the last modified date as a last resort
        Date lastModifiedDate = new Date(srcImagePath.toFile().lastModified());

        log.debug("[dateTaken={}, lastModified={}, path={}]", dateTaken, lastModifiedDate, srcImagePath);

        String folderName = dateTaken.map(FOLDER_DATE_FORMAT::format)
                .orElse("lastModifiedDate/" + FOLDER_DATE_FORMAT.format(lastModifiedDate));
        Path destFolderPath = Paths.get(outputFolder).resolve(folderName);
        Path destImagePath = destFolderPath.resolve(srcImagePath.getFileName());

        return FileCopyBean.builder()
                .sourcePath(srcImagePath)
                .destinationFolder(destFolderPath)
                .destinationPath(destImagePath)
                .build();
    }

    /**
     * Bean that holds info to copy one file to a destination
     */
    @Data
    @Builder
    private static class FileCopyBean {
        private Path sourcePath;
        private Path destinationFolder;
        private Path destinationPath;
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

    /**
     * Only matches paths that point to images
     *
     * @param path the path of the image
     * @return whether the path points to an image
     */
    private static boolean filterByImages(Path path) {
        String mimetype = new MimetypesFileTypeMap().getContentType(path.toFile());
        String type = mimetype.split("/")[0];
        boolean isBitmap = com.google.common.io.Files.getFileExtension(path.toString()).equalsIgnoreCase("bmp");
        return type.equals("image") || isBitmap;
    }
}
