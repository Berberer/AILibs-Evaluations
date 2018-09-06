package avoidingOversearch;

import java.util.List;

import jaicore.search.algorithms.standard.AbstractORGraphSearch;
import jaicore.search.model.other.EvaluatedSearchGraphPath;

public class OurExperimentRunner<N> extends Thread {

	private List<N> bestSolution = null;
	private Double costOfBestSolution = null;
	private AbstractORGraphSearch search;

	private boolean noNextSolution = false;

	public OurExperimentRunner(AbstractORGraphSearch search) {
		this.search = search;
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
			while (!isInterrupted()) {
				EvaluatedSearchGraphPath<N, String, Double> currentSolution = search.nextSolution();
				if (currentSolution == null || currentSolution.getNodes() == null || currentSolution.getNodes().isEmpty()) {
					noNextSolution = true;
					break;
				}
				if (bestSolution == null) {
					bestSolution = currentSolution.getNodes();
				} else {
					double costOfCurrentSolution = currentSolution.getScore();
					if (costOfBestSolution == null || costOfCurrentSolution < costOfBestSolution) {
						bestSolution = currentSolution.getNodes();
						costOfBestSolution = costOfCurrentSolution;
					}
				}
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	public List<N> getBestSolution() {
		return bestSolution;
	}

	public Double getCostOfBestSolution() {
		return costOfBestSolution;
	}

	public boolean isNoNextSolution() {
		return noNextSolution;
	}
}
