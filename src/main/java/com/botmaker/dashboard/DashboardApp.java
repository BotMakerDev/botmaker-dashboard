package com.botmaker.dashboard;

import com.botmaker.dashboard.github.Admin;
import com.botmaker.dashboard.ui.AccountBar;
import com.botmaker.dashboard.ui.CatalogTab;
import com.botmaker.dashboard.ui.ModulesTab;
import com.botmaker.dashboard.ui.QueueTab;
import com.botmaker.dashboard.ui.ReleaseTab;
import com.botmaker.dashboard.ui.ReleasesTab;
import com.botmaker.dashboard.ui.UmbrellaBar;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

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
 * <p><b>The rule the whole module hangs on: it never reimplements a decision {@code release.sh} owns.</b>
 * Which modules a release would cut, what version each gets, what forces what, the tag order and every gate
 * have exactly one implementation, and a second one in Java would diverge on the first rule added and be
 * discovered by a bad tag — which cannot be edited. So this app shells to the script and reads its output,
 * and the day a computation appears here that the script could have answered, that is the bug. (Part C of
 * the plan replaces the script with a library without weakening the rule: the single owner becomes Java the
 * script's callers share, not Java this window keeps to itself.)
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

    /** The tabs that read GitHub rather than the checkout, and so hear about the admin probe instead. */
    private QueueTab queue;
    private CatalogTab catalog;

    @Override
    public void start(Stage stage) {
        Path remembered = DashboardConfig.load().remembered().orElse(null);
        UmbrellaBar umbrellaBar = new UmbrellaBar(stage, remembered, this::umbrellaChosen);
        AccountBar accountBar = new AccountBar(stage, auth, client, this::refreshAdmin);

        adminBadge.getStyleClass().add("badge");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(10, umbrellaBar, spacer, adminBadge, accountBar);
        top.getStyleClass().add("top-bar");
        top.setPadding(new Insets(8, 12, 8, 12));

        modules = new ModulesTab(remembered);
        releases = new ReleasesTab(remembered);
        release = new ReleaseTab(remembered);
        queue = new QueueTab(client, auth);
        catalog = new CatalogTab(client, auth);

        // Catalog sits beside Queue because they are the two halves of one question — what shipped, and
        // what is waiting — and after it because a queue is usually empty while the catalog never is.
        TabPane tabs = new TabPane(
                new Tab("Modules", modules),
                new Tab("Releases", releases),
                new Tab("Release", release),
                new Tab("Queue", queue),
                new Tab("Catalog", catalog));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(tabs);

        Scene scene = new Scene(root, 1100, 720);
        scene.getStylesheets().add(
                DashboardApp.class.getResource("/css/dashboard.css").toExternalForm());

        stage.setTitle("BotMaker Dashboard");
        stage.setScene(scene);
        stage.show();

        refreshAdmin();
    }

    private void umbrellaChosen(Path root) {
        DashboardConfig.save(new DashboardConfig(root));
        modules.setUmbrella(root);
        releases.setUmbrella(root);
        release.setUmbrella(root);
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
        }));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
