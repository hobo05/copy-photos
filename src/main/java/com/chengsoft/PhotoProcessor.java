package com.chengsoft;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.annotations.NotNull;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private static final Logger logger = LogManager.getLogger(PhotoProcessor.class);
    private static final SimpleDateFormat FOLDER_DATE_FORMAT = new SimpleDateFormat("yyyy/yyyy_MM_dd");

    static void copyPhotos(String inputFolder, String outputFolder, List<String> ignoreFolders) throws IOException {
        List<String> caseInsensitiveIgnoreFolders = ignoreFolders.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList());

        try (Stream<Path> paths = Files.walk(Paths.get(inputFolder))) {
            paths.filter(p -> {
                String mimetype = new MimetypesFileTypeMap().getContentType(p.toFile());
                String type = mimetype.split("/")[0];
                return type.equals("image");
            })
                    .filter(p -> caseInsensitiveIgnoreFolders.stream()
                            .noneMatch(ignoreFolder -> p.toString().toLowerCase().contains(ignoreFolder)))
                    .forEach(srcImagePath -> {

                        Optional<ExifSubIFDDirectory> directory = Optional.empty();
                        try {
                            Metadata metadata = ImageMetadataReader.readMetadata(srcImagePath.toFile());
                            // obtain the Exif directory
                            directory = Optional.ofNullable(metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class));
                        } catch (Exception e) {
                            logger.warn("[path={}, exception={}]", srcImagePath, e.getMessage());
                        }

                        // Try 2 different methods to get the original photo date
                        Optional<Date> dateTaken = directory.map(d -> Optional.ofNullable(d.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)))
                                .orElse(directory.flatMap(d -> Optional.ofNullable(d.getDate(ExifSubIFDDirectory.TAG_DATETIME))));

                        // Use the last modified date as a last resort
                        Date lastModifiedDate = new Date(srcImagePath.toFile().lastModified());

                        logger.info("[dateTaken={}, lastModified={}, path={}]", dateTaken, lastModifiedDate, srcImagePath);

                        String folderName = dateTaken.map(FOLDER_DATE_FORMAT::format)
                                .orElse("lastModifiedDate/" + FOLDER_DATE_FORMAT.format(lastModifiedDate));
                        Path destFolderPath = Paths.get(outputFolder).resolve(folderName);
                        Path destImagePath = destFolderPath.resolve(srcImagePath.getFileName());

                        try {
                            // Create directory if necessary
                            if (Files.notExists(destFolderPath)) {
                                Files.createDirectories(destFolderPath);
                            }

                            Files.copy(srcImagePath, destImagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                            logger.info("Copied [srcImagePath={}, destImagePath={}]", srcImagePath, destImagePath);
                        } catch (IOException ex) {
                            logger.error("Error while copying [srcImagePath={}, destImagePath={}]", srcImagePath, destImagePath);
                        }

                    });
        } catch (IOException e) {
            throw e;
        }
    }
}
