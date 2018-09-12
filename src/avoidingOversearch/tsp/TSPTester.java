package avoidingOversearch.tsp;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jaicore.search.algorithms.standard.awastar.AWAStarFactory;
import jaicore.search.algorithms.standard.bestfirst.BestFirstFactory;
import jaicore.search.algorithms.standard.bestfirst.nodeevaluation.IUncertaintyAnnotatingNodeEvaluator;
import jaicore.search.algorithms.standard.bestfirst.nodeevaluation.RandomCompletionBasedNodeEvaluator;
import jaicore.search.algorithms.standard.mcts.UCBPolicy;
import jaicore.search.algorithms.standard.mcts.UCT;
import jaicore.search.algorithms.standard.mcts.UCTFactory;
import jaicore.search.algorithms.standard.mcts.UniformRandomPolicy;
import jaicore.search.algorithms.standard.uncertainty.BasicUncertaintySource;
import jaicore.search.algorithms.standard.uncertainty.OversearchAvoidanceConfig;
import jaicore.search.algorithms.standard.uncertainty.OversearchAvoidanceConfig.OversearchAvoidanceMode;
import jaicore.search.algorithms.standard.uncertainty.UncertaintyORGraphSearchFactory;
import jaicore.search.algorithms.standard.uncertainty.paretosearch.CosinusDistanceComparator;
import jaicore.search.core.interfaces.IGraphSearch;
import jaicore.search.model.probleminputs.GeneralEvaluatedTraversalTree;
import jaicore.search.model.probleminputs.UncertainlyEvaluatedTraversalTree;
import jaicore.search.testproblems.enhancedttsp.EnhancedTTSP;
import jaicore.search.testproblems.enhancedttsp.EnhancedTTSPNode;
import jaicore.search.testproblems.enhancedttsp.EnhancedTTSPToGraphSearchProblemInputReducer;

public class TSPTester {
	public static void main(String[] args) throws Exception {
		int seed = 1;

		int problemSize = 1000;
		int timeout = 60;
		System.out.println("Generating problem ... ");
		EnhancedTTSP tsp = EnhancedTTSP.createRandomProblem(problemSize, seed);

		System.out.println("Testing MCTS ...");
		Double mctsScore = testMCTS(tsp, timeout, seed);
		if (Thread.currentThread().isInterrupted())
			throw new IllegalStateException("Executing thread is interrupted, which must not be the case!");
		System.out.println("MCTS-Score: " + mctsScore);

		System.out.println("Testing Awa* ...");
		Double awaStarScore = testAwaStar(tsp, timeout, seed);
		if (Thread.currentThread().isInterrupted())
			throw new IllegalStateException("Executing thread is interrupted, which must not be the case!");
		System.out.println("Awa*-Score: " + awaStarScore);

		System.out.println("Testing Best-First ...");
		Double bestFirstScore = testBestFirst(tsp, timeout, seed);
		if (Thread.currentThread().isInterrupted())
			throw new IllegalStateException("Executing thread is interrupted, which must not be the case!");
		System.out.println("Best-First-Score: " + bestFirstScore);

		System.out.println("Testing Switch ...");
		Double switchScore = testSwitch(tsp, timeout, seed);
		if (Thread.currentThread().isInterrupted())
			throw new IllegalStateException("Executing thread is interrupted, which must not be the case!");
		System.out.println("Switch-Score: " + switchScore);

		System.out.println("Testing Pareto ...");
		Double paretoScore = testPareto(tsp, timeout, seed);
		if (Thread.currentThread().isInterrupted())
			throw new IllegalStateException("Executing thread is interrupted, which must not be the case!");
		System.out.println("Pareto-Score: " + paretoScore);
	}

	private static Double testAlgorithm(IGraphSearch<?, ?, EnhancedTTSPNode, String, Double, ?, ?> algorithm,
			int timeout) throws Exception {
		algorithm.setTimeout(timeout * 1000, TimeUnit.MILLISECONDS);
		try {
			algorithm.call();
		} catch (TimeoutException e) {
			System.out.println("algorithm finished with timeout exception, which is ok.");
		}
		return (algorithm.getBestSeenSolution() != null) ? algorithm.getBestSeenSolution().getScore() : null;
	}

	private static Double testMCTS(EnhancedTTSP tsp, int timeout, int seed) throws Exception {
		UCTFactory<EnhancedTTSPNode, String> mctsFactory = new UCTFactory<>();
		mctsFactory.setDefaultPolicy(new UniformRandomPolicy<>(new Random(seed)));
		mctsFactory.setTreePolicy(new UCBPolicy<>(false));
		mctsFactory.setProblemInput(new EnhancedTTSPToGraphSearchProblemInputReducer().transform(tsp));
		mctsFactory.setSeed(seed);
		UCT<EnhancedTTSPNode, String> mcts = mctsFactory.getAlgorithm();
		System.out.println("Running " + mcts + " for " + timeout + "s");
		return testAlgorithm(mcts, timeout);
	}

	private static Double testBestFirst(EnhancedTTSP tsp, int timeout, int seed) throws Exception {
		RandomCompletionBasedNodeEvaluator<EnhancedTTSPNode, Double> nodeEvaluator = new RandomCompletionBasedNodeEvaluator<>(
				new Random(seed), 3, tsp.getSolutionEvaluator());
		BestFirstFactory<GeneralEvaluatedTraversalTree<EnhancedTTSPNode, String, Double>, EnhancedTTSPNode, String, Double> bestFirstFactory = new BestFirstFactory<>();
		bestFirstFactory.setProblemInput(new GeneralEvaluatedTraversalTree<>(tsp.getGraphGenerator(), nodeEvaluator));
		return testAlgorithm(bestFirstFactory.getAlgorithm(), timeout);
	}

	private static Double testAwaStar(EnhancedTTSP tsp, int timeout, int seed) throws Exception {
		RandomCompletionBasedNodeEvaluator<EnhancedTTSPNode, Double> nodeEvaluator = new RandomCompletionBasedNodeEvaluator<>(
				new Random(seed), 3, tsp.getSolutionEvaluator());
		AWAStarFactory<GeneralEvaluatedTraversalTree<EnhancedTTSPNode, String, Double>, EnhancedTTSPNode, String, Double> awaStarFactory = new AWAStarFactory<>();
		nodeEvaluator.setGenerator(tsp.getGraphGenerator());
		awaStarFactory.setProblemInput(new GeneralEvaluatedTraversalTree<>(tsp.getGraphGenerator(), nodeEvaluator));
		return testAlgorithm(awaStarFactory.getAlgorithm(), timeout);
	}

	private static Double testPareto(EnhancedTTSP tsp, int timeout, int seed) throws Exception {
		OversearchAvoidanceConfig<EnhancedTTSPNode, Double> paretoConfig = new OversearchAvoidanceConfig<>(
				OversearchAvoidanceMode.PARETO_FRONT_SELECTION, seed);
		paretoConfig.setParetoComperator(new CosinusDistanceComparator<>(2880.0d, 1.0d));
		UncertaintyORGraphSearchFactory<EnhancedTTSPNode, String, Double> paretoFactory = new UncertaintyORGraphSearchFactory<>();
		paretoFactory.setConfig(paretoConfig);
		IUncertaintyAnnotatingNodeEvaluator<EnhancedTTSPNode, Double> nodeEvaluator = new RandomCompletionBasedNodeEvaluator<>(
				new Random(seed), 3, tsp.getSolutionEvaluator());
		nodeEvaluator.setUncertaintySource(new BasicUncertaintySource<>());
		paretoFactory.setProblemInput(new UncertainlyEvaluatedTraversalTree<EnhancedTTSPNode, String, Double>(
				tsp.getGraphGenerator(), nodeEvaluator));
		paretoFactory.setTimeoutForFComputation(5000, n -> Double.MAX_VALUE);
		return testAlgorithm(paretoFactory.getAlgorithm(), timeout);
	}

	private static Double testSwitch(EnhancedTTSP tsp, int timeout, int seed) throws Exception {
		OversearchAvoidanceConfig<EnhancedTTSPNode, Double> switchConfig = new OversearchAvoidanceConfig<>(
				OversearchAvoidanceMode.TWO_PHASE_SELECTION, seed);
		switchConfig.setExploitationScoreThreshold(0.1);
		switchConfig.setExplorationUncertaintyThreshold(0.1);
		switchConfig.setInterval(50);
		switchConfig.setMinimumSolutionDistanceForExploration(0.75d);
		switchConfig.setSolutionDistanceMetric((solution1, solution2) -> {
			int minLength = Math.min(solution1.size(), solution2.size());
			int commonPathLength = 0;
			for (int i = 0; i < minLength; i++) {
				if (solution1.get(i).getCurLocation() == solution2.get(i).getCurLocation()) {
					commonPathLength++;
				} else {
					break;
				}
			}
			return ((double) minLength - commonPathLength) / ((double) minLength);
		});
		switchConfig.activateDynamicPhaseLengthsAdjustment(timeout);
		UncertaintyORGraphSearchFactory<EnhancedTTSPNode, String, Double> switchFactory = new UncertaintyORGraphSearchFactory<>();
		switchFactory.setConfig(switchConfig);
		IUncertaintyAnnotatingNodeEvaluator<EnhancedTTSPNode, Double> nodeEvaluator = new RandomCompletionBasedNodeEvaluator<>(
				new Random(seed), 3, tsp.getSolutionEvaluator());
		nodeEvaluator.setUncertaintySource(new BasicUncertaintySource<>());
		switchFactory.setProblemInput(new UncertainlyEvaluatedTraversalTree<>(tsp.getGraphGenerator(), nodeEvaluator));
		switchFactory.setTimeoutForFComputation(25000, n -> Double.MAX_VALUE);
		return testAlgorithm(switchFactory.getAlgorithm(), timeout);
	}
}
