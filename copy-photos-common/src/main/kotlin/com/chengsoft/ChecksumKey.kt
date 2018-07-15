package com.chengsoft

import java.nio.file.Path

/**
 * Holds a source and destination path to be used for checksum caching
 *
 * @author tcheng
 */
data class ChecksumKey(val source: Path, val destination: Path)
