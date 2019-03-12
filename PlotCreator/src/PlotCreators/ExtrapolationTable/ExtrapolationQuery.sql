SELECT truesaturationpoint, extrapolatedsaturationpoint, relativedifference, absolutedifference FROM saturation_point_results
WHERE dataset='DATASET' AND model='MODEL' AND algorithm = 'ALGORITHM' AND extrapolatedsaturationpoint IS NOT NULL
