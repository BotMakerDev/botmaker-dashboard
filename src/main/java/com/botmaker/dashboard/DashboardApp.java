package com.botmaker.dashboard;

import com.botmaker.dashboard.github.Admin;
import com.botmaker.dashboard.ui.AccountBar;
import com.botmaker.dashboard.ui.ModulesTab;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
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

        TabPane tabs = new TabPane(
                new Tab("Modules", modules),
                new Tab("Releases", releases),
                placeholder("Release", "What ./release.sh --dry-run decides for a given set of flags: the "
                        + "version per module, why each is skipped or forced, the tag order and the gates."),
                placeholder("Queue", "Open pull requests on the plugin registry and the gallery, the one "
                        + "entry file each adds, and the gate's own verdict."));
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

    /**
     * A tab with nothing in it yet, saying what it will hold.
     *
     * <p>Four empty tabs rather than one tab added per phase: the shape of the window is a decision, and a
     * reviewer should be able to see it before any of it works. Each phase replaces one of these.
     */
    private static Tab placeholder(String name, String what) {
        Label title = new Label(name);
        title.getStyleClass().add("placeholder-title");
        Label body = new Label(what);
        body.getStyleClass().add("placeholder-body");
        body.setWrapText(true);
        body.setMaxWidth(560);

        VBox box = new VBox(8, title, body);
        box.getStyleClass().add("placeholder");
        box.setPadding(new Insets(48));

        Region pad = new Region();
        VBox.setVgrow(pad, Priority.ALWAYS);

        Tab tab = new Tab(name, new VBox(box, pad));
        return tab;
    }

    private void umbrellaChosen(Path root) {
        DashboardConfig.save(new DashboardConfig(root));
        modules.setUmbrella(root);
        releases.setUmbrella(root);
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
        }));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
