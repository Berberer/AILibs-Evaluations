const Mustache = require('mustache');
const fs = require('fs');

let template;

function init() {
  return new Promise((resolve, reject) => {
    fs.readFile('Figure.mustache', 'utf8', (err, data) => {
      if (err) {
        reject();
      } else {
        template = data;
        resolve();
      }
    });
  });
}

/* eslint camelcase: ["error", {allow: ["eye_movements"]}] */
const datasetSizes = {
  har: 10299,
  eye_movements: 10936,
  amazon: 1500,
  cifar10: 60000
};

const colors = {
  SimpleRandom: 'red',
  Systematic: 'orange',
  ClusterGMeans: 'brown',
  ClusterKMeans: 'lightgray',
  LLC: 'lime',
  OSMAC: 'green',
  GMeansStratified: 'pink',
  ClassStratified: 'blue',
  AttributeStratified: 'cyan'
};

function createPlot(dataset, model, data) {
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
          samplesize,
          score
        });
      }

      algorithmResults = algorithmResults.sort((a, b) => {
        if (a.samplesize < b.samplesize) {
          return -1;
        }
        if (a.samplesize > b.samplesize) {
          return 1;
        }
        return 0;
      });
      points.push({
        algorithm: algorithm.algorithm,
        color: colors[algorithm.algorithm],
        data: algorithmResults
      });
    }
  }
  if (dataset === 'eye_movements') {
    dataset = 'eye\\_movements';
  }
  return {
    dataset,
    model,
    figure: Mustache.render(template, {
      title: `${model} on ${dataset}`,
      points
    })
  };
}

module.exports = {
  init,
  createPlot
};
