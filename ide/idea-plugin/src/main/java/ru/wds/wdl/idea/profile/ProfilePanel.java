package ru.wds.wdl.idea.profile;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.TableView;
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns;
import com.intellij.ui.treeStructure.treetable.TreeTable;
import com.intellij.ui.treeStructure.treetable.TreeTableModel;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.ListTableModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Окно одного профиля: горячие места таблицей и дерево вызовов.
 * <p>
 * Числа и подписи — те же, что печатает {@code wdl --profile}: один отчёт не должен
 * читаться по-разному в консоли и в IDE.
 */
public final class ProfilePanel extends JBPanel<ProfilePanel> {

    private static final Locale RU = Locale.forLanguageTag("ru");

    private final Project project;
    private final WdlProfileService.Run run;
    private final ListTableModel<ProfileSite> hot;
    private final SearchTextField filter = new SearchTextField(false);
    private final JBCheckBox builtins = new JBCheckBox("встроенные", true);

    ProfilePanel(@NotNull Project project, @NotNull WdlProfileService.Run run) {
        super(new BorderLayout());
        this.project = project;
        this.run = run;
        this.hot = new ListTableModel<>(columns(), rows(), 3, SortOrder.DESCENDING);

        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("Горячие", new JBScrollPane(hotTable()));
        tabs.addTab("Дерево вызовов", new JBScrollPane(callTree()));
        add(header(), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
    }

    /** Пустое окно со строкой о том, чего в нём нет. */
    static JComponent hint(String text) {
        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        JBLabel label = new JBLabel(text, SwingConstants.CENTER);
        label.setForeground(com.intellij.util.ui.UIUtil.getInactiveTextColor());
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    // --- шапка и подвал -----------------------------------------------------

    private JComponent header() {
        JBPanel<?> panel = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2)));
        filter.getTextEditor().getEmptyText().setText("Фильтр по имени");
        filter.getTextEditor().getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) {
                refresh();
            }

            @Override public void removeUpdate(DocumentEvent event) {
                refresh();
            }

            @Override public void changedUpdate(DocumentEvent event) {
                refresh();
            }
        });
        // На длинном скрипте половина строк — println и члены значений; без этого
        // переключателя своя функция теряется среди чужих.
        builtins.addActionListener(event -> refresh());
        panel.add(filter);
        panel.add(builtins);
        JBCheckBox hints = new JBCheckBox("подсказки в редакторе",
                WdlProfileService.getInstance(project).hintsEnabled());
        hints.addActionListener(event -> {
            WdlProfileService.getInstance(project).setHintsEnabled(hints.isSelected());
            ProfileInlays.refresh(project);
        });
        panel.add(hints);
        return panel;
    }

    /**
     * Итог прогона теми же словами и в том же порядке, что печатает {@code wdl --profile}.
     * <p>
     * Один и тот же отчёт не должен читаться по-разному в консоли и в IDE.
     */
    static String footerText(ProfileReport report) {
        StringBuilder text = new StringBuilder(64)
                .append("вызовов ").append(report.calls())
                .append(" · сам ").append(millis(report.selfNanos()))
                .append(" · по часам ").append(millis(report.wallNanos()));
        if (report.threads() > 1) {
            // Больше одного потока — единственное честное объяснение того, что сумма
            // собственного времени оказалась больше времени по часам.
            text.append(" · потоков ").append(report.threads());
        }
        return text.toString();
    }

    private JComponent footer() {
        JBLabel label = new JBLabel(footerText(run.report()));
        label.setBorder(JBUI.Borders.empty(2, 8));
        label.setForeground(com.intellij.util.ui.UIUtil.getInactiveTextColor());
        return label;
    }

    // --- таблица горячих ----------------------------------------------------

    private TableView<ProfileSite> hotTable() {
        TableView<ProfileSite> table = new TableView<>(hot);
        table.setShowGrid(false);
        table.setStriped(true);
        new DoubleClickListener() {
            @Override protected boolean onDoubleClick(@NotNull MouseEvent event) {
                return openSelected(table.getSelectedObject());
            }
        }.installOn(table);
        DumbAwareAction.create(event -> openSelected(table.getSelectedObject()))
                .registerCustomShortcutSet(CommonShortcuts.ENTER, table);
        return table;
    }

    private boolean openSelected(@Nullable ProfileSite site) {
        return site != null && ProfileFiles.navigate(project, site, run.workingDirectory());
    }

    private List<ProfileSite> rows() {
        String needle = filter == null ? "" : filter.getText().trim().toLowerCase(RU);
        boolean withBuiltins = builtins == null || builtins.isSelected();
        List<ProfileSite> rows = new ArrayList<>();
        for (ProfileSite site : run.report().sites()) {
            if (!withBuiltins && site.kind().equals("native")) {
                continue;
            }
            if (!needle.isEmpty() && !site.name().toLowerCase(RU).contains(needle)) {
                continue;
            }
            rows.add(site);
        }
        return rows;
    }

    private void refresh() {
        hot.setItems(rows());
    }

    private ColumnInfo<?, ?>[] columns() {
        return new ColumnInfo[]{
                new ColumnInfo<ProfileSite, ProfileSite>("что") {
                    @Override public ProfileSite valueOf(ProfileSite site) {
                        return site;
                    }

                    @Override public Comparator<ProfileSite> getComparator() {
                        return Comparator.comparing(ProfileSite::title);
                    }

                    @Override public javax.swing.table.TableCellRenderer getRenderer(ProfileSite site) {
                        return new javax.swing.table.DefaultTableCellRenderer() {
                            @Override public java.awt.Component getTableCellRendererComponent(
                                    javax.swing.JTable table, Object value, boolean selected,
                                    boolean focused, int row, int column) {
                                ProfileSite item = (ProfileSite) value;
                                super.getTableCellRendererComponent(table, item.title(), selected, focused, row, column);
                                setIcon(iconOf(item.kind()));
                                setToolTipText(item.file().isBlank() ? null : item.file());
                                return this;
                            }
                        };
                    }
                },
                number("вызовов", site -> String.valueOf(site.calls()), Comparator.comparingLong(ProfileSite::calls)),
                number("всего", site -> millis(site.totalNanos()), Comparator.comparingLong(ProfileSite::totalNanos)),
                number("сам", site -> millis(site.selfNanos()), Comparator.comparingLong(ProfileSite::selfNanos)),
                number("макс", site -> millis(site.maxNanos()), Comparator.comparingLong(ProfileSite::maxNanos)),
        };
    }

    private static ColumnInfo<ProfileSite, String> number(String title,
                                                          java.util.function.Function<ProfileSite, String> text,
                                                          Comparator<ProfileSite> order) {
        return new ColumnInfo<>(title) {
            @Override public String valueOf(ProfileSite site) {
                return text.apply(site);
            }

            @Override public Comparator<ProfileSite> getComparator() {
                return order;
            }
        };
    }

    // --- дерево вызовов -----------------------------------------------------

    /** Корень дерева вызовов: невидимый узел, детьми — записи верхнего уровня. */
    static DefaultMutableTreeNode treeRoot(ProfileReport report) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        for (int site : report.roots()) {
            ProfileSite entry = report.sites().get(site);
            root.add(new CallNode(report, site, entry.calls(), entry.totalNanos(), false));
        }
        return root;
    }

    private TreeTable callTree() {
        ProfileReport report = run.report();
        DefaultMutableTreeNode root = treeRoot(report);
        ListTreeTableModelOnColumns model = new ListTreeTableModelOnColumns(root, new ColumnInfo[]{
                new ColumnInfo<CallNode, CallNode>("что") {
                    @Override public CallNode valueOf(CallNode node) {
                        return node;
                    }

                    @Override public Class<?> getColumnClass() {
                        return TreeTableModel.class;
                    }
                },
                treeNumber("вызовов", node -> String.valueOf(node.calls)),
                treeNumber("всего", node -> millis(node.totalNanos)),
        });
        TreeTable table = new TreeTable(model);
        table.setRootVisible(false);
        table.getTree().setShowsRootHandles(true);
        table.setTreeCellRenderer(new ColoredTreeCellRenderer() {
            @Override public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected,
                                                        boolean expanded, boolean leaf, int row, boolean focused) {
                if (!(value instanceof CallNode node)) {
                    return;
                }
                ProfileSite site = report.sites().get(node.site);
                setIcon(iconOf(site.kind()));
                append(site.title());
                if (node.recursive) {
                    append("  рекурсия", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            }
        });
        new DoubleClickListener() {
            @Override protected boolean onDoubleClick(@NotNull MouseEvent event) {
                return openNode(table, report);
            }
        }.installOn(table);
        DumbAwareAction.create(event -> openNode(table, report))
                .registerCustomShortcutSet(CommonShortcuts.ENTER, table);
        return table;
    }

    private boolean openNode(TreeTable table, ProfileReport report) {
        javax.swing.tree.TreePath path = table.getTree().getSelectionPath();
        if (path == null || !(path.getLastPathComponent() instanceof CallNode node)) {
            return false;
        }
        return ProfileFiles.navigate(project, report.sites().get(node.site), run.workingDirectory());
    }

    private static ColumnInfo<CallNode, String> treeNumber(String title,
                                                           java.util.function.Function<CallNode, String> text) {
        return new ColumnInfo<>(title) {
            @Override public String valueOf(CallNode node) {
                return text.apply(node);
            }
        };
    }

    /**
     * Узел дерева вызовов; дети берутся из рёбер и только когда до них дошли.
     * <p>
     * Готовое дерево росло бы вместе с запуском — миллион итераций дал бы миллион
     * узлов, — а рёбер столько, сколько в коде таких пар. Повтор записи на своём же
     * пути дальше не разворачивается: это рекурсия, и разворачивать её можно вечно.
     */
    static final class CallNode extends DefaultMutableTreeNode {

        private final ProfileReport report;
        final int site;
        final long calls;
        final long totalNanos;
        final boolean recursive;
        private boolean loaded;

        private CallNode(ProfileReport report, int site, long calls, long totalNanos, boolean recursive) {
            this.report = report;
            this.site = site;
            this.calls = calls;
            this.totalNanos = totalNanos;
            this.recursive = recursive;
        }

        @Override public int getChildCount() {
            load();
            return super.getChildCount();
        }

        @Override public TreeNode getChildAt(int index) {
            load();
            return super.getChildAt(index);
        }

        @Override public boolean isLeaf() {
            return recursive || report.callees(site).isEmpty();
        }

        private void load() {
            if (loaded) {
                return;
            }
            loaded = true;
            if (recursive) {
                return;
            }
            for (ProfileEdge edge : report.callees(site)) {
                add(new CallNode(report, edge.callee(), edge.calls(), edge.totalNanos(), onPath(edge.callee())));
            }
        }

        private boolean onPath(int callee) {
            for (TreeNode parent = this; parent != null; parent = parent.getParent()) {
                if (parent instanceof CallNode node && node.site == callee) {
                    return true;
                }
            }
            return false;
        }
    }

    // --- мелочи -------------------------------------------------------------

    static Icon iconOf(String kind) {
        return switch (kind) {
            case "script" -> AllIcons.FileTypes.Text;
            case "constructor" -> AllIcons.Nodes.Class;
            case "native" -> AllIcons.Nodes.Plugin;
            default -> AllIcons.Nodes.Function;
        };
    }

    static String millis(long nanos) {
        return String.format(RU, "%.2f мс", nanos / 1_000_000.0);
    }
}
