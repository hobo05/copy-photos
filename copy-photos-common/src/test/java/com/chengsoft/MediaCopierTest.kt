package com.chengsoft

import com.chengsoft.MediaCopier.TIKA_DATE_FORMAT
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.AutoDetectParser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.*
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.xml.sax.ContentHandler
import rx.Observable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.stream.Collectors


class MediaCopierTest {

    @Rule
    @JvmField
    var temporaryFolder = TemporaryFolder()

    val inputFolder: File by lazy {
        temporaryFolder.newFolder("input")
    }
    val outputFolder: File by lazy {
        temporaryFolder.newFolder("output")
    }

    @Test
    @Throws(IOException::class)
    fun root_folder_single_file_found() {
        // given
        val rootPhoto = createPhoto(inputFolder.toPath(), "photo_")
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())

        // when
        val filteredPaths = mediaCopier.filteredPaths.toList().toBlocking().single()

        // then
        assertThat(filteredPaths).hasSize(1).containsOnly(rootPhoto)
    }

    @Test
    @Throws(IOException::class)
    fun throws_exception_on_nonexisting_path() {
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
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())

        // when
        val filteredPaths = mediaCopier.filteredPaths.toList().toBlocking().single()

        // then
        assertThat(filteredPaths).isEmpty()
    }

    @Test
    @Throws(IOException::class)
    fun hidden_file_not_returned() {
        // given
        createPhoto(prefix = ".hidden")
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())

        // when
        val filteredPaths = mediaCopier.filteredPaths.toList().toBlocking().single()

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
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())

        // when
        val filteredPaths = mediaCopier.filteredPaths.toList().toBlocking().single()

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
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, listOf(ignoredFolder.toString()))

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
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE,
                listOf(ignoredFolderVariedCases))

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
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())
        val filteredPathsObservable = mediaCopier.filteredPaths.toList()

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

    private fun createFolder(folderName: String): Path =
            inputFolder.toPath().resolve(folderName).also { Files.createDirectory(it) }


    private fun createPhotos(num: Int, path: Path?): Array<Path> =
            (1..num).asIterable()
                    .map { createPhoto(path, "photo_") }
                    .toTypedArray()


    private fun createPhoto(path: Path? = inputFolder.toPath(), prefix: String = "photo_") = Files.createTempFile(path, prefix, ".jpg")

    @Test
    fun getPathsGroupedByMediaType() {
        // given
        val mediaMap = mapOf("jpeg" to Media.IMAGE, "png" to Media.IMAGE, "avi" to Media.VIDEO)
        val paths = mediaMap.keys.map { Paths.get(it) }

        val mediaCopier = spy(MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList()
        ) { p -> mediaMap[p.toString()].toString() })
        val filteredPaths = Observable.from(paths)
        willReturn(filteredPaths).given(mediaCopier).filteredPaths

        // when
        val groupedPaths = mediaCopier.pathsGroupedByMediaType.toMap({it.key}, {it}).toBlocking().single()
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
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())
        val destinationPath = outputFolder.toPath().resolve(sourcePhoto.fileName)
        val copyBean = FileCopyBean(sourcePhoto, outputFolder.toPath(), destinationPath)

        // when
        val copiedPhotos = mediaCopier.transferSingleFile(copyBean, TransferMode.COPY, false).toList().toBlocking().single()

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
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())
        val destinationPath = outputFolder.toPath().resolve(sourcePhoto.fileName)
        val copyBean = FileCopyBean(sourcePhoto, outputFolder.toPath(), destinationPath)

        // when
        val movedPhotos = mediaCopier.transferSingleFile(copyBean, TransferMode.MOVE, false).toList().toBlocking().single()

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
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())
        val nonexistentFolder = outputFolder.toPath().resolve("new")
        val destinationPath = nonexistentFolder.resolve(sourcePhoto.fileName)
        val copyBean = FileCopyBean(sourcePhoto, nonexistentFolder, destinationPath)

        // when
        val copiedPhoto = mediaCopier.transferSingleFile(copyBean, TransferMode.COPY, false).toBlocking().single()

        // then
        assertThat(copiedPhoto).exists()

    }

    @Test
    fun transfer_file_does_not_overwrite_same_file() {
        // given
        val sourcePhoto = createPhoto()
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())
        val destinationFolder = outputFolder.toPath()
        val destinationPath = destinationFolder.resolve(sourcePhoto.fileName)
        val copyBean = FileCopyBean(sourcePhoto, destinationFolder, destinationPath)

        Files.copy(sourcePhoto, destinationPath)

        // when
        val copiedPhoto = mediaCopier.transferSingleFile(copyBean, TransferMode.COPY, false).toBlocking().singleOrDefault(null)

        // then
        assertThat(copiedPhoto).isNull()

    }

    @Test
    fun transfer_file_does_overwrites_different_file() {
        // given
        val sourcePhoto = createPhoto()
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())
        val destinationFolder = outputFolder.toPath()
        val destinationPath = destinationFolder.resolve(sourcePhoto.fileName)
        val copyBean = FileCopyBean(sourcePhoto, destinationFolder, destinationPath)

        val fileWithDifferentContent = Files.createFile(destinationPath)
        fileWithDifferentContent.toFile().writeText("different file")

        // when
        val copiedPhoto = mediaCopier.transferSingleFile(copyBean, TransferMode.COPY, false).toBlocking().singleOrDefault(null)

        // then
        assertThat(copiedPhoto)
                .isNotNull()
                .isRegularFile()

    }

    @Test
    fun return_empty_date() {
        // given
        val photo = createPhoto()
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())
        val autoDetectParser = mock(AutoDetectParser::class.java)

        // when
        val dateTaken = mediaCopier.getDateTaken(photo, autoDetectParser)

        // then
        assertThat(dateTaken).isNotPresent
    }

    @Test
    fun createdDate_has_first_priority() {
        // given
        val photo = createPhoto()
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())

        val createdDate = Date()
        val createdDateString = TIKA_DATE_FORMAT.format(createdDate)
        val originalDate = Date(963068536)
        val originalDateString = TIKA_DATE_FORMAT.format(originalDate)

        val autoDetectParser = mock(AutoDetectParser::class.java)
        given(autoDetectParser.parse(any(InputStream::class.java), any(ContentHandler::class.java), any(Metadata::class.java)))
                .willAnswer {
                    val (_,_,metadataObject) = it.arguments
                    val metadata = metadataObject as Metadata
                    metadata.add(TikaCoreProperties.CREATED, createdDateString)
                    metadata.add(Metadata.ORIGINAL_DATE, originalDateString)
                    Unit
                }

        // when
        val dateTaken = mediaCopier.getDateTaken(photo, autoDetectParser)

        // then
        val parseDate = TIKA_DATE_FORMAT.parse(createdDateString)
        assertThat(dateTaken).isPresent.hasValue(parseDate)
    }

    @Test
    fun createdDate_has_second_priority() {
        // given
        val photo = createPhoto()
        val mediaCopier = MediaCopier(inputFolder.absolutePath, outputFolder.absolutePath, Media.IMAGE, emptyList())

        val originalDate = Date(963068536)
        val originalDateString = TIKA_DATE_FORMAT.format(originalDate)

        val autoDetectParser = mock(AutoDetectParser::class.java)
        given(autoDetectParser.parse(any(InputStream::class.java), any(ContentHandler::class.java), any(Metadata::class.java)))
                .willAnswer {
                    val (_,_,metadataObject) = it.arguments
                    val metadata = metadataObject as Metadata
                    metadata.add(Metadata.ORIGINAL_DATE, originalDateString)
                    Unit
                }

        // when
        val dateTaken = mediaCopier.getDateTaken(photo, autoDetectParser)

        // then
        val parseDate = TIKA_DATE_FORMAT.parse(originalDateString)
        assertThat(dateTaken).isPresent.hasValue(parseDate)
    }
}