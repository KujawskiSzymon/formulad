package formulad;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import formulad.ai.*;

import formulad.ai.Node;
import formulad.model.*;
import formulad.model.Gear;

public class FormulaD extends Game implements Runnable {
    private final Map<Node, List<Node>> prevNodeMap;
    private LocalPlayer current;
    private LocalPlayer previous;
    private List<LocalPlayer> waitingPlayers = new ArrayList<>();
    private final List<LocalPlayer> allPlayers;
    private final List<LocalPlayer> players = new ArrayList<>();
    private final List<LocalPlayer> stoppedPlayers = new ArrayList<>();
    private final Map<LocalPlayer, AI> aiMap = new HashMap<>();
    private Integer roll;
    private final Random rng;
    private final Map<Node, Double> distanceMap = new HashMap<>();
    private boolean stopped;
    private static final String gameId = "sebring";
    private boolean enableTimeout;
    private final int initTimeoutInMillis;
    private final int gearTimeoutInMillis;
    private final int moveTimeoutInMillis;
    private boolean highlightCurrentPlayer;
    private final Set<LocalPlayer> disconnectedPlayers = new HashSet<>();
    final Lobby lobby;
    public static final Logger log = Logger.getLogger(FormulaD.class.getName());
    static {
        try {
            log.setUseParentHandlers(false);
            final Handler handler = new FileHandler("formulad.log");
            handler.setFormatter(new CustomRecordFormatter());
            log.addHandler(handler);
        } catch (IOException e) {
            System.err.println("FileHandler for file formulad.log could not be adeded");
            e.printStackTrace();
        }
    }

    public FormulaD(Params params, Lobby lobby, JFrame frame, JPanel panel, PlayerSlot[] slots, String trackId) {
        super(frame, panel);
        initTrack(trackId);
        this.lobby = lobby;
        prevNodeMap = AIUtil.buildPrevNodeMap(nodes);
        final long seed = params.seed == null ? new Random().nextLong() : params.seed;
        this.rng = new Random(seed);
        log.info("Initializing RNG with seed " + seed);
        initTimeoutInMillis = params.initTimeoutInMillis;
        gearTimeoutInMillis = params.gearTimeoutInMillis;
        moveTimeoutInMillis = params.moveTimeoutInMillis;
        allPlayers = new ArrayList<>();
        createGrid(params, attributes, slots, frame);
        waitingPlayers.addAll(players);
        waitingPlayers.sort((p1, p2) -> p1.compareTo(p2, distanceMap, stoppedPlayers));
        final List<PlayerStats> stats = new ArrayList<>();
        for (int i = 0; i < waitingPlayers.size(); i++) {
            final LocalPlayer player = waitingPlayers.get(i);
            final PlayerStats playerStats = player.getStatistics(i + 1, distanceMap);
            stats.add(playerStats);
        }
        notifyAll(new FinalStandings(stats));
        current = waitingPlayers.remove(0);
    }

    private void createGrid(Params params, Map<Node, Double> attributes, PlayerSlot[] slots, JFrame frame) {
        final Map<AI, ProfileMessage> aiToProfile = new HashMap<>();
        for (PlayerSlot slot : slots) {
            final ProfileMessage profile = slot.getProfile();
            if (profile != null) {
                if (profile.isAi()) {
                    aiToProfile.put(new GreatAI(), profile);
                }
                else if (profile.isLocal()) {
                    aiToProfile.put(new ManualAI(new GreatAI(), frame, this, profile.originalProfile), profile);
                }
                else {
                    final RemoteAI client = lobby.getClient(profile.getId());
                    if (client != null) {
                        aiToProfile.put(client, profile);
                    }
                }
            }
        }
        final int playerCount = aiToProfile.size();
        final List<Node> grid = findGrid(attributes).subList(0, playerCount);
        final List<Integer> startingOrder = IntStream.range(0, playerCount).boxed().collect(Collectors.toList());
        Collections.shuffle(startingOrder, rng);
        enableTimeout = true;
        for (Map.Entry<AI, ProfileMessage> e : aiToProfile.entrySet()) {
            createAiPlayer(e, grid, startingOrder, attributes, params.leeway);
        }

        // Create manually controlled AI players
        /*
        for (String path : params.manualAIs) {
            try {
                Class<?> clazz = Class.forName(path);
                final Object ai = clazz.newInstance();
                if (!(ai instanceof AI)) {
                    final String msg = path + " does not implement formulad.ai.AI";
                    log.log(Level.SEVERE, msg);
                    throw new RuntimeException(msg);
                }
                final AI manualAI = new ManualAI((AI) ai, frame, this, profile.getId());
                createAiPlayer(manualAI, grid, startingOrder, attributes, params.leeway);
            } catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
                log.log(Level.SEVERE, "Error while trying to create AI from path " + path, e);
                throw new RuntimeException(e);
            }
        }
        // Timeout makes sense only if there are no manually controlled players.

        // Create automatically controlled AI players
        for (String path : params.localAIs) {
            try {
                Class<?> clazz = Class.forName(path);
                final Object ai = clazz.newInstance();
                if (!(ai instanceof AI)) {
                    final String msg = path + " does not implement formulad.ai.AI";
                    log.log(Level.SEVERE, msg);
                    throw new RuntimeException(msg);
                }
                createAiPlayer((AI) ai, grid, startingOrder, attributes, params.leeway);
            } catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
                log.log(Level.SEVERE, "Error while trying to create AI from path " + path, e);
                throw new RuntimeException(e);
            }
        }
        // Create remotely controlled AI players
        for (RemoteAI client : lobby.clients) {
            createAiPlayer(client, grid, startingOrder, attributes, params.leeway);
        }
        // Create remotely and manually controlled AI players
        /*
        for (String url : params.manualRemoteAIs) {
            final AI remoteAI = new RemoteAI(url);
            final AI manualAI = new ManualAI(remoteAI, frame, this);
            createAiPlayer(manualAI, grid, startingOrder, attributes, params.leeway);
        }
        // Create computer players
        int count = params.simpleAIs;
        while (count-- > 0) {
            final AI ai = new GreatAI();
            createAiPlayer(ai, grid, startingOrder, attributes, params.leeway);
        }*/
    }

    private void createAiPlayer(Map.Entry<AI, ProfileMessage> ai, List<Node> grid, List<Integer> startingOrder, Map<Node, Double> attributes, int leeway) {
        // Recreate track for each player, so nothing bad happens if AI mutates it.
        final int playerCount = allPlayers.size();
        final String playerId = "p" + (playerCount + 1);
        final Track track = ApiHelper.buildTrack(gameId, playerId, nodes);
        final Node startNode = grid.get(startingOrder.get(playerCount));
        final LocalPlayer player = new LocalPlayer(playerId, startNode, attributes.get(startNode), 1, this, leeway, ai.getValue().getColor1(), ai.getValue().getColor2());
        current = player;
        log.info("Initializing player " + playerId);
        NameAtStart nameResponse = getAiInput(() -> ai.getKey().startGame(track), initTimeoutInMillis);
        /*
        final String name;
        final UUID id;
        if (nameResponse == null) {
            log.warning("Received no name from player " + playerId + ", using name Unresponsive instead");
            name = "Unresponsive";
            id = UUID.randomUUID();
        } else {
            name = nameResponse.getName();
            id = nameResponse.getId();
        }*/
        final String name = ai.getValue().getName();
        final UUID id = ai.getValue().getId();
        log.info("Initialization done, player " + name + " starts from position " + (startingOrder.get(playerCount) + 1));
        player.setName(name);
        player.setId(id);
        players.add(player);
        allPlayers.add(player);
        player.setGridPosition(allPlayers.size());
        aiMap.put(player, ai.getKey());
        notifyAll(new CreatedPlayerNotification(current.getId(), name, startNode.getId(), 18, 1));
    }

    public void notifyAll(Object notification) {
        aiMap.values().forEach(a -> a.notify(notification));
    }

    private <T> T getAiInput(Supplier<T> supplier, int timeout) {
        T result = null;
        boolean exception = false;
        final long startTime = System.currentTimeMillis();
        final CompletableFuture<T> future = CompletableFuture.supplyAsync(supplier);
        try {
            if (enableTimeout) {
                result = future.get(timeout + current.getLeeway(), TimeUnit.MILLISECONDS);
            } else {
                result = future.get();
            }
        } catch (TimeoutException e) {
            future.cancel(true);
            exception = true;
            log.log(Level.WARNING, "AI request timed out", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            exception = true;
            log.log(Level.WARNING, "AI request interrupted", e);
        } catch (ExecutionException e) {
            exception = true;
            log.log(Level.WARNING, "AI request raised an exception", e);
        }
        final long timeSpent = System.currentTimeMillis() - startTime;
        current.recordTimeUsed(timeSpent, exception);
        if (enableTimeout && timeSpent > timeout) {
            current.reduceLeeway(timeSpent - timeout);
        }
        final AI ai = aiMap.get(current);
        if (ai instanceof RemoteAI) {
            final RemoteAI client = (RemoteAI) ai;
            if (!client.isConnected() && disconnectedPlayers.add(current)) {
                current.setName(current.getName() + " (DC)");
            }
        }
        return result;
    }

    @Override
    public void run() {
        while (!stopped) {
            highlightCurrentPlayer = true;
            current.beginTurn();
            final AI ai = aiMap.get(current);
            final GameState gameState = ApiHelper.buildGameState(gameId, players);
            log.info("Querying gear input from AI " + current.getNameAndId());
            final Gear gearResponse = getAiInput(() -> ai.selectGear(gameState), gearTimeoutInMillis);
            final Integer selectedGear = gearResponse == null ? null : gearResponse.getGear();
            if (selectedGear != null && current.switchGear(selectedGear)) {
                log.info("Gear input received: " + selectedGear);
            } else {
                log.warning("Invalid gear selection " + selectedGear + ", using current gear instead");
            }
            if (previous != null) {
                previous.clearRoute();
            }
            roll = current.roll(rng);
            final Moves allMoves = current.findAllTargets(roll, gameId, players);
            if (current.getLeeway() <= 0) {
                log.info("Player " + current.getNameAndId() + " used his timeout leeway and was dropped from the game");
                current.stop();
                highlightCurrentPlayer = false;
            } else if (allMoves.getMoves().isEmpty()) {
                log.info("No valid targets after dice roll " + roll + ", DNF");
                current.stop();
                highlightCurrentPlayer = false;
            } else {
                log.info("Querying move input from AI " + current.getNameAndId());
                final SelectedIndex moveResponse = getAiInput(() -> ai.selectMove(allMoves), moveTimeoutInMillis);
                Integer selectedIndex = moveResponse == null ? null : moveResponse.getIndex();
                if (selectedIndex == null || selectedIndex < 0 || selectedIndex >= allMoves.getMoves().size()) {
                    log.warning("Invalid move selection " + selectedIndex + ", using index 0 instead");
                    selectedIndex = 0;
                } else {
                    log.info("Move input received: " + selectedIndex);
                }
                highlightCurrentPlayer = false;
                current.move(selectedIndex, coordinates);
                current.collide(players, prevNodeMap, rng);
                if (roll == 20 || roll == 30) {
                    LocalPlayer.possiblyAddEngineDamage(players, rng);
                }
            }
            roll = null;
            nextPlayer();
            repaint();
        }
        final List<PlayerStats> stats = new ArrayList<>();
        for (int i = 0; i < stoppedPlayers.size(); i++) {
            final LocalPlayer player = stoppedPlayers.get(i);
            final PlayerStats playerStats = player.getStatistics(i + 1, distanceMap);
            stats.add(playerStats);
        }
        final FinalStandings fs = new FinalStandings(stats);
        finalStandings = fs.getStats();
        notifyAll(fs);
        lobby.close();
        repaint();
        clickToExit();
    }

	private List<Node> findGrid(Map<Node, Double> attributes) {
	    final Set<Node> visited = new HashSet<>();
        final List<Node> grid = new ArrayList<>();
        final List<Node> work = new ArrayList<>();
        final List<Node> edges = new ArrayList<>();
        Node center = null;
        for (Node node : nodes) {
            if (node.getType() == Node.Type.FINISH) {
                work.add(node);
                visited.add(node);
                if (node.childCount() == 3) {
                    center = node;
                } else {
                    edges.add(node);
                }
            }
        }
        while (!work.isEmpty()) {
            final Node node = work.remove(0);
            if (node.getType() == Node.Type.START) {
                grid.add(0, node);
            }
            node.forEachChild(next -> {
                if (visited.add(next)) {
                    work.add(next);
                }
            });
        }
        if (center == null) {
            throw new RuntimeException("Finish line must have width 3");
        }
        if (center.hasChildren(edges)) {
            distanceMap.put(center, 0.0);
        } else {
            distanceMap.put(center, 0.5);
            distanceMap.put(edges.get(0), 0.0);
            distanceMap.put(edges.get(1), 0.0);
        }
        work.add(center);
        final List<Node> curves = new ArrayList<>();
        while (!work.isEmpty()) {
            final Node node = work.remove(0);
            final int childCount = node.childCount();
            node.forEachChild(next -> {
                if (distanceMap.containsKey(next)) {
                    return;
                }
                if (next.isCurve()) {
                    curves.add(next);
                    return;
                }
                final int nextChildCount = next.childCount();
                final boolean fromCenterToEdge = childCount == 3 && nextChildCount == 2;
                distanceMap.put(next, distanceMap.get(node) + (fromCenterToEdge ? 0.5 : 1));
                work.add(next);
            });
            if (work.isEmpty() && !curves.isEmpty()) {
                final double maxDistance = distanceMap.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
                for (Node curve : curves) {
                    final double relativeDistance = attributes.get(curve);
                    distanceMap.put(curve, maxDistance + relativeDistance);
                }
                while (!curves.isEmpty()) {
                    final Node curve = curves.remove(0);
                    curve.forEachChild(next -> {
                        if (distanceMap.containsKey(next)) {
                            return;
                        }
                        if (!next.isCurve()) {
                            work.add(next);
                            return;
                        }
                        curves.add(next);
                        distanceMap.put(next, attributes.get(next) + maxDistance);
                    });
                }
                final double newMaxDistance = distanceMap.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
                center = null;
                if (work.isEmpty()) {
                    throw new RuntimeException("Curve exit must have size > 0");
                }
                for (Node straight : work) {
                    boolean allCurves = true;
                    for (Node prev : prevNodeMap.get(straight)) {
                        if (!prev.isCurve()) {
                            allCurves = false;
                            break;
                        }
                    }
                    if (allCurves) {
                        distanceMap.put(straight, newMaxDistance);
                        for (Node otherStraight : work) {
                            if (straight.hasChild(otherStraight)) {
                                center = otherStraight;
                                break;
                            }
                        }
                        break;
                    }
                }
                work.clear();
                work.add(center);
                distanceMap.put(center, newMaxDistance + 0.5);
            }
        }
        return grid;
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        // drawDistances(g2d); // For debugging
    }

    private void drawDistances(Graphics2D g2d) {
        for (Map.Entry<Node, Double> entry : distanceMap.entrySet()) {
            final Point p = coordinates.get(entry.getKey());
            final int posX = p.x - 5;
            final int posY = p.y + 3;
            g2d.setFont(new Font("Arial", Font.PLAIN, 8));
            g2d.setColor(Color.BLUE);
            g2d.drawString(entry.getValue().toString(), posX, posY);
        }
    }

    @Override
    protected void drawInfoBox(Graphics2D g2d) {
        g2d.setColor(Color.GRAY);
        g2d.fillRect(getWidth() - 250, 0, 249, 5 + 15 * allPlayers.size());
        g2d.setColor(Color.BLACK);
        g2d.drawRect(getWidth() - 250, 0, 249, 5 + 15 * allPlayers.size());
        int i = 0;
        synchronized (allPlayers) {
            for (LocalPlayer player : allPlayers) {
                if (player == current) {
                    // Turn marker
                    g2d.setColor(Color.RED);
                    g2d.fillPolygon(new int[] { getWidth() - 252, getWidth() - 257, getWidth() - 257 }, new int[] { i * 15 + 10, i * 15 + 7, i * 15 + 13 }, 3);
                }
                player.draw(g2d, getWidth() - 235, i * 15 + 10, 0);
                player.drawStats(g2d, getWidth() - 220, i * 15 + 15);
                i++;
            }
        }
    }

    @Override
    protected void drawPlayers(Graphics2D g2d) {
        synchronized (allPlayers) {
            for (LocalPlayer player : allPlayers) {
                if (player == current) {
                    player.drawRoll(g2d, roll);
                    if (highlightCurrentPlayer) {
                        player.highlight(g2d, coordinates);
                    }
                }
                player.draw(g2d, coordinates);
            }
        }
    }

    @Override
    protected void drawStandings(Graphics2D g2d) {
        if (finalStandings != null) {
            final int height = 5 + 15 * (finalStandings.length + 1);
            final int x = getWidth() / 2 - 200;
            final int y = getHeight() / 2 - height / 2;
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("Arial", Font.BOLD, 20));
            final int titleWidth = g2d.getFontMetrics().stringWidth("STANDINGS");
            g2d.drawString("STANDINGS", x + 200 - titleWidth / 2, y - 20);
            g2d.setColor(Color.GRAY);
            g2d.fillRect(x, y, 400, height);
            g2d.setColor(Color.BLACK);
            g2d.drawRect(x, y, 400, height);
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("Arial", Font.BOLD, 12));
            g2d.drawString("Name", x + 30, y + 15);
            g2d.drawString("HP", x + 140, y + 15);
            g2d.drawString("Turns", x + 190, y + 15);
            g2d.drawString("Grid", x + 230, y + 15);
            g2d.drawString("Time", x + 270, y + 15);
            g2d.setFont(new Font("Arial", Font.PLAIN, 12));
            for (int i = 0; i < finalStandings.length; ++i) {
                final PlayerStats stats = finalStandings[i];
                final LocalPlayer player = allPlayers.stream().filter(p -> p.getId().equals(stats.playerId)).findFirst().get();
                final String name = player.getName();
                player.draw(g2d, x + 15, y + (i + 1) * 15 + 10, 0);
                g2d.setColor(Color.BLACK);
                g2d.drawString(name, x + 30, y + (i + 1) * 15 + 15);
                final String hp = stats.hitpoints > 0 ? Integer.toString(stats.hitpoints) : "DNF";
                g2d.drawString(hp, x + 140, y + (i + 1) * 15 + 15);
                g2d.drawString(Integer.toString(stats.turns), x + 190, y + (i + 1) * 15 + 15);
                g2d.drawString(Integer.toString(stats.gridPosition), x + 230, y + (i + 1) * 15 + 15);
                final long timeUsed = stats.timeUsed / 100;
                final double timeUsedSecs = timeUsed / 10.0;
                g2d.drawString(Double.toString(timeUsedSecs), x + 270, y + (i + 1) * 15 + 15);
            }
        }
    }

    private void nextPlayer() {
	    // Drop stopped players
	    final Iterator<LocalPlayer> it = players.iterator();
	    while (it.hasNext()) {
	        final LocalPlayer player = it.next();
	        if (player.isStopped()) {
	            stoppedPlayers.add(player);
	            waitingPlayers.remove(player);
	            it.remove();
            }
        }
        if (waitingPlayers.isEmpty()) {
            if (players.isEmpty()) {
                // Making a clone, because sorting depends on the order of stoppedPlayers list
                final List<LocalPlayer> clone = new ArrayList<>(stoppedPlayers);
                stoppedPlayers.sort((p1, p2) -> p1.compareTo(p2, distanceMap, clone));
                // This will make the game thread to stop.
                stopped = true;
                return;
            }
            // Set turn order for next round
            waitingPlayers.addAll(players);
            waitingPlayers.sort((p1, p2) -> p1.compareTo(p2, distanceMap, stoppedPlayers));
            // Sort info box contents to match with current standings and turn order
            synchronized (allPlayers) {
                allPlayers.sort((p1, p2) -> p1.compareTo(p2, distanceMap, stoppedPlayers));
                notifyAll(new Standings(allPlayers));
            }
        }
        previous = current;
        current = waitingPlayers.remove(0);
    }

    private static class Params {
        @Parameter(names = "--manual_ai", description = "Path to manually controlled local AI, can be given multiple times. Keys: '1'-'6': gears, 'a': let AI make move, '.': Brake less, ',': Brake more")
        private List<String> manualAIs = new ArrayList<>();

        //@Parameter(names = "--manual_remote_ai", description = "Path to manually controlled remote AI, can be given multiple times. Keys: '1'-'6': gears, 'a': let AI make move, '.': Brake less, ',': Brake more")
        //private List<String> manualRemoteAIs = new ArrayList<>();

        @Parameter(names = "--local_ai", description = "Path to local AI, can be given multiple times")
        private List<String> localAIs = new ArrayList<>();

        //@Parameter(names = "--remote_ai", description = "URL of AI, can be given multiple times")
        //private List<String> remoteAIs = new ArrayList<>();

        @Parameter(names = "--simple_ais", description = "Number of pre-built AIs to include", arity = 1)
        private int simpleAIs = 0;

        @Parameter(names = "--help", description = "Displays this help", help = true)
        private boolean help;

        @Parameter(names = "--animation_delay", description = "Timeout length in animations (ms)", arity = 1)
        private int animationDelayInMillis = 100;

        @Parameter(names = "--timeout_init", description = "Timeout length for AI initialization (ms)", arity = 1)
        private int initTimeoutInMillis = 3000;

        @Parameter(names = "--timeout_gear", description = "Timeout length for AI gear selection (ms)", arity = 1)
        private int gearTimeoutInMillis = 3000;

        @Parameter(names = "--timeout_move", description = "Timeout length for AI moving (ms)", arity = 1)
        private int moveTimeoutInMillis = 3000;

        @Parameter(names = "--leeway", description = "Total player specific leeway for exceeding timeouts (ms)", arity = 1)
        private int leeway = 3600000;

        @Parameter(names = "--rng_seed", description = "Specify seed used for RNG, uses random seed by default", arity = 1)
        private Long seed = null;
    }

    private static void showMenu(JFrame f, Params params, List<Profile> profiles) {
        final int playerCount = params.manualAIs.size() + params.localAIs.size() + params.simpleAIs;
        final JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        final JPanel title = new JPanel();
        final JLabel titleText = new JLabel("FORMULA D ONLINE");
        titleText.setFont(new Font("Arial", Font.BOLD, 32));
        title.add(titleText);
        final JPanel contents = new JPanel(new GridLayout(0, 2));
        p.add(title);
        p.add(contents);
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        final JPanel buttonPanel = new JPanel(new GridLayout(4, 0));
        final ProfilePanel profilePanel = new ProfilePanel(profiles);
        buttonPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        contents.add(buttonPanel);
        contents.add(profilePanel);

        final JButton singlePlayerButton = new JButton("Single Player");
        singlePlayerButton.addActionListener(e -> {
            // TODO
        });
        final JButton hostMultiplayerButton = new JButton("Host Multiplayer");
        hostMultiplayerButton.addActionListener(e -> {
            String result = (String) JOptionPane.showInputDialog(f, "Select port", "Select port", JOptionPane.PLAIN_MESSAGE,  null, null, "1277");
            if (result == null) {
                return;
            }
            try {
                JLabel label = new JLabel("Connected Clients: 0");
                final int port = Integer.parseInt(result);
                final Lobby lobby = new Lobby(port, label);

                final List<ProfileMessage> localProfiles = profiles.stream().map(ProfileMessage::new).collect(Collectors.toList());
                final JPanel playerPanel = new JPanel(new GridLayout(5, 2));
                final PlayerSlot[] slots = new PlayerSlot[10];
                for (int i = 0; i < slots.length; ++i) {
                    final PlayerSlot slot = new PlayerSlot(f, localProfiles, lobby, slots);
                    playerPanel.add(slot);
                    slots[i] = slot;
                }
                lobby.setSlots(slots);

                final JPanel lobbyPanel = new JPanel();
                lobbyPanel.setLayout(new BoxLayout(lobbyPanel, BoxLayout.Y_AXIS));
                lobbyPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
                //lobbyPanel.add(label);
                // TODO: Change track menu
                JButton changeTrackButton = new JButton();
                final ImageIcon icon = new ImageIcon();
                final BufferedImage image = ImageCache.getImage("/sebring.jpg");
                final int x = image.getWidth();
                final int y = image.getHeight();
                final int scale = Math.max(x / 300, y / 300);
                icon.setImage(image.getScaledInstance(x / scale, y / scale, Image.SCALE_FAST));
                changeTrackButton.setIcon(icon);
                JButton button = new JButton("Start");
                button.addActionListener(event -> {
                    boolean hasPlayers = false;
                    final Set<UUID> ids = new HashSet<>();
                    for (PlayerSlot slot : slots) {
                        final ProfileMessage profile = slot.getProfile();
                        if (profile != null) {
                            hasPlayers = true;
                            if (profile == ProfileMessage.pending) {
                                JOptionPane.showConfirmDialog(lobbyPanel, "Someone is about to join", "Error", JOptionPane.DEFAULT_OPTION);
                                return;
                            }
                            else if (!profile.isAi() && !ids.add(profile.getId())) {
                                JOptionPane.showConfirmDialog(lobbyPanel, "Duplicate profile: " + profile.getName(), "Error", JOptionPane.DEFAULT_OPTION);
                                return;
                            }
                        }
                    }
                    if (!hasPlayers) {
                        JOptionPane.showConfirmDialog(lobbyPanel, "Need at least 1 player", "Error", JOptionPane.DEFAULT_OPTION);
                        return;
                    }
                    lobby.done = true;
                    final FormulaD server = new FormulaD(params, lobby, f, p, slots, "sebring");
                    new Thread(server).start();
                });
                final JPanel gridPanel = new JPanel(new GridLayout(0, 2));
                gridPanel.add(changeTrackButton);
                gridPanel.add(playerPanel);
                lobbyPanel.add(gridPanel);
                lobbyPanel.add(button);
                f.setContentPane(lobbyPanel);
                f.pack();
                lobby.start();
            } catch (NumberFormatException exception) {
                JOptionPane.showConfirmDialog(p, "Invalid port: '" + result + "'", "Error", JOptionPane.DEFAULT_OPTION);
            } catch (IOException exception) {
                JOptionPane.showConfirmDialog(p, "Unable to start server with port " + result, "Error", JOptionPane.DEFAULT_OPTION);
            } catch (IllegalArgumentException exception) {
                JOptionPane.showConfirmDialog(p, "Illegal port: " + result, "Error", JOptionPane.DEFAULT_OPTION);
            }
        });
        final JButton joinMultiplayerButton = new JButton("Join Multiplayer");
        joinMultiplayerButton.addActionListener(e -> {
            String result = (String) JOptionPane.showInputDialog(p, "IP address and port", "IP address and port", JOptionPane.PLAIN_MESSAGE,  null, null, "localhost:1277");
            if (result == null) {
                return;
            }
            String[] addressAndPort = result.split(":");
            try {
                if (addressAndPort.length == 2) {
                    final int port = Integer.parseInt(addressAndPort[1]);
                    Socket socket = new Socket(addressAndPort[0], port);
                    final Client client = new Client(f, socket, p, profilePanel.getActiveProfile());
                    new Thread(client).start();
                } else {
                    JOptionPane.showConfirmDialog(p, "Please specify server IP address and port (for example 123.456.7.8:1277)", "Error", JOptionPane.DEFAULT_OPTION);
                }
            } catch (NumberFormatException exception) {
                JOptionPane.showConfirmDialog(p, "Invalid port: '" + addressAndPort[1] + "'", "Error", JOptionPane.DEFAULT_OPTION);
            } catch (IOException exception) {
                JOptionPane.showConfirmDialog(p, "Unable to connect to server " + result, "Error", JOptionPane.DEFAULT_OPTION);
            }
        });
        singlePlayerButton.setFont(new Font("Arial", Font.BOLD, 20));
        hostMultiplayerButton.setFont(new Font("Arial", Font.BOLD, 20));
        joinMultiplayerButton.setFont(new Font("Arial", Font.BOLD, 20));
        singlePlayerButton.setPreferredSize(new Dimension(80, 40));
        hostMultiplayerButton.setPreferredSize(new Dimension(80, 40));
        joinMultiplayerButton.setPreferredSize(new Dimension(80, 40));
        buttonPanel.add(singlePlayerButton);
        buttonPanel.add(hostMultiplayerButton);
        buttonPanel.add(joinMultiplayerButton);
        f.setContentPane(p);
        f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        f.pack();
        f.setVisible(true);
    }

    public static void main(String[] args) {
        final JFrame f = new JFrame();
        final Params params = new Params();
        final JCommander commander = new JCommander(params);
        try {
            commander.parse(args);
            if (params.help) {
                commander.usage();
                System.exit(1);
            }
        } catch (ParameterException e) {
            System.out.printf("Invalid parameter: %s%n", e.getMessage());
            commander.usage();
            System.exit(2);
        }
        final int playerCount = params.manualAIs.size() + params.localAIs.size() + params.simpleAIs;
        if (playerCount < 1 || playerCount > 10) {
            System.out.printf("Please provide 1-10 players%n");
            commander.usage();
            System.exit(3);
        }
        LocalPlayer.animationDelayInMillis = params.animationDelayInMillis;

        final List<Profile> profiles = new ArrayList<>();
        try {
            final FileInputStream fileIn = new FileInputStream("profiles.dat");
            final ObjectInputStream objectIn = new ObjectInputStream(fileIn);
            while (true) {
                final Object obj = objectIn.readObject();
                if (obj == null) {
                    break;
                } else if (obj instanceof Profile) {
                    profiles.add((Profile) obj);
                }
            }
            objectIn.close();
        } catch (IOException | ClassNotFoundException e) {
            if (profiles.isEmpty()) {
                final Profile defaultProfile = new Profile("Player");
                defaultProfile.setActive(true);
                profiles.add(defaultProfile);
            }
        }
        f.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                super.windowClosing(e);
                try {
                    final FileOutputStream fos = new FileOutputStream("profiles.dat");
                    final ObjectOutputStream oos = new ObjectOutputStream(fos);
                    for (Profile profile : profiles) {
                        oos.writeObject(profile);
                    }
                } catch (IOException ex) {
                }
            }
        });
        boolean activeFound = false;
        for (Profile profile : profiles) {
            if (profile.isActive()) {
                if (activeFound) {
                    profile.setActive(false);
                }
                activeFound = true;
            }
        }
        if (!activeFound) {
            profiles.get(0).setActive(true);
        }
        showMenu(f, params, profiles);
    }
}
