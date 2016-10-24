package com.chengsoft;

import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * Created by tcheng on 4/2/16.
 */
@Ignore
@Slf4j
public class PhotoProcessorTest {

    @Test
    public void testNormalCopy() throws IOException {

        List<String> ignoreFolders = ImmutableList.of("Thumbs");

        String inputFolder = "/Users/tcheng/phototest/input";
        String outputFolder = "/Users/tcheng/phototest/output";

//        String inputFolder = "/Volumes/NTFS/DCIM";
//        String outputFolder = "/Volumes/NTFS/output";

        PhotoProcessor.copyPhotos(inputFolder, outputFolder, ignoreFolders, false)
        .count()
        .subscribe(c -> log.info("Files Copied: {}", c));
    }
}