const Mustache = require('mustache');
const fs = require('fs');
const resultsFetcher = require('../../ResultFetcher.js');

let template;

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
      'src/PlotCreators/SaturationPoint/SaturationPointFigure.mustache',
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

function createPlot(login, model, dataset) {
  let anchorPoints = [];
  return new Promise((resolve, reject) => {
    resultsFetcher
      .fetchResult(login, {
        queryFile: 'src/PlotCreators/Accuracy/AccuracyQuery.sql',
        algorithm: 'SimpleRandom',
        model,
        dataset
      })
      .then(learning => {
        resultsFetcher
          .fetchResult(login, {
            queryFile: 'src/PlotCreators/SaturationPoint/SaturationPointQuery.sql',
            algorithm: 'SimpleRandom',
            model,
            dataset
          })
          .then(saturation => {
            for (let point of learning.data) {
              if (point.samplesize === '8') {
                anchorPoints.push({
                  x: '8',
                  y: point.score
                });
                continue;
              }
              if (point.samplesize === '16') {
                anchorPoints.push({
                  x: '16',
                  y: point.score
                });
                continue;
              }
              if (point.samplesize === '64') {
                anchorPoints.push({
                  x: '64',
                  y: point.score
                });
                continue;
              }
              if (point.samplesize === '128') {
                anchorPoints.push({
                  x: '128',
                  y: point.score
                });
                break;
              }
            }
            if (saturation.data.length === 1) {
              let d = dataset;
              if (dataset === 'eye_movements') {
                d = 'eye\\_movements';
              }
              resolve({
                model,
                dataset: d,
                figure: Mustache.render(template, {
                  datasetSize: datasetSizes[dataset],
                  samples: Math.min(datasetSizes[dataset], 5000),
                  color: 'blue',
                  a: saturation.data[0].ae,
                  b: saturation.data[0].be,
                  c: saturation.data[0].ce,
                  data: learning.data,
                  trueSaturation: saturation.data[0].truesaturationpoint,
                  extrapolatedSaturation: saturation.data[0].extrapolatedsaturationpoint,
                  anchorPoints
                })
              });
            } else {
              reject();
            }
          })
          .catch(err => {
            reject(err);
          });
      })
      .catch(err => {
        reject(err);
      });
  });
}

module.exports = {
  init,
  createPlot
};
