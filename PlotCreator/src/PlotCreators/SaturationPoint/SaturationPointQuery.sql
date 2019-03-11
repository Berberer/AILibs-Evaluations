SELECT truesaturationpoint, extrapolatedsaturationpoint, ae, be, ce FROM saturation_point_results
WHERE dataset='DATASET' AND model='MODEL' AND algorithm = 'ALGORITHM' AND extrapolatedsaturationpoint IS NOT NULL
