package com.moonside.codedoc.services;

import com.moonside.codedoc.exceptions.IngestionException;
import com.moonside.codedoc.model.SourceFile;
import com.moonside.codedoc.model.enums.Language;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Parcourt le répertoire extrait pour y trouver les fichiers source, quel que
 * soit leur langage (voir Language : un fichier est retenu si son extension
 * est connue).
 */
@Service
public class SourceScannerService {

    public List<SourceFile> scan(Path projectRoot) {
        List<SourceFile> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> Language.fromPath(path.getFileName().toString()) != null)
                    .forEach(path -> files.add(toSourceFile(projectRoot, path)));
        } catch (IOException e) {
            throw new IngestionException("Impossible de parcourir le projet extrait.", e);
        }
        return files;
    }

    private SourceFile toSourceFile(Path projectRoot, Path file) {
        String relative = projectRoot.relativize(file).toString().replace('\\', '/');
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = 0;
        }
        return new SourceFile(file, relative, size);
    }
}