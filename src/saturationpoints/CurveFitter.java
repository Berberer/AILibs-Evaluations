package saturationpoints;

import java.util.Collection;

import org.apache.commons.math3.analysis.ParametricUnivariateFunction;
import org.apache.commons.math3.fitting.AbstractCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoint;
import org.apache.commons.math3.fitting.WeightedObservedPoints;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem;
import org.apache.commons.math3.linear.DiagonalMatrix;

import jaicore.ml.interfaces.LearningCurve;
import jaicore.ml.learningcurve.extrapolation.InversePowerLaw.InversePowerLawLearningCurve;

public class CurveFitter extends AbstractCurveFitter{

	ParametricUnivariateFunction function;

	public CurveFitter() {
		function = new ParametricUnivariateFunction() {

			@Override
			public double value(double t, double... parameters) {
				double a = parameters[0];
				double b = parameters[1];
				double c = parameters[2];
				return (1.0d - a) - b * Math.pow(t, c);
			}

			@Override
			public double[] gradient(double t, double... parameters) {
				double b = parameters[1];
				double c = parameters[2];
				return new double[] { -1.0d, -Math.pow(t, c), -(b * Math.pow(t, c) * Math.log(t)) };
			}
		};
	}

	public static LearningCurve fitLearningCurve(WeightedObservedPoints points) {
		CurveFitter fitter = new CurveFitter();
		double parameters[] = fitter.fit(points.toList());
		return new InversePowerLawLearningCurve(parameters[0], parameters[1], parameters[2]);
	}

	@Override
	protected LeastSquaresProblem getProblem(Collection<WeightedObservedPoint> points) {
		final int len = points.size();
        final double[] target  = new double[len];
        final double[] weights = new double[len];
        final double[] initialGuess = { 1.0, 1.0, 1.0 };

        int i = 0;
        for(WeightedObservedPoint point : points) {
            target[i]  = point.getY();
            weights[i] = point.getWeight();
            i += 1;
        }
        
        final AbstractCurveFitter.TheoreticalValuesFunction model = new
            AbstractCurveFitter.TheoreticalValuesFunction(this.function, points);

        return new LeastSquaresBuilder().
            maxEvaluations(Integer.MAX_VALUE).
            maxIterations(Integer.MAX_VALUE).
            start(initialGuess).
            target(target).
            weight(new DiagonalMatrix(weights)).
            model(model.getModelFunction(), model.getModelFunctionJacobian()).
            build();
	}

}
