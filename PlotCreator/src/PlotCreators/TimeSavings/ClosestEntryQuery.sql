SELECT AVG(results.achievedSampleSize) AS achievedSampleSize, AVG(results.samplingTime) AS samplingTime, AVG(results.trainingTime) AS trainingTime FROM
  (
    SELECT * FROM subsampling_results_with_sample_sizes
    WHERE dataset='DATASET' AND model='MODEL' AND algorithm = 'ALGORITHM' AND score IS NOT NULL
    ORDER BY ABS( achievedSampleSize - VALUE )
    LIMIT 5
  ) results
