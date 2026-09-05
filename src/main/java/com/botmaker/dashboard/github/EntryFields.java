package com.botmaker.dashboard.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * A submitted entry file, flattened into name/value rows so a reviewer reads fields rather than JSON.
 *
 * <p><b>The field list comes from the file, never from a schema kept here.</b> What an entry must contain is
 * {@code RegistryGate}'s business and the registry's `CLAUDE.md`'s — a copy of it in this window would be a
 * fourth statement of the same rules, it would go stale on the first field added, and worst of all it would
 * *hide* a field a submission carries that this app has never heard of. That is exactly the field a reviewer
 * most needs to see. So every key is rendered, in the order the file wrote them.
 *
 * <p>Nesting is flattened with dotted names and arrays are indexed, because a plugin entry is shallow and a
 * tree control for two levels costs more than it explains. A value longer than a line is left whole: the
 * point of this view is to be able to read what was submitted, not to fit it.
 */
public final class EntryFields {

    /** One row: the dotted path to a value, and the value as text. */
    public record Field(String name, String value) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EntryFields() {
    }

    /**
     * Parses an entry file's text.
     *
     * <p>Unparseable text is a single field rather than an exception — a submission whose JSON is broken is
     * a real thing to review, and the gate will have failed it, so the window must be able to show it.
     */
    public static List<Field> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of(new Field("(file)", "could not be read"));
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return List.of(new Field("(not valid JSON)", e.getMessage()), new Field("(raw)", json));
        }
        List<Field> out = new ArrayList<>();
        flatten("", root, out);
        return List.copyOf(out);
    }

    private static void flatten(String prefix, JsonNode node, List<Field> out) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name ->
                    flatten(prefix.isEmpty() ? name : prefix + "." + name, node.get(name), out));
            return;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                out.add(new Field(prefix, "(empty)"));
                return;
            }
            // An array of scalars reads as one line; an array of objects gets an index per element, which
            // is the only shape where the position matters to a reviewer.
            boolean scalars = true;
            for (JsonNode item : node) {
                scalars &= item.isValueNode();
            }
            if (scalars) {
                List<String> items = new ArrayList<>();
                node.forEach(item -> items.add(item.asText()));
                out.add(new Field(prefix, String.join(", ", items)));
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), out);
            }
            return;
        }
        out.add(new Field(prefix.isEmpty() ? "(value)" : prefix, node.isNull() ? "null" : node.asText()));
    }
}
