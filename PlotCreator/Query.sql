SELECT samplesize, AVG(score) as score FROM subsampling_results
WHERE dataset='DATASET' AND model='MODEL' AND algorithm = 'ALGORITHM' AND score <> 'NULL'
GROUP BY samplesize
