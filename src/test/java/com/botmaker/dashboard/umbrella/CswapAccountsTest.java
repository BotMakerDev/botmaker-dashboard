package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code cswap list}'s own text, as slots and usage. No address is ever kept. */
class CswapAccountsTest {

    private static final String LISTING = """
            Accounts:
              1: one@example.com [one@example.com's Organization] (active)
                 ├ 5h:   9%   resets 20:40         in 4h 52m
                 └ 7d:  11%   resets Sep 23 04:00  in 6d 12h

              2: two@example.com [two@example.com's Organization]
                 ├ 5h: 100%   resets 16:09         in 22m
                 └ 7d:  15%   resets Sep 22 20:59  in 6d 5h

              3: three@example.com [three@example.com's Organization]
                 ├ 5h:  97%   resets 17:10         in 1h 22m
                 └ 7d:  20%   resets Sep 20 03:00  in 3d 11h

            Running instances:
              ● CLI   ~/IdeaProjects/botmaker  (1 session)
            """;

    @Test
    void eachAccountIsASlotAndItsUsage() {
        List<CswapAccounts.Account> accounts = CswapAccounts.parse(LISTING);
        assertEquals(3, accounts.size());
        assertEquals(new CswapAccounts.Account(1, 9, 11, true), accounts.get(0));
        assertEquals(new CswapAccounts.Account(2, 100, 15, false), accounts.get(1));
        assertEquals(new CswapAccounts.Account(3, 97, 20, false), accounts.get(2));
    }

    @Test
    void theRunningInstancesBelowAreNotAccounts() {
        assertTrue(CswapAccounts.parse(LISTING).stream().allMatch(a -> a.slot() <= 3));
    }

    @Test
    void theLeastUsedGoesFirstAndTiesKeepSlotOrder() {
        assertEquals(List.of(1, 3, 2),
                CswapAccounts.byLeastUsed(CswapAccounts.parse(LISTING)).stream()
                        .map(CswapAccounts.Account::slot).toList());

        List<CswapAccounts.Account> tied = List.of(
                new CswapAccounts.Account(2, 0, 0, false),
                new CswapAccounts.Account(1, 0, 0, false));
        assertEquals(List.of(1, 2),
                CswapAccounts.byLeastUsed(tied).stream().map(CswapAccounts.Account::slot).toList());
    }

    @Test
    void aListingThisParserDoesNotRecogniseIsNoAccountsRatherThanAWrongOne() {
        assertEquals(List.of(), CswapAccounts.parse("cswap: command not found"));
        assertEquals(List.of(), CswapAccounts.parse(""));
    }

    @Test
    void anAccountWithNoUsageLineIsStillOfferedAtZero() {
        // A slot cswap listed without figures is worth trying — the alternative is never drafting at all.
        List<CswapAccounts.Account> accounts = CswapAccounts.parse("Accounts:\n  4: four@example.com [x]\n");
        assertEquals(List.of(new CswapAccounts.Account(4, 0, 0, false)), accounts);
    }

    @Test
    void theLabelNamesTheSlotAndNeverTheAddress() {
        assertEquals("account 3 (5h: 97%)", new CswapAccounts.Account(3, 97, 20, false).label());
    }
}
