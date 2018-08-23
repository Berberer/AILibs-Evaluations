package avoidingOversearch.tsp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jaicore.basic.ListHelper;
import jaicore.basic.SQLAdapter;
import jaicore.basic.StatisticsUtil;
import jaicore.basic.ValueUtil;
import jaicore.basic.chunks.Task;
import jaicore.basic.chunks.TaskChunk;
import jaicore.basic.chunks.TaskChunkUtil;
import jaicore.basic.chunks.TaskKeyComparator;
import jaicore.basic.kvstore.KVStoreUtil;

public class ResultTableCollector {

	public static void main(final String[] args) throws Exception {
		Map<String, String> commonFields = new HashMap<>();
		TaskChunk<Task> cMLPlan = null;
		{
			try (SQLAdapter adapter = new SQLAdapter("isys-db.cs.upb.de", "<Username>", "<Password>",
					"pgotfml_avoiding_oversearch")) {
				cMLPlan = TaskChunkUtil.readFromMySQLTable(adapter, "tsp_results", commonFields);
				Map<String, String> filter = new HashMap<>();
				filter.put("timeout", "60");
				cMLPlan = cMLPlan.select(filter);
				cMLPlan.projectRemove(new String[] { "experiment_id", "timeout", "seed", "cpus", "memory_max",
						"time_start", "score_time", "score_memory", "exception", "time_end" });
				cMLPlan = cMLPlan.group(new String[] { "algorithm", "problem_size" }, new HashMap<>());
			}
		}
		System.out.println(cMLPlan);
		TaskChunk<Task> csvChunks = new TaskChunk<>("chunkID=baselines");
		csvChunks.addAll(cMLPlan);

		List<String> problem_sizes = new LinkedList<>();

		/* Collect all the datasets for which we already have results */
		Set<String> problemSizesOfEval = new HashSet<>();
		for (Task t : cMLPlan) {
			problemSizesOfEval.add(t.getValueAsString("problem_size"));
		}

		for (Task t : csvChunks) {
			List<Double> testErrorRates = t.getValueAsDoubleList("score", ",");
			if (testErrorRates.size() < 2) {
				testErrorRates.add(testErrorRates.get(0));
			}
			t.store("score", ListHelper.implode(testErrorRates, ","));
			t.store("score_mean", StatisticsUtil.mean(t.getValueAsDoubleList("score", ",")) * 100);

			if (!problem_sizes.contains(t.getValueAsString("problem_size"))) {
				problem_sizes.add(t.getValueAsString("problem_size"));
			}

			t.store("problem_size",
					"\\multicolumn{1}{c}{\\rotatebox[origin=l]{90}{" + t.getValueAsString("problem_size") + "}}");
		}

		csvChunks.tTest("problem_size", "algorithm", "score", "two_phase", "ttest");
		csvChunks.best("problem_size", "algorithm", "score_mean", "best");
		csvChunks.sort(new TaskKeyComparator(new String[] { "algorithm", "problem_size" }));

		for (Task t : csvChunks) {
			t.store("entry", ValueUtil.valueToString(t.getValueAsDouble("score_mean"), 2));

			if (t.getValueAsBoolean("best")) {
				t.store("entry", "\\textbf{" + t.getValueAsString("entry") + "}");
			}

			if (t.containsKey("ttest")) {
				switch (t.getValueAsString("ttest")) {
				case "impr":
					t.store("entry", t.getValueAsString("entry") + " $\\bullet$");
					break;
				case "deg":
					t.store("entry", t.getValueAsString("entry") + " $\\circ$");
					break;
				default:
					t.store("entry", t.getValueAsString("entry") + " $\\phantom{\\bullet}$");
					break;

				}
			} else {
				t.store("entry", t.getValueAsString("entry") + " $\\phantom{\\bullet}$");
			}
		}
		String latexTable = KVStoreUtil.kvStoreCollectionToLaTeXTable(csvChunks.toKVStoreCollection(), "algorithm",
				"problem_size", "entry", "-$\\phantom{\\bullet}$");
		System.out.println(latexTable);

	}
}
