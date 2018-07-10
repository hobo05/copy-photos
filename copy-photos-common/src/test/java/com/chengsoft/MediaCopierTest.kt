@file:Suppress("LocalVariableName")

package com.chengsoft

import com.chengsoft.MediaCopier.TIKA_DATE_FORMAT
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.willReturn
import org.mockito.Mockito.spy
import rx.Observable
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.stream.Collectors


class MediaCopierTest {

    @Rule
    @JvmField
    var temporaryFolder = TemporaryFolder()

    private val inputFolder: File by lazy {
        temporaryFolder.newFolder("input")
    }
    private val outputFolder: File by lazy {
        temporaryFolder.newFolder("output")
    }

    @Test
    @Throws(IOException::class)
    fun root_folder_single_file_found() {
        // given
        val rootPhoto = createPhoto(inputFolder.toPath(), "photo_")

        // when
        val filteredPaths = defaultMediaCopier().filteredPaths.toList().toBlocking().single()

        // then
        assertThat(filteredPaths).hasSize(1).containsOnly(rootPhoto)
    }

    @Test
    @Throws(IOException::class)
    fun throws_exception_on_nonexistent_path() {
        // given
        val nonExistentPath = inputFolder.resolve("nonExistentFolder")
        val mediaCopier = MediaCopier(nonExistentPath.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())

        // when
        val thrown = catchThrowable { mediaCopier.filteredPaths.toList().toBlocking().single() }

        // then
        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
    }

    @Test
    @Throws(IOException::class)
    fun folder_not_returned() {
        // given
        createFolder("notAFile")

        // when
        val filteredPaths = defaultMediaCopier().filteredPaths.toList().toBlocking().single()

        // then
        assertThat(filteredPaths).isEmpty()
    }

    @Test
    @Throws(IOException::class)
    fun hidden_file_not_returned() {
        // given
        createPhoto(prefix = ".hidden")

        // when
        val filteredPaths = defaultMediaCopier().filteredPaths.toList().toBlocking().single()

        // then
        assertThat(filteredPaths).isEmpty()
    }

    @Test
    @Throws(IOException::class)
    fun nested_photo_found() {
        // given
        val rootPhoto = createPhoto(inputFolder.toPath(), "photo_")
        val nestedFolder = createFolder("nestedFolder")
        val nestedPhoto = createPhoto(nestedFolder, "photo_")

        // when
        val filteredPaths = defaultMediaCopier().filteredPaths.toList().toBlocking().single()

        // then
        assertThat(filteredPaths).hasSize(2).containsOnly(rootPhoto, nestedPhoto)
    }

    @Test
    @Throws(IOException::class)
    fun ignored_folders_return_no_paths() {
        // given
        val expectedSize = 3
        val expectedFolder = createFolder("expectedFolder")
        val rootPhotos = createPhotos(expectedSize, expectedFolder)
        val ignoredFolder = createFolder("ignoredFolder")
                .also { createPhoto(it, "photo_") }
        val mediaCopier = defaultMediaCopier(ignoreFolders = listOf(ignoredFolder.toString()))

        // when
        val filteredPaths = mediaCopier.filteredPaths.toList().toBlocking().single()

        // then
        assertThat(filteredPaths).hasSize(expectedSize).containsOnly(*rootPhotos)
    }

    @Test
    @Throws(IOException::class)
    fun ignored_folders_case_insensitive() {
        // given
        val rootPhoto = createPhoto(prefix = "photo_")
        val ignoredFolder = createFolder("ignoredFolder")
                .also { createPhoto(it, "photo_") }
        val ignoredFolderVariedCases = ignoredFolder.parent.resolve("IgnOrEdfoldEr").toString()
        val mediaCopier = defaultMediaCopier(ignoreFolders = listOf(ignoredFolderVariedCases))

        // when
        val filteredPaths = mediaCopier.filteredPaths.toList().toBlocking().single()

        // then
        assertThat(filteredPaths).hasSize(1).containsOnly(rootPhoto)
    }

    @Test
    @Throws(IOException::class)
    fun results_are_cached() {
        // given
        val countDownLatch = CountDownLatch(1)
        val rootPhoto = createPhoto(inputFolder.toPath(), "photo_")
        val filteredPathsObservable = defaultMediaCopier().filteredPaths.toList()

        // Check that there is 1 file
        filteredPathsObservable.subscribe {
            assertThat(it).hasSize(1).containsOnly(rootPhoto)

            // when we delete the photo
            Files.delete(rootPhoto)

            // then we still see that there is 1 file
            filteredPathsObservable.subscribe {
                assertThat(it).hasSize(1).containsOnly(rootPhoto)
                countDownLatch.countDown()
            }
        }

        // Wait for subscription to finish
        countDownLatch.await()
    }

    @Test
    fun getPathsGroupedByMediaType() {
        // given
        val mediaMap = mapOf("jpeg" to Media.IMAGE, "png" to Media.IMAGE, "avi" to Media.VIDEO)
        val paths = mediaMap.keys.map { Paths.get(it) }

        val mediaCopier = spy(defaultMediaCopier { p -> mediaMap[p.toString()].toString() })
        val filteredPaths = Observable.from(paths)
        willReturn(filteredPaths).given(mediaCopier).filteredPaths

        // when
        val groupedPaths = mediaCopier.pathsGroupedByMediaType.toMap({ it.key }, { it }).toBlocking().single()
                .mapValues { it.value.toList().toBlocking().single() }

        // then
        assertThat(groupedPaths).isEqualTo(mapOf(
                "image" to listOf(Paths.get("jpeg"), Paths.get("png")),
                "video" to listOf(Paths.get("avi"))))
    }

    @Test
    fun copy_single_file() {
        // given
        val sourcePhoto = createPhoto()
        val destinationPath = outputFolder.toPath().resolve(sourcePhoto.fileName)
        val copyBean = FileCopyBean(sourcePhoto, outputFolder.toPath(), destinationPath)

        // when
        val copiedPhotos = defaultMediaCopier().transferSingleFile(copyBean, TransferMode.COPY, false).toList().toBlocking().single()

        // then
        assertThat(sourcePhoto).exists()
        val outputFolderFiles = Files.walk(outputFolder.toPath()).map { it.fileName.toString() }.collect(Collectors.toList())
        assertThat(copiedPhotos).hasSize(1)
        assertThat(copiedPhotos.first()).`as`("copied file should be one of outputFolderFiles=$outputFolderFiles")
                .isRegularFile()
                .exists()

    }

    @Test
    fun move_single_file() {
        // given
        val sourcePhoto = createPhoto()
        val destinationPath = outputFolder.toPath().resolve(sourcePhoto.fileName)
        val copyBean = FileCopyBean(sourcePhoto, outputFolder.toPath(), destinationPath)

        // when
        val movedPhotos = defaultMediaCopier().transferSingleFile(copyBean, TransferMode.MOVE, false).toList().toBlocking().single()

        // then
        assertThat(sourcePhoto).doesNotExist()
        val outputFolderFiles = Files.walk(outputFolder.toPath()).map { it.fileName.toString() }.collect(Collectors.toList())
        assertThat(movedPhotos).hasSize(1)
        assertThat(movedPhotos.first()).`as`("moved file should be one of outputFolderFiles=$outputFolderFiles")
                .isRegularFile()
                .exists()

    }

    @Test
    fun transfer_file_to_nonexistent_folder_creates_it() {
        // given
        val sourcePhoto = createPhoto()
        val nonexistentFolder = outputFolder.toPath().resolve("new")
        val destinationPath = nonexistentFolder.resolve(sourcePhoto.fileName)
        val copyBean = FileCopyBean(sourcePhoto, nonexistentFolder, destinationPath)

        // when
        val copiedPhoto = defaultMediaCopier().transferSingleFile(copyBean, TransferMode.COPY, false).toBlocking().single()

        // then
        assertThat(copiedPhoto).exists()

    }

    @Test
    fun transfer_file_does_not_overwrite_same_file() {
        // given
        val sourcePhoto = createPhoto()
        val destinationFolder = outputFolder.toPath()
        val destinationPath = destinationFolder.resolve(sourcePhoto.fileName)
        val copyBean = FileCopyBean(sourcePhoto, destinationFolder, destinationPath)

        Files.copy(sourcePhoto, destinationPath)

        // when
        val copiedPhoto = defaultMediaCopier().transferSingleFile(copyBean, TransferMode.COPY, false).toBlocking().singleOrDefault(null)

        // then
        assertThat(copiedPhoto).isNull()

    }

    @Test
    fun transfer_file_does_overwrites_different_file() {
        // given
        val sourcePhoto = createPhoto()
        val destinationFolder = outputFolder.toPath()
        val destinationPath = destinationFolder.resolve(sourcePhoto.fileName)
        val copyBean = FileCopyBean(sourcePhoto, destinationFolder, destinationPath)

        val fileWithDifferentContent = Files.createFile(destinationPath)
        fileWithDifferentContent.toFile().writeText("different file")

        // when
        val copiedPhoto = defaultMediaCopier().transferSingleFile(copyBean, TransferMode.COPY, false).toBlocking().singleOrDefault(null)

        // then
        assertThat(copiedPhoto)
                .isNotNull()
                .isRegularFile()

    }

    @Test
    fun return_empty_date() {
        // given
        val photo = createPhoto()

        // when
        val dateTaken = defaultMediaCopier().getDateTaken(photo)

        // then
        assertThat(dateTaken).isNotPresent
    }

    @Test
    fun files_grouped_by_year() {
        // given
        val creationTime1999 = LocalDateTime.of(1999, Month.JANUARY, 1, 0, 0)
        val creationTime2001 = LocalDateTime.of(2001, Month.JANUARY, 1, 0, 0)
        val photoFolderA = createFolder("photoFolderA")
        val photoFolderB = createFolder("photoFolderB")
        val photoFolderC = createFolder("photoFolderC")
        val photoA = createPhoto(photoFolderA, "photoA_", creationTime1999)
        val photoB = createPhoto(photoFolderB, "photoB_", creationTime1999)
        val photoC = createPhoto(photoFolderC, "photoC_", creationTime2001)

        val mediaCopier = defaultMediaCopier { Media.IMAGE.toString() }

        // when
        val copiedPaths = mediaCopier.transferFiles(TransferMode.COPY, false).toList().toBlocking().single()

        // then
        assertThat(copiedPaths).hasSize(3)
                .containsOnly(
                        photoA.toDestPath("1999/1999_01_01"),
                        photoB.toDestPath("1999/1999_01_01"),
                        photoC.toDestPath("2001/2001_01_01")
                )
    }

    @Test
    fun files_grouped_by_year_and_date() {
        // given
        val creationTime2009_05_05 = LocalDateTime.of(2009, Month.MAY, 5, 0, 0)
        val creationTime2009_05_06 = LocalDateTime.of(2009, Month.MAY, 6, 0, 0)
        val photoFolderA = createFolder("photoFolderA")
        val photoFolderB = createFolder("photoFolderB")
        val photoFolderC = createFolder("photoFolderC")
        val photoA = createPhoto(photoFolderA, "photoA_", creationTime2009_05_05)
        val photoB = createPhoto(photoFolderB, "photoB_", creationTime2009_05_05)
        val photoC = createPhoto(photoFolderC, "photoC_", creationTime2009_05_06)

        val mediaCopier = defaultMediaCopier { Media.IMAGE.toString() }

        // when
        val copiedPaths = mediaCopier.transferFiles(TransferMode.COPY, false).toList().toBlocking().single()

        // then
        assertThat(copiedPaths).hasSize(3)
                .containsOnly(
                        photoA.toDestPath("2009/2009_05_05"),
                        photoB.toDestPath("2009/2009_05_05"),
                        photoC.toDestPath("2009/2009_05_06")
                )
    }

    @Test
    fun transfer_only_photos() {
        // given
        val creationDateTime = LocalDateTime.of(2029, Month.JANUARY, 22, 0, 0)
        val mixMediaFolder = createFolder("mixMediaFolder")
        val photo = createPhoto(mixMediaFolder, creation = creationDateTime)
        createVideo(mixMediaFolder)

        // when
        val copiedPaths = defaultMediaCopier().transferFiles(TransferMode.COPY, false).toList().toBlocking().single()

        // then
        assertThat(copiedPaths).hasSize(1)
                .containsOnly(photo.toDestPath("2029/2029_01_22"))
    }

    @Test
    fun transfer_only_videos() {
        // given
        val mixMediaFolder = createFolder("mixMediaFolder")
        createPhoto(mixMediaFolder)
        val video = createVideo(mixMediaFolder)

        // when
        val copiedPaths = defaultMediaCopier(Media.VIDEO).transferFiles(TransferMode.COPY, false).toList().toBlocking().single()

        // then
        assertThat(copiedPaths).hasSize(1)
                .containsOnly(video.toDestPath("2018/2018_07_10"))
    }

    @Test
    fun transfer_all_files() {
        // given
        val creationDateTime = LocalDateTime.of(2029, Month.JANUARY, 22, 0, 0)
        val mixMediaFolder = createFolder("mixMediaFolder")
        val photo = createPhoto(mixMediaFolder, creation = creationDateTime)
        val video = createVideo(mixMediaFolder)
        val textFile = Files.createTempFile(mixMediaFolder, "text", ".txt")
        textFile.toFile().writeText("this is a text file")
        textFile.setLastModifiedTime(creationDateTime)

        // when
        val copiedPaths = defaultMediaCopier(Media.ALL).transferFiles(TransferMode.COPY, false).toList().toBlocking().single()

        // then
        assertThat(copiedPaths).hasSize(3)
                .containsOnly(
                        photo.toDestPath("2029/2029_01_22"),
                        video.toDestPath("2018/2018_07_10"),
                        textFile.toDestPath("lastModifiedDate/2029/2029_01_22")
                )
    }

    private fun Path.toDestPath(folders: String) = outputFolder.toPath().resolve(folders).resolve(this.fileName)

    private fun defaultMediaCopier(media: Media = Media.IMAGE,
                                   ignoreFolders: List<String> = emptyList(),
                                   mediaTypeResolver: ((Path) -> String)? = null
    ): MediaCopier {
        return if (mediaTypeResolver != null) {
            MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, media, ignoreFolders, mediaTypeResolver)
        } else {
            MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, media, ignoreFolders)
        }
    }

    private fun createFolder(folderName: String): Path =
            inputFolder.toPath().resolve(folderName).also { Files.createDirectory(it) }


    private fun createPhotos(num: Int, path: Path?): Array<Path> =
            (1..num).asIterable()
                    .map { createPhoto(path, "photo_") }
                    .toTypedArray()

    private fun createPhoto(path: Path? = inputFolder.toPath(),
                            prefix: String = "photo_",
                            creation: LocalDateTime? = null,
                            lastModified: LocalDateTime? = null): Path {
        val photo = Files.createTempFile(path, prefix, ".jpg")
        val sampleJpg = Paths.get(this::class.java.classLoader.getResource("sample.jpg").file)

        Files.copy(sampleJpg, photo, StandardCopyOption.REPLACE_EXISTING)

        if (creation != null) {
            val creationString = TIKA_DATE_FORMAT.format(Date.from(creation.toInstant(ZoneOffset.UTC)))

            BufferedOutputStream(FileOutputStream(photo.toFile())).use {
                val outputSet = TiffOutputSet()
                val exifDirectory = outputSet.orCreateExifDirectory
                exifDirectory.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, creationString)
                ExifRewriter().updateExifMetadataLossless(sampleJpg.toFile(), it, outputSet)
            }
        }

        lastModified?.also { photo.setLastModifiedTime(it) }

        return photo
    }

    private fun createVideo(path: Path? = inputFolder.toPath(),
                            prefix: String = "video_",
                            lastModified: LocalDateTime? = null): Path {
        val video = Files.createTempFile(path, prefix, ".jpg")
        val sampleVideo = Paths.get(this::class.java.classLoader.getResource("sample.m4v").file)

        Files.copy(sampleVideo, video, StandardCopyOption.REPLACE_EXISTING)

        lastModified?.also { video.setLastModifiedTime(it) }

        return video
    }

    private fun Path.setLastModifiedTime(lastModified: LocalDateTime) {
        Files.getFileAttributeView(this, BasicFileAttributeView::class.java)
                .setTimes(FileTime.from(lastModified.toInstant(ZoneOffset.UTC)), null, null)
    }
}