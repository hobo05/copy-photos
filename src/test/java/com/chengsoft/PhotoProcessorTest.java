package com.chengsoft;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import org.junit.Ignore;
import org.junit.Test;

import javax.activation.MimetypesFileTypeMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Created by tcheng on 4/2/16.
 */
public class PhotoProcessorTest {

    @Test
    @Ignore
    public void processAndWriteExcel() throws Exception {
//        String inputExcel = "src/test/resources/sample input.xls";
        String inputExcel = "src/test/resources/input file fluorescence 384 well plate.xls";
        String outputExcel = "src/test/resources/java-output.xlsx";
        PhotoProcessor.processAndWriteExcel(inputExcel, outputExcel);
    }

    @Test
    public void testWalkFileTree() {
        SimpleDateFormat folderDateFormat = new SimpleDateFormat("yyyy_MM_dd");

        String outputFolderName = "H:\\Desktop\\Rand\\Pics\\FriendsFamily or Myself\\output";
        try (Stream<Path> paths = Files.walk(Paths.get("H:\\Desktop\\Rand\\Pics\\FriendsFamily or Myself\\iPhoto Library"))) {
            paths.filter(p -> {
                String mimetype = new MimetypesFileTypeMap().getContentType(p.toFile());
                String type = mimetype.split("/")[0];
                return type.equals("image");
            })
                    .forEach(srcImagePath -> {
                        Optional<ExifSubIFDDirectory> directory = Optional.empty();
                        try {
                            Metadata metadata = ImageMetadataReader.readMetadata(srcImagePath.toFile());
                            // obtain the Exif directory
                            directory = Optional.ofNullable(metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class));
                        } catch (Exception e) {
                            System.err.printf("[path=%s, exception=%s]\n", srcImagePath, e.getMessage());
                        }

                        // Try 2 different methods to get the original photo date
                        Optional<Date> dateTaken = directory.map(d -> Optional.ofNullable(d.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)))
                                .orElse(directory.flatMap(d -> Optional.ofNullable(d.getDate(ExifSubIFDDirectory.TAG_DATETIME))));

                        // Use the last modified date as a last resort
                        Date lastModifiedDate = new Date(srcImagePath.toFile().lastModified());

                        System.out.printf("[dateTaken=%s, lastModified=%s, path=%s]\n", dateTaken, lastModifiedDate, srcImagePath);

                        String folderName = folderDateFormat.format(dateTaken.orElse(lastModifiedDate));
                        Path destFolderPath = Paths.get(outputFolderName).resolve(folderName);

                        try {
                            // Create directory if necessary
                            if (Files.notExists(destFolderPath)) {
                                Files.createDirectory(destFolderPath);
                            }

                            Path destImagePath = destFolderPath.resolve(srcImagePath.getFileName());
                            Files.copy(srcImagePath, destImagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                            System.out.printf("Copied [srcImagePath=%s, destImagePath=%s]\n", srcImagePath, destImagePath);
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }

                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}