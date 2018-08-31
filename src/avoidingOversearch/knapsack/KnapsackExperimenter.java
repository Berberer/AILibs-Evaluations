package avoidingOversearch.knapsack;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import org.aeonbits.owner.ConfigCache;

import avoidingOversearch.OurExperimentRunner;
import jaicore.basic.SQLAdapter;
import jaicore.experiments.ExperimentDBEntry;
import jaicore.experiments.ExperimentRunner;
import jaicore.experiments.IExperimentIntermediateResultProcessor;
import jaicore.experiments.IExperimentSetConfig;
import jaicore.experiments.IExperimentSetEvaluator;
import jaicore.search.algorithms.interfaces.IPathUnification;
import jaicore.search.algorithms.standard.awastar.AwaStarSearch;
import jaicore.search.algorithms.standard.bestfirst.BestFirst;
import jaicore.search.algorithms.standard.bestfirst.RandomCompletionEvaluator;
import jaicore.search.algorithms.standard.core.ORGraphSearch;
import jaicore.search.algorithms.standard.mcts.IPathUpdatablePolicy;
import jaicore.search.algorithms.standard.mcts.IPolicy;
import jaicore.search.algorithms.standard.mcts.MCTS;
import jaicore.search.algorithms.standard.mcts.UCBPolicy;
import jaicore.search.algorithms.standard.mcts.UniformRandomPolicy;
import jaicore.search.algorithms.standard.rstar.RStar;
import jaicore.search.algorithms.standard.rstar.RandomCompletionGammaGraphGenerator;
import jaicore.search.algorithms.standard.uncertainty.BasicUncertaintySource;
import jaicore.search.algorithms.standard.uncertainty.UncertaintyRandomCompletionEvaluator;
import jaicore.search.algorithms.standard.uncertainty.explorationexploitationsearch.BasicClockModelPhaseLengthAdjuster;
import jaicore.search.algorithms.standard.uncertainty.explorationexploitationsearch.BasicExplorationCandidateSelector;
import jaicore.search.algorithms.standard.uncertainty.explorationexploitationsearch.UncertaintyExplorationOpenSelection;
import jaicore.search.algorithms.standard.uncertainty.paretosearch.CosinusDistanceComparator;
import jaicore.search.algorithms.standard.uncertainty.paretosearch.ParetoSelection;
import jaicore.search.evaluationproblems.KnapsackProblem;
import jaicore.search.evaluationproblems.KnapsackProblem.KnapsackNode;

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
				double problemSize = Double.valueOf(description.get("problem_size"));
				int timeout = Integer.valueOf(description.get("timeout"));
				KnapsackProblem knapsackProblem = createRandomKnapsackProblem(problemSize, seed);
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
				randomCompletionEvaluator.setGenerator(knapsackProblem.getGraphGenerator());
				
				// Calculate experiment score
				Double score = Double.MAX_VALUE;
				switch (algorithmName) {
					case "two_phase":
						ORGraphSearch<KnapsackNode, String, Double> twoPhaseSearch = new ORGraphSearch<>(
							knapsackProblem.getGraphGenerator(),
							new UncertaintyRandomCompletionEvaluator<>(new Random(seed), 3, pathUnification, knapsackProblem.getSolutionEvaluator(), new BasicUncertaintySource<>())
						);
						twoPhaseSearch.setOpen(new UncertaintyExplorationOpenSelection<KnapsackNode, Double>(
							timeout * 1000, 50, 0.1, 0.1,
							new BasicClockModelPhaseLengthAdjuster(),
							(solution1, solution2) -> {
								double intersectionSize = 0.0d;
								List<String> items1 = solution1.get(solution1.size() - 1).getPackedObjects();
								List<String> items2 = solution2.get(solution2.size() - 1).getPackedObjects();
								for (String s : items1) {
									if (items2.contains(s)) {
										intersectionSize++;
									}
								}
								HashSet<String> unionSet = new HashSet<>();
								unionSet.addAll(items1);
								unionSet.addAll(items2);
								double unionSize = (double) unionSet.size();
								return (unionSize - intersectionSize) / unionSize;
							},
							new BasicExplorationCandidateSelector<>(0.25d))
						);

						OurExperimentRunner<KnapsackProblem.KnapsackNode> twoPhaseER = new OurExperimentRunner<>(twoPhaseSearch, knapsackProblem.getSolutionEvaluator());
						OurExperimentRunner.execute(twoPhaseER, timeout*1000);
						score = twoPhaseER.getCostOfBestSolution();
						break;
					case "pareto":
						ORGraphSearch<KnapsackNode, String, Double> paretoSearch = new ORGraphSearch<>(
							knapsackProblem.getGraphGenerator(),
							new UncertaintyRandomCompletionEvaluator<>(new Random(seed), 3, pathUnification, knapsackProblem.getSolutionEvaluator(), new BasicUncertaintySource<>())
						);
						paretoSearch.setOpen(new ParetoSelection<>(new PriorityQueue<>(new CosinusDistanceComparator(-1.0*knapsackProblem.getKnapsackCapacity(), 1.0))));

						OurExperimentRunner<KnapsackProblem.KnapsackNode> paretoER = new OurExperimentRunner<>(paretoSearch,
								knapsackProblem.getSolutionEvaluator());
						OurExperimentRunner.execute(paretoER, timeout*1000);
						score = paretoER.getCostOfBestSolution();

						break;
					case "awa_star":
						AwaStarSearch<KnapsackNode, String, Double> awaStarSearch;
						try {
							awaStarSearch = new AwaStarSearch<>(knapsackProblem.getGraphGenerator(), randomCompletionEvaluator);
							OurExperimentRunner<KnapsackNode> awaER = new OurExperimentRunner<>(awaStarSearch, knapsackProblem.getSolutionEvaluator());
							OurExperimentRunner.execute(awaER, timeout*1000);
							score = awaER.getCostOfBestSolution();
						} catch (Throwable e) {
							e.printStackTrace();
						}
						break;
					case "r_star":
						RandomCompletionGammaGraphGenerator<KnapsackNode> ggg = new RandomCompletionGammaGraphGenerator<>(knapsackProblem.getGraphGenerator(), knapsackProblem.getSolutionEvaluator(), 3, seed);
						int k, delta;
						switch ((int)problemSize) {
							case 50:
								k = 5;
								delta = 5;
								break;
							case 100:
								k = 15;
								delta = 5;
							case 500:
								k = 20;
								delta = 10;
							case 1000:
								k = 50;
								delta = 25;
							case 5000:
								k = 50;
								delta = 50;
							default:
								k = 5;
								delta = 5;
						}
						RStar<KnapsackNode, String, Integer> rstarSearch = new RStar<>(ggg, 1, k, delta, knapsackProblem.getSolutionEvaluator());

						try {
							rstarSearch.start();
							rstarSearch.join(timeout * 1000);
						} catch (InterruptedException e ) {
							System.out.println("Interrupted while joining RStar.");
							e.printStackTrace();
						}

						List<KnapsackProblem.KnapsackNode> solution = null;
						if (rstarSearch.getGoalState() != null) {
							try {
								solution = rstarSearch.getSolutionPath();
								if (solution != null) {
									// Score will be Double.max_value if nothing was found.
									score = knapsackProblem.getSolutionEvaluator().evaluateSolution(solution);
								}
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
						break;
					case "mcts":
						IPolicy<KnapsackNode, String, Double> randomPolicy = new UniformRandomPolicy<>(new Random(seed));
						IPathUpdatablePolicy<KnapsackNode, String, Double> ucb = new UCBPolicy<>(false);
						MCTS<KnapsackNode, String, Double> mctsSearch = new MCTS<>(
							knapsackProblem.getGraphGenerator(),
							ucb,
							randomPolicy,
							n-> knapsackProblem.getSolutionEvaluator().evaluateSolution(Arrays.asList(n.getPoint()))
						);

						OurExperimentRunner<KnapsackProblem.KnapsackNode> mctsER = new OurExperimentRunner<>(mctsSearch, knapsackProblem.getSolutionEvaluator());
						OurExperimentRunner.execute(mctsER, timeout*1000);
						score = mctsER.getCostOfBestSolution();
						break;
					case "best_first":
						BestFirst<KnapsackNode, String> bestFirstSearch = new BestFirst<>(knapsackProblem.getGraphGenerator(), randomCompletionEvaluator);
						OurExperimentRunner<KnapsackNode> bestFirstSearchER = new OurExperimentRunner<>(bestFirstSearch, knapsackProblem.getSolutionEvaluator());
						OurExperimentRunner.execute(bestFirstSearchER, timeout * 1000);
						score = bestFirstSearchER.getCostOfBestSolution();
						break;
				}
				System.out.println(algorithmName + ": " + score);
				Map<String, Object> results = new HashMap<>();
				results.put("score", score);
				processor.processResults(results);
			}
		});
		runner.randomlyConductExperiments(true);
	}
	
	public static KnapsackProblem createRandomKnapsackProblem (double problemSize, int seed) {
		Random random = new Random(seed);
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
