package com.botmaker.dashboard.umbrella;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which {@code cswap} account to draft with, read from {@code cswap list}.
 *
 * <p><b>A slot, never an address.</b> The accounts are the maintainer's own logins and this window has no
 * reason to put an email on screen or in a log — so an account is a number here, and the parser deliberately
 * keeps nothing else. What it does keep is the five-hour usage, because that is the one fact that decides
 * anything: the drafter takes the least-used account first and moves down the list when one refuses, which
 * is rotation with a starting point rather than a round robin that reliably lands on the exhausted one.
 *
 * <p>The seven-day figure is read and not used. It is the next question when every account is near its
 * five-hour limit, and a parser that skipped it would have to be changed to answer it.
 *
 * <p><b>The output is a human's, so a line this does not recognise is skipped rather than fatal.</b>
 * {@code cswap} is somebody else's tool on the operator's PATH and it may reword its own listing; the drafter
 * degrades to "no account could be read" and the editor stays exactly as it was.
 */
public final class CswapAccounts {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** {@code   3: someone@example.com [org] (active)} — the slot, and nothing after it. */
    private static final Pattern SLOT = Pattern.compile("^\\s{2}(\\d+):\\s+\\S+");

    /** {@code     ├ 5h:   9%   resets 20:40} — the window and its percentage. */
    private static final Pattern USAGE = Pattern.compile("^\\s+\\S?\\s*(5h|7d):\\s+(\\d+)%");

    /**
     * One account, as this window is willing to know it.
     *
     * @param slot    the number {@code cswap run} takes
     * @param fiveHour percent of the five-hour window used, or 0 when the listing did not say
     * @param sevenDay percent of the seven-day window used, or 0
     * @param active   whether {@code cswap} marks it as the current account
     */
    public record Account(int slot, int fiveHour, int sevenDay, boolean active) {

        /** What the status line may say about it: the slot and its usage, never who it is. */
        public String label() {
            return "account " + slot + " (5h: " + fiveHour + "%)";
        }
    }

    private CswapAccounts() {
    }

    /** Runs {@code cswap list}; an empty list is every failure — no {@code cswap}, a timeout, a reword. */
    public static List<Account> list(Path where) {
        Proc listed = Proc.run(where, TIMEOUT, "cswap", "list");
        return listed.ok() ? parse(listed.out()) : List.of();
    }

    /**
     * The accounts in the order the drafter should try them: least five-hour usage first.
     *
     * <p>Ties keep the slot order, so the choice is stable between drafts — an account at 0% twice running
     * is the same account, which is what makes a failure worth moving on from rather than retrying blindly.
     */
    public static List<Account> byLeastUsed(List<Account> accounts) {
        List<Account> sorted = new ArrayList<>(accounts);
        sorted.sort(Comparator.comparingInt(Account::fiveHour).thenComparingInt(Account::slot));
        return List.copyOf(sorted);
    }

    /** {@code cswap list}'s own text, as accounts. Pure, so every shape of that listing is testable. */
    static List<Account> parse(String text) {
        List<Account> accounts = new ArrayList<>();
        int slot = -1;
        boolean active = false;
        int fiveHour = 0;
        int sevenDay = 0;
        for (String line : text.lines().toList()) {
            if (line.startsWith("Running instances:")) {
                break;                                  // the sessions below are not accounts
            }
            Matcher head = SLOT.matcher(line);
            if (head.find()) {
                if (slot > 0) {
                    accounts.add(new Account(slot, fiveHour, sevenDay, active));
                }
                slot = Integer.parseInt(head.group(1));
                active = line.contains("(active)");
                fiveHour = 0;
                sevenDay = 0;
                continue;
            }
            Matcher usage = USAGE.matcher(line);
            if (slot > 0 && usage.find()) {
                int percent = Integer.parseInt(usage.group(2));
                if ("5h".equals(usage.group(1))) {
                    fiveHour = percent;
                } else {
                    sevenDay = percent;
                }
            }
        }
        if (slot > 0) {
            accounts.add(new Account(slot, fiveHour, sevenDay, active));
        }
        return List.copyOf(accounts);
    }
}
