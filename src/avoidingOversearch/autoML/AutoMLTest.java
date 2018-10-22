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

import de.upb.crc901.mlplan.multiclass.wekamlplan.MLPlanWekaClassifier;
import de.upb.crc901.mlplan.multiclass.wekamlplan.weka.WEKAPipelineFactory;
import de.upb.crc901.mlplan.multiclass.wekamlplan.weka.WekaMLPlanWekaClassifier;
import de.upb.crc901.mlplan.multiclass.wekamlplan.weka.model.MLPipeline;
import hasco.core.Util;
import hasco.model.Component;
import hasco.model.ComponentInstance;
import hasco.serialization.ComponentLoader;
import jaicore.graphvisualizer.gui.VisualizationWindow;
import jaicore.ml.WekaUtil;
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

	private static WEKAPipelineFactory pipelineFactory;
	private static ComponentLoader componentLoader;
	private static Collection<Component> components;
	private static Instances train;
	
	public static void main(String[] args) throws Exception {
		int seed = 16;
		int timeout = 30;
		int dataset = 21;

		OpenmlConnector connector = new OpenmlConnector();
		DataSetDescription ds = connector.dataGet(dataset);
		File file = ds.getDataset("4350e421cdc16404033ef1812ea38c01");
		Instances data = new Instances(new BufferedReader(new FileReader(file)));
		data.setClassIndex(data.numAttributes() - 1);
		List<Instances> split = WekaUtil.getStratifiedSplit(data, new Random(seed), 0.6f, 0.2f);
		train = split.get(0);
		Instances validate = split.get(1);
		Instances test = split.get(2);

		IGraphSearch<?, ?, TFDNode, String, Double, ?, ?> algorithm = null;
		Double score = null;

		// Load Haso Components for Weka
		File configFile = new File("conf/automl/searchmodels/weka/weka-all-autoweka.json");
		componentLoader = new ComponentLoader();
		componentLoader.loadComponents(configFile);
		components = componentLoader.getComponents();
		pipelineFactory = new WEKAPipelineFactory();

		// Get Graph generator
		MLPlanWekaClassifier mlplan = new WekaMLPlanWekaClassifier();
		mlplan.setData(data);
		GraphGenerator<TFDNode, String> graphGenerator = mlplan.getGraphGenerator();

		// Create solution evaluator for the search
		ISolutionEvaluator<TFDNode, Double> searchEvaluator = new ISolutionEvaluator<TFDNode, Double>() {
			@Override
			public Double evaluateSolution(List<TFDNode> solutionPath) throws Exception {
				return calculateScore(solutionPath, validate);
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
		System.out.println("Evaluating best found pipeline");
		try {
			score = (algorithm.getBestSeenSolution() != null)
					? calculateScore(algorithm.getBestSeenSolution().getNodes(), test)
					: null;
			System.out.println("Switch-Search: " + score);
		} catch (Exception e) {
			e.printStackTrace();
		}

	}
	
	private static Double calculateScore (List<TFDNode> solutionPath, Instances data) throws Exception {
		if (solutionPath != null && !solutionPath.isEmpty()) {
			ComponentInstance instance = Util.getSolutionCompositionFromState(componentLoader.getComponents(),
					solutionPath.get(solutionPath.size() - 1).getState(), true);
			if (instance != null) {
				MLPipeline pipeline = pipelineFactory
						.getComponentInstantiation(Util.getSolutionCompositionFromState(components,
								solutionPath.get(solutionPath.size() - 1).getState(), true));
				pipeline.buildClassifier(train);
				double[] prediction = pipeline.classifyInstances(data);
				double errorCounter = 0d;
				for (int i = 0; i < data.size(); i++) {
					if (prediction[i] != data.get(i).classValue()) {
						errorCounter++;
					}
				}
				return errorCounter / data.size();
			} else {
				return Double.MAX_VALUE;
			}
		} else {
			return Double.MAX_VALUE;
		}
	}

}