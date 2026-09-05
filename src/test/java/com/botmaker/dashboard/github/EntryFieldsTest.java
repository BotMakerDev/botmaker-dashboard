package com.botmaker.dashboard.github;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntryFieldsTest {

    private static String valueOf(List<EntryFields.Field> fields, String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst()
                .map(EntryFields.Field::value).orElse(null);
    }

    @Test
    void everyKeyInTheFileIsShown_includingOnesThisAppHasNeverHeardOf() {
        // The point of reading the file rather than a schema: a field a submission carries that this window
        // does not know about is exactly the field a reviewer most needs to see.
        List<EntryFields.Field> fields = EntryFields.read("""
                {"id": "com.example.demo", "coordinate": "com.github.x:y:v1", "somethingNew": 3}
                """);
        assertEquals("com.example.demo", valueOf(fields, "id"));
        assertEquals("3", valueOf(fields, "somethingNew"));
    }

    @Test
    void theOrderIsTheFilesOwn() {
        List<EntryFields.Field> fields = EntryFields.read("{\"b\": 1, \"a\": 2}");
        assertEquals(List.of("b", "a"), fields.stream().map(EntryFields.Field::name).toList());
    }

    @Test
    void nestingIsDottedAndScalarArraysAreOneLine() {
        List<EntryFields.Field> fields = EntryFields.read("""
                {"author": {"login": "octocat"}, "tags": ["vision", "input"], "none": []}
                """);
        assertEquals("octocat", valueOf(fields, "author.login"));
        assertEquals("vision, input", valueOf(fields, "tags"));
        assertEquals("(empty)", valueOf(fields, "none"));
    }

    @Test
    void anArrayOfObjectsIsIndexed() {
        List<EntryFields.Field> fields = EntryFields.read("""
                {"valueTypes": [{"id": "one"}, {"id": "two"}]}
                """);
        assertEquals("one", valueOf(fields, "valueTypes[0].id"));
        assertEquals("two", valueOf(fields, "valueTypes[1].id"));
    }

    @Test
    void brokenJsonIsShownRatherThanThrown() {
        // A submission whose JSON does not parse is a real thing to review — the gate will have failed it,
        // and the window must be able to show what was actually submitted.
        List<EntryFields.Field> fields = EntryFields.read("{\"id\": ");
        assertTrue(fields.get(0).name().contains("not valid JSON"));
        assertEquals("{\"id\": ", valueOf(fields, "(raw)"));
    }

    @Test
    void anUnreadableFileSaysSo() {
        assertEquals("could not be read", EntryFields.read(null).get(0).value());
    }
}
