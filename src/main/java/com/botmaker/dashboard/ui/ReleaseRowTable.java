package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Level;
import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Order;
import com.botmaker.cli.release.Plan;
import com.botmaker.cli.release.Version;
import com.botmaker.dashboard.umbrella.Io;
import com.botmaker.dashboard.umbrella.ReleaseSpec;
import com.botmaker.dashboard.umbrella.VersionTargets;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Release tab's table: a row per module the library knows, in {@link Order#TAG}, with its level picker and
 * the arrow that level resolves to.
 *
 * <p>Its own file since 2026-09-29, when the tab was a thousand lines. It holds no arming: every change calls
 * {@code onChange}, and the tab decides from {@link #picked()} what the flags now spell.
 */
final class ReleaseRowTable extends TableView<ReleaseRowTable.Row> {

    /**
     * One module's line: whether it is asked for, at what level or exact version, and what that resolves to.
     *
     * <p><b>A level and a typed version are two states, not one box.</b> The level is always set (the script's
     * own default, since a bare module flag means {@code patch}) and {@link #exact} only matters while
     * {@link #exactChosen} is on, so switching back and forth never loses what was typed.
     *
     * <p>{@link #latest} is {@code null} until this module's tags have been read, which is a git call and so
     * happens off the FX thread. Three states — unread, no tag, a tag — and they are three different things
     * to show.
     */
    public static final class Row {
        private final String module;
        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        private final ObjectProperty<Level> level = new SimpleObjectProperty<>(Level.DEFAULT);
        private final BooleanProperty exactChosen = new SimpleBooleanProperty(false);
        private final StringProperty exact = new SimpleStringProperty("");
        private final StringProperty target = new SimpleStringProperty(VersionTargets.READING);
        private final StringProperty verdict = new SimpleStringProperty("");
        private Optional<Version> latest;

        Row(String module) {
            this.module = module;
        }

        public String getModule() {
            return module;
        }

        public BooleanProperty selectedProperty() {
            return selected;
        }

        public StringProperty targetProperty() {
            return target;
        }

        public StringProperty verdictProperty() {
            return verdict;
        }

        /** What goes on the command line — a level word, or the exact version as typed. */
        String spec() {
            return exactChosen.get() ? exact.get().strip() : level.get().spelling();
        }

        /** A level is always well formed; only what somebody types can fail to be a version. */
        boolean specWellFormed() {
            return !exactChosen.get() || ReleaseSpec.wellFormed(exact.get());
        }

        /**
         * Recomputes the arrow. Pure once the tags are read, so every keystroke and every level click can
         * call it — {@link VersionTargets#latest} is the only part that touches git, and it is cached here.
         */
        void retarget() {
            if (latest == null) {
                target.set(VersionTargets.releasable(module)
                        ? VersionTargets.READING
                        : VersionTargets.UNKNOWN_MODULE);
            } else if (exactChosen.get()) {
                target.set(VersionTargets.forExact(module, exact.get(), latest));
            } else {
                target.set(VersionTargets.forLevel(module, level.get(), latest));
            }
        }
    }

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final Runnable onChange;

    /** @param onChange called after every tick, level click and keystroke — the tab re-reads the flags */
    ReleaseRowTable(Runnable onChange) {
        this.onChange = onChange;
        setItems(rows);
        for (Module module : Order.TAG) {
            if (module.template()) {
                // Released from the Catalog tab, where the thing it is published as is listed. See ReleaseTab's
                // javadoc: this is the one place the rows are not every module the library knows.
                continue;
            }
            Row row = new Row(module.directory());
            // A tick changes what a release would do, so the command line follows every one. Not a disarm:
            // arming is a value comparison, so un-ticking puts the button back for the plan actually read.
            row.selectedProperty().addListener((o, was, is) -> onChange.run());
            row.retarget();
            rows.add(row);
        }
        buildColumns();
        setEditable(true);
        setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    List<Row> rows() {
        return rows;
    }

    /** The ticked rows, as the modules and specs a {@link ReleaseSpec} takes. */
    Map<Module, String> picked() {
        Map<Module, String> picked = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.selectedProperty().get()) {
                Module.byDirectory(row.getModule()).ifPresent(module -> picked.put(module, row.spec()));
            }
        }
        return picked;
    }

    boolean allSpecsWellFormed() {
        return rows.stream().allMatch(Row::specWellFormed);
    }

    /** Verdicts and arrows are one checkout's; a new checkout starts from unread. */
    void forget() {
        rows.forEach(row -> {
            row.verdict.set("");
            row.latest = null;
            row.retarget();
        });
    }

    /**
     * Writes what the pass said about each module into its row. A module the pass did not name says so: the
     * library's answer to <i>what would this release do</i> did not include it.
     */
    void mergeVerdicts(Plan plan) {
        Map<String, String> said = new LinkedHashMap<>();
        for (Plan.Decision decision : plan.decisions()) {
            said.put(decision.module().directory(), decision.releasing()
                    ? "releasing v" + decision.version()
                    : decision.verdict().skipReason());
        }
        rows.forEach(row -> row.verdict.set(said.getOrDefault(row.getModule(), "not in this preview")));
    }

    /**
     * Reads each module's newest tag in the background and fills in the arrows as they arrive.
     *
     * <p>Every row at once since 2026-09-29, each filling in as its fetch answers; one after another was eleven
     * fetches in a row. Each holds its repository's lock, so it never races the Releases tab's fetch. Re-read
     * after every preview, since a tag cut elsewhere in between changes every arrow under it.
     *
     * @param current the checkout on screen when an answer lands — an answer for another one is dropped
     */
    void loadLatest(Path root, ReleaseBackend backend, Supplier<Path> current) {
        for (Row row : List.copyOf(rows)) {
            Path dir = root.resolve(row.getModule());
            Io.async(() -> Io.inRepository(dir, () -> backend.latest(root, row.getModule())))
                    .thenAccept(latest -> Platform.runLater(() -> {
                        if (root.equals(current.get())) {
                            row.latest = latest;
                            row.retarget();
                        }
                    }));
        }
    }

    private void buildColumns() {
        TableColumn<Row, Boolean> pick = new TableColumn<>("");
        pick.setPrefWidth(40);
        pick.setCellValueFactory(c -> c.getValue().selectedProperty());
        pick.setCellFactory(CheckBoxTableCell.forTableColumn(pick));
        pick.setEditable(true);

        TableColumn<Row, String> module = new TableColumn<>("Module");
        module.setPrefWidth(190);
        module.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getModule()));

        TableColumn<Row, Row> spec = new TableColumn<>("Version / level");
        spec.setPrefWidth(250);
        spec.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        spec.setCellFactory(c -> new SpecCell());

        TableColumn<Row, String> target = new TableColumn<>("Would cut");
        target.setPrefWidth(150);
        target.setCellValueFactory(c -> c.getValue().targetProperty());

        TableColumn<Row, String> verdict = new TableColumn<>("Last preview said");
        verdict.setPrefWidth(220);
        verdict.setCellValueFactory(c -> c.getValue().verdictProperty());

        getColumns().setAll(pick, module, spec, target, verdict);
    }

    /**
     * The level picker: three segments and a fourth for a typed version.
     *
     * <p><b>The typed box is marked rather than refusing the keystroke</b>: a half-typed {@code 1.2} is an
     * ordinary state on the way to {@code 1.2.0}. Preview stays disabled while any row is marked.
     *
     * <p>A toggle group can be cleared by clicking the selected button, and here that would mean a module
     * asked for at no level at all — so a null selection is put back.
     */
    private final class SpecCell extends TableCell<Row, Row> {

        private final ToggleGroup group = new ToggleGroup();
        private final ToggleButton patch = segment("patch");
        private final ToggleButton minor = segment("minor");
        private final ToggleButton major = segment("major");
        private final ToggleButton exactly = segment("x.y.z");
        private final TextField typed = new TextField();
        private final HBox box;

        private Row row;
        /** Set while the cell is being filled from a row, so writing the controls does not write back. */
        private boolean filling;

        SpecCell() {
            typed.setPrefColumnCount(6);
            typed.setPromptText("1.2.0");
            typed.getStyleClass().add("exact-version");

            HBox segments = new HBox(patch, minor, major, exactly);
            segments.getStyleClass().add("segmented");
            box = new HBox(8, segments, typed);
            box.setAlignment(Pos.CENTER_LEFT);

            group.selectedToggleProperty().addListener((o, was, is) -> {
                if (is == null) {
                    group.selectToggle(was);
                    return;
                }
                apply();
            });
            typed.textProperty().addListener((o, was, is) -> apply());
        }

        private ToggleButton segment(String text) {
            ToggleButton button = new ToggleButton(text);
            button.setToggleGroup(group);
            button.getStyleClass().addAll("segment", "segment--" + text.replace('.', '-'));
            // A click on the level already chosen changes no toggle, so it would not tick the row through the
            // listener — and "I clicked patch on this row" plainly means this row.
            button.setOnMouseClicked(e -> {
                if (row != null && !filling) {
                    row.selected.set(true);
                }
            });
            return button;
        }

        private void apply() {
            if (filling || row == null) {
                return;
            }
            boolean exact = group.getSelectedToggle() == exactly;
            row.exactChosen.set(exact);
            if (exact) {
                row.exact.set(typed.getText());
            } else if (group.getSelectedToggle() == minor) {
                row.level.set(Level.MINOR);
            } else if (group.getSelectedToggle() == major) {
                row.level.set(Level.MAJOR);
            } else {
                row.level.set(Level.PATCH);
            }
            typed.setDisable(!exact);
            mark();
            row.retarget();
            // Choosing a level for a row is asking for that row. The tick's own listener refreshes the command
            // line; the call below covers a row that was already ticked.
            row.selected.set(true);
            onChange.run();
        }

        private void mark() {
            typed.getStyleClass().remove("cell--broken");
            if (row != null && !row.specWellFormed()) {
                typed.getStyleClass().add("cell--broken");
            }
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            row = empty ? null : item;
            if (row == null) {
                setGraphic(null);
                return;
            }
            filling = true;
            group.selectToggle(switch (row.exactChosen.get() ? null : row.level.get()) {
                case MINOR -> minor;
                case MAJOR -> major;
                case PATCH -> patch;
                case null -> exactly;
            });
            typed.setText(row.exact.get());
            typed.setDisable(!row.exactChosen.get());
            filling = false;
            mark();
            setGraphic(box);
        }
    }
}
