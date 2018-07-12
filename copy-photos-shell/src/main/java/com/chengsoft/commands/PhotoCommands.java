package com.chengsoft.commands;

import com.chengsoft.Media;
import com.chengsoft.MediaCopier;
import com.chengsoft.TransferMode;
import com.google.common.collect.ImmutableList;
import org.springframework.shell.core.CommandMarker;
import org.springframework.shell.core.annotation.CliCommand;
import org.springframework.shell.core.annotation.CliOption;
import org.springframework.stereotype.Component;
import rx.Observable;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
public class PhotoCommands implements CommandMarker {

    @CliCommand(value = "start-transfer", help = "Starts the transferring photos or videos")
    public String startTransfer(
            @CliOption(key = {"src"}, mandatory = true, help = "The source folder") final Path source,
            @CliOption(key = {"dest"}, mandatory = true, help = "The destination folder") final Path dest,
            @CliOption(key = {"type"}, unspecifiedDefaultValue = "ALL", help = "The file types to transfer") final Media media,
            @CliOption(key = {"mode"}, unspecifiedDefaultValue = "COPY", help = "The transfer mode") final TransferMode mode,
            @CliOption(key = {"ignoreFolders"}, help = "Folders to ignore") final String[] ignoreFolders,
            @CliOption(key = {"limit"}, help = "limit number of files to transfer") final Integer limit
    ) {

        // Convert ignore folder array to list
        List<String> ignoreFoldersList = ImmutableList.of();
        if (Objects.nonNull(ignoreFolders))
            ignoreFoldersList = ImmutableList.copyOf(ignoreFolders);

        MediaCopier mediaCopier = new MediaCopier(
                source.toString(),
                dest.toString(),
                media,
                ignoreFoldersList);

        final Observable<Path> dryRunObservable = mediaCopier.transferFiles(mode, true);
        final Observable<Path> transferObservable = mediaCopier.transferFiles(mode, false);
        Optional.ofNullable(limit).ifPresent(dryRunObservable::limit);
        Optional.ofNullable(limit).ifPresent(transferObservable::limit);

        Integer dryRunCount = dryRunObservable
                .count()
                .toBlocking()
                .single();

        if (dryRunCount == 0) {
            return "Dry run count is 0. No files will be "+mode;
        }

        Integer actualCount = transferObservable
                .count()
                .toBlocking()
                .single();

        return String.format("Files attempted to %1$s: %2$d Actual %1$s: %3$d", mode.toString(), dryRunCount, actualCount);
    }
}
