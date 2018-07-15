package com.chengsoft

import java.nio.file.Path

/**
 * Holds the information necessary to copy a file
 *
 * @author tcheng
 */
data class FileCopyBean (val sourcePath: Path, val destinationFolder: Path, val destinationPath: Path)
