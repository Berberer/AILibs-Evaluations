package avoidingOversearch;

import java.util.List;

import jaicore.search.algorithms.standard.AbstractORGraphSearch;
import jaicore.search.core.interfaces.ISolutionEvaluator;
import jaicore.search.model.other.SearchGraphPath;

public class OurExperimentRunner<N> extends Thread {

	private List<N> bestSolution = null;
	private Double costOfBestSolution = null;
	private AbstractORGraphSearch search;
	private ISolutionEvaluator<N, Double> solutionEvaluator;

	private boolean noNextSolution = false;

	public OurExperimentRunner(AbstractORGraphSearch search, ISolutionEvaluator<N, Double> iSolutionEvaluator) {
		this.search = search;
		this.solutionEvaluator = iSolutionEvaluator;
	}

	public static void execute(OurExperimentRunner task, long timeout) {
		task.start();
		try {
			task.join(timeout);
		} catch (InterruptedException e) {
			/* if somebody interrupts us he knows what he is doing */
		}
		if (task.isAlive()) {
			try {
				task.getSearch().cancel();
				task.interrupt();
			} catch (IllegalStateException e) {
				System.err.println("Illegal state catched");
				e.printStackTrace();
			}
		}
	}

	@Override
	public void run() {
		System.exit(0);
		try {
			System.out.println("Running algorithm  ... ");
			System.exit(0);
			while (search.hasNext()) {
				System.out.println("Next solution ...");
				Thread.sleep(1000);
				SearchGraphPath<N, String> currentSolution = search.nextSolution();
				if (currentSolution == null || currentSolution.getNodes() == null || currentSolution.getNodes().isEmpty()) {
					noNextSolution = true;
					break;
				}
				if (bestSolution == null) {
					bestSolution = currentSolution.getNodes();
				} else {
					double costOfCurrentSolution = solutionEvaluator.evaluateSolution(currentSolution.getNodes());
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

	public AbstractORGraphSearch getSearch() {
		return this.search;
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
