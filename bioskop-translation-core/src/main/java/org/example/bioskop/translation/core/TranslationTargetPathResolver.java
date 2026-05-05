package org.example.bioskop.translation.core;

import java.util.regex.Pattern;

public class TranslationTargetPathResolver {
    private static final Pattern LANGUAGE_SUFFIX = Pattern.compile("(-[a-z]{2})+$");
    private static final Pattern TWO_LETTER_LANGUAGE = Pattern.compile("[a-z]{2}");

    public String resolve(String sourcePath, String targetLang, String requestedTargetPath) {
        if (requestedTargetPath != null && !requestedTargetPath.isBlank()) {
            return requestedTargetPath.trim();
        }
        if (targetLang == null || !TWO_LETTER_LANGUAGE.matcher(targetLang).matches()) {
            throw new IllegalArgumentException("targetLang must be two lowercase letters when targetPath is omitted");
        }
        String path = sourcePath.trim();
        int slash = path.lastIndexOf('/');
        String directory = slash < 0 ? "" : path.substring(0, slash + 1);
        String filename = slash < 0 ? path : path.substring(slash + 1);
        if (filename.isBlank()) {
            throw new IllegalArgumentException("sourcePath must include a filename");
        }
        int dot = filename.lastIndexOf('.');
        String name = dot < 0 ? filename : filename.substring(0, dot);
        String extension = dot < 0 ? "" : filename.substring(dot);
        String base = LANGUAGE_SUFFIX.matcher(name).replaceFirst("");
        if (base.isBlank()) {
            throw new IllegalArgumentException("sourcePath filename must have a non-language basename");
        }
        return directory + base + "-" + targetLang + extension;
    }
}
