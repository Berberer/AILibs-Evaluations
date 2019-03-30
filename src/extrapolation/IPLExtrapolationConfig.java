package extrapolation;

import java.io.File;
import java.util.List;

import org.aeonbits.owner.Config.Sources;

import jaicore.experiments.IExperimentSetConfig;

@Sources({ "file:experiment_configuration/ipl_extrapolation.properties" })
public interface IPLExtrapolationConfig extends IExperimentSetConfig {
	public static final String DATASETS = "datasets";
	public static final String SUBSAMPLING_ALGORITHMS = "subsampling_algorithms";
	public static final String EVALUATION_METHODS = "evaluation_methods";
	public static final String TIMEOUTS = "timeouts";
	public static final String SEEDS = "seeds";
	public static final String DATASET_FOLDER = "datasetfolder";

	public static final String SERVICE_HOST = "service.host";
	public static final String SERVICE_PORT = "service.port";

	@Key(DATASETS)
	public List<String> getDatasets();

	@Key(SUBSAMPLING_ALGORITHMS)
	public List<String> getSubsamplingAlgorithms();

	@Key(EVALUATION_METHODS)
	public List<String> getEvaluationMethods();

	@Key(TIMEOUTS)
	public List<String> getTimeouts();

	@Key(SEEDS)
	public List<String> getSeeds();

	@Key(DATASET_FOLDER)
	public File getDatasetFolder();

	@Key(SERVICE_HOST)
	public String getSerivceHost();

	@Key(SERVICE_PORT)
	public String getSerivcePort();
}
