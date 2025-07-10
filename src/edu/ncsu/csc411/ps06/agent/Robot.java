package edu.ncsu.csc411.ps06.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.Stack;

import edu.ncsu.csc411.ps06.environment.Action;
import edu.ncsu.csc411.ps06.environment.Environment;
import edu.ncsu.csc411.ps06.environment.Position;
import edu.ncsu.csc411.ps06.environment.Tile;
import edu.ncsu.csc411.ps06.environment.TileStatus;

/**
 * Represents a planning agent within an environment modeled after the Chip's
 * Challenge Windows 95 game. This agent must develop a plan for navigating the
 * environment to collect chips and keys in order to reach the environment's
 * portal (goal condition).
 * 
 * Problem Set 06 - In this problem set, you will be developing a planning agent
 * to navigate the environment to collect chips scattered across the map. In
 * order to reach the portal (goal condition), the agent must collect all the
 * chips first. In order to do this, the agent will also need to collect
 * assorted keys that can be used to unlock doors blocking some of the chips.
 * 
 * Map difficulties increase by the number of subgoals that the agent must
 * complete. While I will be able to assist in getting started debugging,
 * planning is not a simple algorithm and still a complex task for even the most
 * advanced AIs. This of this as one of those "unsolvable" math problems
 * scrawled in chalk on some abandoned blackboard.
 * 
 * That is to say, you are on your own in this "mostly uncharted" territory.
 * 
 * Planning agent to navigate the environment to collect chips and keys
 * Utilizing pathfinding algorithms and planning: 
 * - A* for pathfinding 
 * - Priority-based goal selection 
 * - Stuck detection and loop avoidance 
 * - Exploration of unexplored areas
 * 
 * @author Minh Tri Pham
 */

public class Robot {

	private Environment env;
	private Stack<Action> currentPlan = new Stack<>();
	private Set<Position> visitedPositions = new HashSet<>();
	private Map<Position, TileStatus> knownTiles = new HashMap<>();
	private Set<TileStatus> collectedItems = new HashSet<>();
	private ArrayList<String> inventory = new ArrayList<>();
	// field to store recent positions for loop detection
	private List<Position> recentPositions = new ArrayList<>();

	/**
	 * Initializes a Robot on a specific tile in the environment.
	 * 
	 * @param env - The Environment
	 */
	public Robot(Environment env) {
		this.env = env;
	}

	/**
	 * Determines the next action for the robot to take. The main decision making
	 * function for the robot.
	 * 
	 * @return should return a single Action from the Action class. -
	 *         Action.DO_NOTHING - Action.MOVE_UP - Action.MOVE_DOWN -
	 *         Action.MOVE_LEFT - Action.MOVE_RIGHT
	 */
	public Action getAction() {
		try {
			// If we have a plan and it's not empty, follow it
			if (currentPlan != null && !currentPlan.isEmpty()) {
				return currentPlan.pop();
			}

			// Create a new plan if needed
			Action newAction = createNewPlan();
			// Ensure we never return null
			return (newAction != null) ? newAction : Action.DO_NOTHING;
		} catch (Exception e) {
			// Emergency fallback to prevent crashes
			System.out.println("Error in getAction: " + e.getMessage());
			return Action.DO_NOTHING;
		}
	}

	/**
	 * Creates a new plan for the robot based on current environmental conditions.
	 * Prioritizes goals in the following order: 1. Reach goal if all chips
	 * collected 2. Collect remaining chips 3. Collect needed keys 4. Explore
	 * environment
	 * 
	 * @return The next action to take based on the new plan
	 */
	private Action createNewPlan() {
		try {
			// Update what we know about the environment
			updateKnowledge();

			// Get our current position
			Position currentPos = env.getRobotPosition(this);
			if (currentPos == null) {
				return Action.DO_NOTHING;
			}

			// Debug info
			System.out.println("Current position: " + currentPos);
			System.out.println("Remaining chips: " + env.getNumRemainingChips());

			// Keep track of position history for stuck detection
			if (recentPositions == null) {
				recentPositions = new ArrayList<>();
			}

			recentPositions.add(currentPos);
			if (recentPositions.size() > 10) {
				recentPositions.remove(0);
			}

			// Check if we're stuck in a loop
			boolean isStuck = false;
			if (recentPositions.size() >= 6) {
				Set<Position> uniquePositions = new HashSet<>(
						recentPositions.subList(recentPositions.size() - 6, recentPositions.size()));
				if (uniquePositions.size() <= 2) {
					isStuck = true;
					System.out.println("DETECTED STUCK CONDITION - trying alternative strategies");
				}
			}

			// Check if we're at the goal and have collected all chips
			if (env.getNumRemainingChips() == 0) {
				// Get goal position safely
				Position goalPos = getGoalPositionSafely();

				if (goalPos != null) {
					System.out.println("Goal found at: " + goalPos);

					// Are we already at the goal?
					if (currentPos.equals(goalPos)) {
						return Action.DO_NOTHING; // We've reached the goal!
					}

					// Try to move toward the goal
					Action goalAction = moveTowardPosition(currentPos, goalPos);

					// If we're stuck and can't reach the goal directly, try finding doors
					if (isStuck) {
						Action doorAction = findAndOpenDoors(currentPos);
						if (doorAction != null) {
							return doorAction;
						}
					}

					return goalAction != null ? goalAction : exploreEnvironment(currentPos);
				} else {
					System.out.println("Goal position is null, searching for goal...");

					// If we're stuck, try finding doors
					if (isStuck) {
						Action doorAction = findAndOpenDoors(currentPos);
						if (doorAction != null) {
							return doorAction;
						}
					}

					return searchForGoal(currentPos);
				}
			}

			// First priority: Collect remaining chips
			Action chipAction = handleChipCollection(currentPos);
			if (chipAction != null) {
				return chipAction;
			}

			// Second priority: Collect keys
			Action keyAction = handleKeyCollection(currentPos);
			if (keyAction != null) {
				return keyAction;
			}

			// If we're stuck, try finding doors
			if (isStuck) {
				Action doorAction = findAndOpenDoors(currentPos);
				if (doorAction != null) {
					return doorAction;
				}
			}

			// If we can't do anything useful, explore
			return exploreEnvironment(currentPos);
		} catch (Exception e) {
			System.out.println("Error in createNewPlan: " + e.getMessage());
			e.printStackTrace();
			return Action.DO_NOTHING;
		}
	}

	/**
	 * Searches for the goal position when it's not directly visible.
	 * 
	 * @param currentPos The robot's current position
	 * @return An action moving toward the suspected goal location
	 */
	private Action searchForGoal(Position currentPos) {
		try {
			// Look for any unexplored areas first
			Map<String, Position> neighbors = env.getNeighborPositions(currentPos);
			Map<String, Tile> neighborTiles = env.getNeighborTiles(this);

			if (neighbors != null && neighborTiles != null) {
				// First check for unexplored positions
				for (Map.Entry<String, Position> entry : neighbors.entrySet()) {
					String direction = entry.getKey();
					Position neighborPos = entry.getValue();

					if (neighborPos != null && !visitedPositions.contains(neighborPos)
							&& neighborTiles.containsKey(direction)) {
						Tile neighborTile = neighborTiles.get(direction);
						if (neighborTile != null) {
							TileStatus status = neighborTile.getStatus();
							if (isPassable(status)) {
								System.out.println("Searching for goal: Moving to unexplored " + direction);
								return getActionForDirection(direction);
							}
						}
					}
				}

				// If no unexplored positions, find least recently visited area
				Position leastVisitedPos = findLeastVisitedArea();
				if (leastVisitedPos != null && !currentPos.equals(leastVisitedPos)) {
					generatePlan(currentPos, leastVisitedPos);
					if (!currentPlan.isEmpty()) {
						System.out.println("Moving to least visited area: " + leastVisitedPos);
						return currentPlan.pop();
					}
				}

				// As a last resort, pick any valid direction
				for (Map.Entry<String, Position> entry : neighbors.entrySet()) {
					String direction = entry.getKey();
					if (neighborTiles.containsKey(direction)) {
						Tile neighborTile = neighborTiles.get(direction);
						if (neighborTile != null && isPassable(neighborTile.getStatus())) {
							System.out.println("Searching for goal: Moving " + direction + " as last resort");
							return getActionForDirection(direction);
						}
					}
				}
			}

			System.out.println("Cannot find any direction to search for goal");
			return Action.DO_NOTHING;
		} catch (Exception e) {
			System.out.println("Error in searchForGoal: " + e.getMessage());
			return Action.DO_NOTHING;
		}
	}

	/**
	 * Finds the least visited area in the known environment.
	 *
	 * @return Position of the least visited accessible area
	 */
	private Position findLeastVisitedArea() {
		try {
			Map<Position, Integer> visitCounts = new HashMap<>();

			// Count all known positions
			for (Position pos : knownTiles.keySet()) {
				visitCounts.put(pos, 0);
			}

			// Count visits
			for (Position visited : visitedPositions) {
				if (visitCounts.containsKey(visited)) {
					visitCounts.put(visited, visitCounts.get(visited) + 1);
				} else {
					visitCounts.put(visited, 1);
				}
			}

			// Find position with lowest visit count that is passable
			Position leastVisited = null;
			int minVisits = Integer.MAX_VALUE;

			for (Map.Entry<Position, Integer> entry : visitCounts.entrySet()) {
				Position pos = entry.getKey();
				int visits = entry.getValue();

				if (visits < minVisits && knownTiles.containsKey(pos)) {
					TileStatus status = knownTiles.get(pos);
					if (isPassable(status)) {
						minVisits = visits;
						leastVisited = pos;
					}
				}
			}

			return leastVisited;
		} catch (Exception e) {
			System.out.println("Error in findLeastVisitedArea: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Handles the collection of chips in the environment.
	 *
	 * @param currentPos The robot's current position
	 * @return An action moving toward the nearest accessible chip
	 */
	private Action handleChipCollection(Position currentPos) {
		try {
			List<Position> chipPositions = new ArrayList<>();
			Map<TileStatus, ArrayList<Position>> envPositions = env.getEnvironmentPositions();

			if (envPositions != null && envPositions.containsKey(TileStatus.CHIP)) {
				chipPositions.addAll(envPositions.get(TileStatus.CHIP));
			}

			if (!chipPositions.isEmpty()) {
				Position nearestChip = findNearestAccessibleTarget(currentPos, chipPositions);
				if (nearestChip != null) {
					generatePlan(currentPos, nearestChip);
					if (!currentPlan.isEmpty()) {
						return currentPlan.pop();
					}
				}
			}
			return null;
		} catch (Exception e) {
			System.out.println("Error in handleChipCollection: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Safely retrieves the goal position using multiple fallback methods.
	 *
	 * @return The position of the goal, or null if not found
	 */
	private Position getGoalPositionSafely() {
		try {
			// Try to get the goal position directly from environment
			Position goalPos = null;
			try {
				goalPos = env.getGoalPosition();
				if (goalPos != null) {
					System.out.println("Got goal position directly: " + goalPos);
					return goalPos;
				}
			} catch (Exception e) {
				System.out.println("Error getting goal position from env: " + e.getMessage());
			}

			// If that didn't work, try to find it ourselves from environment positions
			try {
				Map<TileStatus, ArrayList<Position>> envPositions = env.getEnvironmentPositions();
				if (envPositions != null && envPositions.containsKey(TileStatus.DOOR_GOAL)) {
					ArrayList<Position> goalPositions = envPositions.get(TileStatus.DOOR_GOAL);
					if (goalPositions != null && !goalPositions.isEmpty()) {
						goalPos = goalPositions.get(0);
						System.out.println("Found goal in environment positions: " + goalPos);
						return goalPos;
					}
				}
			} catch (Exception e) {
				System.out.println("Error finding goal in environment positions: " + e.getMessage());
			}

			// If we still can't find it, look for known positions with goal door status
			try {
				for (Map.Entry<Position, TileStatus> entry : knownTiles.entrySet()) {
					if (entry.getValue() == TileStatus.DOOR_GOAL) {
						goalPos = entry.getKey();
						System.out.println("Found goal in known tiles: " + goalPos);
						return goalPos;
					}
				}
			} catch (Exception e) {
				System.out.println("Error finding goal in known tiles: " + e.getMessage());
			}

			// Lastly, try to search through all tiles
			try {
				Map<Position, Tile> allTiles = env.getTiles();
				if (allTiles != null) {
					for (Map.Entry<Position, Tile> entry : allTiles.entrySet()) {
						Position pos = entry.getKey();
						Tile tile = entry.getValue();
						if (tile != null && tile.getStatus() == TileStatus.DOOR_GOAL) {
							System.out.println("Found goal in all tiles: " + pos);
							return pos;
						}
					}
				}
			} catch (Exception e) {
				System.out.println("Error finding goal in all tiles: " + e.getMessage());
			}

			System.out.println("Could not find goal position using any method");
			return null;
		} catch (Exception e) {
			System.out.println("Major error in getGoalPositionSafely: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}

	private Action handleKeyCollection(Position currentPos) {
		try {
			List<Position> keyPositions = new ArrayList<>();
			Map<TileStatus, ArrayList<Position>> envPositions = env.getEnvironmentPositions();

			if (envPositions != null) {
				if (envPositions.containsKey(TileStatus.KEY_BLUE)) {
					keyPositions.addAll(envPositions.get(TileStatus.KEY_BLUE));
				}
				if (envPositions.containsKey(TileStatus.KEY_GREEN)) {
					keyPositions.addAll(envPositions.get(TileStatus.KEY_GREEN));
				}
				if (envPositions.containsKey(TileStatus.KEY_RED)) {
					keyPositions.addAll(envPositions.get(TileStatus.KEY_RED));
				}
				if (envPositions.containsKey(TileStatus.KEY_YELLOW)) {
					keyPositions.addAll(envPositions.get(TileStatus.KEY_YELLOW));
				}
			}

			if (!keyPositions.isEmpty()) {
				System.out.println("Found " + keyPositions.size() + " keys to collect");
				Position nearestKey = findNearestAccessibleTarget(currentPos, keyPositions);
				if (nearestKey != null) {
					System.out.println("Moving toward nearest key at " + nearestKey);
					generatePlan(currentPos, nearestKey);
					if (!currentPlan.isEmpty()) {
						return currentPlan.pop();
					} else {
						System.out.println("Could not plan path to nearest key");
					}
				} else {
					System.out.println("No accessible keys found");
				}
			}
			return null;
		} catch (Exception e) {
			System.out.println("Error in handleKeyCollection: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Determines if a given tile status represents a passable position. Takes into
	 * account the robot's current inventory for doors.
	 *
	 * @param status The TileStatus to check
	 * @return true if the tile is passable, false otherwise
	 */
	private boolean isPassable(TileStatus status) {
		if (status == null) {
			return false;
		}

		try {
			// Impassable tiles
			if (status == TileStatus.WALL || status == TileStatus.WATER) {
				return false;
			}

			// Get holdings safely
			ArrayList<String> holdings = null;
			try {
				holdings = env.getRobotHoldings(this);
			} catch (Exception e) {
				System.out.println("Error getting holdings: " + e.getMessage());
				holdings = new ArrayList<>();
			}

			if (holdings == null) {
				holdings = new ArrayList<>();
			}

			// Check for doors we can't open
			if ((status == TileStatus.DOOR_BLUE && !holdings.contains("KEY_BLUE"))
					|| (status == TileStatus.DOOR_GREEN && !holdings.contains("KEY_GREEN"))
					|| (status == TileStatus.DOOR_RED && !holdings.contains("KEY_RED"))
					|| (status == TileStatus.DOOR_YELLOW && !holdings.contains("KEY_YELLOW"))) {
				return false;
			}

			// Handle goal door separately
			if (status == TileStatus.DOOR_GOAL) {
				int remainingChips = env.getNumRemainingChips();
				if (remainingChips > 0) {
					return false;
				}
			}

			// Any other tile is passable
			return true;
		} catch (Exception e) {
			System.out.println("Error in isPassable: " + e.getMessage());
			// Default to false if we can't determine passability
			return false;
		}
	}

	private Action exploreEnvironment(Position currentPos) {
		try {
			Map<String, Position> neighbors = env.getNeighborPositions(currentPos);
			Map<String, Tile> neighborTiles = env.getNeighborTiles(this);

			if (neighbors == null || neighborTiles == null) {
				return Action.DO_NOTHING;
			}

			// Track the current position
			visitedPositions.add(currentPos);

			// To avoid getting stuck in loops, keep track of last few positions
			if (recentPositions == null) {
				recentPositions = new ArrayList<>();
			}

			recentPositions.add(currentPos);
			if (recentPositions.size() > 10) {
				recentPositions.remove(0);
			}

			// Check if we're in a loop (same position multiple times)
			boolean inLoop = false;
			if (recentPositions.size() >= 6) {
				int count = 0;
				for (Position pos : recentPositions) {
					if (pos.equals(currentPos)) {
						count++;
					}
				}
				if (count >= 3) {
					inLoop = true;
					System.out.println("Detected position loop at " + currentPos);
				}
			}

			// If we're in a loop, take a random valid move
			if (inLoop) {
				List<String> validDirections = new ArrayList<>();
				for (Map.Entry<String, Position> entry : neighbors.entrySet()) {
					String direction = entry.getKey();
					if (neighborTiles.containsKey(direction)) {
						Tile neighborTile = neighborTiles.get(direction);
						if (neighborTile != null && isPassable(neighborTile.getStatus())) {
							validDirections.add(direction);
						}
					}
				}

				if (!validDirections.isEmpty()) {
					// Pick a random direction
					int randomIndex = (int) (Math.random() * validDirections.size());
					String randomDirection = validDirections.get(randomIndex);
					System.out.println("Breaking loop with random move: " + randomDirection);
					return getActionForDirection(randomDirection);
				}
			}

			// Prioritize unvisited positions for exploration
			for (Map.Entry<String, Position> entry : neighbors.entrySet()) {
				String direction = entry.getKey();
				Position neighborPos = entry.getValue();

				if (neighborPos != null && !visitedPositions.contains(neighborPos)
						&& neighborTiles.containsKey(direction)) {
					Tile neighborTile = neighborTiles.get(direction);
					if (neighborTile != null) {
						TileStatus status = neighborTile.getStatus();

						if (isPassable(status)) {
							System.out.println("Exploring unvisited direction: " + direction);
							return getActionForDirection(direction);
						}
					}
				}
			}

			// If no unvisited neighbors, move to least recently visited neighbor
			if (!neighbors.isEmpty()) {
				String leastVisitedDirection = null;
				int minVisits = Integer.MAX_VALUE;

				for (Map.Entry<String, Position> entry : neighbors.entrySet()) {
					String direction = entry.getKey();
					Position neighborPos = entry.getValue();

					if (neighborPos != null && neighborTiles.containsKey(direction)) {
						Tile neighborTile = neighborTiles.get(direction);
						if (neighborTile != null && isPassable(neighborTile.getStatus())) {
							// Count how many times we've visited this position
							int visits = 0;
							for (Position pos : visitedPositions) {
								if (pos.equals(neighborPos)) {
									visits++;
								}
							}

							if (visits < minVisits) {
								minVisits = visits;
								leastVisitedDirection = direction;
							}
						}
					}
				}

				if (leastVisitedDirection != null) {
					System.out.println("Moving to least visited direction: " + leastVisitedDirection);
					return getActionForDirection(leastVisitedDirection);
				}
			}

			// If all else fails, move in any valid direction
			for (Map.Entry<String, Position> entry : neighbors.entrySet()) {
				String direction = entry.getKey();
				if (neighborTiles.containsKey(direction)) {
					Tile neighborTile = neighborTiles.get(direction);
					if (neighborTile != null && isPassable(neighborTile.getStatus())) {
						System.out.println("Moving in any valid direction: " + direction);
						return getActionForDirection(direction);
					}
				}
			}

			System.out.println("No valid moves found, staying put");
			return Action.DO_NOTHING;
		} catch (Exception e) {
			System.out.println("Error in exploreEnvironment: " + e.getMessage());
			return Action.DO_NOTHING;
		}
	}

	private Action getActionForDirection(String direction) {
		switch (direction) {
		case "above":
			return Action.MOVE_UP;
		case "below":
			return Action.MOVE_DOWN;
		case "left":
			return Action.MOVE_LEFT;
		case "right":
			return Action.MOVE_RIGHT;
		default:
			return Action.DO_NOTHING;
		}
	}

	// Helper method to safely generate and store a plan
	private void generatePlan(Position start, Position goal) {
		try {
			if (start == null || goal == null) {
				System.out.println("Cannot generate plan: start or goal is null");
				currentPlan.clear();
				return;
			}

			System.out.println("Planning path from " + start + " to " + goal);
			List<Action> planList = planPath(start, goal);

			// Clear the current plan
			currentPlan.clear();

			// Add actions in reverse order for stack (LIFO) behavior
			if (planList != null && !planList.isEmpty()) {
				System.out.println("Path found with " + planList.size() + " steps");
				for (int i = planList.size() - 1; i >= 0; i--) {
					Action action = planList.get(i);
					if (action != null) {
						currentPlan.push(action);
					}
				}
			} else {
				System.out.println("No path found!");
			}
		} catch (Exception e) {
			System.out.println("Error in generatePlan: " + e.getMessage());
			e.printStackTrace();
			currentPlan.clear(); // Make sure we don't have a corrupted plan
		}
	}

	/**
	 * Updates the robot's knowledge of the environment. Records visited positions,
	 * discovered tiles, and inventory changes.
	 */
	private void updateKnowledge() {
		try {
			// Update what we know about the environment from our current position
			Position robotPos = env.getRobotPosition(this);
			if (robotPos == null)
				return;

			Map<String, Tile> neighborTiles = env.getNeighborTiles(this);
			Map<String, Position> neighborPositions = env.getNeighborPositions(robotPos);

			if (neighborTiles == null || neighborPositions == null)
				return;

			// Record our current position as visited
			visitedPositions.add(robotPos);

			// Update known tiles with neighbor information
			for (Map.Entry<String, Position> entry : neighborPositions.entrySet()) {
				String direction = entry.getKey();
				Position neighborPos = entry.getValue();

				if (direction != null && neighborPos != null && neighborTiles.containsKey(direction)) {
					Tile tile = neighborTiles.get(direction);
					if (tile != null) {
						knownTiles.put(neighborPos, tile.getStatus());
					}
				}
			}

			// Update inventory based on robot holdings
			try {
				inventory.clear();
				ArrayList<String> holdings = env.getRobotHoldings(this);
				if (holdings != null) {
					for (String item : holdings) {
						if (item == null)
							continue;

						inventory.add(item);
						switch (item) {
						case "KEY_BLUE":
							collectedItems.add(TileStatus.KEY_BLUE);
							break;
						case "KEY_GREEN":
							collectedItems.add(TileStatus.KEY_GREEN);
							break;
						case "KEY_RED":
							collectedItems.add(TileStatus.KEY_RED);
							break;
						case "KEY_YELLOW":
							collectedItems.add(TileStatus.KEY_YELLOW);
							break;
						}
					}
				}
			} catch (Exception e) {
				System.out.println("Error updating inventory: " + e.getMessage());
			}
		} catch (Exception e) {
			System.out.println("Error in updateKnowledge: " + e.getMessage());
		}
	}

	private Position findNearestAccessibleTarget(Position start, List<Position> targets) {
		if (start == null || targets == null || targets.isEmpty()) {
			return null;
		}

		try {
			Position nearest = null;
			int shortestPathLength = Integer.MAX_VALUE;

			for (Position target : targets) {
				if (target == null)
					continue;

				List<Action> pathToTarget = planPath(start, target);
				// Only consider this target if we actually found a path to it
				if (pathToTarget != null && !pathToTarget.isEmpty() && pathToTarget.size() < shortestPathLength) {
					shortestPathLength = pathToTarget.size();
					nearest = target;
				}
			}

			return nearest;
		} catch (Exception e) {
			System.out.println("Error in findNearestAccessibleTarget: " + e.getMessage());
			return null;
		}
	}

	private List<Action> planPath(Position start, Position goal) {
		// A* pathfinding algorithm
		if (start == null || goal == null) {
			return new ArrayList<>(); // Return empty list if inputs are invalid
		}

		try {
			PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingInt(n -> n.fScore));
			Map<Position, Node> nodeMap = new HashMap<>();
			Set<Position> closedSet = new HashSet<>();

			// Get tiles data that we know
			Map<Position, TileStatus> tilesToUse = new HashMap<>(knownTiles); // Start with what we know

			// Try to get more complete data if possible
			try {
				Map<Position, Tile> allTiles = env.getTiles();
				if (allTiles != null) {
					for (Map.Entry<Position, Tile> entry : allTiles.entrySet()) {
						Position pos = entry.getKey();
						Tile tile = entry.getValue();
						if (tile != null) {
							tilesToUse.put(pos, tile.getStatus());
						}
					}
				}
			} catch (Exception e) {
				System.out.println("Error getting complete tiles data: " + e.getMessage());
				// Continue with what we know
			}

			Node startNode = new Node(start);
			startNode.gScore = 0;
			startNode.fScore = estimateDistance(start, goal);

			openSet.add(startNode);
			nodeMap.put(start, startNode);

			int iterations = 0;
			final int MAX_ITERATIONS = 1000; // Safety limit

			while (!openSet.isEmpty() && iterations < MAX_ITERATIONS) {
				iterations++;
				Node current = openSet.poll();

				if (current.position.equals(goal)) {
					System.out.println("Path found in " + iterations + " iterations");
					return reconstructPath(current);
				}

				closedSet.add(current.position);

				Map<String, Position> neighbors = null;
				try {
					neighbors = env.getNeighborPositions(current.position);
				} catch (Exception e) {
					System.out.println("Error getting neighbors: " + e.getMessage());
					continue;
				}

				if (neighbors == null)
					continue; // Skip if no neighbors

				for (Map.Entry<String, Position> entry : neighbors.entrySet()) {
					String direction = entry.getKey();
					Position neighborPos = entry.getValue();

					if (direction == null || neighborPos == null)
						continue;

					// Skip if we've already processed this position
					if (closedSet.contains(neighborPos)) {
						continue;
					}

					// Check if the position is passable
					TileStatus neighborStatus = tilesToUse.get(neighborPos);

					// If we don't know about this tile yet, assume it's passable
					if (neighborStatus == null) {
						neighborStatus = TileStatus.BLANK;
					}

					if (!isPassable(neighborStatus)) {
						continue;
					}

					// Calculate tentative g score
					int tentativeGScore = current.gScore + 1;

					// Get or create neighbor node
					Node neighbor = nodeMap.getOrDefault(neighborPos, new Node(neighborPos));

					if (tentativeGScore < neighbor.gScore) {
						// This is a better path
						neighbor.parent = current;
						neighbor.direction = direction;
						neighbor.gScore = tentativeGScore;
						neighbor.fScore = tentativeGScore + estimateDistance(neighborPos, goal);

						nodeMap.put(neighborPos, neighbor);

						// Remove and re-add to update its position in the priority queue
						openSet.remove(neighbor);
						openSet.add(neighbor);
					}
				}
			}

			if (iterations >= MAX_ITERATIONS) {
				System.out.println("Path planning reached iteration limit!");
			}

			// If we reach here, no path was found
			return new ArrayList<>();
		} catch (Exception e) {
			System.out.println("Error in planPath: " + e.getMessage());
			e.printStackTrace();
			return new ArrayList<>();
		}
	}

	private int estimateDistance(Position a, Position b) {
		if (a == null || b == null)
			return Integer.MAX_VALUE;

		// Manhattan distance
		try {
			return Math.abs(a.getRow() - b.getRow()) + Math.abs(a.getCol() - b.getCol());
		} catch (Exception e) {
			System.out.println("Error in estimateDistance: " + e.getMessage());
			return Integer.MAX_VALUE;
		}
	}

	private List<Action> reconstructPath(Node goalNode) {
		List<Action> actions = new ArrayList<>();

		try {
			if (goalNode == null)
				return actions; // Return empty list if goalNode is null

			Node current = goalNode;
			while (current != null && current.parent != null) {
				if (current.direction == null) {
					// If direction is null, skip this node
					current = current.parent;
					continue;
				}

				Action action = getActionForDirection(current.direction);
				if (action != null && action != Action.DO_NOTHING) {
					actions.add(0, action);
				}

				current = current.parent;
			}
		} catch (Exception e) {
			System.out.println("Error in reconstructPath: " + e.getMessage());
			// Return whatever path we've built so far
		}

		return actions;
	}

// ********************** DOOR HANDLING ***********************
// Improved method to handle door opening
// Add this method to actively search for doors when stuck
	private Action findAndOpenDoors(Position currentPos) {
		try {
			// First check if we have any keys
			ArrayList<String> holdings = env.getRobotHoldings(this);
			if (holdings == null || holdings.isEmpty()) {
				System.out.println("No keys to use, can't search for doors");
				return null;
			}

			System.out.println("Looking for doors to open with keys: " + holdings);

			// Check which doors we can open
			boolean hasBlueKey = holdings.contains("KEY_BLUE");
			boolean hasGreenKey = holdings.contains("KEY_GREEN");
			boolean hasRedKey = holdings.contains("KEY_RED");
			boolean hasYellowKey = holdings.contains("KEY_YELLOW");

			// List to store door positions
			List<Position> accessibleDoors = new ArrayList<>();

			// Find corresponding doors in the environment
			Map<TileStatus, ArrayList<Position>> envPositions = env.getEnvironmentPositions();
			if (envPositions != null) {
				// Add only doors we can open
				if (hasBlueKey && envPositions.containsKey(TileStatus.DOOR_BLUE)) {
					accessibleDoors.addAll(envPositions.get(TileStatus.DOOR_BLUE));
				}
				if (hasGreenKey && envPositions.containsKey(TileStatus.DOOR_GREEN)) {
					accessibleDoors.addAll(envPositions.get(TileStatus.DOOR_GREEN));
				}
				if (hasRedKey && envPositions.containsKey(TileStatus.DOOR_RED)) {
					accessibleDoors.addAll(envPositions.get(TileStatus.DOOR_RED));
				}
				if (hasYellowKey && envPositions.containsKey(TileStatus.DOOR_YELLOW)) {
					accessibleDoors.addAll(envPositions.get(TileStatus.DOOR_YELLOW));
				}
			}

			System.out.println("Found " + accessibleDoors.size() + " doors we can open");

			if (!accessibleDoors.isEmpty()) {
				// First try to find any door we haven't visited yet
				for (Position doorPos : accessibleDoors) {
					if (!visitedPositions.contains(doorPos)) {
						System.out.println("Moving toward unvisited door at " + doorPos);
						// Try to find a path to area near the door
						return moveTowardPosition(currentPos, doorPos);
					}
				}

				// If all doors are visited, try the least visited one
				Position leastVisitedDoor = null;
				int minVisits = Integer.MAX_VALUE;

				for (Position doorPos : accessibleDoors) {
					int visits = 0;
					for (Position visited : visitedPositions) {
						if (visited.equals(doorPos)) {
							visits++;
						}
					}

					if (visits < minVisits) {
						minVisits = visits;
						leastVisitedDoor = doorPos;
					}
				}

				if (leastVisitedDoor != null) {
					System.out.println("Moving toward least visited door at " + leastVisitedDoor);
					return moveTowardPosition(currentPos, leastVisitedDoor);
				}
			}

			return null;
		} catch (Exception e) {
			System.out.println("Error in findAndOpenDoors: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Plans and executes movement toward a specific target position.
	 *
	 * @param currentPos The robot's current position
	 * @param targetPos  The position to move toward
	 * @return An action moving toward the target
	 */
	private Action moveTowardPosition(Position currentPos, Position targetPos) {
		try {
			// First try different pathing approaches

			// 1. Try standard A* first
			generatePlan(currentPos, targetPos);
			if (!currentPlan.isEmpty()) {
				System.out.println("Following plan to target, next action: " + currentPlan.peek());
				return currentPlan.pop();
			}

			// 2. Try going to adjacent tile if target is unreachable
			Map<Position, Integer> adjacentPositions = findAdjacentPositions(targetPos);
			if (!adjacentPositions.isEmpty()) {
				System.out.println("Target directly unreachable, trying adjacent positions");
				Position bestAdjacent = null;
				int shortestPath = Integer.MAX_VALUE;

				for (Map.Entry<Position, Integer> entry : adjacentPositions.entrySet()) {
					Position adjPos = entry.getKey();
					List<Action> path = planPath(currentPos, adjPos);
					if (path != null && !path.isEmpty() && path.size() < shortestPath) {
						shortestPath = path.size();
						bestAdjacent = adjPos;
					}
				}

				if (bestAdjacent != null) {
					System.out.println("Found path to adjacent position: " + bestAdjacent);
					generatePlan(currentPos, bestAdjacent);
					if (!currentPlan.isEmpty()) {
						return currentPlan.pop();
					}
				}
			}

			// 3. Try basic direction-based movement
			Map<String, Position> neighbors = env.getNeighborPositions(currentPos);
			Map<String, Tile> neighborTiles = env.getNeighborTiles(this);

			if (neighbors != null && neighborTiles != null) {
				String bestDirection = null;
				int bestDistance = Integer.MAX_VALUE;

				for (Map.Entry<String, Position> entry : neighbors.entrySet()) {
					String direction = entry.getKey();
					Position neighborPos = entry.getValue();

					if (neighborPos != null && neighborTiles.containsKey(direction)) {
						Tile neighborTile = neighborTiles.get(direction);
						if (neighborTile != null && isPassable(neighborTile.getStatus())) {
							int distance = estimateDistance(neighborPos, targetPos);

							// Avoid positions we've recently visited to prevent loops
							boolean recentlyVisited = false;
							if (recentPositions.size() > 3) {
								for (int i = recentPositions.size() - 3; i < recentPositions.size(); i++) {
									if (recentPositions.get(i).equals(neighborPos)) {
										recentlyVisited = true;
										break;
									}
								}
							}

							if (!recentlyVisited && distance < bestDistance) {
								bestDistance = distance;
								bestDirection = direction;
							}
						}
					}
				}

				if (bestDirection != null) {
					System.out.println("Moving " + bestDirection + " toward target");
					return getActionForDirection(bestDirection);
				}
			}

			// 4. Try exploration as a last resort
			return exploreEnvironment(currentPos);
		} catch (Exception e) {
			System.out.println("Error in moveTowardPosition: " + e.getMessage());
			return exploreEnvironment(currentPos);
		}
	}

// Helper method to find passable positions adjacent to a target
	private Map<Position, Integer> findAdjacentPositions(Position targetPos) {
		Map<Position, Integer> adjacentPositions = new HashMap<>();

		try {
			// Get positions around the target
			Map<Position, TileStatus> knownTilesAround = new HashMap<>();

			// Try to get data from environment
			try {
				Map<Position, Tile> allTiles = env.getTiles();
				if (allTiles != null) {
					for (Map.Entry<Position, Tile> entry : allTiles.entrySet()) {
						Position pos = entry.getKey();
						Tile tile = entry.getValue();
						if (tile != null) {
							knownTilesAround.put(pos, tile.getStatus());
						}
					}
				}
			} catch (Exception e) {
				System.out.println("Error getting tiles around target: " + e.getMessage());
				// Continue with what we have in knownTiles
				knownTilesAround.putAll(knownTiles);
			}

			// Check positions in 4 directions (can extend this to 8 if needed)
			int row = targetPos.getRow();
			int col = targetPos.getCol();

			checkAndAddAdjacentPosition(knownTilesAround, adjacentPositions, new Position(row - 1, col), targetPos); // Above
			checkAndAddAdjacentPosition(knownTilesAround, adjacentPositions, new Position(row + 1, col), targetPos); // Below
			checkAndAddAdjacentPosition(knownTilesAround, adjacentPositions, new Position(row, col - 1), targetPos); // Left
			checkAndAddAdjacentPosition(knownTilesAround, adjacentPositions, new Position(row, col + 1), targetPos); // Right

			return adjacentPositions;
		} catch (Exception e) {
			System.out.println("Error in findAdjacentPositions: " + e.getMessage());
			return adjacentPositions;
		}
	}

// Helper to check if a position is passable and add it to adjacent positions
	private void checkAndAddAdjacentPosition(Map<Position, TileStatus> knownTilesAround,
			Map<Position, Integer> adjacentPositions, Position pos, Position targetPos) {
		try {
			if (knownTilesAround.containsKey(pos)) {
				TileStatus status = knownTilesAround.get(pos);
				if (isPassable(status)) {
					adjacentPositions.put(pos, estimateDistance(pos, targetPos));
				}
			} else {
				// If we don't know about this position, assume it's passable (optimistic)
				adjacentPositions.put(pos, estimateDistance(pos, targetPos));
			}
		} catch (Exception e) {
			System.out.println("Error checking adjacent position: " + e.getMessage());
		}
	}

	/**
	 * Inner class representing a node in the A* pathfinding algorithm.
	 */
	private static class Node {
		Position position;
		Node parent;
		String direction;
		int gScore = Integer.MAX_VALUE;
		int fScore = Integer.MAX_VALUE;

		/**
		 * Constructs a new Node for pathfinding.
		 *
		 * @param position The position this node represents
		 */
		Node(Position position) {
			this.position = position;
			this.parent = null;
			this.direction = null;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null || getClass() != obj.getClass())
				return false;
			Node node = (Node) obj;
			return position != null && position.equals(node.position);
		}

		@Override
		public int hashCode() {
			return position != null ? position.hashCode() : 0;
		}
	}

	@Override
	public String toString() {
		return "Robot [pos=" + env.getRobotPosition(this) + "]";
	}
}