package com.chengsoft;

import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.Ignore;
import org.junit.Test;
import org.xml.sax.SAXException;
import rx.Observable;
import rx.observables.GroupedObservable;

import javax.activation.MimetypesFileTypeMap;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.stream.Stream;

import static com.chengsoft.PhotoProcessor.*;

/**
 * Created by tcheng on 4/2/16.
 */
@Ignore
@Slf4j
public class PhotoProcessorTest {

    @Test
    public void testCopyPhotos() throws IOException {

        List<String> ignoreFolders = ImmutableList.of("Thumbs");

        String inputFolder = "/Users/tcheng/phototest/input";
        String outputFolder = "/Users/tcheng/phototest/output";

//        String inputFolder = "/Volumes/NTFS/DCIM";
//        String outputFolder = "/Volumes/NTFS/output";

        copyFiles(inputFolder, outputFolder, ignoreFolders, Media.VIDEO, false)
                .count()
                .subscribe(c -> log.info("Files Copied: {}", c));
    }

    @Test
    public void testCopyVideos() throws IOException, TikaException, SAXException, ParseException {

//        List<String> ignoreFolders = ImmutableList.of("Thumbs");
//
//        String inputFolder = "/Users/tcheng/phototest/input";
//        String outputFolder = "/Users/tcheng/phototest/output";
//
//        PhotoProcessor.copyPhotos(inputFolder, outputFolder, ignoreFolders, false)
//                .count()
//                .subscribe(c -> log.info("Files Copied: {}", c));

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

        Path videoPath = Paths.get("/Users/tcheng/phototest/input/VID_20160915_115528661.mp4");
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler();
        Metadata metadata = new Metadata();
        try (InputStream stream = Files.newInputStream(videoPath)) {
            parser.parse(stream, handler, metadata);
            log.info(handler.toString());
            log.info(metadata.toString());
            String dateString = metadata.get(TikaCoreProperties.CREATED);
            log.info("Creation Date={}", dateFormat.parse(dateString));
//            log.info("Type={}", metadata.get(TikaCoreProperties.TYPE));
            log.info("Type={}", metadata.get(Metadata.CONTENT_TYPE));
            log.info("Java Files type={}", Files.probeContentType(videoPath));
            String mimetype = new MimetypesFileTypeMap().getContentType(videoPath.toFile());
            log.info("Mimetype: {}", mimetype);

            TikaConfig tika = new TikaConfig();
            MediaType mediaType = tika.getDetector().detect(TikaInputStream.get(videoPath), new Metadata());
            log.info("Tika mediatype: {}", mediaType.getType());
        }
    }

    @Test
    public void groupObservable() {
        String inputFolder = "/Users/tcheng/phototest/input";
        Stream<Path> pathStream = Stream.empty();
        try {
            pathStream = Files.walk(Paths.get(inputFolder));
        } catch (IOException e) {
            e.printStackTrace();
        }

        Observable<GroupedObservable<String, Path>> groupBy = Observable.from(pathStream::iterator)
                .filter(Files::isRegularFile)
                .groupBy(this::getMediaType)
                .cache(); // important since streams cannot be reused

        groupBy
                .filter(go -> go.getKey().equals("image"))
                .flatMap(a -> a)
                .subscribe(media -> log.info(media.toString()));

        log.info("break");
        groupBy.filter(go -> go.getKey().equals("video"))
                .flatMap(a -> a)
                .subscribe(media -> log.info(media.toString()));
    }

    private String getMediaType(Path path) {
        try {
            TikaConfig tika = new TikaConfig();
            return tika.getDetector().detect(TikaInputStream.get(path), new Metadata()).getType();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean filterByImages(Path path) {
        String mimetype = new MimetypesFileTypeMap().getContentType(path.toFile());
        String type = mimetype.split("/")[0];
        boolean isBitmap = com.google.common.io.Files.getFileExtension(path.toString()).equalsIgnoreCase("bmp");
        return type.equals("image") || isBitmap;
    }
}