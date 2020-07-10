package formulad.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import formulad.model.GameState;
import formulad.model.Moves;
import formulad.model.NameAtStart;
import formulad.model.PlayerState;
import formulad.model.SelectedIndex;
import formulad.model.Track;
import formulad.model.ValidMove;

public class ExampleAI implements AI {

    private String playerId;
    private Map<Integer, Node> nodeMap;
    private Map<String, PlayerState> playerMap;
    private Node location;
    private PlayerState player;
    private Random random = new Random();

    @Override
    public NameAtStart startGame(Track track) {
        playerId = track.getPlayer().getPlayerId();
        nodeMap = AIUtil.buildNodeMap(track.getTrack().getNodes(), track.getTrack().getEdges());
        return new NameAtStart().name("Example");
    }

    @Override
    public formulad.model.Gear selectGear(GameState gameState) {
        playerMap = AIUtil.buildPlayerMap(gameState);
        player = playerMap.get(playerId);
        location = nodeMap.get(player.getNodeId());

        // TODO: Implement better AI
        int newGear = player.getGear() + 1;
        if (newGear > 4) newGear = 4;
        return new formulad.model.Gear().gear(newGear);
    }

    @Override
    public SelectedIndex selectMove(Moves moves) {
        // TODO: Implement better AI
        int leastDamage = player.getHitpoints();
        final List<ValidMove> validMoves = moves.getMoves();
        final List<Integer> bestTargets = new ArrayList<>();
        for (int i = 0; i < validMoves.size(); i++) {
            final ValidMove vm = validMoves.get(i);
            final int damage = vm.getBraking() + vm.getOvershoot();
            if (damage < leastDamage) {
                bestTargets.clear();
                leastDamage = damage;
            }
            if (damage <= leastDamage) {
                bestTargets.add(i);
            }
        }
        if (bestTargets.isEmpty()) {
            // Flaw in this AI, just select something valid
            return new SelectedIndex().index(0);
        }
        return new SelectedIndex().index(bestTargets.get(random.nextInt(bestTargets.size())));
    }
}
