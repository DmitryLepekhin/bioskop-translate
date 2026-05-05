package org.example.bioskop.translation.core.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.example.bioskop.translation.core.srt.SubtitleCue;
import org.example.bioskop.translation.core.storage.TranslationStorage;

public class TranslationContextLoader {
    private final TranslationStorage storage;
    private final ObjectMapper objectMapper;

    public TranslationContextLoader(TranslationStorage storage) {
        this(storage, new ObjectMapper());
    }

    public TranslationContextLoader(TranslationStorage storage, ObjectMapper objectMapper) {
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    public TranslationContext loadForSourcePath(String sourcePath) {
        String directory = directory(sourcePath);
        String notes = readOptional(directory + "notes.md");
        List<CharacterInfo> characters = List.of();
        Map<Integer, String> speakersByCueId = Map.of();

        String charactersContent = readOptional(directory + "characters.json");
        if (charactersContent != null) {
            characters = parseCharacters(charactersContent);
        }

        String speakersContent = readOptional(directory + "speakers.csv");
        if (speakersContent != null) {
            speakersByCueId = parseSpeakers(speakersContent);
        }

        return new TranslationContext(notes, characters, speakersByCueId);
    }

    public List<SubtitleCue> attachSpeakers(List<SubtitleCue> cues, Map<Integer, String> speakersByCueId) {
        return cues.stream()
            .map(cue -> cue.withSpeaker(speakersByCueId.get(cue.id())))
            .toList();
    }

    private String readOptional(String path) {
        if (!storage.exists(path)) {
            return null;
        }
        return storage.readText(path);
    }

    private String directory(String sourcePath) {
        int slash = sourcePath.lastIndexOf('/');
        return slash < 0 ? "" : sourcePath.substring(0, slash + 1);
    }

    private List<CharacterInfo> parseCharacters(String content) {
        try {
            CharactersFile file = objectMapper.readValue(content, CharactersFile.class);
            return file.characters() == null ? List.of() : List.copyOf(file.characters());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse characters.json", e);
        }
    }

    private Map<Integer, String> parseSpeakers(String content) {
        Map<Integer, String> speakers = new HashMap<>();
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (i == 0 && line.equalsIgnoreCase("cue,character")) {
                continue;
            }
            String[] parts = line.split(",", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid speakers.csv line: " + line);
            }
            speakers.put(Integer.parseInt(parts[0].trim()), parts[1].trim());
        }
        return Map.copyOf(speakers);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CharactersFile(List<CharacterInfo> characters) {
    }
}
