package avoidingOversearch.tsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
import jaicore.search.algorithms.standard.bestfirst.RandomCompletionEvaluator;
import jaicore.search.algorithms.standard.mcts.IPathUpdatablePolicy;
import jaicore.search.algorithms.standard.mcts.IPolicy;
import jaicore.search.algorithms.standard.mcts.MCTS;
import jaicore.search.algorithms.standard.mcts.UCBPolicy;
import jaicore.search.algorithms.standard.mcts.UniformRandomPolicy;
import jaicore.search.evaluationproblems.EnhancedTTSP;
import jaicore.search.evaluationproblems.EnhancedTTSP.EnhancedTTSPNode;
import jaicore.search.structure.core.Node;

public class TSPExperimenter {

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
				EnhancedTTSP tsp = createRandomTSP(problemSize);
				RandomCompletionEvaluator<EnhancedTTSPNode, Double> randomCompletionEvaluator = new RandomCompletionEvaluator<>(
					new Random(seed),
					3,
					new IPathUnification<EnhancedTTSPNode>() {
						@Override
						public List<EnhancedTTSPNode> getSubsumingKnownPathCompletion(
								Map<List<EnhancedTTSPNode>, List<EnhancedTTSPNode>> knownPathCompletions,
								List<EnhancedTTSPNode> path) throws InterruptedException {
							return null;
						}
					},
					tsp.getSolutionEvaluator()
				);

				// Calculate experiment score
				double score = Double.MAX_VALUE;
				switch (algorithmName) {
					case "two-phase":
						break;
					case "pareto":
						break;
					case "awa-star":
						AwaStarSearch<EnhancedTTSPNode, String, Double> awaStarSearch;
						try {
							awaStarSearch = new AwaStarSearch<>(tsp.getGraphGenerator(), randomCompletionEvaluator, tsp.getSolutionEvaluator());
							List<Node<EnhancedTTSPNode, Double>>solution = awaStarSearch.search(timeout);
							List<EnhancedTTSPNode> solutionPath = new ArrayList<>();
							solution.forEach(n -> solutionPath.add(n.getPoint()));
							score = tsp.getSolutionEvaluator().evaluateSolution(solutionPath);
						} catch (Throwable e) {
							e.printStackTrace();
						}
						break;
					case "r-star":
						break;
					case "mcts":
						IPolicy<EnhancedTTSPNode, String, Double> randomPolicy = new UniformRandomPolicy<>(new Random(seed));
						IPathUpdatablePolicy<EnhancedTTSPNode, String, Double> ucb = new UCBPolicy<>();
						
						MCTS<EnhancedTTSPNode, String, Double> mctsSearch = new MCTS<>(
							tsp.getGraphGenerator(),
							ucb,
							randomPolicy,
							n-> tsp.getSolutionEvaluator().evaluateSolution(Arrays.asList(n.getPoint()))
						);
						long t = System.currentTimeMillis();
						long end = t + timeout * 1000;
						List<EnhancedTTSPNode> solution = mctsSearch.nextSolution();
						while (solution != null && System.currentTimeMillis() < end) {
							Double solutionScore = tsp.getSolutionEvaluator().evaluateSolution(solution);
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
	
	public static EnhancedTTSP createRandomTSP (double problemSize) {
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
		};
		List<Boolean> blockedHours = Arrays.asList(new Boolean[] 
				{ true, true, true, true, true, true, false, false, false, false, false, false,
				false, false, false, false, false, false, false, false, false, false, true, true });
		double maxConsecutiveDrivingTime = random.nextInt(5) + 5;
		double durationOfShortBreak = random.nextInt(3) + 3;
		double durationOfLongBreak = random.nextInt(6) + 6;
		return new EnhancedTTSP(minTravelTimesGraph, (short)0, blockedHours, 8, maxConsecutiveDrivingTime, durationOfShortBreak, durationOfLongBreak);
	}

}
