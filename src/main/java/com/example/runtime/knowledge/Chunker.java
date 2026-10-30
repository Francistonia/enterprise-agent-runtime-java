package com.example.runtime.knowledge;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class Chunker {
    public List<String> split(String text) {
        // Code-point boundaries preserve CJK and supplementary characters. Prefer paragraph endings.
        int[] points = text.replace("\r\n", "\n").codePoints().toArray();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < points.length) {
            int end = Math.min(start + 900, points.length);
            if (end < points.length) {
                for (int p = end - 1; p >= start + 450; p--) {
                    if (points[p] == '\n') { end = p + 1; break; }
                }
            }
            String chunk = new String(points, start, end - start).trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
            if (end == points.length) break;
            start = Math.max(start + 1, end - 100);
        }
        return chunks;
    }
}
