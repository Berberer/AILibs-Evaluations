package avoidingOversearch.knapsack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.aeonbits.owner.ConfigCache;

import jaicore.basic.SQLAdapter;
import jaicore.experiments.ExperimentDBEntry;
import jaicore.experiments.ExperimentRunner;
import jaicore.experiments.IExperimentIntermediateResultProcessor;
import jaicore.experiments.IExperimentSetConfig;
import jaicore.experiments.IExperimentSetEvaluator;
import jaicore.search.algorithms.interfaces.IPathUnification;
import jaicore.search.algorithms.standard.awastar.AwaStarSearch;
import jaicore.search.algorithms.standard.bestfirst.RandomCompletionEvaluator;
import jaicore.search.algorithms.standard.mcts.IPathUpdatablePolicy;
import jaicore.search.algorithms.standard.mcts.IPolicy;
import jaicore.search.algorithms.standard.mcts.MCTS;
import jaicore.search.algorithms.standard.mcts.UCBPolicy;
import jaicore.search.algorithms.standard.mcts.UniformRandomPolicy;
import jaicore.search.evaluationproblems.KnapsackProblem;
import jaicore.search.evaluationproblems.KnapsackProblem.KnapsackNode;
import jaicore.search.structure.core.Node;

public class KnapsackExperimenter {

	public static void main(String[] args) {
		IExampleMCCConfig m = ConfigCache.getOrCreate(IExampleMCCConfig.class);
		if (m.getDatasetFolder() == null || !m.getDatasetFolder().exists())
			throw new IllegalArgumentException("config specifies invalid dataset folder " + m.getDatasetFolder());

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
				double problemSize = Double.valueOf(description.get("problem-size"));
				int timeout = Integer.valueOf(description.get("timeout"));
				KnapsackProblem knapsackProblem = createRandomKnapsackProblem(problemSize);
				RandomCompletionEvaluator<KnapsackNode, Double> randomCompletionEvaluator = new RandomCompletionEvaluator<>(
					new Random(seed),
					3,
					new IPathUnification<KnapsackNode>() {
						@Override
						public List<KnapsackNode> getSubsumingKnownPathCompletion(
								Map<List<KnapsackNode>, List<KnapsackNode>> knownPathCompletions,
								List<KnapsackNode> path) throws InterruptedException {
							return null;
						}
					},
					knapsackProblem.getSolutionEvaluator()
				);
				
				// Calculate experiment score
				double score = Double.MAX_VALUE;
				switch (algorithmName) {
					case "two-phase":
						break;
					case "pareto":
						break;
					case "awa-star":
						AwaStarSearch<KnapsackNode, String, Double> awaStarSearch;
						try {
							awaStarSearch = new AwaStarSearch<>(knapsackProblem.getGraphGenerator(), randomCompletionEvaluator, knapsackProblem.getSolutionEvaluator());
							List<Node<KnapsackNode, Double>>solution = awaStarSearch.search(timeout);
							List<KnapsackNode> solutionPath = new ArrayList<>();
							solution.forEach(n -> solutionPath.add(n.getPoint()));
							score = knapsackProblem.getSolutionEvaluator().evaluateSolution(solutionPath);
						} catch (Throwable e) {
							e.printStackTrace();
						}
						break;
					case "r-star":
						break;
					case "mcts":
						IPolicy<KnapsackNode, String, Double> randomPolicy = new UniformRandomPolicy<>(new Random(seed));
						IPathUpdatablePolicy<KnapsackNode, String, Double> ucb = new UCBPolicy<>();
						
						MCTS<KnapsackNode, String, Double> mctsSearch = new MCTS<>(
							knapsackProblem.getGraphGenerator(),
							ucb,
							randomPolicy,
							n-> knapsackProblem.getSolutionEvaluator().evaluateSolution(Arrays.asList(n.getPoint()))
						);
						long t = System.currentTimeMillis();
						long end = t + timeout * 1000;
						List<KnapsackNode> solution = mctsSearch.nextSolution();
						while (solution != null && System.currentTimeMillis() < end) {
							Double solutionScore = knapsackProblem.getSolutionEvaluator().evaluateSolution(solution);
							if (score > solutionScore ) {
								score = solutionScore;
							}
							solution = mctsSearch.nextSolution();
						}
						break;
				}

				Map<String, Object> results = new HashMap<>();
				results.put("score", score);
				processor.processResults(results);
			}
		});
		runner.randomlyConductExperiments(true);
	}
	
	public static KnapsackProblem createRandomKnapsackProblem (double problemSize) {
		Random random = new Random((long) problemSize);
		int itemAmount = random.nextInt(((int) problemSize / 2)) + 5;
		HashSet<String> objects = new HashSet<>();
		HashMap<String, Double> values = new HashMap<>();
		HashMap<String, Double> weights = new HashMap<>();
		for (int i = 0; i < itemAmount; i++) {
			String key = String.valueOf(i);
			objects.add(key);
			weights.put(key, random.nextDouble() * problemSize);
			values.put(key, random.nextDouble());
		}
		int bonusCombinationAmount = random.nextInt(((int) problemSize / 10)) + 1;
		HashMap<Set<String>, Double> bonusPoints = new HashMap<>();
		for (int i = 0; i < bonusCombinationAmount; i++) {
			int combinationSize = random.nextInt(((int) itemAmount / 4)) + 2;
			HashSet<String> combination = new HashSet<>();
			for (int o = 0; o < combinationSize; o++) {
				combination.add(String.valueOf(random.nextInt(itemAmount)));
			}
			bonusPoints.put(combination, random.nextDouble());
		}
		return new KnapsackProblem(objects, values, weights, bonusPoints, problemSize);
	}

}
