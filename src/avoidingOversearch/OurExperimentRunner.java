package avoidingOversearch;

import java.util.List;

import jaicore.search.algorithms.interfaces.IORGraphSearch;
import jaicore.search.algorithms.interfaces.ISolutionEvaluator;

/**
 * Experiment runner for ORGraphSearches.
 * 
 * @param <T>
 */
public class OurExperimentRunner<T> extends Thread {

	private List<T> bestSolution = null;
	private Double costOfBestSolution = null;

	private IORGraphSearch<T, String, Double> search;
	private ISolutionEvaluator<T, Double> solutionEvaluator;

	private boolean noNextSolution = false;

	public OurExperimentRunner(IORGraphSearch<T, String, Double> search, ISolutionEvaluator<T, Double> solutionEvaluator) {
		this.search = search;
		this.solutionEvaluator = solutionEvaluator;
	}

	public static void execute(Thread task, long timeout) {
		task.start();
		try {
			task.join(timeout);
		} catch (InterruptedException e) {
			/* if somebody interrupts us he knows what he is doing */
		}
		if (task.isAlive()) {
			try {

				task.interrupt();
			} catch (IllegalStateException e) {
				System.err.println("Illegal state catched");
				e.printStackTrace();
			}
		}
	}

	@Override
	public void run() {
		try {
			// if (search instanceof IObservableORGraphSearch)
			// new SimpleGraphVisualizationWindow<>((IObservableORGraphSearch) search);
			while (!isInterrupted()) {
				List<T> currentSolution = search.nextSolution();

				if (currentSolution == null) {
					noNextSolution = true;
					break;
				}

				// System.out.println("Next solution");
				if (bestSolution == null) {
					bestSolution = currentSolution;
				} else {
					double costOfCurrentSolution = solutionEvaluator.evaluateSolution(currentSolution);
					if (costOfBestSolution == null || costOfCurrentSolution < costOfBestSolution) {
						bestSolution = currentSolution;
						costOfBestSolution = costOfCurrentSolution;
					}
				}
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	public List<T> getBestSolution() {
		return bestSolution;
	}

	public double getCostOfBestSolution() {
		return costOfBestSolution;
	}

	public boolean isNoNextSolution() {
		return noNextSolution;
	}
}
