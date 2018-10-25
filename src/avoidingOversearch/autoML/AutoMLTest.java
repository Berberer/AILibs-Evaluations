package avoidingOversearch.autoML;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.openml.apiconnector.io.OpenmlConnector;
import org.openml.apiconnector.xml.DataSetDescription;

import de.upb.crc901.mlpipeline_evaluation.DatasetOrigin;
import de.upb.crc901.mlpipeline_evaluation.PipelineEvaluationCache;
import de.upb.crc901.mlplan.multiclass.wekamlplan.MLPlanWekaClassifier;
import de.upb.crc901.mlplan.multiclass.wekamlplan.weka.WekaMLPlanWekaClassifier;
import hasco.core.Util;
import hasco.model.Component;
import hasco.serialization.ComponentLoader;
import jaicore.basic.SQLAdapter;
import jaicore.graphvisualizer.gui.VisualizationWindow;
import jaicore.planning.graphgenerators.task.tfd.TFDNode;
import jaicore.search.algorithms.standard.bestfirst.nodeevaluation.RandomCompletionBasedNodeEvaluator;
import jaicore.search.algorithms.standard.uncertainty.BasicUncertaintySource;
import jaicore.search.algorithms.standard.uncertainty.OversearchAvoidanceConfig;
import jaicore.search.algorithms.standard.uncertainty.UncertaintyORGraphSearchFactory;
import jaicore.search.algorithms.standard.uncertainty.OversearchAvoidanceConfig.OversearchAvoidanceMode;
import jaicore.search.core.interfaces.GraphGenerator;
import jaicore.search.core.interfaces.IGraphSearch;
import jaicore.search.core.interfaces.ISolutionEvaluator;
import jaicore.search.model.probleminputs.UncertainlyEvaluatedTraversalTree;
import weka.core.Instances;

public class AutoMLTest {

	private static final int CAR = 21;
	private static final int CREDIT_G = 31;
	private static final int GLASS = 41;
	private static final int ABALONE = 183;
	private static final int QUAKE = 209;

	public static void main(String[] args) throws Exception {
		int seed = 16;
		int timeout = 30;
		int dataset = CAR;

		OpenmlConnector connector = new OpenmlConnector();
		DataSetDescription ds = connector.dataGet(dataset);
		File file = ds.getDataset("4350e421cdc16404033ef1812ea38c01");
		Instances data = new Instances(new BufferedReader(new FileReader(file)));
		data.setClassIndex(data.numAttributes() - 1);

		DatasetOrigin datasetOrigin = DatasetOrigin.OPENML_DATASET_ID;
		String testEvaluationTechnique = "single";
		String testSplitTechnique = "MCCV_0.7";
		String valEvaluationTechnique = "multi";
		String valSplitTechnique = "3MCCV_0.8";

		SQLAdapter adapter = new SQLAdapter("<HOST>", "<USER>", "<PASSWORD>", "<DB>");
		PipelineEvaluationCache cache = new PipelineEvaluationCache(String.valueOf(dataset), datasetOrigin,
				testEvaluationTechnique, testSplitTechnique, seed, valSplitTechnique, valEvaluationTechnique, seed,
				adapter);
		ComponentLoader loader = new ComponentLoader(new File("conf/automl/searchmodels/weka/weka-all-autoweka.json"));
		Collection<Component> components = loader.getComponents();

		IGraphSearch<?, ?, TFDNode, String, Double, ?, ?> algorithm = null;
		Double score = null;

		// Get Graph generator
		MLPlanWekaClassifier mlplan = new WekaMLPlanWekaClassifier();
		mlplan.setData(data);
		GraphGenerator<TFDNode, String> graphGenerator = mlplan.getGraphGenerator();

		// Create solution evaluator for the search
		ISolutionEvaluator<TFDNode, Double> searchEvaluator = new ISolutionEvaluator<TFDNode, Double>() {
			@Override
			public Double evaluateSolution(List<TFDNode> solutionPath) throws Exception {

				return cache.getResultOrExecuteEvaluation(Util.getSolutionCompositionFromState(
						loader.getComponents(), solutionPath.get(solutionPath.size() - 1).getState(), true));
			}

			@Override
			public boolean doesLastActionAffectScoreOfAnySubsequentSolution(List<TFDNode> partialSolutionPath) {
				return false;
			}

			@Override
			public void cancel() {
			}
		};

		// Configure and create algorithm
		RandomCompletionBasedNodeEvaluator<TFDNode, Double> nodeEvaluator = new RandomCompletionBasedNodeEvaluator<>(
				new Random(seed), 3, searchEvaluator);
		nodeEvaluator.setGenerator(graphGenerator);
		nodeEvaluator.setUncertaintySource(new BasicUncertaintySource<>());

		OversearchAvoidanceConfig<TFDNode, Double> switchConfig = new OversearchAvoidanceConfig<>(
				OversearchAvoidanceMode.TWO_PHASE_SELECTION, seed);
		switchConfig.setExploitationScoreThreshold(0.1);
		switchConfig.setExplorationUncertaintyThreshold(0.1);
		switchConfig.setInterval(50);
		switchConfig.setMinimumSolutionDistanceForExploration(0.75d);
		switchConfig.setSolutionDistanceMetric(new PipelineMetric(components));
		switchConfig.activateDynamicPhaseLengthsAdjustment(timeout);
		UncertaintyORGraphSearchFactory<TFDNode, String, Double> switchFactory = new UncertaintyORGraphSearchFactory<>();
		switchFactory.setConfig(switchConfig);
		switchFactory.setProblemInput(new UncertainlyEvaluatedTraversalTree<>(graphGenerator, nodeEvaluator));
		switchFactory.setTimeoutForFComputation(600000, n -> {
			n.setAnnotation("uncertainty", 1);
			return Double.MAX_VALUE;
		});
		algorithm = switchFactory.getAlgorithm();

		// Start search algorithm
//		new VisualizationWindow<>(algorithm);
		algorithm.setTimeout(timeout * 1000, TimeUnit.MILLISECONDS);
		try {
			algorithm.call();
		} catch (TimeoutException e) {
			System.out.println("algorithm finished with timeout exception, which is ok.");
		}
		mlplan.cancel();
		algorithm.cancel();
		searchEvaluator.cancel();
		nodeEvaluator.cancel();
		adapter.close();
		System.out.println("Evaluating best found pipeline");
		try {
			score = (algorithm.getBestSeenSolution() != null) ? algorithm.getBestSeenSolution().getScore() : null;
			System.out.println("Switch-Search: " + score);
		} catch (Exception e) {
			e.printStackTrace();
		}

	}
}
