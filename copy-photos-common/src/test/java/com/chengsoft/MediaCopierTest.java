package com.chengsoft;

import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
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
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Created by tcheng on 4/2/16.
 */
@Ignore
@Slf4j
public class MediaCopierTest {

    @Test
    public void testCopyPhotos() throws IOException {

        List<String> ignoreFolders = ImmutableList.of("Thumbs");

        String inputFolder = "/Users/tcheng/phototest/input";
        String outputFolder = "/Users/tcheng/phototest/output";

//        String inputFolder = "/Volumes/NTFS/DCIM";
//        String outputFolder = "/Volumes/NTFS/output";

        MediaCopier mediaCopier = new MediaCopier(
                inputFolder,
                outputFolder,
                Media.IMAGE,
                ignoreFolders);
        mediaCopier.transferFiles(TransferMode.COPY, true)
                .count()
                .subscribe(c -> log.info("Files to copy: {}", c));

        mediaCopier.transferFiles(TransferMode.COPY, false)
                .count()
                .subscribe(c -> log.info("Files Copied: {}", c));
    }

    @Test
    public void testDetectMetadata() throws IOException, TikaException, SAXException, ParseException {

//        List<String> ignoreFolders = ImmutableList.of("Thumbs");
//
        String inputFolder = "/Users/tcheng/phototest/input";
//        String outputFolder = "/Users/tcheng/phototest/output";
//
//        MediaCopier.copyPhotos(inputFolder, outputFolder, ignoreFolders, false)
//                .count()
//                .subscribe(c -> log.info("Files Copied: {}", c));

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

//        Path videoPath = Paths.get("/Users/tcheng/phototest/input/VID_20160915_115528661.mp4");

        try (Stream<Path> pathStream = Files.walk(Paths.get(inputFolder))) {
            pathStream
                    .filter(Files::isRegularFile)
                    .forEach(videoPath -> {
                AutoDetectParser parser = new AutoDetectParser();
                BodyContentHandler handler = new BodyContentHandler();
                Metadata metadata = new Metadata();
                try (InputStream stream = Files.newInputStream(videoPath)) {
                    parser.parse(stream, handler, metadata);
                    log.info(handler.toString());
                    log.info("Metadata={}", metadata.toString());
                    Optional.ofNullable(metadata.get(TikaCoreProperties.CREATED))
                            .ifPresent(dateString -> log.info("Creation Date={}", uncheckedParse(dateFormat, dateString)));
                    log.info("Type={}", metadata.get(Metadata.CONTENT_TYPE));
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            });
        }
    }

    private Date uncheckedParse(DateFormat dateFormat, String string) {
        try {
            return dateFormat.parse(string);
        } catch (ParseException e) {
            throw new RuntimeException(e);
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