SELECT achievedSampleSize AS samplesize, AVG(score) AS score FROM subsampling_results_with_sample_sizes
WHERE dataset='DATASET' AND model='MODEL' AND algorithm = 'ALGORITHM' AND score IS NOT NULL
GROUP BY achievedSampleSize
ORDER BY CAST(achievedSampleSize AS unsigned) ASC
