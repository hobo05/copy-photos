package com.chengsoft.commands;

import com.chengsoft.Media;
import com.chengsoft.MediaCopier;
import com.google.common.collect.ImmutableList;
import org.springframework.shell.core.CommandMarker;
import org.springframework.shell.core.annotation.CliCommand;
import org.springframework.shell.core.annotation.CliOption;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

@Component
public class PhotoCommands implements CommandMarker {

    @CliCommand(value = "start-copy", help = "Starts the process of copying photos or videos")
    public String startCopy(
            @CliOption(key = {"src"}, mandatory = true, help = "The source folder") final Path source,
            @CliOption(key = {"dest"}, mandatory = true, help = "The destination folder") final Path dest,
            @CliOption(key = {"type"}, unspecifiedDefaultValue = "ALL", help = "The file types to transfer") final Media media,
            @CliOption(key = {"ignoreFolders"}, help = "Folders to ignore") final String[] ignoreFolders
    ) throws InterruptedException {

        // Convert ignore folder array to list
        List<String> ignoreFoldersList = ImmutableList.of();
        if (Objects.nonNull(ignoreFolders))
            ignoreFoldersList = ImmutableList.copyOf(ignoreFolders);

        MediaCopier mediaCopier = MediaCopier.builder()
                .inputFolder(source.toString())
                .outputFolder(dest.toString())
                .ignoreFolders(ignoreFoldersList)
                .media(media)
                .build();

        Integer dryRunCount = mediaCopier.copyFiles(true)
                .count()
                .toBlocking()
                .single();

        if (dryRunCount == 0) {
            return "Dry run count is 0. No files will be copied";
        }

        Integer actualCount = mediaCopier.copyFiles(false)
                .count()
                .toBlocking()
                .single();

        return String.format("Files attempted to copy: %d Actual copied: %d", dryRunCount, actualCount);
    }
}
