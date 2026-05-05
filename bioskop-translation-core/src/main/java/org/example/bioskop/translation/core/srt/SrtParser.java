package org.example.bioskop.translation.core.srt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SrtParser {
    private static final Pattern TIMING = Pattern.compile(
        "^(\\d{2}:\\d{2}:\\d{2},\\d{1,3})\\s+-->\\s+(\\d{2}:\\d{2}:\\d{2},\\d{1,3})$"
    );

    public List<SubtitleCue> parse(String content) {
        if (content == null) {
            throw new MalformedSrtException("SRT content must not be null");
        }

        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isEmpty()) {
            return List.of();
        }

        String[] blocks = normalized.split("\\n\\s*\\n");
        List<SubtitleCue> cues = new ArrayList<>(blocks.length);
        for (String block : blocks) {
            cues.add(parseBlock(block));
        }
        return List.copyOf(cues);
    }

    private SubtitleCue parseBlock(String block) {
        String[] lines = block.split("\\n", -1);
        if (lines.length < 3) {
            throw new MalformedSrtException("SRT cue must contain id, timing, and text: " + block);
        }

        int id = parseId(lines[0].trim());
        Matcher timing = TIMING.matcher(lines[1].trim());
        if (!timing.matches()) {
            throw new MalformedSrtException("Invalid SRT timing line for cue " + id + ": " + lines[1]);
        }

        StringBuilder text = new StringBuilder();
        for (int i = 2; i < lines.length; i++) {
            if (i > 2) {
                text.append('\n');
            }
            text.append(lines[i]);
        }
        if (text.toString().isBlank()) {
            throw new MalformedSrtException("SRT cue text must not be blank for cue " + id);
        }

        return new SubtitleCue(
            id,
            parseTimestamp(timing.group(1)),
            parseTimestamp(timing.group(2)),
            text.toString(),
            null
        );
    }

    private int parseId(String value) {
        try {
            int id = Integer.parseInt(value);
            if (id <= 0) {
                throw new MalformedSrtException("SRT cue id must be positive: " + value);
            }
            return id;
        } catch (NumberFormatException e) {
            throw new MalformedSrtException("Invalid SRT cue id: " + value);
        }
    }

    static long parseTimestamp(String value) {
        String[] hourAndRest = value.split(":", 3);
        String[] secondAndMillis = hourAndRest[2].split(",", 2);
        long hours = Long.parseLong(hourAndRest[0]);
        long minutes = Long.parseLong(hourAndRest[1]);
        long seconds = Long.parseLong(secondAndMillis[0]);
        long millis = Long.parseLong(secondAndMillis[1]);
        return hours * 3_600_000 + minutes * 60_000 + seconds * 1_000 + millis;
    }
}
