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

function init() {
  return new Promise((resolve, reject) => {
    fs.readFile(
      'src/PlotCreators/ExtrapolationTable/ExtrapolationTable.mustache',
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
      dbRequests.push(
        resultsFetcher.fetchResult(login, {
          queryFile: 'src/PlotCreators/ExtrapolationTable/ExtrapolationQuery.sql',
          algorithm,
          model,
          dataset
        })
      );
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
          figure: createTable(results, colors)
        });
      })
      .catch(err => {
        reject(err);
      });
  });
}

function createTable(data, colors) {
  const points = [];
  for (let algorithm of data) {
    if (algorithm.data.length === 1 && Number(algorithm.data[0].relativedifference) < 1) {
      points.push({
        algorithm: algorithm.algorithm,
        color: colors[algorithm.algorithm],
        extrapolatedPoint: algorithm.data[0].extrapolatedsaturationpoint,
        truePoint: algorithm.data[0].truesaturationpoint,
        absoluteDiff: algorithm.data[0].absolutedifference,
        relativeDiff: Number(algorithm.data[0].relativedifference).toFixed(3)
      });
    }
  }
  return Mustache.render(template, {
    points
  });
}

module.exports = {
  init,
  createPlot
};
