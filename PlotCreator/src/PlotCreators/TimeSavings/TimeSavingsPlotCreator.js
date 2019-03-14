const Mustache = require('mustache');
const fs = require('fs');
const resultsFetcher = require('../../ResultFetcher.js');

let template;

const algorithms = [
  'SimpleRandom',
  'Systematic',
  'ClusterGMeans',
  'ClusterKMeans',
  'LCC',
  'OSMAC',
  'GMeansStratified',
  'AttributeStratified',
  'ClassStratified'
];

const maxDifference = 25;

function init() {
  return new Promise((resolve, reject) => {
    fs.readFile(
      'src/PlotCreators/TimeSavings/TimeSavingsFigure.mustache',
      'utf8',
      (err, data) => {
        if (err) {
          reject(err);
        } else {
          template = data;
          resolve();
        }
      }
    );
  });
}

function createPlot(login, model, dataset, colors) {
  return new Promise((resolve, reject) => {
    const dbRequests = [];
    for (let algorithm of algorithms) {
      dbRequests.push(aggregateData(algorithm, model, dataset, login));
    }
    Promise.all(dbRequests)
      .then(results => {
        let d = dataset;
        if (dataset === 'eye_movements') {
          d = 'eye\\_movements';
        }
        resolve({
          model,
          dataset: d,
          figure: createFigure(results, colors)
        });
      })
      .catch(err => {
        reject(err);
      });
  });
}

function aggregateData(algorithm, model, dataset, login) {
  return new Promise((resolve, reject) => {
    resultsFetcher
      .fetchResult(login, {
        queryFile: 'src/PlotCreators/SaturationPoint/SaturationPointQuery.sql',
        algorithm,
        model,
        dataset
      })
      .then(saturationPointResult => {
        if (saturationPointResult.data.length === 1) {
          const sampleSize = saturationPointResult.data[0].truesaturationpoint;
          resultsFetcher
            .fetchResult(login, {
              queryFile: 'src/PlotCreators/TimeSavings/ClosestEntryQuery.sql',
              model,
              dataset,
              algorithm,
              value: sampleSize
            })
            .then(closestEntry => {
              if (
                closestEntry.data.length === 1 &&
                Math.abs(closestEntry.data[0].achievedSampleSize - sampleSize) <=
                  maxDifference
              ) {
                let samplingTime = closestEntry.data[0].samplingTime;
                let partialTrainingTime = closestEntry.data[0].trainingTime;
                resultsFetcher
                  .fetchResult(login, {
                    queryFile: 'src/PlotCreators/TimeSavings/FullTrainingTimeQuery.sql',
                    model,
                    dataset
                  })
                  .then(fullTrainingTimeResult => {
                    if (
                      fullTrainingTimeResult.data.length === 1 &&
                      fullTrainingTimeResult.data[0].fullTrainingTime
                    ) {
                      resolve({
                        algorithm,
                        samplingTime,
                        partialTrainingTime,
                        fullTrainingTime: fullTrainingTimeResult.data[0].fullTrainingTime
                      });
                    } else {
                      resolve();
                    }
                  })
                  .catch(err => {
                    reject(err);
                  });
              } else {
                resolve();
              }
            })
            .catch(err => {
              reject(err);
            });
        } else {
          resolve();
        }
      })
      .catch(err => {
        reject(err);
      });
  });
}

function createFigure(data, colors) {
  const results = {};
  const trainingTimes = [];
  const samplingTimes = [];
  let fullTrainingTime = 0;
  for (let algorithm of data) {
    if (algorithm) {
      results[algorithm.algorithm] = {
        samplingTime: algorithm.samplingTime,
        partialTrainingTime: algorithm.partialTrainingTime
      };
      fullTrainingTime = algorithm.fullTrainingTime;
    }
  }
  for (let algorithm in results) {
    if (Object.prototype.hasOwnProperty.call(results, algorithm)) {
      const times = [];
      for (let a in results) {
        if (Object.prototype.hasOwnProperty.call(results, algorithm)) {
          times.push({
            algorithm: a,
            time: 0
          });
        }
      }
      for (let t of times) {
        if (t.algorithm === algorithm) {
          t.time = results[algorithm].samplingTime;
        }
      }
      times.push({ algorithm: 'Full', time: 0 });
      const samplingTime = {
        color: colors[algorithm],
        times
      };
      const trainingTime = {
        algorithm,
        time: results[algorithm].partialTrainingTime
      };
      samplingTimes.push(samplingTime);
      trainingTimes.push(trainingTime);
    }
  }
  return Mustache.render(template, {
    trainingTimes,
    samplingTimes,
    fullTrainingTime
  });
}

module.exports = {
  init,
  createPlot
};
