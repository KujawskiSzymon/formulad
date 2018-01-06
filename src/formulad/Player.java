package formulad;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class Player {
    private Node node;
    private int hitpoints = 18;
    private int gear;
    private int adjust;
    private int curveStops;
    private double angle;
    private List<Node> route;
    private int lapsToGo;
    private Color color1 = new Color(0x770000);
    private Color color2 = Color.RED;

    public Player(final int laps) {
        lapsToGo = laps;
    }

    public void drawStats(final Graphics2D g2d, final Integer roll) {
        // TODO: Clean up
        if (roll != null) {
            g2d.setColor(Color.GREEN);
            final int width = (roll + adjust >= 10) ? 25 : 20;
            final int x = (roll + adjust >= 10) ? 43 : 46;
            MapEditor.drawOval(g2d, 50, 20, width, 20, true, true, Color.BLACK, 1);
            g2d.drawString(Integer.toString(roll + adjust), x, 24);
        }
        g2d.setColor(Color.RED);
        final int width = (hitpoints + adjust >= 10) ? 25 : 20;
        final int x = (hitpoints + adjust >= 10) ? 13 : 16;
        MapEditor.drawOval(g2d, 20, 50, width, 20, true, true, Color.BLACK, 1);
        g2d.drawString(Integer.toString(hitpoints + adjust), x, 54);
        MapEditor.drawOval(g2d, 20, 20, 20, 20, true, true, Color.BLACK, 1);
        g2d.setColor(Color.WHITE);
        g2d.drawString(Integer.toString(gear), 16, 24);
    }

    public void drawPath(final Graphics g) {
        if (route != null) {
            for (int i = 0; i < route.size() - 1; i++) {
                final Node n1 = route.get(i);
                final Node n2 = route.get(i + 1);
                g.drawLine(n1.x, n1.y, n2.x, n2.y);
            }
        }
    }

    public void draw(final Graphics2D g) {
        AffineTransform at = new AffineTransform();
        at.translate(node.x, node.y);
        g.transform(at);
        g.rotate(angle);
        g.setColor(Color.BLACK);
        g.fillRect(-7, -3, 1, 7);
        g.fillRect(-6, 0, 1, 1);
        g.fillRect(-4, -4, 3, 2);
        g.fillRect(-4, 3, 3, 2);
        g.fillRect(6, -3, 1, 7);
        g.fillRect(3, -2, 1, 1);
        g.fillRect(3, 2, 1, 1);
        g.fillRect(2, -4, 3, 2);
        g.fillRect(2, 3, 3, 2);
        g.setColor(color1);
        g.fillRect(-5, -2, 6, 5);
        g.fillRect(1, -1, 5, 3);
        g.setColor(color2);
        g.fillRect(-4, -1, 5, 3);
        g.fillRect(1, 0, 5, 1);
        g.setColor(color1);
        g.fillRect(-3, 0, 3, 1);
        // needed if something is drawn after this
        g.rotate(-angle);
        g.translate(-node.x, -node.y);
    }

    public boolean adjustRoll(final int roll, final int delta) {
        final int newAdjust = adjust + delta;
        if (roll + newAdjust >= 0 && newAdjust <= 0 && hitpoints + newAdjust > 0) {
            adjust = newAdjust;
        }
        return adjust == newAdjust;
    }

    public boolean switchGear(final int newGear) {
        if (newGear > 0 && newGear < gear - 1 && hitpoints > gear - 1 - newGear) {
            // downwards more than 1
            hitpoints -= gear - 1 - newGear;
            gear = newGear;
        } else if (Math.abs(newGear - gear) <= 1) {
            gear = newGear;
        }
        return gear == newGear;
    }

    public void move(final DamageAndPath dp) {
        final List<Node> route = dp.getPath();
        if (route == null || route.isEmpty()) {
            throw new RuntimeException("Invalid route: " + route);
        }
        if (node != null && route.get(0) != node) {
            throw new RuntimeException("Invalid starting point for route: " + route);
        }
        int size = route.size();
        if (size > 1) {
            final Node n1 = route.get(size - 2);
            final Node n2 = route.get(size - 1);
            angle = Math.atan2(n2.y - n1.y, n2.x - n1.x);
        }
        if (node != null && node.type != MapEditor.FINISH) {
            for (final Node node : route) {
                if (node.type == MapEditor.FINISH) {
                    lapsToGo--;
                    break;
                }
            }
        }
        this.route = route;
        node = route.get(size - 1);
        boolean onlyCurves = true;
        for (final Node node : route) {
            if (!node.isCurve()) {
                onlyCurves = false;
                break;
            }
        }
        if (!onlyCurves) {
            // path contained non-curve nodes
            curveStops = 0;
        }
        if (node.isCurve()) {
            // movement ended in a curve
            curveStops++;
        }
        hitpoints += adjust;
        hitpoints -= dp.getDamage();
        adjust = 0;
    }

    public Map<Node, DamageAndPath> findTargetNodes(final int roll) {
        final Map<Node, DamageAndPath> result = NodeUtil.findNodes(node, roll + adjust, new HashSet<>(), true, curveStops, lapsToGo == 0);
        final Map<Node, DamageAndPath> targets = new HashMap<>();
        for (final Map.Entry<Node, DamageAndPath> entry : result.entrySet()) {
            if (entry.getValue().getDamage() < hitpoints + adjust) {
                targets.put(entry.getKey(), entry.getValue());
            }
        }
        return targets;
    }
}