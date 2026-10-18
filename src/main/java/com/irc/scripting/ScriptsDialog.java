package com.irc.scripting;

import net.runelite.client.util.Filepath;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Shared modeless manager. Selecting a row edits; ticking a box enables execution. EDT only. */
final class ScriptsDialog extends JDialog {
    static final String EXAMPLE = "; Review before enabling. Replies also require Allow replies.\n"
            + "alias hello {\n  echo -a Hello $1\n}\n\n"
            + "on *:TEXT:!ping:#:{\n  msg $chan pong\n}\n";
    private final ScriptManager manager;
    private final ScriptEditor editor;
    private final JTable table;
    private final Model model = new Model();
    private final JLabel path = new JLabel("Select a script"), status = new JLabel(" ");
    private final List<JButton> buttons = new ArrayList<>();
    private final JCheckBox replies = new JCheckBox("Allow replies to the originating conversation");
    private final JCheckBox privateMessages = new JCheckBox("Allow private conversations");
    private final Consumer<List<ScriptManager.View>> listener;
    private String selected, disk = "", saved = "";
    private boolean updating, busy, stopped;
    static final class Draft {
        final String name, source;
        Draft(String name, String source) { this.name = name; this.source = source; }
    }
    private volatile Draft draft = new Draft(null, null);

    ScriptsDialog(ScriptManager manager) {
        super((Window) null, "IRC Scripts", ModalityType.MODELESS);
        this.manager = manager;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        editor = new ScriptEditor(() -> save(false, () -> {}), () -> save(true, () -> {}));
        editor.onChange(this::title);
        editor.editable(false);
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setMaxWidth(65);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updating || busy) return;
            int row = table.getSelectedRow();
            if (row < 0 || row >= model.rows.size()) return;
            String target = model.rows.get(row).name;
            if (!target.equals(selected)) guard(() -> open(target));
        });
        JPanel left = new JPanel(new BorderLayout()); left.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel files = new JPanel(new java.awt.GridLayout(0, 2, 4, 4));
        button(files, "New", this::create);
        button(files, "Add file…", this::importFile);
        button(files, "Remove", () -> remove(false));
        button(files, "Delete file…", () -> remove(true));
        left.add(files, BorderLayout.SOUTH); left.setMinimumSize(new Dimension(260, 200));
        JPanel right = new JPanel(new BorderLayout(4, 4));
        JPanel permissions = new JPanel(new java.awt.GridLayout(0, 1));
        permissions.add(path); permissions.add(replies); permissions.add(privateMessages);
        replies.setToolTipText("Allows msg/notice only to the originating channel or query. No arbitrary recipients or service commands.");
        privateMessages.setToolTipText("Allows incoming private messages; private replies also require Allow replies.");
        replies.addActionListener(e -> changePermissions()); privateMessages.addActionListener(e -> changePermissions());
        right.add(permissions, BorderLayout.NORTH); right.add(editor, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        button(actions, "Save", () -> save(false, () -> {}));
        button(actions, "Save & Reload", () -> save(true, () -> {}));
        button(actions, "Reload disk", () -> guard(() -> { if (selected != null) operation(manager.reload(selected), () -> open(selected)); }));
        button(actions, "Reopen file", () -> guard(() -> { if (selected != null) open(selected); }));
        right.add(actions, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right); split.setDividerLocation(300);
        JPanel content = new JPanel(new BorderLayout(6, 6)); content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(new JLabel("IRC-only scripts • Check to enable and load at startup • Imported files are copied"), BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER); content.add(status, BorderLayout.SOUTH); setContentPane(content);
        setSize(1120, 700); setMinimumSize(new Dimension(920, 500));
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { guard(() -> setVisible(false)); } });
        listener = rows -> SwingUtilities.invokeLater(() -> { if (!stopped) refresh(rows); });
        manager.listen(listener);
        manager.ready().whenComplete((ignored, error) -> { if (error != null) SwingUtilities.invokeLater(() -> report(error)); });
    }

    private void button(JPanel container, String title, Runnable action) {
        JButton button = new JButton(title); button.addActionListener(e -> { if (!busy) action.run(); });
        buttons.add(button); container.add(button);
    }
    private void title() {
        boolean modified = dirty();
        draft = new Draft(modified ? selected : null, modified ? editor.source() : null);
        setTitle("IRC Scripts" + (selected == null ? "" : " — " + selected + (modified ? " *" : "")));
    }
    private boolean dirty() { return selected != null && !editor.source().equals(saved); }
    private void busy(boolean value) {
        busy = value; table.setEnabled(!value); editor.editable(!value && selected != null);
        replies.setEnabled(!value && selected != null); privateMessages.setEnabled(!value && selected != null);
        for (JButton button : buttons) button.setEnabled(!value);
    }
    private void refresh(List<ScriptManager.View> rows) {
        updating = true;
        model.rows = rows; model.fireTableDataChanged();
        replies.setEnabled(!busy && selected != null); privateMessages.setEnabled(!busy && selected != null);
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).name.equals(selected)) {
            table.setRowSelectionInterval(i, i); path.setText(rows.get(i).path); path.setToolTipText(rows.get(i).path);
            replies.setSelected(rows.get(i).replies); privateMessages.setSelected(rows.get(i).privateMessages);
        }
        updating = false;
        // A command may remove the current script. Retain unsaved text until the user chooses to leave it.
        if (selected != null && rows.stream().noneMatch(v -> v.name.equals(selected))) status.setText("Script removed from list; copy any unsaved text before selecting another script.");
    }
    private void restoreSelection() { refresh(model.rows); }
    private void changePermissions() {
        if (updating || busy || selected == null) return;
        operation(manager.permissions(selected, replies.isSelected(), privateMessages.isSelected()),
                () -> status.setText("Permissions saved; existing timers cancelled."));
    }
    private void guard(Runnable next) {
        if (busy) return;
        if (!dirty()) { next.run(); return; }
        restoreSelection();
        int choice = JOptionPane.showOptionDialog(this, "Save changes to " + selected + "?", "Unsaved script",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, new String[]{"Save", "Discard", "Cancel"}, "Save");
        if (choice == 0) save(false, next);
        else if (choice == 1) operation(manager.discardDraft(selected), () -> { editor.source(saved); title(); next.run(); });
    }
    private void open(String name) {
        busy(true);
        manager.document(name).whenComplete((document, error) -> SwingUtilities.invokeLater(() -> {
            if (stopped) return;
            busy(false);
            if (error != null) { report(error); restoreSelection(); return; }
            selected = name; disk = document.disk; saved = normalize(disk);
            editor.source(normalize(document.source)); editor.editable(true); title(); refresh(manager.snapshot());
            status.setText(dirty() ? "Recovered unsaved draft. Save & Reload to apply." : "Ready. Save & Reload applies edits; checkbox enables execution.");
        }));
    }
    private static String normalize(String value) { return value.replace("\r\n", "\n").replace('\r', '\n'); }
    private void save(boolean reload, Runnable next) {
        if (busy || selected == null) return;
        String text = editor.source();
        operation(manager.save(selected, text, disk, reload), () -> {
            disk = saved = text; title(); status.setText(reload ? "Saved and validated. Disabled scripts remain disabled." : "Saved. Reload to apply to a running script."); next.run();
        });
    }
    private void create() {
        guard(() -> {
            String name = JOptionPane.showInputDialog(this, "Script name (letters, digits, - or _):", "hello");
            if (name == null) return;
            try { String file = ScriptManager.filename(name); operation(manager.add(file, EXAMPLE), () -> open(file)); }
            catch (IllegalArgumentException ex) { report(ex); }
        });
    }
    private void importFile() {
        guard(() -> {
            List<Filepath> files = new Filepath.Chooser().setDialogTitle("Import an IRC script (copy into plugin storage)")
                    .setAcceptsFiles().addExtensionFilter("IRC scripts", "mrc", "txt").showDialog(this);
            if (files == null || files.isEmpty()) return;
            Filepath file = files.get(0);
            String name = JOptionPane.showInputDialog(this, "Name for the imported copy:", file.getFileName().replaceFirst("\\.[^.]+$", ""));
            if (name == null) return;
            try { String target = ScriptManager.filename(name); operation(manager.importFile(file, target), () -> open(target)); }
            catch (IllegalArgumentException ex) { report(ex); }
        });
    }
    private void remove(boolean deleteFile) {
        if (selected == null) return;
        guard(() -> {
            String name = selected;
            if (deleteFile && JOptionPane.showConfirmDialog(this, "Delete the managed copy of " + name + " and its saved variables?",
                    "Delete script file", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            operation(manager.remove(name, deleteFile), () -> {
                selected = null; disk = saved = ""; editor.source(""); editor.editable(false); path.setText("Select a script"); title();
                status.setText(deleteFile ? "Managed file deleted. Original imported file is unchanged." : "Removed from list. File retained; /script load can add it again.");
            });
        });
    }
    private void operation(CompletableFuture<?> future, Runnable success) {
        busy(true);
        future.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (stopped) return;
            busy(false);
            if (error != null) report(error); else success.run();
            refresh(manager.snapshot());
        }));
    }
    private void report(Throwable ex) {
        if (stopped) return;
        String message = ScriptManager.message(ex); status.setText(message); status.setToolTipText(message);
        java.util.regex.Matcher line = java.util.regex.Pattern.compile("Line (\\d+):").matcher(message);
        if (line.find()) editor.goToLine(Integer.parseInt(line.group(1)));
    }
    Draft draft() { return draft; }
    void shutdown() { stopped = true; manager.unlisten(listener); editor.stop(); dispose(); }

    private final class Model extends AbstractTableModel {
        List<ScriptManager.View> rows = new ArrayList<>();
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 3; }
        public String getColumnName(int column) { return new String[]{"Enabled", "Script", "Status"}[column]; }
        public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        public Object getValueAt(int row, int column) {
            ScriptManager.View value = rows.get(row); return column == 0 ? value.enabled : column == 1 ? value.name : value.status;
        }
        public boolean isCellEditable(int row, int column) { return column == 0 && !busy; }
        public void setValueAt(Object value, int row, int column) {
            if (column == 0 && !busy) {
                String name = rows.get(row).name;
                operation(manager.enable(name, Boolean.TRUE.equals(value)), () -> status.setText("Updated " + name + "; checkbox state is remembered."));
            }
        }
    }
}
