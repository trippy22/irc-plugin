package com.irc.ui;

import com.irc.protocol.ChannelListEntry;
import com.irc.protocol.ChannelUserList;
import com.irc.model.IrcChatModel;
import com.irc.IrcConfig;
import com.irc.protocol.IrcFormatting;
import com.irc.model.IrcMessage;
import com.irc.protocol.SimpleIrcClient;

import com.google.inject.Provides;
import com.irc.emoji.EmojiParser;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.LinkBrowser;
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.HyperlinkEvent;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;
import java.awt.event.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

@Slf4j
public class IrcPanel extends PluginPanel {
    @Inject
    private IrcConfig config;
    @Inject
    private ConfigManager configManager;
    @Inject
    private OkHttpClient okHttpClient;

    @Getter
    private final JPanel chatContent = new JPanel(new BorderLayout());
    private IrcPanelWindow panelWindow;
    private IrcWindowController windowController;
    private JPanel controlPanel;
    private final IrcChatModel model = new IrcChatModel();
    private final Consumer<IrcChatModel.Snapshot> modelListener = this::renderModel;
    private final Map<Long, JScrollPane> renderedConversations = new LinkedHashMap<>();
    private final JLabel sessionStatus = new JLabel("Offline");
    private final JPanel headerPanel = new JPanel(new BorderLayout());
    private boolean renderingModel;

    public IrcChatModel getModel() { return model; }
    private IrcDesktopLayout desktopLayout;
    private boolean detachedLayout;
    private Timer flashTimer;
    private JTabbedPane tabbedPane;
    public JTextField inputField;
    @Getter
    private final Map<String, ChannelPane> channelPanes = Collections.synchronizedMap(new LinkedHashMap<>());
    @Getter
    private NavigationButton navigationButton;

    private BiConsumer<String, String> onMessageSend;
    private BiConsumer<String, String> onChannelJoin;
    private Consumer<String> onChannelLeave;
    private Consumer<Boolean> onReconnect;
    private Consumer<String> onChannelListRequest;
    private Runnable onChannelListTimeout;
    private ChannelListDialog channelListDialog;
    private Timer channelListTimeout;
    private static final int CHANNEL_LIST_TIMEOUT_MS = 30000;
    private Font font;

    private static final String SYSTEM_TAB = "System";

    private final JComboBox<String> bufferDropdown = getBufferComboBox();
    private final InputHistory inputHistory = new InputHistory(20);

    private static final String USERS_HEADER_PREFIX = "Users (";
    /** Render cache only; the shared model owns the roster. */
    private List<ChannelUserList.Entry> displayedEntries = Collections.emptyList();
    private final JComboBox<String> nickDropdown = getNickComboBox();

    public ArrayList<String> getChannelNames() {
        Map<String, ChannelPane> panes = getChannelPanes();
        synchronized (panes) {
            return new ArrayList<>(panes.keySet());
        }
    }

    /**
     * Shared by the side panel and the game chatbox, so the trailing character class decides where
     * a link ends in both. A character missing from it does not reject the URL, it truncates it -
     * the link still renders and still clicks, it just goes somewhere else.
     *
     * '+' and '#' are kept last: placed mid-class either would form a range with the character
     * after it ('+' to ';' silently sweeps in digits and punctuation). Neither can start a link,
     * so a channel name like #osrs is still not matched.
     */
    public static final Pattern VALID_LINK = Pattern.compile("(https?://([\\w-]+\\.)+[\\w-]+([\\w-;:,./?%&=+#]*))");

    private void initializeFlashTimer() {
        // Change color for different flash
        flashTimer = new Timer(500, e -> {
            String currentTab = getCurrentChannel();
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                String tabTitle = tabbedPane.getTitleAt(i);
                if (!SYSTEM_TAB.equals(tabTitle) && isUnread(tabTitle) && !tabTitle.equals(currentTab)) {
                    tabbedPane.setForegroundAt(i, new Color(135, 206, 250)); // Change color for different flash
                } else if (!SYSTEM_TAB.equals(tabTitle) && !isUnread(tabTitle)) {
                    tabbedPane.setForegroundAt(i, Color.white);
                }
            }
        });
        flashTimer.start();
    }

    public void initializeGui() {
        setLayout(new BorderLayout());
        font = new Font(config.fontFamily(), Font.PLAIN, config.fontSize());
        tabbedPane = new JTabbedPane();
        tabbedPane.setPreferredSize(new Dimension(300, 400));
        tabbedPane.setUI(new BasicTabbedPaneUI() {
            @Override
            protected int calculateTabAreaHeight(int tabPlacement, int runCount, int maxTabHeight) {
                return 0;
            }
        });
        inputField = new JTextField();
        inputField.setFont(font);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent e) {
                hideAllPreviews();
            }
        });

        controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        // Two equal columns rather than FlowLayout: the panel is only ~225px wide, and a long
        // channel name made the buffer dropdown wide enough to push the pair onto a second row.
        // A grid splits the width evenly and clips inside a cell instead of wrapping. Fill order
        // is still left-to-right, so the nick dropdown stays left of the buffer dropdown.
        JPanel row2 = new JPanel(new GridLayout(1, 2, 2, 0));

        JButton addButton = new JButton("+");
        JButton removeButton = new JButton("-");
        JButton reloadButton = new JButton();
        try {
            Image img = ImageUtil.loadImageResource(getClass(), "/com/irc/reload.png");
            reloadButton.setIcon(new ImageIcon(img));
        } catch (Exception ignored) {
            reloadButton.setText("R");
        }
        Dimension standard = new Dimension(25, 25);
        addButton.setPreferredSize(standard);
        removeButton.setPreferredSize(standard);
        reloadButton.setPreferredSize(standard);
        final JComboBox<String> fontComboBox = getFontComboBox();


        bufferDropdown.addActionListener(this::actionPerformed);

        addButton.addActionListener(e -> promptAddChannel());
        removeButton.addActionListener(e -> promptRemoveChannel());
        reloadButton.addActionListener(e -> onReconnect.accept(true));
        row1.add(reloadButton);
        row1.add(addButton);
        row1.add(removeButton);
        row1.add(fontComboBox);
        row2.add(nickDropdown);
        row2.add(bufferDropdown);
        controlPanel.add(row1);
        controlPanel.add(row2);
        Action originalPasteAction = inputField.getActionMap().get("paste");
        Action customPasteAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                originalPasteAction.actionPerformed(e);
                String text = inputField.getText();
                inputField.setText(convertModernEmojis(text));
            }
        };
        inputField.getActionMap().put("paste", customPasteAction);
        setupShortcuts();
        inputField.addActionListener(e -> {
            String message = inputField.getText();
            if (!message.isEmpty() && onMessageSend != null) {
                String conversation = getCurrentChannel();
                model.draft(conversation, "");
                inputHistory.add(message);
                onMessageSend.accept(conversation, message);
            }
        });
        headerPanel.add(controlPanel, BorderLayout.CENTER);
        sessionStatus.setName("ircSessionStatus");
        sessionStatus.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        headerPanel.add(sessionStatus, BorderLayout.NORTH);
        chatContent.add(headerPanel, BorderLayout.NORTH);
        chatContent.add(tabbedPane, BorderLayout.CENTER);
        chatContent.add(inputField, BorderLayout.SOUTH);
        add(chatContent, BorderLayout.CENTER);
        panelWindow = new IrcPanelWindow(this, chatContent, this::prepareForHostChange,
                this::hideAllPreviews, this::requestDock);
        windowController = new IrcWindowController(this::renderHost, this::persistDock);
        navigationButton = generateNavigationButton();

        tabbedPane.addChangeListener(e -> onFocusedBufferChanged());
        initializeFlashTimer();
        model.setHistoryLimit(config.getMaxScrollback());
        model.listen(modelListener);
        inputField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void changed() { if (!renderingModel) model.draft(model.snapshot().selected, inputField.getText()); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        });
    }

    /** Runs when the focused buffer changes. Package-private so tests can drive it without the
     *  full initializeGui() Swing setup, which cannot run headless. */
    void onFocusedBufferChanged() {
        if (renderingModel) return;
        int index = tabbedPane.getSelectedIndex();
        if (index >= 0) model.select(tabbedPane.getTitleAt(index));
    }
    public void cycleChannel() {
        List<String> channels = this.getChannelNames();
        if (channels.isEmpty()) return;

        String current = this.getCurrentChannel();
        int index = channels.indexOf(current);
        index = (index + 1) % channels.size();
        this.setFocusedChannel(channels.get(index));
    }

    public void cycleChannelBackwards() {
        List<String> channels = this.getChannelNames();
        if (channels.isEmpty()) return;

        String current = this.getCurrentChannel();
        int index = channels.indexOf(current);
        index = (index - 1 < 0 ? channels.size() - 1 : (index - 1) % channels.size());
        this.setFocusedChannel(channels.get(index));
    }

    private JComboBox<String> getFontComboBox() {
        final JComboBox<String> fontComboBox = getStringFontComboBox();
        fontComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                Font font = new Font(value.toString(), Font.PLAIN, config.fontSize());
                label.setFont(font);
                return label;
            }
        });

        bufferDropdown.setBackground(Color.DARK_GRAY);
        bufferDropdown.setForeground(Color.WHITE);

        return fontComboBox;
    }

    private JComboBox<String> getBufferComboBox() {
        final JComboBox<String> bufferComboBox = getStringJComboBox();

        bufferComboBox.addMouseWheelListener(e -> {
            if (e.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL) {
                int direction = e.getWheelRotation(); // +1 down, -1 up
                int index = bufferComboBox.getSelectedIndex();

                if (direction > 0 && index < bufferComboBox.getItemCount() - 1) {
                    this.cycleChannel();
                } else if (direction < 0 && index > 0) {
                    this.cycleChannelBackwards();
                }
            }
        });

        return bufferComboBox;
    }

    private JComboBox<String> getNickComboBox() {
        final JComboBox<String> combo = new JComboBox<>();
        combo.setBackground(Color.DARK_GRAY);
        combo.setForeground(Color.WHITE);
        // row2's GridLayout decides the width (~105px per column), so this mainly fixes the height.
        // "Users (999)" measures ~89px including the combo's chrome, so the column has headroom;
        // 90px was on the clip boundary, which is why the header read "Users (...".
        combo.setPreferredSize(new Dimension(110, 25));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String text = value == null ? "" : value.toString();
                if (text.startsWith(USERS_HEADER_PREFIX)) {
                    label.setForeground(Color.GRAY);
                } else {
                    label.setForeground(nickColorAt(index, text));
                }
                return label;
            }
        });
        combo.addActionListener(e -> openQueryFromNickDropdown());
        return combo;
    }

    /**
     * Colors a dropdown label the same way the chat pane colors that nick. The label carries a
     * prefix character, so the raw nick is taken from the backing entry rather than the text.
     */
    private Color nickColor(String label) {
        for (ChannelUserList.Entry entry : displayedEntries) {
            if (label.equals(entry.getPrefix() + entry.getNick())) {
                return nickColorFor(entry.getNick());
            }
        }
        return Color.WHITE;
    }

    /** Colours a dropdown row. Prefers the row index the renderer already has; the label scan is
     *  only for index -1, the collapsed button, which renders one item. */
    private Color nickColorAt(int index, String label) {
        List<ChannelUserList.Entry> entries = displayedEntries;
        if (index >= 1 && index <= entries.size()) {
            return nickColorFor(entries.get(index - 1).getNick());
        }
        return nickColor(label);
    }

    private Color nickColorFor(String nick) {
        try {
            return Color.decode(ChannelPane.htmlColorById(ChannelPane.nickColorId(nick)));
        } catch (NumberFormatException ignored) {
            return Color.WHITE;
        }
    }

    /** Opens (or focuses) a PM buffer for the selected nick, then returns to the header. */
    private void openQueryFromNickDropdown() {
        List<ChannelUserList.Entry> entries = displayedEntries;
        int index = nickDropdown.getSelectedIndex();
        if (index < 1 || index > entries.size()) {
            return;
        }
        String nick = entries.get(index - 1).getNick();
        nickDropdown.setSelectedIndex(0);
        addChannel(nick);
        setFocusedChannel(nick);
    }

    /** Pushes a fresh roster in. Only redraws when it is for the buffer currently on screen. */
    public void setChannelUsers(String channel, List<ChannelUserList.Entry> entries) {
        model.users(channel, entries);
    }
    /**
     * Rebuilds the dropdown for the focused buffer. Action listeners are detached for the
     * duration: this runs from inside the dropdown's own listener whenever selecting a nick
     * changes the focused tab.
     */
    private void repopulateNickDropdown() {
        if (tabbedPane == null) {
            return;
        }
        String channel = getCurrentChannel();
        List<ChannelUserList.Entry> entries = model.snapshot().conversations.stream()
                .filter(b -> model.sameName(b.name, channel)).map(b -> b.users).findFirst().orElse(Collections.emptyList());
        if (entries.equals(displayedEntries) && desktopLayout == null) return;
        displayedEntries = entries;
        if (desktopLayout != null) desktopLayout.updateUsers(channel, entries);

        ActionListener[] listeners = nickDropdown.getActionListeners();
        for (ActionListener listener : listeners) {
            nickDropdown.removeActionListener(listener);
        }
        try {
            nickDropdown.removeAllItems();
            nickDropdown.addItem(USERS_HEADER_PREFIX + entries.size() + ")");
            for (ChannelUserList.Entry entry : entries) {
                nickDropdown.addItem(entry.getPrefix() + entry.getNick());
            }
            nickDropdown.setSelectedIndex(0);
            nickDropdown.setEnabled(SimpleIrcClient.isChannel(channel));
        } finally {
            for (ActionListener listener : listeners) {
                nickDropdown.addActionListener(listener);
            }
        }
    }

    private JComboBox<String> getStringJComboBox() {
        final JComboBox<String> bufferComboBox = new JComboBox<>();

        bufferComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

                if (isUnread(value.toString())) {
                    label.setForeground(new Color(135, 206, 250)); // Change color for different flash
                } else {
                    label.setForeground(Color.white);
                }

                return label;
            }
        });
        return bufferComboBox;
    }

    public void hideAllPreviews() {
        if (channelPanes != null) {
            synchronized (channelPanes) {
                for (ChannelPane pane : channelPanes.values()) {
                    pane.cancelPreviewManager();
                }
            }
        }
    }

    public NavigationButton generateNavigationButton() {
        navigationButton = NavigationButton.builder()
                .tooltip("IRC")
                .icon(ImageUtil.loadImageResource(getClass(), "/com/irc/icon.png"))
                .priority(config.getPanelPriority())
                .panel(this)
                .build();
        return navigationButton;
    }

    public void setFocusedChannel(String channel) {
        if (!renderingModel) model.select(channel);
    }
    private JComboBox<String> getStringFontComboBox() {
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        final JComboBox<String> fontComboBox = new JComboBox<>(fonts);
        int selectedIndex = Arrays.asList(fonts).indexOf(config.fontFamily());
        if (selectedIndex < 0) {
            selectedIndex = 0;
            font = new Font(fonts[0], Font.PLAIN, config.fontSize());
        }
        fontComboBox.setSelectedIndex(selectedIndex);
        fontComboBox.setPreferredSize(new Dimension(110, 25));
        fontComboBox.addActionListener(e -> {
            if (fontComboBox.getSelectedItem() != null) {
                String selected = fontComboBox.getSelectedItem().toString();
                configManager.setConfiguration("irc", "fontFamily", selected);
                updateFont();
            }
        });
        return fontComboBox;
    }

    private void updateFont() {
        font = new Font(config.fontFamily(), Font.PLAIN, config.fontSize());
        inputField.setFont(font);
        synchronized (channelPanes) {
            for (ChannelPane channelPane : channelPanes.values()) {
                channelPane.setFont(font);
            }
        }
    }

    @Provides
    IrcConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrcConfig.class);
    }

    public void init(BiConsumer<String, String> messageSendCallback, BiConsumer<String, String> channelJoinCallback, Consumer<String> channelLeaveCallback, Consumer<Boolean> onReconnect, Consumer<String> channelListRequestCallback, Runnable channelListTimeoutCallback) {
        this.onMessageSend = messageSendCallback;
        this.onChannelJoin = channelJoinCallback;
        this.onChannelLeave = channelLeaveCallback;
        this.onReconnect = onReconnect;
        this.onChannelListRequest = channelListRequestCallback;
        this.onChannelListTimeout = channelListTimeoutCallback;
    }

    public String getCurrentChannel() { return model.snapshot().selected; }

    boolean isUnread(String name) {
        for (IrcChatModel.Conversation conversation : model.snapshot().conversations) {
            if (model.sameName(conversation.name, name)) return conversation.unread;
        }
        return false;
    }

    public void clearCurrentPane() { model.clear(getCurrentChannel()); }
    public boolean isPane(String name) {
        return tabbedPane.indexOfTab(name) != -1;
    }

    /** Asks the plugin for a channel list. {@code query} is passed to the server verbatim. */
    public void requestChannelList(String query) {
        if (onChannelListRequest != null) {
            onChannelListRequest.accept(query != null ? query : "");
        }
    }

    /**
     * Starts the window in which a LIST reply is expected. A server that never sends 323 would
     * otherwise leave the user with no feedback at all.
     *
     * <p>The expiry is reported through the plugin rather than written straight into this panel:
     * "Requesting channel list..." and an explicit 263 refusal both reach the game chatbox as well
     * as the panel, and a user watching game chat with the sidebar collapsed would otherwise see
     * the request announced and never learn it failed.
     */
    public void armChannelListTimeout() {
        cancelChannelListTimeout();
        channelListTimeout = new Timer(CHANNEL_LIST_TIMEOUT_MS, e -> {
            if (onChannelListTimeout != null) {
                onChannelListTimeout.run();
            }
        });
        channelListTimeout.setRepeats(false);
        channelListTimeout.start();
    }

    /** Stops the pending timeout. Safe to call when nothing is armed. */
    public void cancelChannelListTimeout() {
        if (channelListTimeout != null) {
            channelListTimeout.stop();
        }
    }

    /**
     * Releases what this panel owns outside its own component tree, on plugin shutdown.
     *
     * The channel browser is a top-level {@link java.awt.Window}: dropping the panel does not take
     * it with it. Without this it stays on screen after the plugin is disabled, wired to a plugin
     * that is gone - Refresh and Join both return silently, so it looks alive and does nothing.
     * Re-enabling compounds it, because the injector hands out a fresh panel with a fresh dialog
     * and the old one leaks along with its sorter and up to 20,000 entries.
     *
     * Nulling both fields makes a later {@link #showChannelList} rebuild cleanly. Safe to call
     * repeatedly, and when nothing was ever armed or shown.
     */
    public void shutdown() {
        model.unlisten(modelListener);
        if (windowController != null) windowController.close();
        if (panelWindow != null) {
            panelWindow.shutdown();
            panelWindow = null;
        }
        if (flashTimer != null) {
            flashTimer.stop();
        }
        hideAllPreviews();
        cancelChannelListTimeout();
        channelListTimeout = null;
        if (channelListDialog != null) {
            channelListDialog.dispose();
            channelListDialog = null;
        }
    }

    public void setDetached(boolean detached, boolean alwaysOnTop) {
        windowController.configure(detached, alwaysOnTop);
    }

    private void renderHost(boolean detached, boolean alwaysOnTop) {
        if (detachedLayout != detached) {
            if (detached) {
                if (desktopLayout == null) {
                    desktopLayout = new IrcDesktopLayout(config.server().getHostname(),
                            this::isUnread, this::setFocusedChannel,
                            nick -> {
                                addChannel(nick);
                                setFocusedChannel(nick);
                                inputField.requestFocusInWindow();
                            },
                            nick -> onMessageSend.accept(getCurrentChannel(), "/whois " + nick),
                            this::promptAddChannel, this::promptRemoveChannel,
                            () -> requestChannelList(""), () -> onReconnect.accept(true),
                            this::requestDock);
                }
                headerPanel.remove(controlPanel);
                desktopLayout.attachChat(tabbedPane, inputField);
                chatContent.add(desktopLayout, BorderLayout.CENTER);
                refreshDesktopChannels();
                repopulateNickDropdown();
            } else {
                chatContent.remove(desktopLayout);
                headerPanel.add(controlPanel, BorderLayout.CENTER);
                chatContent.add(tabbedPane, BorderLayout.CENTER);
                chatContent.add(inputField, BorderLayout.SOUTH);
            }
            detachedLayout = detached;
            chatContent.revalidate();
            chatContent.repaint();
        }
        panelWindow.setDetached(detached, alwaysOnTop);
    }

    private void refreshDesktopChannels() {
        if (desktopLayout != null) desktopLayout.updateChannels(getChannelNames(), getCurrentChannel());
    }

    private void requestDock() { windowController.dock(); }

    private void persistDock() {
        configManager.setConfiguration("irc", "popOut", false);
        IrcConfigUi.syncPopOutCheckbox(configManager.getConfigDescriptor(config), false);
    }

    private void prepareForHostChange() {
        hideAllPreviews();
        // Dialog owners cannot change. Recreate the browser against the new host on next use.
        if (channelListDialog != null) {
            channelListDialog.dispose();
            channelListDialog = null;
        }
    }

    /**
     * Shows the channel browser. Reuses one dialog so a repeated /list refreshes in place.
     * An empty result still opens - "0 channels" answers a narrow query.
     *
     * <p>Does not marshal onto the EDT itself - callers on a background thread (e.g. the IRC
     * reader thread delivering a LIST reply) must wrap the call in
     * {@link SwingUtilities#invokeLater}.
     */
    public void showChannelList(List<ChannelListEntry> entries, String query, boolean truncated) {
        cancelChannelListTimeout();
        if (channelListDialog == null) {
            channelListDialog = new ChannelListDialog(
                    SwingUtilities.getWindowAncestor(chatContent),
                    (channel, password) -> {
                        if (onChannelJoin != null) {
                            onChannelJoin.accept(channel, password);
                        }
                    },
                    this::requestChannelList);
        }
        channelListDialog.setEntries(entries, query, truncated);
        channelListDialog.showDialog();
    }

    public void addChannel(String channel) {
        boolean focus = config.autofocusOnNewTab() || model.sameName(channel, config.channel())
                || model.snapshot().conversations.size() == 1;
        model.open(channel, focus);
    }

    public void removeChannel(String channel) { model.close(channel); }

    public void addMessage(IrcMessage message) {
        model.setHistoryLimit(config.getMaxScrollback());
        model.append(message, config.autofocusOnNewTab() || model.snapshot().conversations.size() == 1);
    }

    /** Widgets are projections. Recreating either host can reconstruct everything from this snapshot. */
    private void renderModel(IrcChatModel.Snapshot state) {
        if (tabbedPane == null) return;
        renderingModel = true;
        try {
            List<String> names = new ArrayList<>();
            for (IrcChatModel.Conversation b : state.conversations) names.add(b.name);
            if (!names.equals(getChannelNames())) {
                tabbedPane.removeAll();
                bufferDropdown.removeAllItems();
                channelPanes.clear();
                Set<Long> ids = new HashSet<>();
                for (IrcChatModel.Conversation b : state.conversations) {
                    ids.add(b.id);
                    JScrollPane scroll = renderedConversations.computeIfAbsent(b.id, ignored -> {
                        ChannelPane pane = new ChannelPane(font, config, okHttpClient);
                        JScrollPane view = new JScrollPane(pane);
                        view.getVerticalScrollBar().addAdjustmentListener(e -> pane.cancelPreviewManager());
                        return view;
                    });
                    channelPanes.put(b.name, (ChannelPane) scroll.getViewport().getView());
                    tabbedPane.addTab(b.name, scroll);
                    bufferDropdown.addItem(b.name);
                }
                renderedConversations.entrySet().removeIf(e -> {
                    if (ids.contains(e.getKey())) return false;
                    ((ChannelPane) e.getValue().getViewport().getView()).cancelPreviewManager();
                    return true;
                });
            }
            for (IrcChatModel.Conversation b : state.conversations) {
                channelPanes.get(b.name).showMessages(b.messages);
                if (model.sameName(b.name, state.selected)) {
                    int index = tabbedPane.indexOfTab(b.name);
                    tabbedPane.setSelectedIndex(index);
                    bufferDropdown.setSelectedIndex(index);
                    tabbedPane.setForegroundAt(index, Color.WHITE);
                    if (!inputField.getText().equals(b.draft)) inputField.setText(b.draft);
                    String status = label(state.connection) + (state.nick.isEmpty() ? "" : " · " + state.nick);
                    if (b.membership != IrcChatModel.Membership.NONE) status += " | " + b.name + ": " + label(b.membership);
                    sessionStatus.setText(status);
                    sessionStatus.setToolTipText(b.detail.isEmpty() ? status : status + " — " + b.detail);
                }
            }
            repopulateNickDropdown();
            refreshDesktopChannels();
        } finally { renderingModel = false; }
    }

    private static String label(Enum<?> state) {
        String text = state.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
    /**
     * Gives a component the keyboard focus as soon as it is actually on screen.
     *
     * {@link JOptionPane#showOptionDialog} does not set the pane's {@code wantsInput} flag, so
     * BasicOptionPaneUI's initial-value selection focuses the default button instead of the input
     * field - unlike {@code showInputDialog}, which focuses the field. Requesting focus before the
     * dialog is shown is a no-op (the component has no window yet), hence the hierarchy listener.
     */
    private static void focusWhenShown(JComponent component) {
        component.addHierarchyListener(new HierarchyListener() {
            @Override
            public void hierarchyChanged(HierarchyEvent e) {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && component.isShowing()) {
                    component.requestFocusInWindow();
                }
            }
        });
    }

    private void promptAddChannel() {
        JTextField channelField = new JTextField();
        focusWhenShown(channelField);
        Object[] options = {"Join", "Browse…", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                chatContent,
                new Object[]{"Enter channel name:", channelField},
                "Add channel",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[0]);

        if (choice == 1) {
            // Browse: let the user pick from the server's list instead of typing a name.
            requestChannelList("");
            return;
        }
        if (choice != 0) return;

        String channel = channelField.getText();
        if (channel == null || channel.trim().isEmpty()) return;

        String password = JOptionPane.showInputDialog(chatContent, "Enter channel password (optional):");
        if (!SimpleIrcClient.isChannel(channel)) {
            channel = "#" + channel;
        }
        if (onChannelJoin != null) {
            onChannelJoin.accept(channel, password);
        }
    }

    public void renameChannel(String oldName, String newName) { model.rename(oldName, newName); }

    private void promptRemoveChannel() {
        String channel = getCurrentChannel();
        if (!channel.equals("System")) {
            int result = JOptionPane.showConfirmDialog(chatContent, "Close " + channel + "?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.YES_OPTION && onChannelLeave != null) {
                onChannelLeave.accept(channel);
            }
        }
    }

    private void actionPerformed(ActionEvent e) {
        int idx = bufferDropdown.getSelectedIndex();
        int i = 0;
        synchronized (channelPanes) {
            for (Map.Entry<String, ChannelPane> channel : channelPanes.entrySet()) {
                if (i == idx) {
                    this.setFocusedChannel(channel.getKey());
                    break;
                }

                i++;
            }
        }
        hideAllPreviews();
    }


    public static class ChannelPane extends JTextPane {
        private final IrcConfig config;
        private final ArrayList<String> messageLog;
        private boolean renderPending;
        private List<IrcMessage> displayedMessages = Collections.emptyList();
        private Map<IrcMessage, String> formattedMessages = new IdentityHashMap<>();

        void showMessages(List<IrcMessage> messages) {
            if (messages.equals(displayedMessages)) return;
            displayedMessages = messages;
            messageLog.clear();
            Map<IrcMessage, String> next = new IdentityHashMap<>();
            for (IrcMessage message : messages) {
                String html = formattedMessages.get(message);
                if (html == null) html = formatPanelMessage(message, config);
                next.put(message, html);
                messageLog.add(html);
            }
            formattedMessages = next;
            scheduleRender();
        }
        private static final Pattern UNDERLINE = Pattern.compile("\u001F([^\u001F\u000F]+)[\u001F\u000F]?");
        private static final Pattern ITALIC = Pattern.compile("\u001D([^\u001D\u000F]+)[\u001D\u000F]?");
        private static final Pattern BOLD = Pattern.compile("\u0002([^\u0002\u000F]+)[\u0002\u000F]?");
        private static final Pattern COLORS = Pattern.compile("(?:\u0003\\d\\d?(?:,\\d\\d?)?\\s*)?\u000F?\u0003(\\d\\d?)(?:,(\\d\\d?))?([^\u0003\u000F]+)\u000F?");
        private final PreviewManager previewManager;

        ChannelPane(Font font, IrcConfig config, OkHttpClient okHttpClient) {
            this.config = config;
            this.previewManager = new PreviewManager(this, okHttpClient);
            setContentType("text/html");
            setFont(font);
            setEditable(false);
            messageLog = new ArrayList<>();

            addHyperlinkListener(e -> {
                if (e.getURL() != null) {
                    String url = e.getURL().toString();
                    if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                        try {
                            LinkBrowser.browse(e.getURL().toURI().toString());
                        } catch (Exception ignored) {
                        }
                    } else if (e.getEventType() == HyperlinkEvent.EventType.ENTERED) {
                        if (this.config.hoverPreviewImages() && previewManager.isImageUrl(url)) {
                            MouseEvent mouseEvent = (e.getInputEvent() instanceof MouseEvent)
                                    ? (MouseEvent) e.getInputEvent()
                                    : null;

                            if (mouseEvent != null) {
                                previewManager.requestShow(mouseEvent.getPoint(), url);
                            }
                        }
                    } else if (e.getEventType() == HyperlinkEvent.EventType.EXITED) {
                        previewManager.cancelPreview();
                    }
                }
            });
        }

        private void scheduleRender() {
            if (renderPending) return;
            renderPending = true;
            SwingUtilities.invokeLater(() -> {
                renderPending = false;
                JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
                JScrollBar bar = scroll == null ? null : scroll.getVerticalScrollBar();
                boolean follow = bar == null || bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 8;
                Point position = scroll == null ? null : scroll.getViewport().getViewPosition();
                setText("<html><body style='color:" + ColorUtil.toHexColor(ColorScheme.TEXT_COLOR) + ";'>" + String.join("", messageLog) + "</body></html>");
                if (follow) setCaretPosition(getDocument().getLength());
                else scroll.getViewport().setViewPosition(position);
            });
        }

        private String formatPanelMessage(IrcMessage message, IrcConfig config) {
            if (message.getType() == IrcMessage.MessageType.HISTORY_SEPARATOR) {
                return "<div style='color: #808080; text-align: center;'>--- Begin of chat ---</div>";
            }
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
            String timeStamp = "";
            if (config.timestamp()) {
                timeStamp = "[" + formatter.format(message.getTimestamp()) + "] ";
            }
            String color;
            switch (message.getType()) {
                case SYSTEM:
                case NICK_CHANGE:
                case KICK:
                case MODE:
                    color = ColorUtil.toHexColor(ColorScheme.BRAND_ORANGE);
                    break;
                case JOIN:
                    color = ColorUtil.toHexColor(ColorScheme.PROGRESS_INPROGRESS_COLOR);
                    break;
                case PART:
                case QUIT:
                    color = ColorUtil.toHexColor(ColorScheme.PROGRESS_ERROR_COLOR);
                    break;
                case TOPIC:
                    color = ColorUtil.toHexColor(ColorScheme.TEXT_COLOR);
                    break;
                default:
                    color = ColorUtil.toHexColor(ColorScheme.LIGHT_GRAY_COLOR);
            }
            String sender = escapeHtml4(message.getSender());
            if (config.colorizedNicks()) {
                String senderColor = htmlColorById(nickColorId(message.getSender()));
                sender = String.format("<font style=\"color:%s\">%s</font>", senderColor, sender);
            }
            return String.format("<div style='color: %s'>%s%s: %s</div>", color, timeStamp, sender, formatMessage(message.getContent()));
        }

        private String formatMessage(String message) {
            String msg = formatColorCodes(escapeHtml4(message));
            Matcher matcher = VALID_LINK.matcher(msg);
            return convertModernEmojis(matcher.replaceAll("<a href=\"$1\">$1</a>"));
        }

        private String formatColorCodes(String message) {
            Matcher underline_matcher = UNDERLINE.matcher(message);
            message = underline_matcher.replaceAll("<u>$1</u>");
            Matcher italic_matcher = ITALIC.matcher(message);
            message = italic_matcher.replaceAll("<i>$1</i>");
            Matcher bold_matcher = BOLD.matcher(message);
            message = bold_matcher.replaceAll("<b>$1</b>");
            Matcher color_matcher = COLORS.matcher(message);
            StringBuffer sb = new StringBuffer();
            while (color_matcher.find()) {
                color_matcher.appendReplacement(sb, Matcher.quoteReplacement(
                        colorSpan(color_matcher.group(1), color_matcher.group(2), color_matcher.group(3))));
            }
            color_matcher.appendTail(sb);
            return IrcFormatting.stripCodes(sb.toString());
        }

        static String htmlColorById(String id) {
            switch (id) {
                case "00":
                case "0":
                    return "white";
                case "01":
                case "1":
                    return "black";
                case "02":
                case "2":
                    return "#000080";
                case "03":
                case "3":
                    return "#008000";
                case "04":
                case "4":
                    return "#FF0000";
                case "05":
                case "5":
                    return "#800000";
                case "06":
                case "6":
                    return "#800080";
                case "07":
                case "7":
                    return "#FFA500";
                case "08":
                case "8":
                    return "#FFFF00";
                case "09":
                case "9":
                    return "#00FF00";
                case "10":
                    return "#008080";
                case "11":
                    return "#00FFFF";
                case "12":
                    return "#0000FF";
                case "13":
                    return "#FF00FF";
                case "14":
                    return "#808080";
                case "15":
                    return "#C0C0C0";
                default:
                    return extendedColorById(id);
            }
        }

        /**
         * Palette nicks are colored from: the classic 02-13 plus the extended palette's vivid and
         * pastel rows 64-87. Near-black rows (16-39) and dark grays (88-93) are excluded as
         * unreadable on the dark panel. Shared with the nicklist dropdown so a nick looks the
         * same in both places.
         */
        private static final String[] NICK_COLOR_IDS = {
                "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13",
                "64", "65", "66", "67", "68", "69", "70", "71", "72", "73", "74", "75",
                "76", "77", "78", "79", "80", "81", "82", "83", "84", "85", "86", "87"
        };

        /**
         * Deterministic palette code for a nick. Uses floorMod rather than abs: for a nick whose
         * hashCode is Integer.MIN_VALUE, Math.abs returns Integer.MIN_VALUE again and the index
         * goes negative.
         */
        static String nickColorId(String nick) {
            return NICK_COLOR_IDS[Math.floorMod(nick.hashCode(), NICK_COLOR_IDS.length)];
        }

        /**
         * https://modern.ircdocs.horse/formatting.html
         */
        private static final String[] EXTENDED_COLORS = {
            "#470000", // 16
            "#472100", // 17
            "#474700", // 18
            "#324700", // 19
            "#004700", // 20
            "#00472C", // 21
            "#004747", // 22
            "#002747", // 23
            "#000047", // 24
            "#2E0047", // 25
            "#470047", // 26
            "#47002A", // 27
            "#740000", // 28
            "#743A00", // 29
            "#747400", // 30
            "#517400", // 31
            "#007400", // 32
            "#007449", // 33
            "#007474", // 34
            "#004074", // 35
            "#000074", // 36
            "#4B0074", // 37
            "#740074", // 38
            "#740045", // 39
            "#B50000", // 40
            "#B56300", // 41
            "#B5B500", // 42
            "#7DB500", // 43
            "#00B500", // 44
            "#00B571", // 45
            "#00B5B5", // 46
            "#0063B5", // 47
            "#0000B5", // 48
            "#7500B5", // 49
            "#B500B5", // 50
            "#B5006B", // 51
            "#FF0000", // 52
            "#FF8C00", // 53
            "#FFFF00", // 54
            "#B2FF00", // 55
            "#00FF00", // 56
            "#00FFA0", // 57
            "#00FFFF", // 58
            "#008CFF", // 59
            "#0000FF", // 60
            "#A500FF", // 61
            "#FF00FF", // 62
            "#FF0098", // 63
            "#FF5959", // 64
            "#FFB459", // 65
            "#FFFF71", // 66
            "#CFFF60", // 67
            "#6FFF6F", // 68
            "#65FFC9", // 69
            "#6DFFFF", // 70
            "#59B4FF", // 71
            "#5959FF", // 72
            "#C459FF", // 73
            "#FF66FF", // 74
            "#FF59BC", // 75
            "#FF9C9C", // 76
            "#FFD39C", // 77
            "#FFFF9C", // 78
            "#E2FF9C", // 79
            "#9CFF9C", // 80
            "#9CFFDB", // 81
            "#9CFFFF", // 82
            "#9CD3FF", // 83
            "#9C9CFF", // 84
            "#DC9CFF", // 85
            "#FF9CFF", // 86
            "#FF94D3", // 87
            "#000000", // 88
            "#131313", // 89
            "#282828", // 90
            "#363636", // 91
            "#4D4D4D", // 92
            "#656565", // 93
            "#818181", // 94
            "#9F9F9F", // 95
            "#BCBCBC", // 96
            "#E2E2E2", // 97
            "#FFFFFF", // 98
        };

        private static String extendedColorById(String id) {
            try {
                int code = Integer.parseInt(id);
                if (code >= 16 && code <= 98) {
                    return EXTENDED_COLORS[code - 16];
                }
            } catch (NumberFormatException ignored) {
                // Not a numeric code - fall through to the default below.
            }
            return "black";
        }

        static String colorSpan(String fgId, String bgId, String text) {
            StringBuilder style = new StringBuilder("color:").append(htmlColorById(fgId));
            String content = text;
            if (hasPaletteColor(bgId)) {
                style.append(";background-color:").append(htmlColorById(bgId));
                content = text.replace(" ", "&nbsp;");
            }
            return "<span style=\"" + style + "\">" + content + "</span>";
        }

        private static boolean hasPaletteColor(String id) {
            if (id == null) {
                return false;
            }
            try {
                int code = Integer.parseInt(id);
                return code >= 0 && code <= 98;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        public void cancelPreviewManager() {
            this.previewManager.cancelPreviewManager();
        }
    }

    private static final Pattern MODERN_EMOJI_PATTERN = Pattern.compile("[" + "\uD83E\uDD70-\uD83E\uDDFF" +
            "\uD83E\uDE00-\uD83E\uDEFF" +
            "\uD83E\uDF00-\uD83E\uDFFF" +
            "\uD83E\uDD00-\uD83E\uDD6F" +
            "\uD83E\uDEC0-\uD83E\uDECF" + "\uD83E\uDED0-\uD83E\uDEFF" +
            "\uD83E\uDF00-\uD83E\uDF2F" +
            "\uD83E\uDF30-\uD83E\uDF5F" +
            "\uD83E\uDF60-\uD83E\uDF8F" +
            "\uFE0F" +
            "]" + "|\uD83C[\uDFFB-\uDFFF]" +
            "|\uD83E[\uDDB0-\uDDBF]"
    );

    public static String convertModernEmojis(String text) {
        if (text == null) return "";
        Matcher matcher = MODERN_EMOJI_PATTERN.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        matcher.reset();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String modernEmoji = matcher.group();
            String replacement = EmojiParser.parseToAliases(modernEmoji, EmojiParser.FitzpatrickAction.PARSE);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private enum IrcShortcut {
        COLOR(KeyStroke.getKeyStroke(KeyEvent.VK_K, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "\u0003", "insertColorCode"),
        BOLD(KeyStroke.getKeyStroke(KeyEvent.VK_B, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "\u0002", "insertBold"),
        ITALIC(KeyStroke.getKeyStroke(KeyEvent.VK_I, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "\u001D", "insertItalic"),
        UNDERLINE(KeyStroke.getKeyStroke(KeyEvent.VK_U, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "\u001F", "insertUnderline");

        private final KeyStroke keyStroke;
        private final String insertText;
        private final String actionKey;

        IrcShortcut(KeyStroke keyStroke, String insertText, String actionKey) {
            this.keyStroke = keyStroke;
            this.insertText = insertText;
            this.actionKey = actionKey;
        }
    }

    private class TextInsertAction extends AbstractAction {
        private final String textToInsert;

        TextInsertAction(String textToInsert) {
            this.textToInsert = textToInsert;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            String currentText = inputField.getText();
            int caretPosition = inputField.getCaretPosition();
            String newText;
            int newCaretPosition;
            if (inputField.getSelectedText() != null) {
                int selStart = inputField.getSelectionStart();
                int selEnd = inputField.getSelectionEnd();
                String selectedText = inputField.getSelectedText();
                newText = currentText.substring(0, selStart) + textToInsert + selectedText + textToInsert + currentText.substring(selEnd);
                newCaretPosition = selEnd + (textToInsert.length() * 2);
            } else {
                newText = currentText.substring(0, caretPosition) + textToInsert + currentText.substring(caretPosition);
                newCaretPosition = caretPosition + textToInsert.length();
            }
            inputField.setText(newText);
            inputField.setCaretPosition(newCaretPosition);
        }
    }

    private void setupShortcuts() {
        InputMap inputMap = inputField.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = inputField.getActionMap();
        for (IrcShortcut shortcut : IrcShortcut.values()) {
            inputMap.put(shortcut.keyStroke, shortcut.actionKey);
            actionMap.put(shortcut.actionKey, new TextInsertAction(shortcut.insertText));
        }

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "historyPrevious");
        actionMap.put("historyPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                recallHistory(inputHistory.previous(inputField.getText()));
            }
        });
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "historyNext");
        actionMap.put("historyNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                recallHistory(inputHistory.next());
            }
        });
    }

    private void recallHistory(String text) {
        if (text == null) {
            return;
        }
        inputField.setText(text);
        inputField.setCaretPosition(text.length());
    }
}
