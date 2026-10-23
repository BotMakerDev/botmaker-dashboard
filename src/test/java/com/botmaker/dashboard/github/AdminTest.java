package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.Publishers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who the dashboard offers the catalog's edits to: the signed-in account id in {@code publishers.json}'s admins,
 * the list the catalog's check reads. Every other answer, including every failure, is read-only.
 */
class AdminTest {

    private static final Publishers PUBLISHERS = new Publishers(
            List.of(new Publishers.Publisher(298085676, "LiQiyeDev")),
            List.of(new Publishers.Publisher(42, "trusteddev")));

    private static Admin read(String user) throws Exception {
        return Admin.read(new ObjectMapper().readTree(user), Optional.of(PUBLISHERS));
    }

    @Test
    void anAdminsAccountIdIsTheOnlyThingThatEnablesWriting() throws Exception {
        Admin admin = read("{\"login\":\"LiQiyeDev\",\"id\":298085676}");
        assertTrue(admin.canWrite());
        assertEquals("LiQiyeDev", admin.login());
        assertEquals(298085676, admin.id());
        assertTrue(admin.summary().startsWith("admin · BotMakerDev/botmaker-catalog"));
    }

    @Test
    void theAdminsLoginOnAnotherAccountIsNot() throws Exception {
        // A login can be renamed and then taken: the check compares ids, and so does this.
        Admin taken = read("{\"login\":\"LiQiyeDev\",\"id\":999}");
        assertFalse(taken.canWrite());
        assertTrue(taken.summary().startsWith("read-only"));
    }

    @Test
    void aTrustedAccountIsNoAdmin() throws Exception {
        assertFalse(read("{\"login\":\"trusteddev\",\"id\":42}").canWrite());
    }

    @Test
    void everyFailureIsReadOnlyWithAReason() throws Exception {
        assertTrue(Admin.read(null, Optional.of(PUBLISHERS)).reason().contains("could not read"));
        assertFalse(read("{\"login\":\"nobody\"}").canWrite(), "no id at all");
        Admin noFile = Admin.read(new ObjectMapper().readTree("{\"login\":\"LiQiyeDev\",\"id\":298085676}"),
                Optional.empty());
        assertFalse(noFile.canWrite());
        assertTrue(noFile.reason().contains("publishers.json"));
    }
}
