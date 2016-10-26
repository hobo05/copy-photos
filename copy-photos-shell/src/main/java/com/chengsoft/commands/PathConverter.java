package com.chengsoft.commands;

import org.springframework.shell.core.Completion;
import org.springframework.shell.core.Converter;
import org.springframework.shell.core.MethodTarget;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Created by tcheng on 10/24/16.
 */
@Component
public class PathConverter implements Converter<Path> {
    @Override
    public boolean supports(Class<?> requiredType, String optionContext) {
        return Path.class.isAssignableFrom(requiredType);
    }

    @Override
    public Path convertFromText(String value, Class<?> targetType, String optionContext) {
        return Paths.get(value);
    }

    @Override
    public boolean getAllPossibleValues(List<Completion> completions, Class<?> targetType, String existingData, String optionContext, MethodTarget target) {
        try {
            Path searchPath = Paths.get(existingData);
            if (!Files.isDirectory(searchPath))
                searchPath = searchPath.getParent();

            if (Files.exists(searchPath)) {
                    Files.walk(searchPath, 1)
                            .forEach(p -> completions.add(new Completion(p.toString())));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not get autocomplete values", e);
        }

        return false;
    }
}
