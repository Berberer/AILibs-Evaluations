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
import jaicore.search.algorithms.standard.core.ORGraphSearch;
import jaicore.search.algorithms.standard.mcts.IPathUpdatablePolicy;
import jaicore.search.algorithms.standard.mcts.IPolicy;
import jaicore.search.algorithms.standard.mcts.MCTS;
import jaicore.search.algorithms.standard.mcts.UCBPolicy;
import jaicore.search.algorithms.standard.mcts.UniformRandomPolicy;
import jaicore.search.algorithms.standard.uncertainty.BasicUncertaintySource;
import jaicore.search.algorithms.standard.uncertainty.UncertaintyRandomCompletionEvaluator;
import jaicore.search.algorithms.standard.uncertainty.explorationexploitationsearch.BasicClockModelPhaseLengthAdjuster;
import jaicore.search.algorithms.standard.uncertainty.explorationexploitationsearch.BasicExplorationCandidateSelector;
import jaicore.search.algorithms.standard.uncertainty.explorationexploitationsearch.UncertaintyExplorationOpenSelection;
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
				IPathUnification<KnapsackNode> pathUnification = new IPathUnification<KnapsackNode>() {
					@Override
					public List<KnapsackNode> getSubsumingKnownPathCompletion(
							Map<List<KnapsackNode>, List<KnapsackNode>> knownPathCompletions,
							List<KnapsackNode> path) throws InterruptedException {
						return null;
					}
				};
				RandomCompletionEvaluator<KnapsackNode, Double> randomCompletionEvaluator = new RandomCompletionEvaluator<>(
					new Random(seed),
					3,
					pathUnification,
					knapsackProblem.getSolutionEvaluator()
				);
				
				// Calculate experiment score
				double score = Double.MAX_VALUE;
				switch (algorithmName) {
					case "two-phase":
						ORGraphSearch<KnapsackNode, String, Double> twoPhaseSearch = new ORGraphSearch<>(
							knapsackProblem.getGraphGenerator(),
							new UncertaintyRandomCompletionEvaluator<>(new Random(seed), 3, pathUnification, knapsackProblem.getSolutionEvaluator(), new BasicUncertaintySource<>())
						);
						twoPhaseSearch.setOpen(new UncertaintyExplorationOpenSelection<KnapsackNode, Double>(
							timeout * 1000, 50, 0.1, 0.1,
							new BasicClockModelPhaseLengthAdjuster(),
							(solution1, solution2) -> {
								double intersectionSize = 0.0d;
								for (String s : solution1.getPackedObjects()) {
									if (solution2.getPackedObjects().contains(s)) {
										intersectionSize++;
									}
								}
								HashSet<String> unionSet = new HashSet<>();
								unionSet.addAll(solution1.getPackedObjects());
								unionSet.addAll(solution2.getPackedObjects());
								double unionSize = (double) unionSet.size();
								return (unionSize - intersectionSize) / unionSize;
							},
							new BasicExplorationCandidateSelector<>(0.25d))
						);
						long twoPhaseEnd = System.currentTimeMillis() + timeout * 1000;
						List<KnapsackNode> twoPhaseSolution = twoPhaseSearch.nextSolution();
						while (twoPhaseSolution != null && System.currentTimeMillis() < twoPhaseEnd) {
							Double solutionScore = knapsackProblem.getSolutionEvaluator().evaluateSolution(twoPhaseSolution);
							if (score > solutionScore ) {
								score = solutionScore;
							}
							twoPhaseSolution = twoPhaseSearch.nextSolution();
						}
						break;
					case "pareto":
						// TODO: Add pareto search
						break;
					case "awa-star":
						AwaStarSearch<KnapsackNode, String, Double> awaStarSearch;
						try {
							awaStarSearch = new AwaStarSearch<>(knapsackProblem.getGraphGenerator(), randomCompletionEvaluator, knapsackProblem.getSolutionEvaluator());
							List<Node<KnapsackNode, Double>> awaStarSolution = awaStarSearch.search(timeout);
							List<KnapsackNode> solutionPath = new ArrayList<>();
							awaStarSolution.forEach(n -> solutionPath.add(n.getPoint()));
							score = knapsackProblem.getSolutionEvaluator().evaluateSolution(solutionPath);
						} catch (Throwable e) {
							e.printStackTrace();
						}
						break;
					case "r-star":
						// TODO: Add R* search
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
						long mctsEnd = System.currentTimeMillis() + timeout * 1000;
						List<KnapsackNode> mctsSolution = mctsSearch.nextSolution();
						while (mctsSolution != null && System.currentTimeMillis() < mctsEnd) {
							Double solutionScore = knapsackProblem.getSolutionEvaluator().evaluateSolution(mctsSolution);
							if (score > solutionScore ) {
								score = solutionScore;
							}
							mctsSolution = mctsSearch.nextSolution();
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
