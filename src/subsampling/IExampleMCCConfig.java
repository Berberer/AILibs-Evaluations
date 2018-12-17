package subsampling;

import java.io.File;
import java.util.List;

import org.aeonbits.owner.Config.Sources;

import jaicore.experiments.IExperimentSetConfig;

/**
 * In fact, this config is an exact copy of IMultiClassClassificationExperimentConfig,
 * which cannot be used here to avoid cyclic dependencies.
 * Typically, in this case, you just would extend IMultiClassClassificationExperimentConfig
 * and leave the interface (mostly) empty except that you define the URL for the file.
 * 
 * 
 * @author fmohr
 *
 */
@Sources({ "file:./setup.properties" })
public interface IExampleMCCConfig extends IExperimentSetConfig {
	public static final String DATASETS = "datasets";
	public static final String SAMPLESIZES = "samplesizes";
	public static final String ALGORITHMS = "algorithms";
	public static final String MODELS = "MODELS";
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
