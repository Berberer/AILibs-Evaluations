package avoidingOversearch.tsp;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.aeonbits.owner.ConfigCache;

import jaicore.basic.SQLAdapter;
import jaicore.experiments.ExperimentDBEntry;
import jaicore.experiments.ExperimentRunner;
import jaicore.experiments.IExperimentIntermediateResultProcessor;
import jaicore.experiments.IExperimentSetConfig;
import jaicore.experiments.IExperimentSetEvaluator;
import jaicore.search.algorithms.standard.awastar.AWAStarFactory;
import jaicore.search.algorithms.standard.bestfirst.BestFirstFactory;
import jaicore.search.algorithms.standard.bestfirst.nodeevaluation.RandomCompletionBasedNodeEvaluator;
import jaicore.search.algorithms.standard.mcts.UCBPolicy;
import jaicore.search.algorithms.standard.mcts.UCTFactory;
import jaicore.search.algorithms.standard.mcts.UniformRandomPolicy;
import jaicore.search.algorithms.standard.uncertainty.BasicUncertaintySource;
import jaicore.search.algorithms.standard.uncertainty.OversearchAvoidanceConfig;
import jaicore.search.algorithms.standard.uncertainty.UncertaintyORGraphSearchFactory;
import jaicore.search.algorithms.standard.uncertainty.OversearchAvoidanceConfig.OversearchAvoidanceMode;
import jaicore.search.algorithms.standard.uncertainty.paretosearch.CosinusDistanceComparator;
import jaicore.search.core.interfaces.IGraphSearch;
import jaicore.search.model.probleminputs.GeneralEvaluatedTraversalTree;
import jaicore.search.model.probleminputs.UncertainlyEvaluatedTraversalTree;
import jaicore.search.testproblems.enhancedttsp.EnhancedTTSP;
import jaicore.search.testproblems.enhancedttsp.EnhancedTTSPNode;
import jaicore.search.testproblems.enhancedttsp.EnhancedTTSPToGraphSearchProblemInputReducer;

public class TSPExperimenter {

	public static void main(String[] args) {
		IExampleMCCConfig m = ConfigCache.getOrCreate(IExampleMCCConfig.class);

		ExperimentRunner runner = new ExperimentRunner(new IExperimentSetEvaluator() {

			@Override
			public IExperimentSetConfig getConfig() {
				return m;
			}

			@Override
			public void evaluate(ExperimentDBEntry experimentEntry, SQLAdapter adapter,
					IExperimentIntermediateResultProcessor processor) throws Exception {

				// Get experiment setup
				Map<String, String> description = experimentEntry.getExperiment().getValuesOfKeyFields();
				String algorithmName = description.get("algorithm");
				int seed = Integer.valueOf(description.get("seed"));
				int problemSize = Integer.valueOf(description.get("problem_size"));
				int timeout = Integer.valueOf(description.get("timeout"));
				EnhancedTTSP tsp = EnhancedTTSP.createRandomProblem(problemSize, seed);
				IGraphSearch<?, ?, EnhancedTTSPNode, String, Double, ?, ?> algorithm = null;
				Double score = null;

				// Configure and create algorithm
				RandomCompletionBasedNodeEvaluator<EnhancedTTSPNode, Double> nodeEvaluator = new RandomCompletionBasedNodeEvaluator<>(
						new Random(seed), 3, tsp.getSolutionEvaluator());
				nodeEvaluator.setGenerator(tsp.getGraphGenerator());
				nodeEvaluator.setUncertaintySource(new BasicUncertaintySource<>());
				switch (algorithmName) {
				case "two_phase":
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
					switchFactory.setProblemInput(
							new UncertainlyEvaluatedTraversalTree<>(tsp.getGraphGenerator(), nodeEvaluator));
					switchFactory.setTimeoutForFComputation(25000, n -> Double.MAX_VALUE);
					break;
				case "pareto":
					OversearchAvoidanceConfig<EnhancedTTSPNode, Double> paretoConfig = new OversearchAvoidanceConfig<>(
							OversearchAvoidanceMode.PARETO_FRONT_SELECTION, seed);
					paretoConfig.setParetoComparator(new CosinusDistanceComparator<>(2880.0d, 1.0d));
					UncertaintyORGraphSearchFactory<EnhancedTTSPNode, String, Double> paretoFactory = new UncertaintyORGraphSearchFactory<>();
					paretoFactory.setConfig(paretoConfig);
					paretoFactory
							.setProblemInput(new UncertainlyEvaluatedTraversalTree<EnhancedTTSPNode, String, Double>(
									tsp.getGraphGenerator(), nodeEvaluator));
					paretoFactory.setTimeoutForFComputation(5000, n -> Double.MAX_VALUE);
					algorithm = paretoFactory.getAlgorithm();
					break;
				case "awa_star":
					AWAStarFactory<GeneralEvaluatedTraversalTree<EnhancedTTSPNode, String, Double>, EnhancedTTSPNode, String, Double> awaStarFactory = new AWAStarFactory<>();
					awaStarFactory.setProblemInput(
							new GeneralEvaluatedTraversalTree<>(tsp.getGraphGenerator(), nodeEvaluator));
					algorithm = awaStarFactory.getAlgorithm();
					break;
				case "mcts":
					UCTFactory<EnhancedTTSPNode, String> mctsFactory = new UCTFactory<>();
					mctsFactory.setDefaultPolicy(new UniformRandomPolicy<>(new Random(seed)));
					mctsFactory.setTreePolicy(new UCBPolicy<>(false));
					mctsFactory.setProblemInput(new EnhancedTTSPToGraphSearchProblemInputReducer().transform(tsp));
					mctsFactory.setSeed(seed);
					algorithm = mctsFactory.getAlgorithm();
					break;
				case "best_first":
					BestFirstFactory<GeneralEvaluatedTraversalTree<EnhancedTTSPNode, String, Double>, EnhancedTTSPNode, String, Double> bestFirstFactory = new BestFirstFactory<>();
					bestFirstFactory.setProblemInput(
							new GeneralEvaluatedTraversalTree<>(tsp.getGraphGenerator(), nodeEvaluator));
					algorithm = bestFirstFactory.getAlgorithm();
					break;
				}

				if (algorithm != null) {
					algorithm.setTimeout(timeout * 1000, TimeUnit.MILLISECONDS);
					try {
						algorithm.call();
					} catch (TimeoutException e) {
						System.out.println("algorithm finished with timeout exception, which is ok.");
					}
					score = (algorithm.getBestSeenSolution() != null) ? algorithm.getBestSeenSolution().getScore()
							: null;
				}

				System.out.println(algorithmName + ": " + score);
				Map<String, Object> results = new HashMap<>();
				results.put("score", score);
				processor.processResults(results);
			}
		});
		runner.randomlyConductExperiments(true);
	}

}
