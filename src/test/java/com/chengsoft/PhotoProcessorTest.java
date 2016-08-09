package com.chengsoft;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.google.common.collect.ImmutableList;
import org.junit.Ignore;
import org.junit.Test;

import javax.activation.MimetypesFileTypeMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Created by tcheng on 4/2/16.
 */
public class PhotoProcessorTest {

    @Test
    public void testWalkFileTree() throws IOException {

        List<String> ignoreFolders = ImmutableList.of("Thumbs");

        String outputFolder = "H:\\Desktop\\Rand\\Pics\\FriendsFamily or Myself\\output";
        String inputFolder = "H:\\Desktop\\Rand\\Pics\\FriendsFamily or Myself\\iPhoto Library";

        PhotoProcessor.copyPhotos(inputFolder, outputFolder, ignoreFolders);
    }
}