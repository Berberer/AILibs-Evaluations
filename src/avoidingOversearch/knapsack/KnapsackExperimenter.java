package avoidingOversearch.knapsack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
import jaicore.search.testproblems.knapsack.KnapsackProblem;
import jaicore.search.testproblems.knapsack.KnapsackProblem.KnapsackNode;
import jaicore.search.testproblems.knapsack.KnapsackToGraphSearchProblemInputReducer;

public class KnapsackExperimenter {

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
				KnapsackProblem knapsack = KnapsackProblem.createRandomProblem(problemSize, seed);
				IGraphSearch<?, ?, KnapsackNode, String, Double, ?, ?> algorithm = null;
				Double score = null;

				// Configure and create algorithm
				RandomCompletionBasedNodeEvaluator<KnapsackNode, Double> nodeEvaluator = new RandomCompletionBasedNodeEvaluator<>(
						new Random(seed), 3, knapsack.getSolutionEvaluator());
				nodeEvaluator.setGenerator(knapsack.getGraphGenerator());
				nodeEvaluator.setUncertaintySource(new BasicUncertaintySource<>());
				switch (algorithmName) {
				case "two_phase":
					OversearchAvoidanceConfig<KnapsackNode, Double> switchConfig = new OversearchAvoidanceConfig<>(
							OversearchAvoidanceMode.TWO_PHASE_SELECTION, seed);
					switchConfig.setExploitationScoreThreshold(0.1);
					switchConfig.setExplorationUncertaintyThreshold(0.1);
					switchConfig.setInterval(50);
					switchConfig.setMinimumSolutionDistanceForExploration(0.75d);
					switchConfig.setSolutionDistanceMetric((solution1, solution2) -> {
						double intersectionSize = 0.0d;
						List<String> items1 = solution1.get(solution1.size() - 1).getPackedObjects();
						List<String> items2 = solution2.get(solution2.size() - 1).getPackedObjects();
						for (String s : items1) {
							if (items2.contains(s)) {
								intersectionSize++;
							}
						}
						Set<String> unionSet = new HashSet<>();
						unionSet.addAll(items1);
						unionSet.addAll(items2);
						double unionSize = (double) unionSet.size();
						return (unionSize - intersectionSize) / unionSize;
					});
					switchConfig.activateDynamicPhaseLengthsAdjustment(timeout);
					UncertaintyORGraphSearchFactory<KnapsackNode, String, Double> switchFactory = new UncertaintyORGraphSearchFactory<>();
					switchFactory.setConfig(switchConfig);
					switchFactory.setProblemInput(
							new UncertainlyEvaluatedTraversalTree<>(knapsack.getGraphGenerator(), nodeEvaluator));
					switchFactory.setTimeoutForFComputation(25000, n -> Double.MAX_VALUE);
					break;
				case "pareto":
					OversearchAvoidanceConfig<KnapsackNode, Double> paretoConfig = new OversearchAvoidanceConfig<>(
							OversearchAvoidanceMode.PARETO_FRONT_SELECTION, seed);
					paretoConfig.setParetoComperator(
							new CosinusDistanceComparator<>(-1.0 * knapsack.getKnapsackCapacity(), 1.0d));
					UncertaintyORGraphSearchFactory<KnapsackNode, String, Double> paretoFactory = new UncertaintyORGraphSearchFactory<>();
					paretoFactory.setConfig(paretoConfig);
					paretoFactory.setProblemInput(new UncertainlyEvaluatedTraversalTree<KnapsackNode, String, Double>(
							knapsack.getGraphGenerator(), nodeEvaluator));
					paretoFactory.setTimeoutForFComputation(5000, n -> Double.MAX_VALUE);
					algorithm = paretoFactory.getAlgorithm();
					break;
				case "awa_star":
					AWAStarFactory<GeneralEvaluatedTraversalTree<KnapsackNode, String, Double>, KnapsackNode, String, Double> awaStarFactory = new AWAStarFactory<>();
					awaStarFactory.setProblemInput(
							new GeneralEvaluatedTraversalTree<>(knapsack.getGraphGenerator(), nodeEvaluator));
					algorithm = awaStarFactory.getAlgorithm();
					break;
				case "mcts":
					UCTFactory<KnapsackNode, String> mctsFactory = new UCTFactory<>();
					mctsFactory.setDefaultPolicy(new UniformRandomPolicy<>(new Random(seed)));
					mctsFactory.setTreePolicy(new UCBPolicy<>(false));
					mctsFactory.setProblemInput(new KnapsackToGraphSearchProblemInputReducer().transform(knapsack));
					mctsFactory.setSeed(seed);
					algorithm = mctsFactory.getAlgorithm();
					break;
				case "best_first":
					BestFirstFactory<GeneralEvaluatedTraversalTree<KnapsackNode, String, Double>, KnapsackNode, String, Double> bestFirstFactory = new BestFirstFactory<>();
					bestFirstFactory.setProblemInput(
							new GeneralEvaluatedTraversalTree<>(knapsack.getGraphGenerator(), nodeEvaluator));
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
