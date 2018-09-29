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

import java.util.*;
import java.util.regex.Matcher;

public class ResultTableCreator {

    private static final String DBHOST = "isys-db.cs.upb.de";
    private static final String DBUSER = "pgotfml";
    private static final String PASSWORD = "automl2018";
    private static final String DATABASE = "pgotfml_avoiding_oversearch";

    private static final String TABLE = "knapsack_cleaned";
    private static final String ALGORITHM = "pareto";  // "pareto" or "two_phase"
    private static final String TIMEOUT = "60"; // "60", "3600", "21600"


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
        HashMap<String, String> remove = new HashMap<>();
        remove.put("algorithm", "pareto_old");
        chunk.removeAny(remove, true);
        remove.put("algorithm", "two_phase_old");
        chunk.removeAny(remove, true);
        if (ALGORITHM.equals("two_phase")) {
            remove.put("algorithm", "pareto");
        } else if (ALGORITHM.equals("pareto")) {
            remove.put("algorithm", "two_phase");
        } else {
            throw new IllegalArgumentException("ALGORITHM constant has to be 'pareto' or 'two_phase'");
        }
        chunk.removeAny(remove, true);

        // Remove unneccesary columns.
        chunk.projectRemove(new String[]{"experiment_id", "timeout", "cpus", "memory_max",
                "time_start", "score_time", "score_memory", "exception", "time_end"});

        // Make scores positiove.

        // TTest
        // Group by problem size.
        TaskChunk<Task> groupedProblemSize = chunk.group(new String[]{"problem_size", "algorithm"}, new HashMap<>());

        System.out.println("###########");
        System.out.println(groupedProblemSize);





        for (Task t : groupedProblemSize) {
            // Make scores positive
            List<Double> scoresNeg = t.getValueAsDoubleList("score", ",");
            String scoresPos = "";
            for (double s : scoresNeg) {
                scoresPos += Double.toString(Math.abs(s)) + ",";
            }
            t.store("score", scoresPos);
        }

        groupedProblemSize.tTest("problem_size", "algorithm", "score", ALGORITHM, "ttest");

        groupedProblemSize.sort(new TaskKeyComparator(new String[] { "algorithm", "problem_size" }));

        System.out.println("#########asd##");
        System.out.println(groupedProblemSize);



        // Average scores
        for (Task t : groupedProblemSize) {
            t.store("score_mean", StatisticsUtil.mean(t.getValueAsDoubleList("score", ",")));
        }

        // groupedProblemSize.best("problem_size", "algorithm", "score_mean", "best");


        groupedProblemSize.projectRemove(new String[]{"score", "seed"});

        System.out.println("###########");
        System.out.println(groupedProblemSize);

        // Maximum mean score per problem size.
        HashMap<String, TaskChunk.EGroupMethod> groupByMax= new HashMap();
        groupByMax.put("score_mean", TaskChunk.EGroupMethod.MAX);
        TaskChunk<Task> maxPerProblemSize = groupedProblemSize.group(new String[]{"problem_size"}, groupByMax);

        System.out.println("######MAX PER PROBLEM SIZE#####");
        System.out.println(maxPerProblemSize);

        // Maximum mean score per problem size.
        HashMap<String, TaskChunk.EGroupMethod> groupByMin= new HashMap();
        groupByMin.put("score_mean", TaskChunk.EGroupMethod.MIN);
        TaskChunk<Task> minPerProblemSize = groupedProblemSize.group(new String[]{"problem_size"}, groupByMin);

        System.out.println("###### MIN PER PROBLEM SIZE #####");
        System.out.println(minPerProblemSize);

        for (Task t: groupedProblemSize) {
            double score = t.getValueAsDouble("score_mean");
            Map<String, String> sizeFilter = new HashMap<>();
            int problemSize = t.getValueAsInt("problem_size");
            sizeFilter.put("problem_size", t.getValueAsString("problem_size"));
            double max = maxPerProblemSize.select(sizeFilter).get(0).getValueAsDouble("score_mean");
            double min = minPerProblemSize.select(sizeFilter).get(0).getValueAsDouble("score_mean");
            System.out.println(problemSize + ", max: " + max + ", min: " + min);
            double normalized;

            if (max - min != 0d) {
                normalized = Math.abs((score - min) / (max - min));
            } else {
                normalized = 1d;
            }
            t.store("score_mean_norm", normalized);
        }

        System.out.println("#####FINAL?######");
        System.out.println(groupedProblemSize);


//        System.exit(1);
//
//
////        chunk.tTest("problem_size", "algorithm", "score", ALGORITHM, "ttest");
////        chunk.best("problem_size", "algorithm", "score_mean", "best");
////        chunk.sort(new TaskKeyComparator(new String[] { "algorithm", "problem_size" }));
//
//
//        // Group by problem size and seed, calculate maximum for each group.
//        HashMap<String, TaskChunk.EGroupMethod> groupByMaxScore= new HashMap();
//        groupByMaxScore.put("score_mean", TaskChunk.EGroupMethod.MAX);
//        TaskChunk<Task> groupedMaxScore = chunk.group(new String[]{"problem_size", "seed"}, groupByMaxScore);
//
//        System.out.println("###########");
//        System.out.println(groupedMaxScore);
//        for (Task t : groupedMaxScore) {
//            Integer problemSize = t.getValueAsInt("problem_size");
//            Integer seed = t.getValueAsInt("seed");
//            Double maxValue = t.getValueAsDouble("score_mean");
//            if (problems.containsKey(problemSize)) {
//                Problem p = problems.get(problemSize);
//                p.setMax(seed, maxValue);
//            } else {
//                Problem p = new Problem(problemSize);
//                p.setMax(seed, maxValue);
//                problems.put(problemSize, p);
//            }
//
//        }
//
//        // Group by problem size and seed, calculate minimum for each group.
//        HashMap<String, TaskChunk.EGroupMethod> groupByMinScore= new HashMap();
//        groupByMinScore.put("score_mean", TaskChunk.EGroupMethod.MIN);
//        TaskChunk<Task> groupedMinScore = chunk.group(new String[]{"problem_size", "seed"}, groupByMinScore);
//
////        System.out.println("###########");
////        System.out.println(groupedMinScore);
//        int i = 0;
//        for (Task t : groupedMinScore) {
//            Integer problemSize = t.getValueAsInt("problem_size");
//            Integer seed = t.getValueAsInt("seed");
//            Double minValue = t.getValueAsDouble("score_mean");
//            Problem p = problems.get(problemSize);
//            p.setMin(seed, minValue);
//        }
//
////        System.out.println("###########");
////        System.out.println(chunk);
//
//        // Normalize averaged scores.
//        for (Task t : chunk) {
//            // Get max and for for problem size and seed
//            Integer problemSize = t.getValueAsInt("problem_size");
//            Integer seed = t.getValueAsInt("seed");
//            Double max = Math.abs(problems.get(problemSize).getMax(seed));
//            Double min = Math.abs(problems.get(problemSize).getMin(seed));
//            Double score = Math.abs(t.getValueAsDouble("score_mean"));
//            Double normalized;
//            if (max - min != 0d) {
//                normalized = Math.abs((score - min) / (max - min));
//            } else {
//                normalized = 1d;
//            }
//            t.store("score_mean_norm", normalized);
//        }
//
////        System.out.println("###########");
////        System.out.println(chunk);
//
//        // Group by algorithm and problem size, calculate average of normalized scores
//        HashMap<String, TaskChunk.EGroupMethod> groupByAvgNormScore= new HashMap();
//        groupByAvgNormScore.put("score_mean_norm", TaskChunk.EGroupMethod.AVG);
//        TaskChunk<Task> groupedAvgScore = chunk.group(new String[]{"problem_size", "seed", "algorithm"}, groupByAvgNormScore);
//
//        System.out.println("###########");
//        System.out.println(groupedAvgScore);
//
//        // Rename problem_size
////        for (Task t : groupedAvgScore) {
////            t.store("problem_size",
////                    "\\multicolumn{1}{c}{\\rotatebox[origin=l]{90}{" + t.getValueAsString("problem_size") + "}}");
////        }
//
//        System.out.println("###########");
//        System.out.println(chunk);







        // Latex table
        for (Task t : groupedProblemSize) {
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

        String latexTable = KVStoreUtil.kvStoreCollectionToLaTeXTable(groupedProblemSize.toKVStoreCollection(), "algorithm",
                "problem_size", "entry", "-$\\phantom{\\bullet}$");
        System.out.println(latexTable);


    }
}