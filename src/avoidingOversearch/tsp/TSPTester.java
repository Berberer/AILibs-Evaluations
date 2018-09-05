package avoidingOversearch.tsp;

import java.util.Random;

import avoidingOversearch.OurExperimentRunner;
import jaicore.search.algorithms.standard.mcts.UCBPolicy;
import jaicore.search.algorithms.standard.mcts.UCTFactory;
import jaicore.search.algorithms.standard.mcts.UniformRandomPolicy;
import jaicore.search.testproblems.enhancedttsp.EnhancedTTSP;
import jaicore.search.testproblems.enhancedttsp.EnhancedTTSPNode;
import jaicore.search.testproblems.enhancedttsp.EnhancedTTSPToGraphSearchProblemInputReducer;

public class TSPTester {
	public static void main(String[] args) {
		int seed = 1;
		int problemSize = 3;
		int timeout = 120;
		System.out.print("Generating problem ... ");
		EnhancedTTSP tsp = EnhancedTTSP.createRandomProblem(problemSize, seed);
		
		System.out.println("Testing MCTS ...");
		testMCTS(tsp, timeout, seed);
	}
	
	private static void testMCTS (EnhancedTTSP tsp, int timeout, int seed) {
		UCTFactory<EnhancedTTSPNode, String> factory = new UCTFactory<>();
		factory.setDefaultPolicy(new UniformRandomPolicy<>(new Random(seed)));
		factory.setTreePolicy(new UCBPolicy<>(false));
		factory.setProblemInput(new EnhancedTTSPToGraphSearchProblemInputReducer().transform(tsp));
		factory.setSeed(seed);
		OurExperimentRunner<EnhancedTTSPNode> mctsER = new OurExperimentRunner<>(factory.getAlgorithm());
		OurExperimentRunner.execute(mctsER, timeout * 1000);
		System.out.println("MCTS-Score: " + mctsER.getCostOfBestSolution());
	}
}
