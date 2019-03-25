package extrapolation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.aeonbits.owner.ConfigCache;

import de.upb.crc901.mlplan.core.MLPlan;
import de.upb.crc901.mlplan.core.MLPlanBuilder;
import jaicore.basic.SQLAdapter;
import jaicore.basic.TimeOut;
import jaicore.experiments.ExperimentDBEntry;
import jaicore.experiments.ExperimentRunner;
import jaicore.experiments.IExperimentIntermediateResultProcessor;
import jaicore.experiments.IExperimentSetConfig;
import jaicore.experiments.IExperimentSetEvaluator;
import jaicore.ml.WekaUtil;
import jaicore.ml.core.dataset.IInstance;
import jaicore.ml.core.dataset.sampling.inmemory.ASamplingAlgorithm;
import jaicore.ml.core.dataset.sampling.inmemory.factories.GmeansSamplingFactory;
import jaicore.ml.core.dataset.sampling.inmemory.factories.LocalCaseControlSamplingFactory;
import jaicore.ml.core.dataset.sampling.inmemory.factories.OSMACSamplingFactory;
import jaicore.ml.core.dataset.sampling.inmemory.factories.SimpleRandomSamplingFactory;
import jaicore.ml.core.dataset.sampling.inmemory.factories.StratifiedSamplingFactory;
import jaicore.ml.core.dataset.sampling.inmemory.factories.SystematicSamplingFactory;
import jaicore.ml.core.dataset.sampling.inmemory.factories.interfaces.ISamplingAlgorithmFactory;
import jaicore.ml.core.dataset.sampling.inmemory.stratified.sampling.AttributeBasedStratiAmountSelectorAndAssigner;
import jaicore.ml.core.dataset.sampling.inmemory.stratified.sampling.DiscretizationHelper.DiscretizationStrategy;
import jaicore.ml.learningcurve.extrapolation.LearningCurveExtrapolationMethod;
import jaicore.ml.learningcurve.extrapolation.ipl.InversePowerLawExtrapolationMethod;
import jaicore.ml.learningcurve.extrapolation.lc.LinearCombinationExtrapolationMethod;
import weka.classifiers.Classifier;
import weka.classifiers.evaluation.Evaluation;
import weka.core.Instances;

public class ExtrapolationExperimenter {

	private static final double[] ANCHORPOINTS_FEW = { 0.001, 0.005, 0.01, 0.02, 0.04 };

	private static final double[] ANCHORPOINTS_STANDARD = { 0.001, 0.005, 0.01, 0.02, 0.04, 0.08, 0.1, 0.15, 0.2 };

	private static final double[] ANCHORPOINTS_MANY = { 0.001, 0.005, 0.01, 0.02, 0.04, 0.08, 0.1, 0.15, 0.2, 0.25, 0.3,
			0.4, 0.5 };

	private static final String EVALUATION_METHOD_SATURATION_POINT = "extrapolated_saturation_point";

	private static final String EVALUATION_METHOD_LEARNING_CURVE = "learning_curve_extrapolation";

	public static void main(String[] args) {
		IExtrapolationConfig config = ConfigCache.getOrCreate(IExtrapolationConfig.class);

		ExperimentRunner runner = new ExperimentRunner(new IExperimentSetEvaluator() {

			@Override
			public IExperimentSetConfig getConfig() {
				return config;
			}

			@Override
			public void evaluate(ExperimentDBEntry experimentEntry, SQLAdapter adapter,
					IExperimentIntermediateResultProcessor processor) throws Exception {

				// Get experiment setup
				Map<String, String> description = experimentEntry.getExperiment().getValuesOfKeyFields();
				String dataset = description.get("dataset");
				String subsamplingAlgorithm = description.get("subsampling_algorithm");
				String extrapolationAlgorithm = description.get("extrapolation_algorithm");
				String evaluationMethod = description.get("evaluation_method");
				String anchorPoint = description.get("anchorpoint");
				int timeout = Integer.valueOf(description.get("timeout"));
				long seed = Long.valueOf(description.get("seed"));

				Instances data = new Instances(new BufferedReader(
						new FileReader(new File(config.getDatasetFolder() + File.separator + dataset + ".arff"))));
				data.setClassIndex(data.numAttributes() - 1);
				List<Instances> splits = WekaUtil.getStratifiedSplit(data, seed, 0.8);

				MLPlanBuilder builder = new MLPlanBuilder();
				builder.withAutoWEKAConfiguration();
				builder.withRandomCompletionBasedBestFirstSearch();
				builder.withTimeoutForNodeEvaluation(new TimeOut(1000, TimeUnit.SECONDS));
				builder.withTimeoutForSingleSolutionEvaluation(new TimeOut(1000, TimeUnit.SECONDS));

				ISamplingAlgorithmFactory<IInstance, ? extends ASamplingAlgorithm<IInstance>> subsamplingAlgorithmFactory;

				switch (subsamplingAlgorithm) {
				case "SimpleRandom":
					subsamplingAlgorithmFactory = new SimpleRandomSamplingFactory<>();
					break;
				case "Systematic":
					subsamplingAlgorithmFactory = new SystematicSamplingFactory<>();
					break;
				case "LCC":
					subsamplingAlgorithmFactory = new LocalCaseControlSamplingFactory<>();
					break;
				case "OSMAC":
					subsamplingAlgorithmFactory = new OSMACSamplingFactory<>();
					break;
				case "GMeansStratified":
					subsamplingAlgorithmFactory = new GmeansSamplingFactory<>();
					break;
				case "AttributeStratified":
					List<Integer> attributeIndices = new ArrayList<>();
					attributeIndices.add(data.classIndex());

					AttributeBasedStratiAmountSelectorAndAssigner<IInstance> selectorAndAssigner = new AttributeBasedStratiAmountSelectorAndAssigner<>(
							attributeIndices, DiscretizationStrategy.EQUAL_SIZE, 10);

					subsamplingAlgorithmFactory = new StratifiedSamplingFactory<>(selectorAndAssigner,
							selectorAndAssigner);
					break;
				default:
					throw new RuntimeException(String.format("Invalid subsampling algorith %s", subsamplingAlgorithm));
				}

				LearningCurveExtrapolationMethod extrapolationMethod;

				switch (extrapolationAlgorithm) {
				case "lc":
					extrapolationMethod = new LinearCombinationExtrapolationMethod(config.getLcSerivceHost(),
							config.getLcSerivcePort());
					break;
				case "ipl":
					extrapolationMethod = new InversePowerLawExtrapolationMethod(config.getIplSerivceHost(),
							config.getIplSerivcePort());
					break;
				default:
					throw new RuntimeException(
							String.format("Invalid extrapolation algorith %s", extrapolationAlgorithm));
				}

				int[] anchorpoints;
				switch (anchorPoint) {
				case "few":
					anchorpoints = generateDatasetDependentAnchorpoints(data.size(), ANCHORPOINTS_FEW);
					break;
				case "standard":
					anchorpoints = generateDatasetDependentAnchorpoints(data.size(), ANCHORPOINTS_STANDARD);
					break;
				case "many":
					anchorpoints = generateDatasetDependentAnchorpoints(data.size(), ANCHORPOINTS_MANY);
					break;
				default:
					throw new RuntimeException(String.format("Invalid anchorpoints parameter %s", anchorPoint));
				}

				if (evaluationMethod.equals(EVALUATION_METHOD_LEARNING_CURVE)) {
					builder.withLearningCurveExtrapolationEvaluation(anchorpoints, subsamplingAlgorithmFactory, 0.7,
							extrapolationMethod);
				} else if (evaluationMethod.equals(EVALUATION_METHOD_SATURATION_POINT)) {
					builder.withExtrapolatedSaturationPointEvaluation(anchorpoints, subsamplingAlgorithmFactory, 0.7,
							extrapolationMethod);
				}
				MLPlan mlplan = new MLPlan(builder, splits.get(0));
				mlplan.setPortionOfDataForPhase2(0.3f);
				mlplan.setLoggerName("mlplan");
				mlplan.setTimeout(timeout, TimeUnit.SECONDS);
				mlplan.setNumCPUs(experimentEntry.getExperiment().getNumCPUs());

				Classifier optimizedClassifier = mlplan.call();
				Evaluation eval = new Evaluation(splits.get(0));
				eval.evaluateModel(optimizedClassifier, splits.get(1));

				Map<String, Object> results = new HashMap<>();

				double accuracy = eval.pctCorrect();
				int numberOfSolution = mlplan.getNumberOfSolutions();

				results.put("accuracy", accuracy);
				results.put("solution_count", numberOfSolution);
				processor.processResults(results);
			}
		});
		runner.randomlyConductExperiments(true);
	}

	private static int[] generateDatasetDependentAnchorpoints(int datasetSize, double[] relativeSizes) {
		int[] toReturn = new int[relativeSizes.length];
		for (int i = 0; i < relativeSizes.length; i++) {
			toReturn[i] = (int) (relativeSizes[i] * datasetSize);
		}
		return toReturn;
	}

}
