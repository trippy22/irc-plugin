package com.irc.scripting;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.DocumentFilter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.undo.UndoManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain Swing editor; the highlighting only changes presentation and never evaluates text. */
final class ScriptEditor extends JPanel {
    private final JTextPane text = new JTextPane(new DefaultStyledDocument()) {
        @Override public boolean getScrollableTracksViewportWidth() {
            return getParent() == null || getUI().getPreferredSize(this).width <= getParent().getWidth();
        }
    };
    private final JTextArea lines = new JTextArea("1");
    private final JTextField find = new JTextField(12), replace = new JTextField(12);
    private final JLabel position = new JLabel("Line 1");
    private final UndoManager undo = new UndoManager();
    private final Timer highlight = new Timer(180, e -> highlight());
    private Runnable changed = () -> {};
    private boolean loading;
    private static final Pattern TOKENS = Pattern.compile("(?mi)^\\s*;[^\\n]*|\\b(?:alias|on|TEXT|if|elseif|else|var|set|unset|inc|dec|return|halt|echo|msg|notice|timer[a-z_0-9-]*)\\b|[$%][a-zA-Z_0-9.]+(?:-[0-9]*)?");

    ScriptEditor(Runnable save, Runnable saveReload) {
        super(new BorderLayout());
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        text.setFont(mono); lines.setFont(mono); lines.setEditable(false); lines.setFocusable(false);
        // Keep the editing caret independent of the host look-and-feel's caret implementation.
        DefaultCaret caret = new DefaultCaret();
        caret.setBlinkRate(500);
        text.setCaret(caret);
        text.setCaretColor(text.getForeground());
        text.putClientProperty("caretWidth", 2);
        lines.setBorder(BorderFactory.createEmptyBorder(3, 6, 0, 6));
        text.setMargin(new java.awt.Insets(3, 4, 3, 4));
        JScrollPane scroll = new JScrollPane(text); scroll.setRowHeaderView(lines); add(scroll, BorderLayout.CENTER);
        JPanel search = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        search.add(new JLabel("Find")); search.add(find);
        button(search, "Next", this::findNext);
        search.add(new JLabel("Replace")); search.add(replace);
        button(search, "Replace", this::replaceOne);
        button(search, "All", this::replaceAll);
        JPanel bottom = new JPanel(new BorderLayout()); bottom.add(search, BorderLayout.CENTER); bottom.add(position, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        find.addActionListener(e -> findNext());
        highlight.setRepeats(false);
        ((AbstractDocument) text.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override public void insertString(FilterBypass fb, int offset, String value, AttributeSet attributes) throws BadLocationException {
                replace(fb, offset, 0, value, attributes);
            }
            @Override public void replace(FilterBypass fb, int offset, int length, String value, AttributeSet attributes) throws BadLocationException {
                String normalized = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
                if (fb.getDocument().getLength() - length + normalized.length() <= IrcScript.MAX_SOURCE) {
                    super.replace(fb, offset, length, normalized, attributes);
                } else position.setText("Script limit: 65536 characters; edit was not inserted");
            }
        });
        text.getDocument().addUndoableEditListener(e -> {
            // Styling edits must not pollute undo history.
            if (!loading && (!(e.getEdit() instanceof AbstractDocument.DefaultDocumentEvent)
                    || ((AbstractDocument.DefaultDocumentEvent) e.getEdit()).getType() != DocumentEvent.EventType.CHANGE)) undo.addEdit(e.getEdit());
        });
        text.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { }
            private void update() { highlight.restart(); updateLines(); if (!loading) changed.run(); }
        });
        text.addCaretListener(e -> {
            int line = text.getDocument().getDefaultRootElement().getElementIndex(text.getCaretPosition());
            position.setText("Line " + (line + 1) + " · " + text.getDocument().getLength() + " / 65536 characters");
        });
        bind("control Z", "undo", () -> { if (undo.canUndo()) undo.undo(); });
        bind("control Y", "redo", () -> { if (undo.canRedo()) undo.redo(); });
        bind("control F", "find", () -> find.requestFocusInWindow());
        bind("control S", "save", save);
        bind("control shift S", "save-reload", saveReload);
        bind("TAB", "indent", () -> indent(false));
        bind("shift TAB", "outdent", () -> indent(true));
        bind("ENTER", "newline", this::newline);
    }

    private void bind(String key, String name, Runnable action) {
        text.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        text.getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent e) { action.run(); } });
    }
    private static void button(JPanel panel, String title, Runnable action) {
        JButton button = new JButton(title); button.addActionListener(e -> action.run()); panel.add(button);
    }
    void onChange(Runnable action) { changed = action; }
    String source() {
        try { return text.getDocument().getText(0, text.getDocument().getLength()); }
        catch (BadLocationException ex) { throw new IllegalStateException(ex); }
    }
    void source(String source) {
        if (source.length() > IrcScript.MAX_SOURCE) throw new IllegalArgumentException("Editor source is too large");
        loading = true;
        try { text.setText(source.replace("\r\n", "\n").replace('\r', '\n')); text.setCaretPosition(0); undo.discardAllEdits(); }
        finally { loading = false; }
    }
    void editable(boolean enabled) {
        text.setEditable(enabled);
        // Saving temporarily makes the document read-only without necessarily moving focus.
        text.getCaret().setVisible(enabled && text.isFocusOwner());
    }
    void stop() { highlight.stop(); text.getCaret().setVisible(false); }
    void goToLine(int line) {
        javax.swing.text.Element root = text.getDocument().getDefaultRootElement();
        if (line > 0 && line <= root.getElementCount()) {
            text.setCaretPosition(root.getElement(line - 1).getStartOffset()); text.requestFocusInWindow();
        }
    }
    private void updateLines() {
        int count = text.getDocument().getDefaultRootElement().getElementCount();
        StringBuilder out = new StringBuilder("1");
        for (int i = 2; i <= count; i++) out.append('\n').append(i);
        lines.setText(out.toString());
    }
    private static SimpleAttributeSet color(Color color) {
        SimpleAttributeSet style = new SimpleAttributeSet(); StyleConstants.setForeground(style, color); return style;
    }
    private void highlight() {
        String source = source();
        text.getStyledDocument().setCharacterAttributes(0, source.length(), color(text.getForeground()), true);
        Matcher matcher = TOKENS.matcher(source);
        while (matcher.find()) {
            String token = matcher.group().trim();
            Color color = token.startsWith(";") ? new Color(115, 145, 110)
                    : token.startsWith("$") || token.startsWith("%") ? new Color(100, 160, 210) : new Color(190, 130, 210);
            text.getStyledDocument().setCharacterAttributes(matcher.start(), matcher.end() - matcher.start(), color(color), true);
        }
    }
    private void findNext() {
        String needle = find.getText(); if (needle.isEmpty()) return;
        int at = source().indexOf(needle, text.getSelectionEnd());
        if (at < 0) at = source().indexOf(needle);
        if (at >= 0) { text.requestFocusInWindow(); text.select(at, at + needle.length()); }
        else position.setText("No match");
    }
    private void replaceOne() {
        if (!text.isEditable() || find.getText().isEmpty()) return;
        if (find.getText().equals(text.getSelectedText())) text.replaceSelection(replace.getText());
        findNext();
    }
    private void replaceAll() {
        if (!text.isEditable() || find.getText().isEmpty()) return;
        String source = source(), needle = find.getText(), value = replace.getText();
        StringBuilder out = new StringBuilder(); int start = 0, at;
        while ((at = source.indexOf(needle, start)) >= 0) {
            out.append(source, start, at).append(value); start = at + needle.length();
            if (out.length() > IrcScript.MAX_SOURCE) { position.setText("Replacement exceeds script size limit"); return; }
        }
        out.append(source.substring(start));
        text.selectAll(); text.replaceSelection(out.toString());
    }
    private void newline() {
        if (!text.isEditable()) return;
        String source = source(); int caret = text.getSelectionStart(), start = source.lastIndexOf('\n', caret - 1) + 1;
        int end = start; while (end < caret && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) end++;
        String indentation = source.substring(start, end);
        if (source.substring(start, caret).trim().endsWith("{")) indentation += "  ";
        text.replaceSelection("\n" + indentation);
    }
    private void indent(boolean remove) {
        if (!text.isEditable()) return;
        if (!remove && text.getSelectionStart() == text.getSelectionEnd()) { text.replaceSelection("  "); return; }
        String source = source();
        int start = source.lastIndexOf('\n', text.getSelectionStart() - 1) + 1;
        int end = text.getSelectionEnd();
        String block = source.substring(start, end);
        String updated = remove ? block.replaceAll("(?m)^( {1,2}|\\t)", "") : block.replaceAll("(?m)^", "  ");
        text.select(start, end); text.replaceSelection(updated); text.select(start, start + updated.length());
    }
}
