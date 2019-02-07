package subsampling;

import java.io.File;
import java.util.List;

import org.aeonbits.owner.Config.LoadPolicy;
import org.aeonbits.owner.Config.LoadType;
import org.aeonbits.owner.Config.Sources;

import jaicore.experiments.IExperimentSetConfig;

@Sources({ "file:./setup.properties", "file:./database.properties"})
@LoadPolicy(LoadType.MERGE)
public interface ISubsamplingConfig extends IExperimentSetConfig {
	public static final String DATASETS = "datasets";
	public static final String SAMPLESIZES = "samplesizes";
	public static final String ALGORITHMS = "algorithms";
	public static final String MODELS = "models";
	public static final String SEEDS = "seeds";
	public static final String datasetFolder = "datasetfolder";
	
	@Key(ALGORITHMS)
	public List<String> getAlgorithms();
	
	@Key(MODELS)
	public List<String> getModels();
	
	@Key(SEEDS)
	public List<String> getSeeds();
	
	@Key(DATASETS)
	public List<String> getDatasets();
	
	@Key(SAMPLESIZES)
	public List<String> getSampleSizes();
	
	@Key(datasetFolder)
	public File getDatasetFolder();
}
