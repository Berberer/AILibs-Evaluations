package saturationpoints;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import org.aeonbits.owner.ConfigCache;
import org.apache.commons.math3.fitting.WeightedObservedPoints;

import jaicore.basic.SQLAdapter;
import jaicore.experiments.ExperimentDBEntry;
import jaicore.experiments.ExperimentRunner;
import jaicore.experiments.IExperimentIntermediateResultProcessor;
import jaicore.experiments.IExperimentSetConfig;
import jaicore.experiments.IExperimentSetEvaluator;
import jaicore.ml.interfaces.AnalyticalLearningCurve;
import jaicore.ml.interfaces.LearningCurve;
import jaicore.ml.learningcurve.extrapolation.LearningCurveExtrapolationMethod;
import jaicore.ml.learningcurve.extrapolation.ipl.InversePowerLawExtrapolationMethod;
import jaicore.ml.learningcurve.extrapolation.ipl.InversePowerLawLearningCurve;
import subsampling.ISubsamplingConfig;

public class SaturationPointExperimenter {

	public static void main(String[] args) {
		ISubsamplingConfig m = ConfigCache.getOrCreate(ISubsamplingConfig.class);

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
				String dataset = description.get("dataset");
				String model = description.get("model");
				String algorithm = description.get("algorithm");
				double epsilon = 0.00025d;
				int datasetSize = 0;
				switch (dataset) {
				case "har":
					datasetSize = 10299;
					break;
				case "eye_movements":
					datasetSize = 10936;
					break;
				case "amazon":
					datasetSize = 1500;
					break;
				case "cifar10":
					datasetSize = 60000;
					break;
				}

				// Get measured points for the given dataset/algorithm/model combination
				String query = "SELECT achievedSampleSize AS X, AVG(score) AS Y "
						+ "FROM subsampling_results_with_sample_sizes WHERE dataset = '" + dataset + "' AND "
						+ "model = '" + model + "' AND algorithm = '" + algorithm + "' AND score IS NOT NULL "
						+ "GROUP BY achievedSampleSize ORDER BY CAST(achievedSampleSize AS unsigned) ASC";
				ResultSet rs = adapter.getResultsOfQuery(query);
				Map<Double, Double> points = new HashMap<Double, Double>();
				WeightedObservedPoints weightedObservedPoints = new WeightedObservedPoints();
				while (rs.next()) {
					double x = rs.getDouble("X");
					double y = rs.getDouble("Y");
					points.put(x, y);
					weightedObservedPoints.add(x, y);
				}

				// Fit curve to values
				LearningCurve fittedCurve = CurveFitter.fitLearningCurve(weightedObservedPoints);

				// Extrapolate saturation point
				LearningCurveExtrapolationMethod extrapolationMethod = new InversePowerLawExtrapolationMethod();
				int[] selectedAnchorpointsX = new int[] { 8, 16, 64, 128 };
				double[] selectedAnchorpointsY = new double[] { points.get(8.0d), points.get(16.0d), points.get(64.0d),
						points.get(128.0d) };
				LearningCurve extrapolatedCurve = extrapolationMethod.extrapolateLearningCurveFromAnchorPoints(
						selectedAnchorpointsX, selectedAnchorpointsY, datasetSize);

				// Submit results
				int trueSaturationPoint = (int) ((AnalyticalLearningCurve) fittedCurve).getSaturationPoint(epsilon);
				int extrapolatedSaturationPoint = (int) ((AnalyticalLearningCurve) extrapolatedCurve)
						.getSaturationPoint(epsilon);
				int absoluteDifference = Math.abs(trueSaturationPoint - extrapolatedSaturationPoint);
				BigDecimal relativeDifference = new BigDecimal(((double) absoluteDifference) / ((double) datasetSize));

				Map<String, Object> results = new HashMap<>();
				results.put("truesaturationpoint", trueSaturationPoint);
				results.put("extrapolatedsaturationpoint", extrapolatedSaturationPoint);
				results.put("absolutedifference", absoluteDifference);
				results.put("relativedifference", relativeDifference.toPlainString());
				results.put("at", ((InversePowerLawLearningCurve)fittedCurve).getA());
				results.put("ae", ((InversePowerLawLearningCurve)extrapolatedCurve).getA());
				results.put("bt", ((InversePowerLawLearningCurve)fittedCurve).getB());
				results.put("be", ((InversePowerLawLearningCurve)extrapolatedCurve).getB());
				results.put("ct", ((InversePowerLawLearningCurve)fittedCurve).getC());
				results.put("ce", ((InversePowerLawLearningCurve)extrapolatedCurve).getC());
				processor.processResults(results);
			}
		});
		runner.randomlyConductExperiments(true);
	}

}
