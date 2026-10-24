package com.botmaker.dashboard.ui;

import com.botmaker.cli.catalog.Publishers;
import com.botmaker.cli.catalog.Rank;
import com.botmaker.dashboard.github.Admin;
import com.botmaker.dashboard.github.Authors;
import com.botmaker.dashboard.github.Catalog;
import com.botmaker.dashboard.github.CatalogEdits;
import com.botmaker.dashboard.github.CatalogFilter;
import com.botmaker.dashboard.github.CatalogRow;
import com.botmaker.dashboard.github.PublishersFile;
import com.botmaker.dashboard.umbrella.Links;
import com.botmaker.dashboard.ui.widgets.LinkBar;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The Catalog tab: every listed bot and plugin, and every author, as one grid of cards (the user's pick, layout A,
 * 2026-10-23), read from {@code catalog.json} on the catalog's {@code main} — the file Studio reads.
 *
 * <p>Chips choose the kind (All, Bots, Plugins, Authors) and the ranks, a search box narrows by words; which cards
 * show is {@link CatalogFilter}'s answer and who the authors are is {@link Authors}', both tested without a window.
 * An owner's name on a card opens that author.
 *
 * <p>The side panel shows the selected card, a bot's or plugin's every approved release newest first. For an admin
 * it adds the edits — Remove beside each release (2026-10-24), Remove from catalog…, an author's Rank — each a pull request ({@link CatalogEdits}) the catalog's check decides and merges. Nothing
 * here judges an entry, and nothing is reloaded after a proposal: nothing published has changed until it merges.
 */
public final class CatalogTab extends BorderPane {

    private final GitHubClient client;
    private final GitHubAuth auth;

    private List<CatalogRow> rows = List.of();
    private List<Authors.Author> authors = List.of();

    private final ToggleGroup kinds = new ToggleGroup();
    private final Map<CatalogFilter.View, ToggleButton> kindChips = new EnumMap<>(CatalogFilter.View.class);
    private final Map<Rank, ToggleButton> rankChips = new EnumMap<>(Rank.class);
    private final TextField search = new TextField();

    private final TilePane grid = new TilePane();
    private final Label empty = new Label();

    private final VBox panel = new VBox(8);
    private final Label status = new Label();
    private final Button refresh = new Button("Refresh");

    /** {@code bot:<repo>}, {@code plugin:<id>} or {@code author:<login>}, lower-case; blank for none. */
    private String selected = "";
    /** True while an edit's pull request is being opened: the panel's edit buttons wait for it. */
    private boolean writing;
    /** The pull request the last edit opened, linked at the foot of its card's panel; {@code null} before the first. */
    private CatalogEdits.Proposal opened;
    private String openedFor = "";

    /**
     * The edits' inputs, one set kept across panel rebuilds so a redraw never wipes what the admin typed; emptied
     * when another card is selected ({@link #inputsFor}).
     */
    private final TextField whyInput = new TextField();
    private final ComboBox<Rank> rankInput = new ComboBox<>();
    private String inputsFor = "";

    /** Counts reloads, so an older answer landing after a newer one is dropped. */
    private int generation;

    private Admin admin = new Admin(false, "not checked yet");

    /**
     * {@code client} and {@code auth} are used only by {@link #reload} and the edits; a test that fills the tab
     * with {@link #fill} passes neither.
     */
    public CatalogTab(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;

        status.getStyleClass().add("status-line");
        refresh.setOnAction(e -> reload());

        whyInput.setPromptText("Why (optional; goes in the pull request)");
        rankInput.getItems().setAll(Rank.values());
        rankInput.setCellFactory(list -> rankCell());
        rankInput.setButtonCell(rankCell());

        HBox kindStrip = new HBox();
        kindStrip.getStyleClass().add("chip-strip");
        for (CatalogFilter.View view : CatalogFilter.View.values()) {
            ToggleButton chip = chip(view.displayName());
            chip.setToggleGroup(kinds);
            chip.setUserData(view);
            kindChips.put(view, chip);
            kindStrip.getChildren().add(chip);
        }
        kindChips.get(CatalogFilter.View.ALL).setSelected(true);
        kinds.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now == null) {
                was.setSelected(true); // Exactly one kind is on; clicking it again keeps it.
                return;
            }
            redraw();
        });

        HBox rankStrip = new HBox();
        rankStrip.getStyleClass().add("chip-strip");
        for (Rank rank : Rank.values()) {
            ToggleButton chip = chip(rank.displayName());
            chip.selectedProperty().addListener((obs, was, now) -> redraw());
            rankChips.put(rank, chip);
            rankStrip.getChildren().add(chip);
        }

        search.setPromptText("Search name, author, tags");
        search.setPrefColumnCount(18);
        search.textProperty().addListener((obs, was, now) -> redraw());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, refresh, kindStrip, rankStrip, search, spacer, status);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        grid.getStyleClass().add("catalog-grid");
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(12));
        grid.setPrefTileWidth(CatalogCards.WIDTH);
        empty.getStyleClass().add("placeholder-body");
        ScrollPane cards = new ScrollPane(grid);
        cards.setFitToWidth(true);

        panel.getStyleClass().add("catalog-panel");
        panel.setPadding(new Insets(14));
        ScrollPane side = new ScrollPane(panel);
        side.setFitToWidth(true);

        SplitPane split = new SplitPane(cards, side);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.68);

        setTop(bar);
        setCenter(split);
        redraw();
        // No reload here: whoever builds the tab reloads it once it has the admin verdict.
    }

    private static ToggleButton chip(String text) {
        ToggleButton chip = new ToggleButton(text);
        chip.getStyleClass().add("chip");
        return chip;
    }

    /** {@code catalog.json} and {@code publishers.json}, off the FX thread. */
    public void reload() {
        int asked = ++generation;
        refresh.setDisable(true);
        status.setText("Reading " + Catalog.REPO + " …");
        CompletableFuture<Optional<PublishersFile>> ranks = PublishersFile.fetch(client, auth)
                .exceptionally(error -> Optional.empty());
        Catalog.fetch(client).thenCombine(ranks, Loaded::new)
                .whenComplete((loaded, error) -> Platform.runLater(() -> {
                    if (asked != generation) {
                        return; // A newer reload is under way; this answer is older than it.
                    }
                    refresh.setDisable(writing);
                    if (error != null) {
                        status.setText("Could not read the catalog: " + QueueTab.message(error));
                        return;
                    }
                    fill(loaded.read(), loaded.file().map(PublishersFile::publishers).orElse(null));
                }));
    }

    private record Loaded(Catalog.Read read, Optional<PublishersFile> file) {
    }

    /**
     * Shows {@code read}'s rows and the authors behind them. {@code publishers} is {@code null} when the file
     * could not be read: the authors are then the rows' owners only, and the status line says so.
     */
    void fill(Catalog.Read read, Publishers publishers) {
        rows = read.rows();
        authors = Authors.of(rows, publishers == null ? new Publishers(List.of(), List.of()) : publishers);
        redraw();
        long bots = rows.stream().filter(r -> r.kind() == CatalogRow.Kind.BOT).count();
        String summary = read.problem().isEmpty()
                ? CatalogCards.count((int) bots, "bot") + ", " + CatalogCards.count(rows.size() - (int) bots, "plugin")
                + ", " + CatalogCards.count(authors.size(), "author")
                : read.problem();
        status.setText(summary
                + (publishers == null ? "  ·  could not read " + Publishers.FILE + ": ranks from the catalog only" : "")
                + (admin.canWrite() ? "" : "  ·  read-only: " + admin.reason()));
    }

    /** Called whenever the admin probe answers again — sign-in, sign-out, and once at startup. */
    public void setAdmin(Admin admin) {
        this.admin = admin;
        showPanel();
    }

    private CatalogFilter filter() {
        CatalogFilter.View view = (CatalogFilter.View) kinds.getSelectedToggle().getUserData();
        Set<Rank> ranks = rankChips.entrySet().stream().filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey).collect(Collectors.toCollection(() -> EnumSet.noneOf(Rank.class)));
        return new CatalogFilter(view, ranks, search.getText());
    }

    private void redraw() {
        CatalogFilter filter = filter();
        List<Node> cards = new ArrayList<>();
        for (CatalogRow row : filter.apply(rows)) {
            cards.add(card(CatalogCards.entry(row, this::openAuthor), key(row)));
        }
        for (Authors.Author author : filter.applyAuthors(authors)) {
            cards.add(card(CatalogCards.author(author), key(author)));
        }
        if (cards.isEmpty()) {
            empty.setText(rows.isEmpty() && authors.isEmpty()
                    ? "Nothing listed yet — or press Refresh." : "Nothing matches these chips and words.");
            cards.add(empty);
        }
        grid.getChildren().setAll(cards);
        showPanel();
    }

    private Node card(VBox card, String key) {
        card.setUserData(key);
        if (key.equals(selected)) {
            card.getStyleClass().add(CatalogCards.SELECTED);
        }
        card.setOnMouseClicked(e -> select(key));
        return card;
    }

    private void select(String key) {
        selected = key;
        for (Node node : grid.getChildren()) {
            node.getStyleClass().remove(CatalogCards.SELECTED);
            if (key.equals(node.getUserData())) {
                node.getStyleClass().add(CatalogCards.SELECTED);
            }
        }
        showPanel();
    }

    /** An owner link: the Authors chip, with that author's card shown and selected. */
    private void openAuthor(String login) {
        String key = "author:" + login.toLowerCase(Locale.ROOT);
        selected = key;
        Authors.Author author = author(key).orElse(null);
        if (author != null && !new CatalogFilter(CatalogFilter.View.AUTHORS, filter().ranks(), search.getText())
                .applyAuthors(List.of(author)).contains(author)) {
            // The chips or the words would hide the very card that was asked for.
            rankChips.values().forEach(chip -> chip.setSelected(false));
            search.clear();
        }
        kindChips.get(CatalogFilter.View.AUTHORS).setSelected(true);
        redraw();
    }

    private static String key(CatalogRow row) {
        return row.kind().id() + ":" + (row.kind() == CatalogRow.Kind.PLUGIN ? row.id() : row.repo())
                .toLowerCase(Locale.ROOT);
    }

    private static String key(Authors.Author author) {
        return "author:" + author.login().toLowerCase(Locale.ROOT);
    }

    private Optional<CatalogRow> row(String key) {
        return rows.stream().filter(r -> key(r).equals(key)).findFirst();
    }

    private Optional<Authors.Author> author(String key) {
        return authors.stream().filter(a -> key(a).equals(key)).findFirst();
    }

    // ---------------------------------------------------------------------------------------------------
    // The side panel.
    // ---------------------------------------------------------------------------------------------------

    private void showPanel() {
        if (!selected.equals(inputsFor)) {
            // Another card: its edits start empty. The same card redrawn (a chip, a search word, a reload) keeps
            // what the admin was typing.
            inputsFor = selected;
            whyInput.clear();
            rankInput.setValue(null);
        }
        List<Node> parts = new ArrayList<>();
        Optional<CatalogRow> row = row(selected);
        Optional<Authors.Author> author = row.isPresent() ? Optional.empty() : author(selected);
        if (row.isPresent()) {
            entryPanel(row.get(), parts);
        } else if (author.isPresent()) {
            authorPanel(author.get(), parts);
        } else {
            Label hint = new Label("Pick a card to see its details"
                    + (admin.canWrite() ? " and its edits." : "."));
            hint.getStyleClass().add("placeholder-body");
            hint.setWrapText(true);
            parts.add(hint);
        }
        if (opened != null && openedFor.equals(selected)) {
            CatalogEdits.Proposal proposal = opened;
            Hyperlink link = new Hyperlink("Opened #" + proposal.number() + " — open it on GitHub");
            link.getStyleClass().add("card-owner");
            link.setOnAction(e -> Browse.open(proposal.url(), status::setText));
            parts.add(section("Last edit"));
            parts.add(link);
        }
        panel.getChildren().setAll(parts);
    }

    private void entryPanel(CatalogRow row, List<Node> parts) {
        parts.add(heading(row.name(), row.rank()));
        parts.add(line(row.kind().displayName() + " by @" + row.owner()));
        parts.add(dim(row.repo() + "  ·  ★ " + row.stars()));
        if (!row.forkOf().isEmpty()) {
            parts.add(dim("based on " + row.forkOf()));
        }
        if (!row.description().isEmpty()) {
            parts.add(line(row.description()));
        }
        if (!row.tags().isEmpty()) {
            parts.add(dim("tags: " + String.join(", ", row.tags())));
        }
        parts.add(section(row.releases().size() == 1 ? "Approved release" : "Approved releases, newest first"));
        row.releases().forEach(release -> parts.add(releaseLine(row, release)));
        if (!row.plugins().isEmpty()) {
            parts.add(section("Plugins at " + row.tag()));
            row.plugins().forEach(pin -> parts.add(dim(pin.id() + "  " + pin.version())));
        }
        parts.add(dim(row.entryPath()));
        parts.add(links(row.links()));
        if (admin.canWrite()) {
            entryEdits(row, parts);
        }
    }

    private void authorPanel(Authors.Author author, List<Node> parts) {
        parts.add(heading(author.login(), author.rank()));
        parts.add(dim(author.id().map(id -> "account " + id + " in " + Publishers.FILE)
                .orElse("not in " + Publishers.FILE + ": ranked by the catalog")));
        if (!author.bots().isEmpty()) {
            parts.add(section("Bots"));
            author.bots().forEach(r -> parts.add(openLink(r)));
        }
        if (!author.plugins().isEmpty()) {
            parts.add(section("Plugins"));
            author.plugins().forEach(r -> parts.add(openLink(r)));
        }
        parts.add(links(List.of(new Links.Link("GitHub", "https://github.com/" + author.login()))));
        if (admin.canWrite()) {
            authorEdits(author, parts);
        }
    }

    /** One of an author's rows, which selects it under the kind chip that shows it. */
    private Node openLink(CatalogRow row) {
        Hyperlink link = new Hyperlink(row.name() + "  " + row.tag());
        link.getStyleClass().add("card-owner");
        link.setOnAction(e -> {
            selected = key(row);
            kindChips.get(row.kind() == CatalogRow.Kind.BOT ? CatalogFilter.View.BOTS : CatalogFilter.View.PLUGINS)
                    .setSelected(true);
            redraw();
        });
        return link;
    }

    /**
     * One approved release: its tag and pinned commit, and for an admin a Remove beside it. The only release has
     * none: it goes with the entry (Remove from catalog…).
     */
    private Node releaseLine(CatalogRow row, CatalogRow.Release release) {
        Label label = dim(release.tag() + (release.commit().isEmpty() ? "" : "  ·  " + release.shortCommit()));
        HBox line = new HBox(8, label);
        line.setAlignment(Pos.CENTER_LEFT);
        if (admin.canWrite() && row.releases().size() > 1) {
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            Button remove = new Button("Remove");
            remove.getStyleClass().add("danger");
            remove.setDisable(writing);
            remove.setOnAction(e -> {
                if (confirmReleaseRemoval(row, release.tag())) {
                    write("remove " + row.name() + " " + release.tag(), CatalogEdits.removeRelease(client, auth,
                            admin, row, release.tag(), whyInput.getText()));
                }
            });
            line.getChildren().addAll(gap, remove);
        }
        return line;
    }

    private void entryEdits(CatalogRow row, List<Node> parts) {
        parts.add(section("Admin"));
        TextField why = whyInput;

        Button remove = new Button("Remove from catalog…");
        remove.getStyleClass().add("danger");
        remove.setDisable(writing);
        remove.setOnAction(e -> {
            if (confirmRemoval(row)) {
                write("remove " + row.name(), CatalogEdits.remove(client, auth, admin, row, why.getText()));
            }
        });

        parts.add(remove);
        parts.add(why);
    }

    private void authorEdits(Authors.Author author, List<Node> parts) {
        parts.add(section("Admin"));
        TextField why = whyInput;
        ComboBox<Rank> rank = rankInput;
        if (rank.getValue() == null) {
            rank.setValue(author.rank());
        }

        Button save = new Button("Save rank");
        save.setDisable(writing || rank.getValue() == author.rank());
        // setOnAction, not a listener: the combo outlives this panel, and a listener per rebuild would pile up.
        rank.setOnAction(e -> save.setDisable(writing || rank.getValue() == author.rank()));
        save.setOnAction(e -> write("rank @" + author.login() + " " + rank.getValue().displayName(),
                CatalogEdits.setRank(client, auth, admin, author.login(), author.id(), rank.getValue(),
                        why.getText())));

        parts.add(new HBox(8, new Label("Rank"), rank, save));
        parts.add(why);
    }

    private static ListCell<Rank> rankCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Rank item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        };
    }

    /** The removal is everyone's: Studio stops listing it once the catalog merges, so it is asked first. */
    private boolean confirmRemoval(CatalogRow row) {
        ButtonType removeButton = new ButtonType("Remove", ButtonBar.ButtonData.OK_DONE);
        Alert alert = Themed.dialog(new Alert(Alert.AlertType.CONFIRMATION, "", removeButton, ButtonType.CANCEL),
                getScene() == null ? null : getScene().getWindow());
        alert.setTitle("Remove from catalog");
        alert.setHeaderText("Remove " + row.name() + " from the catalog?");
        alert.setContentText("This opens a pull request deleting " + row.entryPath() + ". Once the catalog merges "
                + "it, Studio no longer lists " + row.name() + ". The repository and its releases stay.");
        return alert.showAndWait().filter(removeButton::equals).isPresent();
    }

    /** A release removed is refused by every Studio that loaded it, so it is asked first too. */
    private boolean confirmReleaseRemoval(CatalogRow row, String tag) {
        ButtonType removeButton = new ButtonType("Remove " + tag, ButtonBar.ButtonData.OK_DONE);
        Alert alert = Themed.dialog(new Alert(Alert.AlertType.CONFIRMATION, "", removeButton, ButtonType.CANCEL),
                getScene() == null ? null : getScene().getWindow());
        alert.setTitle("Remove this release");
        alert.setHeaderText("Stop approving " + row.name() + " " + tag + "?");
        alert.setContentText("This opens a pull request removing " + tag + " from " + row.entryPath() + ". Once the "
                + "catalog merges it, Studio no longer offers " + tag + ", and a project that loaded it is asked to "
                + "upgrade. The git tag and " + row.name() + "'s other releases stay.");
        return alert.showAndWait().filter(removeButton::equals).isPresent();
    }

    /** Runs one edit and says, in the status line, the pull request it opened or why it did not. */
    private void write(String what, CompletableFuture<CatalogEdits.Proposal> edit) {
        String card = selected;
        writing = true;
        refresh.setDisable(true);
        showPanel();
        status.setText("Opening a pull request to " + what + " …");
        edit.whenComplete((proposal, error) -> Platform.runLater(() -> {
            writing = false;
            refresh.setDisable(false);
            if (error != null) {
                showPanel();
                status.setText("Could not " + what + ": " + QueueTab.message(error));
                return;
            }
            opened = proposal;
            openedFor = card;
            // The reason went into that pull request; the next edit gives its own.
            whyInput.clear();
            showPanel();
            status.setText("Opened #" + proposal.number() + " to " + what
                    + "; the catalog merges it once its check passes — " + proposal.url());
        }));
    }

    private Node heading(String name, Rank rank) {
        Label title = new Label(name);
        title.getStyleClass().add("placeholder-title");
        title.setWrapText(true);
        HBox heading = new HBox(8, title, CatalogCards.rankBadge(rank));
        heading.setFillHeight(false);
        return heading;
    }

    private static Label line(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    private static Label dim(String text) {
        Label label = line(text);
        label.getStyleClass().add("card-meta");
        return label;
    }

    private static Label section(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("panel-section");
        return label;
    }

    private Node links(List<Links.Link> links) {
        LinkBar bar = new LinkBar(url -> Browse.open(url, status::setText));
        bar.show(links);
        return bar;
    }
}
