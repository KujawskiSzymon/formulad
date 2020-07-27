package gp;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.List;
import java.util.logging.Level;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.filechooser.FileFilter;

import gp.ai.AIUtil;
import gp.ai.Node;
import gp.editor.*;
import org.apache.commons.lang3.tuple.Pair;

public class MapEditor extends JPanel {

    public static final int DIAMETER = 8;

    private static final Color ARC_BEGIN = new Color(0x333300);
    private static final Color ARC_END = new Color(0x990000);
    private static final Color LIGHT_RED = new Color(0xFF6600);

    private final JFrame frame;
    private int nextNodeId;
    private final List<Node> nodes = new ArrayList<>();
    private final Map<Node, Double> attributes = new HashMap<>();
    private final Map<Node, Double> gridAngles = new HashMap<>();
    private final Map<Node, Point> coordinates = new HashMap<>();
    private Node selectedNode;
    private BufferedImage backgroundImage;
    private String backgroundImageFileName;
    private Node.Type stroke = Node.Type.STRAIGHT;
    private Corner infoBoxCorner = Corner.NE;

    // After drawing an edge, automatically select the target node.
    private boolean autoSelectMode = true;

    private final HashMap<Node.Type, MenuItem> strokes = new HashMap<>();
    private final MenuItem setGarage;
    private final MenuItem setCurveDistance;
    private final MenuItem setGridAngle;

    private final List<Node> debugNeighbors = new ArrayList<>();
    private final UndoStack stack = new UndoStack(nodes, coordinates, attributes, gridAngles);

    public enum Corner { NE, SE, SW, NW };

	public MapEditor(JFrame frame) {
	    this.frame = frame;
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    final Node node = getNode(nodes, coordinates, e.getX(), e.getY(), DIAMETER);
                    if (node == null) {
                        if ((e.getModifiers() & InputEvent.SHIFT_MASK) != 0 && autoSelectMode && selectedNode != null) {
                            final String value = (String) JOptionPane.showInputDialog(
                                    MapEditor.this,
                                    "Number of new nodes:",
                                    "New node count",
                                    JOptionPane.PLAIN_MESSAGE,
                                    null,
                                    null,
                                    1
                            );
                            try {
                                final int nodeCount = Integer.parseInt(value);
                                if (nodeCount < 1) {
                                    return;
                                }
                                final Point start = coordinates.get(selectedNode);
                                final int dx = e.getX() - start.x;
                                final int dy = e.getY() - start.y;
                                System.out.println(dx + " " + dy + " " + (dx * dx + dy * dy));
                                if (dx * dx + dy * dy < 16 * DIAMETER * DIAMETER * nodeCount) {
                                    // Not enough space
                                    return;
                                }
                                final CreateNodesItem item = new CreateNodesItem(nextNodeId, stroke, start, selectedNode, dx, dy, nodeCount);
                                stack.execute(item);
                                nextNodeId += nodeCount;
                                select(item.getLastItem());
                                repaint();
                            } catch (NumberFormatException ex) {
                                return;
                            }
                            return;
                        }
                        final CreateNodeItem item = new CreateNodeItem(nextNodeId++, stroke, new Point(e.getX(), e.getY()), selectedNode);
                        stack.execute(item);
                        if (autoSelectMode && selectedNode != null) {
                            select(nodes.get(nodes.size() - 1));
                        } else {
                            select(null);
                        }
                    } else if (selectedNode == null) {
                        select(node);
                    } else if (selectedNode != node) {
                        if (autoSelectMode) {
                            final CreateEdgeItem item = new CreateEdgeItem(selectedNode, node);
                            stack.execute(item);
                        }
                        select(node);
                    } else {
                        select(null);
                    }
                    repaint();
                } else if (e.getButton() == MouseEvent.BUTTON3) {
                    final Node node = getNode(nodes, coordinates, e.getX(), e.getY(), DIAMETER / 2 + 1);
                    if (node != null) {
                        if (selectedNode == node) {
                            select(null);
                        }
                        final RemoveNodeItem item = new RemoveNodeItem(node);
                        stack.execute(item);
                        repaint();
                    }
                }
            }
        });
        // TODO: UNDO
        // TODO: Shift all nodes
        // TODO: Scale node coordinates
        // TODO: Unify node identifiers
        final MenuBar menuBar = new MenuBar();
        final Menu fileMenu = new Menu("File");
        final Menu editMenu = new Menu("Edit");
        final Menu strokeMenu = new Menu("Stroke");
        final Menu validationMenu = new Menu("Validation");

        menuBar.add(fileMenu);
        final MenuItem loadBackgroundImage = new MenuItem("Load Background Image");
        loadBackgroundImage.addActionListener(e -> open());
        loadBackgroundImage.setShortcut(new MenuShortcut(KeyEvent.VK_O));
        fileMenu.add(loadBackgroundImage);
        final MenuItem loadTrackData = new MenuItem("Load Track Data");
        loadTrackData.addActionListener(e -> load());
        loadTrackData.setShortcut(new MenuShortcut(KeyEvent.VK_L));
        fileMenu.add(loadTrackData);
        final MenuItem saveTrackData = new MenuItem("Save Track Data as ...");
        saveTrackData.addActionListener(e -> save());
        saveTrackData.setShortcut(new MenuShortcut(KeyEvent.VK_S));
        fileMenu.add(saveTrackData);

        menuBar.add(editMenu);
        final MenuItem undo = new MenuItem("Undo");
        undo.addActionListener(e -> {
            stack.undo();
            select(null);
            repaint();
        });
        undo.setShortcut(new MenuShortcut(KeyEvent.VK_Z));
        editMenu.add(undo);
        stack.setMenuItem(undo);
        final MenuItem autoSelectMode = new MenuItem("Switch Mode");
        autoSelectMode.addActionListener(e -> toggleSelectMode());
        autoSelectMode.setShortcut(new MenuShortcut(KeyEvent.VK_A));
        editMenu.add(autoSelectMode);
        setGarage = new MenuItem("Set Garage");
        setGarage.addActionListener(e -> setAttribute());
        setGarage.setShortcut(new MenuShortcut(KeyEvent.VK_E));
        editMenu.add(setGarage);
        setCurveDistance = new MenuItem("Set Curve Distance");
        setCurveDistance.addActionListener(e -> setAttribute());
        setCurveDistance.setShortcut(new MenuShortcut(KeyEvent.VK_E));
        editMenu.add(setCurveDistance);
        setGridAngle = new MenuItem("Set Grid Angle");
        setGridAngle.addActionListener(e -> setGridAngle());
        setGridAngle.setShortcut(new MenuShortcut(KeyEvent.VK_G));
        editMenu.add(setGridAngle);
        final MenuItem moveInfoBox = new MenuItem("Move Info Box");
        moveInfoBox.addActionListener(e -> switchInfoBoxCorner());
        moveInfoBox.setShortcut(new MenuShortcut(KeyEvent.VK_I));
        editMenu.add(moveInfoBox);

        menuBar.add(strokeMenu);
        final MenuItem strokeStraight = new MenuItem("Straight");
        strokeStraight.addActionListener(e -> setStroke(Node.Type.STRAIGHT));
        strokeStraight.setShortcut(new MenuShortcut(KeyEvent.VK_T));
        strokeMenu.add(strokeStraight);
        strokes.put(Node.Type.STRAIGHT, strokeStraight);
        final MenuItem strokeCurve1 = new MenuItem("1 Stop Curve");
        strokeCurve1.addActionListener(e -> setStroke(Node.Type.CURVE_1));
        strokeCurve1.setShortcut(new MenuShortcut(KeyEvent.VK_1));
        strokeMenu.add(strokeCurve1);
        strokes.put(Node.Type.CURVE_1, strokeCurve1);
        final MenuItem strokeCurve2 = new MenuItem("2 Stop Curve");
        strokeCurve2.addActionListener(e -> setStroke(Node.Type.CURVE_2));
        strokeCurve2.setShortcut(new MenuShortcut(KeyEvent.VK_2));
        strokeMenu.add(strokeCurve2);
        strokes.put(Node.Type.CURVE_2, strokeCurve2);
        final MenuItem strokeCurve3 = new MenuItem("3 Stop Curve");
        strokeCurve3.addActionListener(e -> setStroke(Node.Type.CURVE_3));
        strokeCurve3.setShortcut(new MenuShortcut(KeyEvent.VK_3));
        strokeMenu.add(strokeCurve3);
        strokes.put(Node.Type.CURVE_3, strokeCurve3);
        final MenuItem strokeFinishLine = new MenuItem("Finish Line");
        strokeFinishLine.addActionListener(e -> setStroke(Node.Type.FINISH));
        strokeFinishLine.setShortcut(new MenuShortcut(KeyEvent.VK_F));
        strokeMenu.add(strokeFinishLine);
        strokes.put(Node.Type.FINISH, strokeFinishLine);
        final MenuItem strokePitLane = new MenuItem("Pit Lane");
        strokePitLane.addActionListener(e -> setStroke(Node.Type.PIT));
        strokePitLane.setShortcut(new MenuShortcut(KeyEvent.VK_P));
        strokeMenu.add(strokePitLane);
        strokes.put(Node.Type.PIT, strokePitLane);

        setStroke(Node.Type.STRAIGHT);
        select(null);

        menuBar.add(validationMenu);
        final MenuItem validate = new MenuItem("Validate Track");
        validate.addActionListener(e -> validateTrack());
        validate.setShortcut(new MenuShortcut(KeyEvent.VK_V));
        validationMenu.add(validate);

        frame.setMenuBar(menuBar);
        frame.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                if (selectedNode != null) {
                    Point delta;
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_UP:
                            delta = new Point(0, -1);
                            break;
                        case KeyEvent.VK_DOWN:
                            delta = new Point(0, 1);
                            break;
                        case KeyEvent.VK_LEFT:
                            delta = new Point(-1, 0);
                            break;
                        case KeyEvent.VK_RIGHT:
                            delta = new Point(1, 0);
                            break;
                        case KeyEvent.VK_D:
                            debugNeighbors.clear();
                            final Map<Node, List<Node>> prevNodeMap = AIUtil.buildPrevNodeMap(nodes);
                            final Map<Node, Double> distanceMap = new HashMap<>();
                            Main.findGrid(nodes, attributes, gridAngles, distanceMap, prevNodeMap);
                            final Map<Node, Set<Node>> collisionMap = TrackLanes.buildCollisionMap(nodes, distanceMap);
                            debugNeighbors.addAll(collisionMap.get(selectedNode));
                            repaint();
                            return;
                        default:
                            return;
                    }
                    final Point p = coordinates.get(selectedNode);
                    p.x += delta.x;
                    p.y += delta.y;
                    repaint();
                } else if (e.getKeyCode() == KeyEvent.VK_D) {
                    debugNeighbors.clear();
                    repaint();
                }
            }
        });
    }

    private void select(Node node) {
	    selectedNode = node;
	    if (selectedNode != null) System.out.println(selectedNode.getId());
	    setGarage.setEnabled(selectedNode != null && selectedNode.getType() == Node.Type.PIT);
        setCurveDistance.setEnabled(selectedNode != null && selectedNode.isCurve());
	    setGridAngle.setEnabled(selectedNode != null && selectedNode.getType() != Node.Type.PIT);
    }

	private void setStroke(Node.Type stroke) {
	    this.stroke = stroke;
	    strokes.forEach((type, item) -> item.setEnabled(stroke != type));
    }

    private void toggleSelectMode() {
	    autoSelectMode = !autoSelectMode;
	    repaint();
    }

    @Override
    public void paintComponent(Graphics g) {
	    super.paintComponent(g);
	    if (backgroundImage != null) {
            g.drawImage(backgroundImage, 0, 0, null);
        }
        final Graphics2D g2d = (Graphics2D) g;
        for (Node node : nodes) {
            drawNode(g2d, node);
        }
        UIUtil.drawInfoBox(g2d, this, 10, infoBoxCorner);
        final int x = UIUtil.getX(infoBoxCorner, this, 250);
        final int y = UIUtil.getY(infoBoxCorner, this, 5 + 15 * 10);
        if (selectedNode != null) {
            final Point p = coordinates.get(selectedNode);
            drawOval(g2d, p.x, p.y, DIAMETER + 2, DIAMETER + 2, true, false, Color.YELLOW, 1);
            final Double attr = attributes.get(selectedNode);
            if (attr != null) {
                drawOval(g2d, x + 210, y + 40, 50, 50, true, true, Color.BLACK, 1);
                final String attrStr = Double.toString(attr);
                g2d.setColor(Color.WHITE);
                g2d.setFont(Player.rollFont);
                final int width = g2d.getFontMetrics().stringWidth(attrStr);
                g2d.drawString(attrStr, x + 210 - width / 2, y + 48);
            }
        }
        g.setColor(Color.BLACK);
        g.setFont(Game.headerFont);
        final String modeStr = autoSelectMode ? "Mode: Draw edges" : "Mode: Select nodes";
        g.drawString(modeStr, x + 20, y + 15);
        drawArcs(g2d, nodes);
        for (Node neighbor : debugNeighbors) {
            final Point p = coordinates.get(neighbor);
            drawOval(g2d, p.x, p.y, DIAMETER, DIAMETER, true, true, Color.BLUE, 1);
        }
    }

    boolean open() {
        final JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select background image");
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
        fileChooser.addChoosableFileFilter(new Filter(Filter.imageExtensions, "Image only"));
        fileChooser.setAcceptAllFileFilterUsed(true);
        final int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            final File selectedFile = fileChooser.getSelectedFile();
            backgroundImage = ImageCache.getImageFromPath(selectedFile.getAbsolutePath());
            if (backgroundImage == null) {
                JOptionPane.showConfirmDialog(this, "Unable to open image " + selectedFile.getName(), "Error", JOptionPane.DEFAULT_OPTION);
                return false;
            }
            backgroundImageFileName = selectedFile.getName();
            setPreferredSize(new Dimension(backgroundImage.getWidth(), backgroundImage.getHeight()));
            frame.pack();
            final Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            frame.setSize(Math.min(screenSize.width, frame.getWidth()), Math.min(screenSize.height - 100, frame.getHeight()));
            return true;
        }
        return false;
    }

    private void save() {
	    if (backgroundImageFileName == null) {
	        return;
        }
        final JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
        fileChooser.addChoosableFileFilter(new Filter(Filter.dataExtensions, "Data file"));
        fileChooser.setAcceptAllFileFilterUsed(true);
        final int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            final File selectedFile = fileChooser.getSelectedFile();
            if (!selectedFile.getName().toLowerCase().endsWith(".dat")) {
                JOptionPane.showConfirmDialog(this, "Track data file must end with .dat. Track data is NOT saved!", "Wrong File Extension", JOptionPane.DEFAULT_OPTION);
                return;
            }
            try (final PrintWriter writer = new PrintWriter(selectedFile, "UTF-8")) {
                writer.print(backgroundImageFileName);
                writer.print(" ");
                writer.println(infoBoxCorner.ordinal());
                final Map<Node, Integer> idMap = new HashMap<>();
                for (int i = 0; i < nodes.size(); i++) {
                    final Node node = nodes.get(i);
                    final Point p = coordinates.get(node);
                    idMap.put(node, i);
                    writer.print(i);
                    writer.print(" ");
                    writer.print(p.x);
                    writer.print(" ");
                    writer.print(p.y);
                    writer.print(" ");
                    writer.println(node.getType().ordinal());
                }
                writer.println();
                for (Node node : nodes) {
                    node.forEachChild(child -> {
                        writer.print(idMap.get(node));
                        writer.print(" ");
                        writer.println(idMap.get(child));
                    });
                }
                writer.println();
                if (!attributes.isEmpty()) {
                    for (Map.Entry<Node, Double> entry : attributes.entrySet()) {
                        writer.print(idMap.get(entry.getKey()));
                        writer.print(" ");
                        writer.println(entry.getValue());
                    }
                }
                if (!gridAngles.isEmpty()) {
                    writer.println();
                    for (Map.Entry<Node, Double> entry : gridAngles.entrySet()) {
                        writer.print(idMap.get(entry.getKey()));
                        writer.print(" ");
                        writer.println(entry.getValue());
                    }
                }

            } catch (FileNotFoundException | UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            JOptionPane.showConfirmDialog(this, "Track data saved successfully", "Success", JOptionPane.DEFAULT_OPTION);
        }
    }

    private void load() {
        final JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Load track data");
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
        fileChooser.addChoosableFileFilter(new Filter(Filter.dataExtensions, "Data file"));
        fileChooser.setAcceptAllFileFilterUsed(true);
        final int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            final File selectedFile = fileChooser.getSelectedFile();
            try (FileInputStream fis = new FileInputStream(selectedFile)) {
                final Pair<String, Corner> p = loadNodes(fis, nodes, attributes, gridAngles, coordinates);
                if (p == null) {
                    JOptionPane.showConfirmDialog(this, "Wrong file format: " + selectedFile.getName(), "File Format Error", JOptionPane.DEFAULT_OPTION);
                    return;
                }
                infoBoxCorner = p.getRight();
                nextNodeId += nodes.size();
                repaint();
            } catch (IOException e) {
                throw new RuntimeException("Can't bother to fix this properly", e);
            }
        }
    }

    private static Pair<String, Corner> parseHeaderRow(String headerLine) {
        if (headerLine != null && !headerLine.isEmpty()) {
            final String[] parts = headerLine.split(" ");
            if (parts.length < 2) return null;
            try {
                final int infoBoxCorner = Integer.parseInt(parts[1]);
                return Pair.of(parts[0], Corner.values()[infoBoxCorner]);
            } catch (NumberFormatException e) {
                // Fail...
            }
        }
        return null;
    }

    public static Pair<String, Corner> loadHeader(String trackId, boolean external) {
        try (InputStream is = external ? new FileInputStream(trackId) : Main.class.getResourceAsStream("/" + trackId); InputStreamReader ir = new InputStreamReader(is); final BufferedReader br = new BufferedReader(ir)) {
            final String headerLine = br.readLine();
            return parseHeaderRow(headerLine);
        } catch (IOException e) {
            Main.log.log(Level.SEVERE, "Reading header of " + trackId + " failed", e);
        }
        return null;
    }

    public static Pair<String, Corner> loadNodes(InputStream map, Collection<Node> nodes, Map<Node, Double> attributes, Map<Node, Double> gridAngles, Map<Node, Point> coordinates) {
	    Pair<String, Corner> result = null;
        try (InputStreamReader ir = new InputStreamReader(map); final BufferedReader br = new BufferedReader(ir)) {
            final String headerLine = br.readLine();
            result = parseHeaderRow(headerLine);
            if (result == null) {
                return null;
            }
            // File format begins with nodes, then a single empty line and then edges,
            // then a single empty line and then attributes.
            final Map<Integer, Node> idMap = new HashMap<>();
            final Map<Node, Double> attrMap = new HashMap<>();
            final Map<Node, Double> gridAngleMap = new HashMap<>();
            final Map<Node, Point> coordMap = new HashMap<>();
            int emptyLines = 0;
            String line;
            while ((line = br.readLine()) != null) {
                final String[] parts = line.split(" ");
                if (parts.length == 1 && "".equals(parts[0])) {
                    emptyLines++;
                } else if (emptyLines == 1) {
                    final int fromId = Integer.parseInt(parts[0]);
                    final int toId = Integer.parseInt(parts[1]);
                    idMap.get(fromId).addChild(idMap.get(toId));
                } else if (emptyLines == 0) {
                    final int id = Integer.parseInt(parts[0]);
                    final int x = Integer.parseInt(parts[1]);
                    final int y = Integer.parseInt(parts[2]);
                    final Node.Type type = Node.Type.values()[Integer.parseInt(parts[3])];
                    final Node node = new Node(id, type);
                    idMap.put(id, node);
                    coordMap.put(node, new Point(x, y));
                } else if (emptyLines == 2) {
                    final int id = Integer.parseInt(parts[0]);
                    final double attribute = Double.parseDouble(parts[1]);
                    attrMap.put(idMap.get(id), attribute);
                } else {
                    final int id = Integer.parseInt(parts[0]);
                    final double attribute = Double.parseDouble(parts[1]);
                    gridAngleMap.put(idMap.get(id), attribute);
                }
            }
            // The file format was appraently good.
            nodes.clear();
            nodes.addAll(idMap.values());
            attributes.clear();
            attributes.putAll(attrMap);
            gridAngles.clear();
            gridAngles.putAll(gridAngleMap);
            coordinates.clear();
            coordinates.putAll(coordMap);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    private void setAttribute() {
	    if (selectedNode != null) {
	        if (selectedNode.getType() == Node.Type.PIT) {
	            if (attributes.containsKey(selectedNode)) {
	                attributes.remove(selectedNode);
                } else {
	                attributes.put(selectedNode, 1.0);
                }
            } else {
                final Object attr = JOptionPane.showInputDialog(
                        this,
                        "Set Curve Distance:",
                        "Set Curve Distance",
                        JOptionPane.PLAIN_MESSAGE,
                        null,
                        null,
                        attributes.get(selectedNode)
                );
                if (attr != null) {
                    if (!attr.toString().isEmpty()) {
                        attributes.put(selectedNode, Double.valueOf(attr.toString()));
                    } else {
                        attributes.remove(selectedNode);
                    }
                }
	        }
            repaint();
        }
    }

    private void setGridAngle() {
        if (selectedNode != null) {
            final Object attr = JOptionPane.showInputDialog(
                    this,
                    "Set Grid Angle:",
                    "Set Grid Angle",
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    null,
                    gridAngles.get(selectedNode)
            );
            if (attr != null && !attr.toString().isEmpty()) {
                gridAngles.put(selectedNode, Double.valueOf(attr.toString()));
            } else {
                gridAngles.remove(selectedNode);
            }
            repaint();
        }
    }

    private void validateTrack() {
	    try {
            final Map<Node, List<Node>> prevNodeMap = AIUtil.buildPrevNodeMap(nodes);
            final Map<Node, Double> distanceMap = new HashMap<>();
            final List<Node> grid = Main.findGrid(nodes, attributes, gridAngles, distanceMap, prevNodeMap);
            if (grid.size() < 10) {
                JOptionPane.showConfirmDialog(this, "Track validation failed: Starting grid has less than 10 spots", "Validation Error", JOptionPane.DEFAULT_OPTION);
            }
            TrackLanes.buildCollisionMap(nodes, distanceMap);
            JOptionPane.showConfirmDialog(this, "Track seems OK", "Success", JOptionPane.DEFAULT_OPTION);
        } catch (Exception e) {
            JOptionPane.showConfirmDialog(this, "Track validation failed: " + e.getMessage(), "Validation Error", JOptionPane.DEFAULT_OPTION);
        }
    }

    private void switchInfoBoxCorner() {
	    final Corner[] corners = Corner.values();
	    infoBoxCorner = corners[(infoBoxCorner.ordinal() + 1) % corners.length];
	    repaint();
    }

    private void drawNode(Graphics2D g2d, Node node) {
        final Color color;
        switch (node.getType()) {
            case STRAIGHT:
                color = Color.GREEN;
                break;
            case CURVE_2:
                color = Color.RED;
                break;
            case CURVE_1:
                color = LIGHT_RED;
                break;
            case START:
                color = Color.WHITE;
                break;
            case FINISH:
                color = Color.BLUE;
                break;
            case CURVE_3:
                color = new Color(0xAA0000);
                break;
            case PIT:
                color = Color.BLACK;
                break;
            default:
                throw new RuntimeException("Unknown node type");
        }
        final Point p = coordinates.get(node);
        final Double angle = gridAngles.get(node);
        if (angle == null) {
            drawOval(g2d, p.x, p.y, DIAMETER, DIAMETER, true, true, color, 0);
        } else {
            Player.draw(g2d, p.x, p.y, angle / 180 * Math.PI, Color.BLUE, Color.BLACK, 1.0);
        }
        final Double attr = attributes.get(node);
        if (attr != null) {
            // Draw attribute indicator
            drawOval(g2d, p.x, p.y, 4, 4, true, true, Color.YELLOW, 0);
        }
    }

    private void drawArcs(Graphics2D g2d, Collection<Node> nodes) {
        final Color tmpC = g2d.getColor();
        for (Node node : nodes) {
            final Point p = coordinates.get(node);
            node.forEachChild(child -> {
                final Point np = coordinates.get(child);
                final int midX = (p.x + np.x) / 2;
                final int midY = (p.y + np.y) / 2;
                g2d.setColor(ARC_BEGIN);
                g2d.drawLine(p.x, p.y, midX, midY);
                g2d.setColor(ARC_END);
                g2d.drawLine(midX, midY, np.x, np.y);
            });
        }
        g2d.setColor(tmpC);
    }

    public static void drawOval(Graphics2D g2d, int x, int y, int width, int height, boolean centered, boolean filled, Color color, int lineThickness) {
        // Store before changing.
        final Stroke tmpS = g2d.getStroke();
        final Color tmpC = g2d.getColor();

        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(lineThickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        final int x2 = centered ? x - (width / 2) : x;
        final int y2 = centered ? y - (height / 2) : y;

        if (filled) g2d.fillOval(x2, y2, width, height);
        else g2d.drawOval(x2, y2, width, height);

        // Set values to previous when done.
        g2d.setColor(tmpC);
        g2d.setStroke(tmpS);
    }

    @Nullable
    public static Node getNode(Collection<Node> nodes, Map<Node, Point> coordinates, int x, int y, int threshold) {
        for (Node node : nodes) {
            final Point point = coordinates.get(node);
            if (Math.hypot(x - point.x, y - point.y) < threshold) {
                return node;
            }
        }
        return null;
    }

    private static class Filter extends FileFilter {
        final static String[] imageExtensions = {"jpeg", "jpg", "gif", "tiff", "tif", "png"};
        final static String[] dataExtensions = {"dat"};

        private final String[] extensions;
        private final String description;

        Filter(String[] extensions, String description) {
            this.extensions = extensions;
            this.description = description;
        }

        @Override
        public boolean accept(File f) {
            if (f.isDirectory()) {
                return true;
            }
            String extension = getExtension(f);
            if (extension != null) {
                for (String allowedExtension : extensions) {
                    if (extension.equals(allowedExtension)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public String getDescription() {
            return description;
        }

        String getExtension(File f) {
            String ext = null;
            String s = f.getName();
            int i = s.lastIndexOf('.');
            if (i > 0 &&  i < s.length() - 1) {
                ext = s.substring(i+1).toLowerCase();
            }
            return ext;
        }
    }

    @Override
    public String getName() {
	    return "Map Editor";
    }
}
