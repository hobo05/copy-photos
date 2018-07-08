package com.chengsoft

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.willReturn
import org.mockito.Mockito.spy
import rx.Observable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch

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
}