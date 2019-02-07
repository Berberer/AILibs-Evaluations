package saturationpoints;

import java.util.List;

import org.aeonbits.owner.Config.LoadPolicy;
import org.aeonbits.owner.Config.LoadType;
import org.aeonbits.owner.Config.Sources;

import jaicore.experiments.IExperimentSetConfig;

@Sources({ "file:./setup.properties", "file:./database.properties" })
@LoadPolicy(LoadType.MERGE)
public interface ISaturationPointConfig extends IExperimentSetConfig {
	public static final String DATASETS = "datasets";
	public static final String ALGORITHMS = "algorithms";
	public static final String MODELS = "models";

	@Key(ALGORITHMS)
	public List<String> getAlgorithms();

	@Key(MODELS)
	public List<String> getModels();

	@Key(DATASETS)
	public List<String> getDatasets();
}
