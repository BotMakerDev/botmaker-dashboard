package com.botmaker.dashboard.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What GitHub's repository object is read as.
 *
 * <p>Worth a test although it is four lines, because every branch but the first one is a *failure* being
 * turned into read-only, and a bug here has the shape that is hardest to notice: buttons that are enabled
 * when they should not be, on the one repository where a wrong merge is a published plugin.
 */
class AdminTest {

    private static Admin read(String json) throws Exception {
        return Admin.read(new ObjectMapper().readTree(json));
    }

    @Test
    void pushTrueIsTheOnlyThingThatEnablesWriting() throws Exception {
        assertTrue(read("{\"permissions\":{\"admin\":false,\"push\":true,\"pull\":true}}").canWrite());
    }

    @Test
    void pushFalseIsReadOnly() throws Exception {
        Admin admin = read("{\"permissions\":{\"admin\":false,\"push\":false,\"pull\":true}}");
        assertFalse(admin.canWrite());
        assertTrue(admin.summary().startsWith("read-only"));
    }

    @Test
    void anAnonymousReadHasNoPermissionsBlockAtAll() throws Exception {
        // What a rejected or expired token degrades to: a 200 with the public repository object, which
        // carries no permissions. Absent must never read as "push".
        assertFalse(read("{\"name\":\"botmaker-plugin-registry\"}").canWrite());
    }

    @Test
    void everyFailureTheClientFoldsIntoNullIsReadOnly() {
        Admin admin = Admin.read(null);
        assertFalse(admin.canWrite());
        assertTrue(admin.summary().contains("could not read"));
    }
}
