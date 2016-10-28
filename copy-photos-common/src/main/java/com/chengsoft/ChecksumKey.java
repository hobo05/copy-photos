package com.chengsoft;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

/**
 * Holds a source and destination path to be used for checksum caching
 *
 * @author tcheng
 */
@Data
@Builder
public class ChecksumKey {
    private Path source;
    private Path destination;
}
