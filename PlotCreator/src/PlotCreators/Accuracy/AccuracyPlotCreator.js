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

/* eslint camelcase: ["error", {allow: ["eye_movements"]}] */
const datasetSizes = {
  har: 10299,
  eye_movements: 10936,
  amazon: 1500,
  cifar10: 60000
};

function init() {
  return new Promise((resolve, reject) => {
    fs.readFile(
      'src/PlotCreators/Accuracy/AccuracyFigure.mustache',
      'utf8',
      (err1, templateData) => {
        if (err1) {
          reject(err1);
        } else {
          template = templateData;
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
          queryFile: 'src/PlotCreators/Accuracy/AccuracyQuery.sql',
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
          figure: createFigure(results, model, dataset, colors)
        });
      })
      .catch(err => {
        reject(err);
      });
  });
}

function createFigure(data, model, dataset, colors) {
  const points = [];
  for (let algorithm of data) {
    if (algorithm.data.length > 1) {
      let algorithmResults = [];
      for (let row of algorithm.data) {
        const score = row.score;
        let samplesize;
        if (row.samplesize === '100p') {
          samplesize = datasetSizes[dataset];
        } else if (row.samplesize.includes('p')) {
          samplesize = row.samplesize.replace(/p/g, '');
          samplesize = Math.floor(datasetSizes[dataset] * Number('0.' + samplesize));
        } else {
          samplesize = Number(row.samplesize);
        }
        algorithmResults.push({
          samplesize: samplesize / datasetSizes[dataset],
          score
        });
      }

      points.push({
        algorithm: algorithm.algorithm,
        color: colors[algorithm.algorithm],
        data: algorithmResults
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
