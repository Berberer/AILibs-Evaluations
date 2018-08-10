package avoidingOversearch.autoML;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

import org.aeonbits.owner.ConfigCache;

import avoidingOversearch.OurExperimentRunner;
import de.upb.crc901.automl.hascowekaml.WEKAPipelineFactory;
import de.upb.crc901.automl.pipeline.basic.MLPipeline;
import hasco.core.HASCOProblemReduction;
import hasco.core.Util;
import hasco.model.ComponentInstance;
import hasco.serialization.ComponentLoader;
import jaicore.basic.SQLAdapter;
import jaicore.experiments.ExperimentDBEntry;
import jaicore.experiments.ExperimentRunner;
import jaicore.experiments.IExperimentIntermediateResultProcessor;
import jaicore.experiments.IExperimentSetConfig;
import jaicore.experiments.IExperimentSetEvaluator;
import jaicore.ml.WekaUtil;
import jaicore.planning.algorithms.forwarddecomposition.ForwardDecompositionHTNPlannerFactory;
import jaicore.planning.graphgenerators.task.tfd.TFDNode;
import jaicore.search.algorithms.interfaces.IPathUnification;
import jaicore.search.algorithms.interfaces.ISolutionEvaluator;
import jaicore.search.algorithms.standard.bestfirst.RandomCompletionEvaluator;
import jaicore.search.algorithms.standard.core.ORGraphSearch;
import jaicore.search.algorithms.standard.mcts.IPathUpdatablePolicy;
import jaicore.search.algorithms.standard.mcts.IPolicy;
import jaicore.search.algorithms.standard.mcts.MCTS;
import jaicore.search.algorithms.standard.mcts.UCBPolicy;
import jaicore.search.algorithms.standard.mcts.UniformRandomPolicy;
import jaicore.search.algorithms.standard.uncertainty.BasicUncertaintySource;
import jaicore.search.algorithms.standard.uncertainty.UncertaintyRandomCompletionEvaluator;
import jaicore.search.algorithms.standard.uncertainty.paretosearch.CosinusDistanceComparator;
import jaicore.search.algorithms.standard.uncertainty.paretosearch.ParetoSelection;
import jaicore.search.structure.core.GraphGenerator;
import weka.core.Instances;

public class AutoMLExperimenter {

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

				/* get experiment setup */
				Map<String, String> description = experimentEntry.getExperiment().getValuesOfKeyFields();
				String algorithmName = description.get("algorithm");
				String datasetName = description.get("dataset");
				int seed = Integer.valueOf(description.get("seed"));
				int timeout = Integer.valueOf(description.get("timeout"));

				// Calculate experiment score
				IPathUnification<TFDNode> pathUnification = new IPathUnification<TFDNode>() {
					@Override
					public List<TFDNode> getSubsumingKnownPathCompletion(
							Map<List<TFDNode>, List<TFDNode>> knownPathCompletions,
							List<TFDNode> path) throws InterruptedException {
						return null;
					}
				};
				Instances data = new Instances(new BufferedReader(
						new FileReader(new File(m.getDatasetFolder() + File.separator + datasetName + ".arff"))));
				data.setClassIndex(data.numAttributes() - 1);
				List<Instances> split = WekaUtil.getStratifiedSplit(data, new Random(seed), 0.6f, 0.2f);
				Instances train = split.get(0);
				Instances validate = split.get(1);
				Instances test = split.get(2);
				
				File configFile = new File("model/weka/weka-all-autoweka.json");
				ComponentLoader componentLoader = new ComponentLoader();
				componentLoader.loadComponents(configFile);
				HASCOProblemReduction reduction = new HASCOProblemReduction(configFile, "AbstractClassifier", true);
				GraphGenerator<TFDNode, String> graphGenerator = reduction
						.getGraphGeneratorUsedByHASCOForSpecificPlanner(new ForwardDecompositionHTNPlannerFactory<>());
				WEKAPipelineFactory pipelineFactory = new WEKAPipelineFactory();
				ISolutionEvaluator<TFDNode, Double> searchEvaluator = new ISolutionEvaluator<TFDNode, Double>() {
					@Override
					public Double evaluateSolution(List<TFDNode> solutionPath) throws Exception {
						if (solutionPath != null && !solutionPath.isEmpty()) {
							ComponentInstance instance = Util.getSolutionCompositionFromState(componentLoader.getComponents(),
									solutionPath.get(solutionPath.size() - 1).getState());
							if (instance != null) {
								MLPipeline pipeline = pipelineFactory.getComponentInstantiation(
										Util.getSolutionCompositionFromState(componentLoader.getComponents(),
												solutionPath.get(solutionPath.size() - 1).getState()));
								pipeline.buildClassifier(train);
								double[] prediction = pipeline.classifyInstances(validate);
								double errorCounter = 0d;
								for (int i = 0; i < test.size(); i++) {
									if (prediction[i] != test.get(i).classValue()) {
										errorCounter++;
									}
								}
								return errorCounter / test.size();
							} else {
								return Double.MAX_VALUE;
							}					
						} else {
							return Double.MAX_VALUE;
						}
					}

					@Override
					public boolean doesLastActionAffectScoreOfAnySubsequentSolution(List<TFDNode> partialSolutionPath) {
						return false;
					}
				};
				RandomCompletionEvaluator<TFDNode, Double> randomCompletionEvaluator = new RandomCompletionEvaluator<>(
					new Random(seed),
					3,
					pathUnification,
					searchEvaluator
				);
				ISolutionEvaluator<TFDNode, Double> scoreEvaluator = new ISolutionEvaluator<TFDNode, Double>() {
					@Override
					public Double evaluateSolution(List<TFDNode> solutionPath) throws Exception {
						
						if (solutionPath != null && !solutionPath.isEmpty()) {
							ComponentInstance instance = Util.getSolutionCompositionFromState(componentLoader.getComponents(),
									solutionPath.get(solutionPath.size() - 1).getState());
							if (instance != null) {
								MLPipeline pipeline = pipelineFactory.getComponentInstantiation(
										Util.getSolutionCompositionFromState(componentLoader.getComponents(),
												solutionPath.get(solutionPath.size() - 1).getState()));
								pipeline.buildClassifier(train);
								double[] prediction = pipeline.classifyInstances(test);
								double errorCounter = 0d;
								for (int i = 0; i < test.size(); i++) {
									if (prediction[i] != test.get(i).classValue()) {
										errorCounter++;
									}
								}
								return errorCounter / test.size();
							} else {
								return Double.MAX_VALUE;
							}					
						} else {
							return Double.MAX_VALUE;
						}
					}

					@Override
					public boolean doesLastActionAffectScoreOfAnySubsequentSolution(List<TFDNode> partialSolutionPath) {
						return false;
					}
				};

				Double score = Double.MAX_VALUE;
				switch (algorithmName) {
				case "ml_plan":
					break;
				case "two_phase":
					break;
				case "pareto":
					ORGraphSearch<TFDNode, String, Double> paretoSearch = new ORGraphSearch<>(
						graphGenerator,
						new UncertaintyRandomCompletionEvaluator<>(new Random(seed), 3, pathUnification, scoreEvaluator, new BasicUncertaintySource<>())
					);
					paretoSearch.setOpen(new ParetoSelection<>(new PriorityQueue<>(new CosinusDistanceComparator<TFDNode, Double>(1.0, 1.0))));

					OurExperimentRunner<TFDNode> paretoER = new OurExperimentRunner<>(paretoSearch,searchEvaluator);
					OurExperimentRunner.execute(paretoER, timeout*1000l);
					score = scoreEvaluator.evaluateSolution(paretoER.getBestSolution());
					break;
				case "awa_star":
					break;
				case "r_star":
					break;
				case "mcts":
					IPolicy<TFDNode, String, Double> randomPolicy = new UniformRandomPolicy<>(new Random(seed));
					IPathUpdatablePolicy<TFDNode, String, Double> ucb = new UCBPolicy<>(false);
					MCTS<TFDNode, String, Double> mctsSearch = new MCTS<>(
						graphGenerator,
						ucb,
						randomPolicy,
						n-> searchEvaluator.evaluateSolution(Arrays.asList(n.getPoint()))
					);

					OurExperimentRunner<TFDNode> mctsER = new OurExperimentRunner<>(mctsSearch, searchEvaluator);
					OurExperimentRunner.execute(mctsER, timeout*1000l);
					score = scoreEvaluator.evaluateSolution(mctsER.getBestSolution());
					break;
				}

				Map<String, Object> results = new HashMap<>();
				results.put("score", score);
				processor.processResults(results);
			}
		});
		runner.randomlyConductExperiments(true);
	}

}
