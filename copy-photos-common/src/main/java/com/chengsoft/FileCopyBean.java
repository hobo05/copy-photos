package com.chengsoft;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

/**
 * Holds the information necessary to copy a file
 *
 * @author tcheng
 */
@Data
@Builder
public class FileCopyBean {
    private Path sourcePath;
    private Path destinationFolder;
    private Path destinationPath;
}
