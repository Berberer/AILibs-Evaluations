package saturationpoints;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.math3.fitting.WeightedObservedPoints;

import jaicore.basic.SQLAdapter;
import jaicore.ml.interfaces.AnalyticalLearningCurve;
import jaicore.ml.interfaces.LearningCurve;
import jaicore.ml.learningcurve.extrapolation.LearningCurveExtrapolationMethod;
import jaicore.ml.learningcurve.extrapolation.ipl.InversePowerLawExtrapolationMethod;

public class SaturationPointTester {

	public static void main(String[] args) throws Exception {

		// Setup
		String algorithm = "SimpleRandom";
		String model = "KNN1";
		String dataset = "har";
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
				+ "FROM subsampling_results_with_sample_sizes WHERE dataset = '" + dataset + "' AND " + "model = '"
				+ model + "' AND algorithm = '" + algorithm + "' AND score IS NOT NULL "
				+ "GROUP BY achievedSampleSize ORDER BY CAST(achievedSampleSize AS unsigned) ASC";
		SQLAdapter adapter = new SQLAdapter("isys-db.cs.upb.de", "<USER>", "<PASSWORD>", "pgotfml_subsampling", true);
		ResultSet rs = adapter.getResultsOfQuery(query);
		Map<Double, Double> points = new HashMap<Double, Double>();
		WeightedObservedPoints weightedObservedPoints = new WeightedObservedPoints();
		while (rs.next()) {
			double x = rs.getDouble("X");
			double y = rs.getDouble("Y");
			System.out.println(x + ", " + y);
			points.put(x, y);
			weightedObservedPoints.add(x, y);
		}

		// Fit curve to values
		LearningCurve fittedCurve = CurveFitter.fitLearningCurve(weightedObservedPoints);
		System.out.println("TRUE CURVE: " + fittedCurve);

		// Extrapolate saturation point
		LearningCurveExtrapolationMethod extrapolationMethod = new InversePowerLawExtrapolationMethod();
		int[] selectedAnchorpointsX = new int[] { 8, 16, 64, 128 };
		double[] selectedAnchorpointsY = new double[] { points.get(8.0d), points.get(16.0d), points.get(64.0d),
				points.get(128.0d) };
		LearningCurve extrapolatedCurve = extrapolationMethod
				.extrapolateLearningCurveFromAnchorPoints(selectedAnchorpointsX, selectedAnchorpointsY, datasetSize);
		System.out.println("EXTRAPOLATED CURVE: " + extrapolatedCurve);

		// Print results
		int trueSaturationPoint = (int) ((AnalyticalLearningCurve)fittedCurve).getSaturationPoint(epsilon);
		int extrapolatedSaturationPoint = (int) ((AnalyticalLearningCurve)extrapolatedCurve).getSaturationPoint(epsilon);
		int absoluteDifference = Math.abs(trueSaturationPoint - extrapolatedSaturationPoint);
		BigDecimal relativeDifference = new BigDecimal(((double) absoluteDifference) / ((double) datasetSize));

		System.out.println("TRUE SATURATION POINT: " + trueSaturationPoint);
		System.out.println("EXTRAPOLATED SATURATION POINT: " + extrapolatedSaturationPoint);
		System.out.println("ABSOLUTE DIFFERENCE: " + absoluteDifference);
		System.out.println("RELATIVE DIFFERENCE: " + relativeDifference.toPlainString());
		adapter.close();
	}
}
