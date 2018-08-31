package avoidingOversearch.tsp;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import avoidingOversearch.OurExperimentRunner;
import jaicore.search.algorithms.interfaces.IPathUnification;
import jaicore.search.algorithms.standard.bestfirst.RandomCompletionEvaluator;
import jaicore.search.algorithms.standard.mcts.IPathUpdatablePolicy;
import jaicore.search.algorithms.standard.mcts.IPolicy;
import jaicore.search.algorithms.standard.mcts.MCTS;
import jaicore.search.algorithms.standard.mcts.UCBPolicy;
import jaicore.search.algorithms.standard.mcts.UniformRandomPolicy;
import jaicore.search.evaluationproblems.EnhancedTTSP;
import jaicore.search.evaluationproblems.EnhancedTTSP.EnhancedTTSPNode;

public class TSPTester {
	public static void main(String[] args) {
		int seed = 5;
		double problemSize = 100;
		int timeout = 60;
		System.out.print("Generating problem ... ");
		EnhancedTTSP tsp = TSPExperimenter.createRandomTSP(problemSize, seed);
		System.out.println("done");
		IPathUnification<EnhancedTTSPNode> pathUnification = new IPathUnification<EnhancedTTSPNode>() {
			@Override
			public List<EnhancedTTSPNode> getSubsumingKnownPathCompletion(Map<List<EnhancedTTSPNode>, List<EnhancedTTSPNode>> knownPathCompletions, List<EnhancedTTSPNode> path)
					throws InterruptedException {
				return null;
			}
		};
		RandomCompletionEvaluator<EnhancedTTSPNode, Double> randomCompletionEvaluator = new RandomCompletionEvaluator<EnhancedTTSPNode, Double>(new Random(seed), 3, pathUnification,
				tsp.getSolutionEvaluator());
		randomCompletionEvaluator.setGenerator(tsp.getGraphGenerator());

		IPolicy<EnhancedTTSPNode, String, Double> randomPolicy = new UniformRandomPolicy<>(new Random(seed));
		IPathUpdatablePolicy<EnhancedTTSPNode, String, Double> ucb = new UCBPolicy<>(false);
		MCTS<EnhancedTTSPNode, String, Double> mctsSearch = new MCTS<>(tsp.getGraphGenerator(), ucb, randomPolicy, n -> tsp.getSolutionEvaluator().evaluateSolution(Arrays.asList(n.getPoint())));

		OurExperimentRunner<EnhancedTTSPNode> mctsER = new OurExperimentRunner<>(mctsSearch, tsp.getSolutionEvaluator());
		OurExperimentRunner.execute(mctsER, timeout * 1000);
		System.out.println(mctsER.getCostOfBestSolution());

	}
}
