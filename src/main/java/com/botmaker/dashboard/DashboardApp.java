package com.botmaker.dashboard;

import com.botmaker.dashboard.github.Admin;
import com.botmaker.dashboard.ui.AccountBar;
import com.botmaker.dashboard.ui.Browse;
import com.botmaker.dashboard.ui.CatalogTab;
import com.botmaker.dashboard.ui.ChangelogTab;
import com.botmaker.dashboard.ui.ModulesTab;
import com.botmaker.dashboard.ui.QueueTab;
import com.botmaker.dashboard.ui.ReleaseTab;
import com.botmaker.dashboard.ui.ReleasesTab;
import com.botmaker.dashboard.ui.Themed;
import com.botmaker.dashboard.ui.UmbrellaBar;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * The operator's window: what the release constellation is doing, and the queue of submissions waiting on
 * a verdict.
 *
 * <p><b>It is a window onto a working copy, not a service.</b> Everything it shows about releases comes from
 * the umbrella checkout — {@code release.sh}, each submodule's git, and the committed {@code releases/*.md}
 * logs — so the first thing it asks for is where that checkout is, and the answer is remembered under
 * {@link com.botmaker.shared.config.CacheDirs}. Everything it shows about submissions comes from the GitHub
 * API through the account the operator signs in with.
 *
 * <p><b>The rule the whole module hangs on: it never reimplements a decision the release owns.</b> Which
 * modules a release would cut, what version each gets, what forces what, the tag order and every gate have
 * exactly one implementation, and a second one here would diverge on the first rule added and be discovered
 * by a bad tag — which cannot be edited. The day a computation appears here that the owner could have
 * answered, that is the bug.
 *
 * <p>Until 2026-09-16 that meant shelling to {@code ./release.sh --dry-run} and reading its stdout, which
 * kept the rule by keeping the decisions out of reach. The owner is {@code com.botmaker.cli.release} now and
 * this window <b>calls</b> it — as do {@code botmaker release} and the release workflow — so the rule reads
 * in its strict form: one implementation, and every caller reaches it. That is also why this window can cut
 * a release at all; see {@code ui/ReleaseTab} for the arming and the confirmation that guard it.
 *
 * <p>The same applies to the queue: a submission's verdict is the registry CI's check run, which runs
 * {@code RegistryGate} out of {@code botmaker-cli}'s main artifact. This window reads that verdict and
 * validates nothing itself — the check that refuses a pull request must be the one its author already ran.
 * The Catalog tab is the other half of that: what was already admitted, read from each data repository's
 * {@code main} rather than from the checked-out submodule, whose pointer trails whenever CI regenerates an
 * index.
 *
 * <p><b>Admin is not a role this app grants.</b> The write actions are enabled by
 * {@code permissions.push} on the plugin registry, read from the GitHub API for the signed-in account —
 * see {@link Admin}. There is no allowlist and no role table, because the power already exists on
 * github.com and a second list of who has it is a list that goes wrong.
 */
public final class DashboardApp extends Application {

    private final GitHubClient client = new GitHubClient();
    private final GitHubAuth auth = new GitHubAuth();

    private final Label adminBadge = new Label();

    /** The tabs with content so far. Held because the umbrella picker has to tell them the path moved. */
    private ModulesTab modules;
    private ReleasesTab releases;
    private ReleaseTab release;
    private ChangelogTab changelog;

    /** The tabs that read GitHub rather than the checkout, and so hear about the admin probe instead. */
    private QueueTab queue;
    private CatalogTab catalog;

    @Override
    public void start(Stage stage) {
        DashboardConfig config = DashboardConfig.load();
        Path remembered = config.remembered().orElse(null);
        // Both before the first window exists: the theme listener themes windows as they appear, and a
        // link clicked in the first second must not find the browser unplugged.
        Browse.install(getHostServices());
        Themed.install(Themed.resolve(config.theme()));

        UmbrellaBar umbrellaBar = new UmbrellaBar(stage, remembered, this::umbrellaChosen);
        AccountBar accountBar = new AccountBar(stage, auth, client, this::refreshAdmin);

        adminBadge.getStyleClass().add("badge");

        Button themeToggle = new Button();
        themeToggle.getStyleClass().add("theme-toggle");
        showTheme(themeToggle);
        themeToggle.setOnAction(e -> {
            Theme next = Themed.current().other();
            Themed.set(next);
            DashboardConfig.save(DashboardConfig.load().withTheme(next));
            showTheme(themeToggle);
        });

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(10, umbrellaBar, spacer, adminBadge, accountBar, themeToggle);
        top.getStyleClass().add("top-bar");
        top.setPadding(new Insets(8, 12, 8, 12));

        modules = new ModulesTab(remembered);
        releases = new ReleasesTab(remembered);
        release = new ReleaseTab(remembered);
        changelog = new ChangelogTab(remembered, client, auth);
        queue = new QueueTab(client, auth);
        catalog = new CatalogTab(client, auth);

        // Catalog sits beside Queue because they are the two halves of one question — what shipped, and
        // what is waiting — and after it because a queue is usually empty while the catalog never is.
        // The Release tab's header carries the live badge, so a release running in its own process is visible
        // from every other tab; and its board's pulses stop while the tab cannot be seen.
        Tab releaseTab = new Tab("Release", release);
        releaseTab.setGraphic(release.badge());
        release.setShowing(false);
        releaseTab.selectedProperty().addListener((o, was, is) -> release.setShowing(is));

        // Changelog sits beside Release because it is what a refused release sends you to write: the gate
        // refuses a module whose CHANGELOG.md describes neither the version nor an [Unreleased] section.
        TabPane tabs = new TabPane(
                new Tab("Modules", modules),
                new Tab("Releases", releases),
                releaseTab,
                new Tab("Changelog", changelog),
                new Tab("Queue", queue),
                new Tab("Catalog", catalog));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(tabs);

        Scene scene = new Scene(root, 1100, 720);
        root.getStyleClass().add("app-root");
        Themed.scene(scene);

        stage.setTitle("BotMaker Dashboard");
        applyAppIcons(stage);
        stage.setScene(scene);
        stage.show();

        refreshAdmin();
    }

    /**
     * The window's own icon, in every size the desktop may ask for.
     *
     * <p>Six sizes rather than one: a window manager picks the nearest and scales it, and a 512 scaled into
     * a 16px taskbar slot is mush. The set is this module's, not Studio's — the two live side by side in an
     * application menu, so the mark is a release board where Studio's is the robot.
     *
     * <p>The packaged app gets its icon from jpackage (the {@code dist} profile's {@code <icon>}), which is
     * a different thing: that one is the desktop entry and the launcher, this one is the running window.
     */
    private void applyAppIcons(Stage stage) {
        for (int size : new int[] {16, 32, 64, 128, 256, 512}) {
            try (InputStream in = getClass().getResourceAsStream("/icons/icon-" + size + ".png")) {
                if (in != null) stage.getIcons().add(new Image(in));
            } catch (IOException e) {
                // A window with no icon is a window; there is nothing to tell the operator here.
            }
        }
    }

    private void umbrellaChosen(Path root) {
        DashboardConfig.save(DashboardConfig.load().withUmbrella(root));
        modules.setUmbrella(root);
        releases.setUmbrella(root);
        release.setUmbrella(root);
        changelog.setUmbrella(root);
    }

    /** The toggle names the palette it switches <i>to</i>, which is the only thing a click would change. */
    private static void showTheme(Button toggle) {
        boolean dark = Themed.current() == Theme.DARK;
        toggle.setText(dark ? "☀" : "☾");
        toggle.setTooltip(new Tooltip(dark ? "Switch to the light theme" : "Switch to the dark theme"));
    }

    /**
     * Re-asks GitHub whether this account may write, and says so in one line.
     *
     * <p>Runs on every sign-in and sign-out, never cached across accounts: the whole point of reading the
     * permission rather than keeping a list is that the answer is GitHub's to change at any moment.
     */
    private void refreshAdmin() {
        adminBadge.setText("checking…");
        Admin.probe(client, auth).thenAccept(verdict -> javafx.application.Platform.runLater(() -> {
            adminBadge.setText(verdict.summary());
            adminBadge.getStyleClass().removeAll("badge--write", "badge--read");
            adminBadge.getStyleClass().add(verdict.canWrite() ? "badge--write" : "badge--read");
            // The queue's write buttons follow the badge exactly — one probe, one answer, no second list.
            queue.setAdmin(verdict);
            queue.reload();
            // The catalog's Edit and Unpublish follow the same badge, and it is read with the token when
            // there is one, which lifts the anonymous rate limit — so a sign-in is a reason to read again.
            catalog.setAdmin(verdict);
            catalog.reload();
            // The drafter is the owner's, and who is signed in has just changed — so it is asked again
            // rather than left showing what the previous account could do.
            changelog.signedInChanged(client, auth);
        }));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
