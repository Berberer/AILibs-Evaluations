package avoidingOversearch.autoML;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
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
import de.upb.crc901.automl.hascowekaml.WEKAPipelineFactory;
import de.upb.crc901.automl.pipeline.basic.MLPipeline;
import hasco.core.HASCOProblemReduction;
import hasco.core.Util;
import hasco.model.Component;
import hasco.model.ComponentInstance;
import hasco.model.NumericParameterDomain;
import hasco.model.Parameter;
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
import jaicore.search.algorithms.standard.awastar.AwaStarSearch;
import jaicore.search.algorithms.standard.bestfirst.BestFirst;
import jaicore.search.algorithms.standard.bestfirst.RandomCompletionEvaluator;
import jaicore.search.algorithms.standard.core.ORGraphSearch;
import jaicore.search.algorithms.standard.core.TimedNodeEvaluator;
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
				int timeout = 3600;

				// Calculate experiment score
				IPathUnification<TFDNode> pathUnification = new IPathUnification<TFDNode>() {
					@Override
					public List<TFDNode> getSubsumingKnownPathCompletion(
							Map<List<TFDNode>, List<TFDNode>> knownPathCompletions, List<TFDNode> path)
							throws InterruptedException {
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
							ComponentInstance instance = Util.getSolutionCompositionFromState(
									componentLoader.getComponents(),
									solutionPath.get(solutionPath.size() - 1).getState());
							if (instance != null) {
								MLPipeline pipeline = pipelineFactory.getComponentInstantiation(
										Util.getSolutionCompositionFromState(componentLoader.getComponents(),
												solutionPath.get(solutionPath.size() - 1).getState()));
								pipeline.buildClassifier(train);
								double[] prediction = pipeline.classifyInstances(validate);
								double errorCounter = 0d;
								for (int i = 0; i < validate.size(); i++) {
									if (prediction[i] != validate.get(i).classValue()) {
										errorCounter++;
									}
								}
								return errorCounter / validate.size();
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
				TimedNodeEvaluator<TFDNode, Double> timedRandomCompletionEvaluator = new TimedNodeEvaluator<>(
						new RandomCompletionEvaluator<>(new Random(seed), 3, pathUnification, searchEvaluator), 300000,
						n -> Double.MAX_VALUE);
				TimedNodeEvaluator<TFDNode, Double> timedUncertaintyRandomCompletionEvaluator = new TimedNodeEvaluator<>(
						new UncertaintyRandomCompletionEvaluator<>(new Random(seed), 3, pathUnification,
								searchEvaluator, new BasicUncertaintySource<>()),
						300000, n -> Double.MAX_VALUE);
				ISolutionEvaluator<TFDNode, Double> scoreEvaluator = new ISolutionEvaluator<TFDNode, Double>() {
					@Override
					public Double evaluateSolution(List<TFDNode> solutionPath) throws Exception {

						if (solutionPath != null && !solutionPath.isEmpty()) {
							ComponentInstance instance = Util.getSolutionCompositionFromState(
									componentLoader.getComponents(),
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
				case "best_first":
					BestFirst<TFDNode, String> bestFirstSearch = new BestFirst<>(graphGenerator,
							timedRandomCompletionEvaluator);
					OurExperimentRunner<TFDNode> bestFirstER = new OurExperimentRunner<>(bestFirstSearch,
							searchEvaluator);
					OurExperimentRunner.execute(bestFirstER, timeout * 1000l);
					score = scoreEvaluator.evaluateSolution(bestFirstER.getBestSolution());
					break;
				case "two_phase":
					ORGraphSearch<TFDNode, String, Double> twoPhaseSearch = new ORGraphSearch<>(graphGenerator,
							timedUncertaintyRandomCompletionEvaluator);
					twoPhaseSearch.setOpen(new UncertaintyExplorationOpenSelection<TFDNode, Double>(timeout * 1000, 50,
							0.1, 0.1, new BasicClockModelPhaseLengthAdjuster(), (solution1, solution2) -> {
								List<Component> components1 = null;
								List<Component> components2 = null;
								ComponentInstance componentInstance1 = null;
								ComponentInstance componentInstance2 = null;
								try {
									if (solution1 != null && !solution1.isEmpty()) {
										componentInstance1 = Util.getSolutionCompositionFromState(
												componentLoader.getComponents(),
												solution1.get(solution1.size() - 1).getState());
										components1 = Util.getComponentsOfComposition(componentInstance1);
										if (solution2 != null && !solution2.isEmpty()) {
											componentInstance2 = Util.getSolutionCompositionFromState(
													componentLoader.getComponents(),
													solution2.get(solution2.size() - 1).getState());
											components2 = Util.getComponentsOfComposition(componentInstance2);
										}
									}
								} catch (Exception e) {
									return Double.MAX_VALUE;
								}
								if (componentInstance1 != null && componentInstance2 != null && components1 != null
										&& components2 != null && !components1.isEmpty() && !components2.isEmpty()) {
									Component model1 = null, model2 = null, pp1 = null, pp2 = null;
									for (Component component : components1) {
										if (component.getProvidedInterfaces().contains("AbstractPreprocessor")) {
											pp1 = component;
										} else if (component.getProvidedInterfaces().contains("AbstractClassifier")) {
											model1 = component;
										}
									}
									for (Component component : components2) {
										if (component.getProvidedInterfaces().contains("AbstractPreprocessor")) {
											pp2 = component;
										} else if (component.getProvidedInterfaces().contains("AbstractClassifier")) {
											model2 = component;
										}
									}
									if (model1 != null && model2 != null && pp1 != null && pp2 != null) {
										boolean sameModel = model1.getName().equals(model2.getName());
										boolean samePP = pp1.getName().equals(pp2.getName());
										if (sameModel && samePP) {
											List<Double> numeric1 = new ArrayList<>();
											List<Double> numeric2 = new ArrayList<>();
											Set<String> categorical1 = new HashSet<>();
											Set<String> categorical2 = new HashSet<>();
											List<Parameter> pm1 = model1.getParameters().getLinearization();
											List<Parameter> pm2 = model2.getParameters().getLinearization();
											List<Parameter> ppp1 = pp1.getParameters().getLinearization();
											List<Parameter> ppp2 = pp2.getParameters().getLinearization();
											Map<String, String> params1 = componentInstance1.getParameterValues();
											Map<String, String> params2 = componentInstance2.getParameterValues();
											if (params1.isEmpty() || params2.isEmpty()) {
												return 1.0d;
											}

											for (Parameter p : pm1) {
												if (p.isNumeric()) {
													String value = params1.get(p.getName());
													try {
														Double d = Double.parseDouble(value);
														numeric1.add((d
																- ((NumericParameterDomain) p.getDefaultDomain())
																		.getMin())
																/ (((NumericParameterDomain) p.getDefaultDomain())
																		.getMax()
																		- ((NumericParameterDomain) p
																				.getDefaultDomain()).getMin()));
													} catch (Exception e) {
														numeric1.add(0.0d);
													}
												} else if (p.isCategorical()) {
													categorical1.add(p.getName() + "=" + params1.get(p.getName()));
												}
											}
											for (Parameter p : ppp1) {
												if (p.isNumeric()) {
													String value = params1.get(p.getName());
													try {
														Double d = Double.parseDouble(value);
														numeric1.add((d
																- ((NumericParameterDomain) p.getDefaultDomain())
																		.getMin())
																/ (((NumericParameterDomain) p.getDefaultDomain())
																		.getMax()
																		- ((NumericParameterDomain) p
																				.getDefaultDomain()).getMin()));
													} catch (Exception e) {
														numeric1.add(0.0d);
													}
												} else if (p.isCategorical()) {
													categorical1.add(p.getName() + "=" + params1.get(p.getName()));
												}
											}
											for (Parameter p : pm2) {
												if (p.isNumeric()) {
													String value = params2.get(p.getName());
													try {
														Double d = Double.parseDouble(value);
														numeric2.add((d
																- ((NumericParameterDomain) p.getDefaultDomain())
																		.getMin())
																/ (((NumericParameterDomain) p.getDefaultDomain())
																		.getMax()
																		- ((NumericParameterDomain) p
																				.getDefaultDomain()).getMin()));
													} catch (Exception e) {
														numeric2.add(0.0d);
													}
												} else if (p.isCategorical()) {
													categorical2.add(p.getName() + "=" + params2.get(p.getName()));
												}
											}
											for (Parameter p : ppp2) {
												if (p.isNumeric()) {
													String value = params2.get(p.getName());
													try {
														Double d = Double.parseDouble(value);
														numeric2.add((d
																- ((NumericParameterDomain) p.getDefaultDomain())
																		.getMin())
																/ (((NumericParameterDomain) p.getDefaultDomain())
																		.getMax()
																		- ((NumericParameterDomain) p
																				.getDefaultDomain()).getMin()));
													} catch (Exception e) {
														numeric2.add(0.0d);
													}
												} else if (p.isCategorical()) {
													categorical2.add(p.getName() + "=" + params2.get(p.getName()));
												}
											}

											Double numericDistance = 0.0d;
											for (int i = 0; i < Math.min(numeric1.size(), numeric2.size()); i++) {
												numericDistance += Math.pow(numeric1.get(i) - numeric2.get(i), 2);
											}
											numericDistance = Math.sqrt(numericDistance);

											Set<String> commonSet = new HashSet<>();
											commonSet.addAll(categorical1);
											commonSet.addAll(categorical2);
											double intersectionSize = 0.0d;
											for (String s : categorical1) {
												if (categorical2.contains(s)) {
													intersectionSize++;
												}
											}
											Double categoricalDistance = (commonSet.size() - intersectionSize)
													/ commonSet.size();

											return numericDistance + categoricalDistance;
										} else if (sameModel && !samePP) {
											return 2;
										} else if (!sameModel && samePP) {
											return 3;
										} else {
											return 5;
										}
									}
								}
								return Double.MAX_VALUE;
							}, new BasicExplorationCandidateSelector<>(1.5d)));

					OurExperimentRunner<TFDNode> twoPhaseER = new OurExperimentRunner<>(twoPhaseSearch,
							searchEvaluator);
					OurExperimentRunner.execute(twoPhaseER, timeout * 1000);
					score = scoreEvaluator.evaluateSolution(twoPhaseER.getBestSolution());
					break;
				case "pareto":
					ORGraphSearch<TFDNode, String, Double> paretoSearch = new ORGraphSearch<>(graphGenerator,
							timedUncertaintyRandomCompletionEvaluator);
					paretoSearch.setOpen(new ParetoSelection<>(
							new PriorityQueue<>(new CosinusDistanceComparator<TFDNode, Double>(1.0, 1.0))));

					OurExperimentRunner<TFDNode> paretoER = new OurExperimentRunner<>(paretoSearch, searchEvaluator);
					OurExperimentRunner.execute(paretoER, timeout * 1000l);
					score = scoreEvaluator.evaluateSolution(paretoER.getBestSolution());
					break;
				case "awa_star":
					AwaStarSearch<TFDNode, String, Double> awaStarSearch;
					try {
						awaStarSearch = new AwaStarSearch<>(graphGenerator, timedRandomCompletionEvaluator);
						OurExperimentRunner<TFDNode> awaER = new OurExperimentRunner<>(awaStarSearch, searchEvaluator);
						OurExperimentRunner.execute(awaER, timeout * 1000);
						score = scoreEvaluator.evaluateSolution(awaER.getBestSolution());
					} catch (Throwable e) {
						e.printStackTrace();
					}
					break;
				case "mcts":
					IPolicy<TFDNode, String, Double> randomPolicy = new UniformRandomPolicy<>(new Random(seed));
					IPathUpdatablePolicy<TFDNode, String, Double> ucb = new UCBPolicy<>(false);
					MCTS<TFDNode, String, Double> mctsSearch = new MCTS<>(graphGenerator, ucb, randomPolicy,
							n -> searchEvaluator.evaluateSolution(Arrays.asList(n.getPoint())));

					OurExperimentRunner<TFDNode> mctsER = new OurExperimentRunner<>(mctsSearch, searchEvaluator);
					OurExperimentRunner.execute(mctsER, timeout * 1000l);
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
