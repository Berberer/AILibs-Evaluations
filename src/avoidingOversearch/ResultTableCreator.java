package avoidingOversearch;

import jaicore.basic.SQLAdapter;
import jaicore.basic.StatisticsUtil;
import jaicore.basic.ValueUtil;
import jaicore.basic.chunks.Task;
import jaicore.basic.chunks.TaskChunk;
import jaicore.basic.chunks.TaskChunkUtil;
import jaicore.basic.chunks.TaskKeyComparator;
import jaicore.basic.kvstore.KVStoreUtil;
import org.omg.Messaging.SYNC_WITH_TRANSPORT;

import java.util.HashMap;
import java.util.Map;

public class ResultTableCreator {

    private static final String DBHOST = "isys-db.cs.upb.de";
    private static final String DBUSER = "pgotfml";
    private static final String PASSWORD = "automl2018";
    private static final String DATABASE = "pgotfml_avoiding_oversearch";

    private static final String TABLE = "knapsack_cleaned";
    private static final String ALGORITHM = "two_phase";
    private static final String TIMEOUT = "60";


    static class Problem {

        private Integer problemSize;
        private HashMap<Integer, Double> minPerSeed = new HashMap<>();
        private HashMap<Integer, Double> maxPerSeed = new HashMap<>();

        public Problem(Integer problemSize) {
            this.problemSize = problemSize;
        }

        public void setMax(Integer seed, Double max) {
            maxPerSeed.put(seed, max);
        }

        public void setMin(Integer seed, Double min) {
            minPerSeed.put(seed, min);
        }

        public Double getMax(Integer seed) {
            return maxPerSeed.get(seed);
        }

        public Double getMin(Integer seed) {
            return minPerSeed.get(seed);
        }

        public String toString() {
            int minSeeds = 0;
            for (Integer seed : minPerSeed.keySet()) {
                minSeeds += seed;
            }
            int maxSeeds = 0;
            for (Integer seed : maxPerSeed.keySet()) {
                maxSeeds += seed;
            }
            String s = problemSize + "keySetSum=[" + minSeeds + ", " + maxSeeds + "]";
            return s;
        }

    }

    public static void main(final String[] args) throws Exception {
        HashMap<Integer, Problem> problems = new HashMap<>();
        Map<String, String> commonFields = new HashMap<>();
        TaskChunk<Task> chunk = null;
        System.out.println("Connection");
        {
            try (SQLAdapter adapter = new SQLAdapter(DBHOST, DBUSER, PASSWORD, DATABASE)) {
                chunk = TaskChunkUtil.readFromMySQLTable(adapter, TABLE, commonFields);
            }
        }

        // Filter by timeout, i.e. only keep values where timeout=TIMEOUT
        Map<String, String> filter = new HashMap<>();
        filter.put("timeout", TIMEOUT);
        chunk = chunk.select(filter);

        // Remove unneccesary columns.
        chunk.projectRemove(new String[]{"experiment_id", "timeout", "cpus", "memory_max",
                "time_start", "score_time", "score_memory", "exception", "time_end"});

        // Average scores
        for (Task t : chunk) {
            t.store("score_mean", StatisticsUtil.mean(t.getValueAsDoubleList("score", ",")));
        }
        // We do not need individual scores anymore.
        chunk.projectRemove(new String[]{"score"});

//        System.out.println("###########");
//        System.out.println(chunk);


        // Group by problem size and seed, calculate maximum for each group.
        HashMap<String, TaskChunk.EGroupMethod> groupByMaxScore= new HashMap();
        groupByMaxScore.put("score_mean", TaskChunk.EGroupMethod.MAX);
        TaskChunk<Task> groupedMaxScore = chunk.group(new String[]{"problem_size", "seed"}, groupByMaxScore);

//        System.out.println("###########");
//        System.out.println(groupedMaxScore);
        for (Task t : groupedMaxScore) {
            Integer problemSize = t.getValueAsInt("problem_size");
            Integer seed = t.getValueAsInt("seed");
            Double maxValue = t.getValueAsDouble("score_mean");
            if (problems.containsKey(problemSize)) {
                Problem p = problems.get(problemSize);
                p.setMax(seed, maxValue);
            } else {
                Problem p = new Problem(problemSize);
                p.setMax(seed, maxValue);
                problems.put(problemSize, p);
            }

        }

        // Group by problem size and seed, calculate minimum for each group.
        HashMap<String, TaskChunk.EGroupMethod> groupByMinScore= new HashMap();
        groupByMinScore.put("score_mean", TaskChunk.EGroupMethod.MIN);
        TaskChunk<Task> groupedMinScore = chunk.group(new String[]{"problem_size", "seed"}, groupByMinScore);

//        System.out.println("###########");
//        System.out.println(groupedMinScore);
        int i = 0;
        for (Task t : groupedMinScore) {
            Integer problemSize = t.getValueAsInt("problem_size");
            Integer seed = t.getValueAsInt("seed");
            Double minValue = t.getValueAsDouble("score_mean");
            Problem p = problems.get(problemSize);
            p.setMin(seed, minValue);
        }

        // Normalize averaged scores.
        for (Task t : chunk) {
            // Get max and for for problem size and seed
            Integer problemSize = t.getValueAsInt("problem_size");
            Integer seed = t.getValueAsInt("seed");
            Double max = problems.get(problemSize).getMax(seed);
            Double min = problems.get(problemSize).getMin(seed);
            Double score = t.getValueAsDouble("score_mean");
            Double normalized = (score - min) / (max - min);
            t.store("score_mean_norm", normalized);
        }

        // Group by algorithm and problem size, calculate average of normalized scores
        HashMap<String, TaskChunk.EGroupMethod> groupByAvgNormScore= new HashMap();
        groupByAvgNormScore.put("score_mean_norm", TaskChunk.EGroupMethod.AVG);
        TaskChunk<Task> groupedAvgScore = chunk.group(new String[]{"problem_size", "seed", "algorithm"}, groupByAvgNormScore);


        // Tests
        //groupedAvgScore.tTest("problem_size", "algorithm", "score", "pareto", "ttest");
        //groupedAvgScore.best("problem_size", "algorithm", "score_mean", "best");
        // groupedAvgScore.sort(new TaskKeyComparator(new String[] { "algorithm", "problem_size" }));



        // Latex table
        for (Task t : groupedAvgScore) {
            t.store("entry", ValueUtil.valueToString(t.getValueAsDouble("score_mean_norm"), 2));

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

        String latexTable = KVStoreUtil.kvStoreCollectionToLaTeXTable(groupedAvgScore.toKVStoreCollection(), "algorithm",
                "problem_size", "entry", "-$\\phantom{\\bullet}$");
        System.out.println(latexTable);


    }
}