package avoidingOversearch.tsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

import avoidingOversearch.OurExperimentRunner;
import avoidingOversearch.OurExperimentRunnerAWAStar;
import org.aeonbits.owner.ConfigCache;

import jaicore.basic.SQLAdapter;
import jaicore.basic.sets.SetUtil.Pair;
import jaicore.experiments.ExperimentDBEntry;
import jaicore.experiments.ExperimentRunner;
import jaicore.experiments.IExperimentIntermediateResultProcessor;
import jaicore.experiments.IExperimentSetConfig;
import jaicore.experiments.IExperimentSetEvaluator;
import jaicore.graph.LabeledGraph;
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
import jaicore.search.evaluationproblems.EnhancedTTSP;
import jaicore.search.evaluationproblems.EnhancedTTSP.EnhancedTTSPNode;

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
				double problemSize = Double.valueOf(description.get("problem_size"));
				int timeout = Integer.valueOf(description.get("timeout"));
				EnhancedTTSP tsp = createRandomTSP(problemSize);
				IPathUnification<EnhancedTTSPNode> pathUnification = new IPathUnification<EnhancedTTSPNode>() {
					@Override
					public List<EnhancedTTSPNode> getSubsumingKnownPathCompletion(
							Map<List<EnhancedTTSPNode>, List<EnhancedTTSPNode>> knownPathCompletions,
							List<EnhancedTTSPNode> path) throws InterruptedException {
						return null;
					}
				};
				RandomCompletionEvaluator<EnhancedTTSPNode, Double> randomCompletionEvaluator = new RandomCompletionEvaluator<>(
						new Random(seed), 3, pathUnification, tsp.getSolutionEvaluator());
				randomCompletionEvaluator.setGenerator(tsp.getGraphGenerator());

				// Calculate experiment score
				double score = Double.MAX_VALUE;
				switch (algorithmName) {
				case "two_phase":
					ORGraphSearch<EnhancedTTSPNode, String, Double> twoPhaseSearch = new ORGraphSearch<>(
							tsp.getGraphGenerator(), new UncertaintyRandomCompletionEvaluator<>(new Random(seed), 3,
									pathUnification, tsp.getSolutionEvaluator(), new BasicUncertaintySource<>()));
					twoPhaseSearch
							.setOpen(new UncertaintyExplorationOpenSelection<EnhancedTTSPNode, Double>(timeout * 1000,
									50, 0.1, 0.1, new BasicClockModelPhaseLengthAdjuster(), (solution1, solution2) -> {
										int minLength = Math.min(solution1.size(), solution2.size());
										int commonPathLength = 0;
										for (int i = 0; i < minLength; i++) {
											if (solution1.get(i).getCurLocation() == solution2.get(i)
													.getCurLocation()) {
												commonPathLength++;
											} else {
												break;
											}
										}
										return ((double) minLength - commonPathLength) / ((double) minLength);
									}, new BasicExplorationCandidateSelector<>(0.75d)));

					OurExperimentRunner<EnhancedTTSPNode> twoPhaseER = new OurExperimentRunner<>(twoPhaseSearch,
							tsp.getSolutionEvaluator());
					OurExperimentRunner.execute(twoPhaseER, timeout * 1000);
					score = twoPhaseER.getCostOfBestSolution();

					break;
				case "pareto":
					ORGraphSearch<EnhancedTTSPNode, String, Double> paretoSearch = new ORGraphSearch<>(
							tsp.getGraphGenerator(), new UncertaintyRandomCompletionEvaluator<>(new Random(seed), 3,
									pathUnification, tsp.getSolutionEvaluator(), new BasicUncertaintySource<>()));
					// paretoSearch.setOpen(new ParetoSelection<>(new PriorityQueue<>(new
					// CosinusDistanceComparator(1.0, 1.0))));
					paretoSearch.setOpen(new ParetoSelection<>(
							new PriorityQueue<>(new CosinusDistanceComparator(12.0 * 12.0 * 2.0 * problemSize, 1.0))));

					OurExperimentRunner<EnhancedTTSPNode> paretoER = new OurExperimentRunner<>(paretoSearch,
							tsp.getSolutionEvaluator());
					OurExperimentRunner.execute(paretoER, timeout * 1000);
					score = paretoER.getCostOfBestSolution();

					break;
				case "awa_star":
					AwaStarSearch<EnhancedTTSPNode, String, Double> awaStarSearch;
					try {
						awaStarSearch = new AwaStarSearch<>(tsp.getGraphGenerator(), randomCompletionEvaluator);
						OurExperimentRunnerAWAStar<EnhancedTTSPNode> awaER = new OurExperimentRunnerAWAStar<>(
								awaStarSearch, tsp.getSolutionEvaluator());
						OurExperimentRunner.execute(awaER, timeout * 1000);
						score = awaER.getCostOfBestSolution();
					} catch (Throwable e) {
						e.printStackTrace();
					}
					break;
				case "r_star":
					RandomCompletionGammaGraphGenerator<EnhancedTTSPNode> ggg = new RandomCompletionGammaGraphGenerator<>(
							tsp.getGraphGenerator(), tsp.getSolutionEvaluator(), 3, seed);
					int k, delta;
					switch ((int) problemSize) {
					case 50:
						k = 5;
						delta = 5;
						break;
					case 100:
						k = 10;
						delta = 10;
					case 500:
						k = 10;
						delta = 15;
					case 1000:
						k = 25;
						delta = 35;
					case 5000:
						k = 30;
						delta = 50;
					default:
						k = 25;
						delta = 5;
					}
					RStar<EnhancedTTSPNode, String, Integer> rstarSearch = new RStar<>(ggg, 1, k, delta);

					try {
						rstarSearch.start();
						rstarSearch.join(timeout * 1000);
					} catch (InterruptedException e) {
						System.out.println("Interrupted while joining RStar.");
						e.printStackTrace();
					}

					List<EnhancedTTSPNode> solution = null;
					if (rstarSearch.getGoalState() != null) {
						try {
							solution = rstarSearch.getSolutionPath();
							if (solution != null) {
								// Score will be Double.max_value if nothing was found.
								score = tsp.getSolutionEvaluator().evaluateSolution(solution);
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
					break;
				case "mcts":
					IPolicy<EnhancedTTSPNode, String, Double> randomPolicy = new UniformRandomPolicy<>(
							new Random(seed));
					IPathUpdatablePolicy<EnhancedTTSPNode, String, Double> ucb = new UCBPolicy<>(false);
					MCTS<EnhancedTTSPNode, String, Double> mctsSearch = new MCTS<>(tsp.getGraphGenerator(), ucb,
							randomPolicy,
							n -> tsp.getSolutionEvaluator().evaluateSolution(Arrays.asList(n.getPoint())));

					OurExperimentRunner<EnhancedTTSPNode> mctsER = new OurExperimentRunner<>(mctsSearch,
							tsp.getSolutionEvaluator());
					OurExperimentRunner.execute(mctsER, timeout * 1000);
					score = mctsER.getCostOfBestSolution();

					break;
				case "best_first":
					BestFirst<EnhancedTTSPNode, String> bestFirstSearch = new BestFirst<>(tsp.getGraphGenerator(),
							randomCompletionEvaluator);
					OurExperimentRunner<EnhancedTTSPNode> bestFirstER = new OurExperimentRunner<>(bestFirstSearch,
							tsp.getSolutionEvaluator());
					OurExperimentRunner.execute(bestFirstER, timeout * 1000);
					score = bestFirstER.getCostOfBestSolution();
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

	public static EnhancedTTSP createRandomTSP(double problemSize) {
		Random random = new Random((long) problemSize);
		LabeledGraph<Short, Double> minTravelTimesGraph = new LabeledGraph<>();
		List<Pair<Double, Double>> coordinates = new ArrayList<>();
		for (short i = 0; i < problemSize; i++) {
			coordinates.add(new Pair<>(random.nextDouble() * 12, random.nextDouble() * 12));
			minTravelTimesGraph.addItem(i);
		}
		for (short i = 0; i < problemSize; i++) {
			double x1 = coordinates.get(i).getX();
			double y1 = coordinates.get(i).getY();
			for (short j = 0; j < i; j++) {
				double x2 = coordinates.get(j).getX();
				double y2 = coordinates.get(j).getY();
				double minTravelTime = Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
				minTravelTimesGraph.addEdge(i, j, minTravelTime);
				minTravelTimesGraph.addEdge(j, i, minTravelTime);
			}
		}
		List<Boolean> blockedHours = Arrays
				.asList(new Boolean[] { true, true, true, true, true, true, false, false, false, false, false, false,
						false, false, false, false, false, false, false, false, false, false, true, true });
		double maxConsecutiveDrivingTime = random.nextInt(5) + 5;
		double durationOfShortBreak = random.nextInt(3) + 3;
		double durationOfLongBreak = random.nextInt(6) + 6;
		return new EnhancedTTSP(minTravelTimesGraph, (short) 0, blockedHours, 8, maxConsecutiveDrivingTime,
				durationOfShortBreak, durationOfLongBreak);
	}

}
