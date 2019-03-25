package extrapolation;

import java.io.File;
import java.util.List;

import org.aeonbits.owner.Config.Sources;

import jaicore.experiments.IExperimentSetConfig;

@Sources({ "file:experiment_configuration/extrapolation.properties" })
public interface IExtrapolationConfig extends IExperimentSetConfig {
	public static final String DATASETS = "datasets";
	public static final String SUBSAMPLING_ALGORITHMS = "subsampling_algorithms";
	public static final String EXTRAPOLATION_ALGORITHMS = "extrapolation_algorithms";
	public static final String EVALUATION_METHODS = "evaluation_methods";
	public static final String ANCHORPOINTS = "anchorpoints";
	public static final String TIMEOUTS = "timeouts";
	public static final String SEEDS = "seeds";
	public static final String DATASET_FOLDER = "datasetfolder";

	public static final String LC_SERVICE_HOST = "lc.service.host";
	public static final String LC_SERVICE_PORT = "lc.service.port";
	public static final String IPL_SERVICE_HOST = "ilp.service.host";
	public static final String IPL_SERVICE_PORT = "ilp.service.port";

	@Key(DATASETS)
	public List<String> getDatasets();

	@Key(SUBSAMPLING_ALGORITHMS)
	public List<String> getSubsamplingAlgorithms();

	@Key(EXTRAPOLATION_ALGORITHMS)
	public List<String> getExtrapolationAlgorithms();

	@Key(EVALUATION_METHODS)
	public List<String> getEvaluationMethods();

	@Key(ANCHORPOINTS)
	public List<String> getAnchorpoints();

	@Key(TIMEOUTS)
	public List<String> getTimeouts();

	@Key(SEEDS)
	public List<String> getSeeds();

	@Key(DATASET_FOLDER)
	public File getDatasetFolder();

	@Key(LC_SERVICE_HOST)
	public String getLcSerivceHost();

	@Key(LC_SERVICE_PORT)
	public String getLcSerivcePort();

	@Key(IPL_SERVICE_HOST)
	public String getIplSerivceHost();

	@Key(IPL_SERVICE_PORT)
	public String getIplSerivcePort();
}
