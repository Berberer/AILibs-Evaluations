SELECT avg(trainingTime) AS fullTrainingTime FROM subsampling_results_with_sample_sizes
WHERE dataset='DATASET' AND model='MODEL' AND samplesize='100p' AND score IS NOT NULL
