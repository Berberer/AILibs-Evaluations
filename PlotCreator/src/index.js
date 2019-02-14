const Mustache = require('mustache');
const fs = require('fs');
const resultsFetcher = require('./ResultFetcher.js');

const colors = {
  SimpleRandom: 'red',
  Systematic: 'orange',
  ClusterGMeans: 'brown',
  ClusterKMeans: 'lightgray',
  LCC: 'lime',
  OSMAC: 'green',
  GMeansStratified: 'pink',
  ClassStratified: 'blue',
  AttributeStratified: 'cyan'
};

let plotCreator;
let queryFile;
let label;
let caption;

switch (process.argv[4]) {
  case 'accuracy':
    plotCreator = require('./PlotCreators/Accuracy/AccuracyPlotCreator.js');
    queryFile = 'src/PlotCreators/Accuracy/AccuracyQuery.sql';
    label = 'AccuracyResults';
    caption = 'Results for the accuracy measurements in \\textit{Experiment A}';
    break;
  default:
    if (process.argv[4]) {
      console.log(`Unknown plot type ${process.argv[4]}`);
    } else {
      console.log('No plot type provided');
    }
    process.exit();
}

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

const models = ['SVM', 'DecisionTree', 'KNN1', 'KNN5'];

const datasets = ['amazon', 'cifar10', 'eye_movements', 'har'];

plotCreator
  .init()
  .then(() => {
    const plotPromises = [];
    for (let dataset of datasets) {
      for (let model of models) {
        const promises = algorithms.map(algorithm => {
          return resultsFetcher.fetchResult(
            { user: process.argv[2], password: process.argv[3] },
            {
              queryFile,
              algorithm,
              model,
              dataset
            }
          );
        });

        plotPromises.push(
          new Promise((resolve, reject) => {
            Promise.all(promises)
              .then(data => {
                resolve(plotCreator.createPlot(dataset, model, data, colors));
              })
              .catch(err => {
                reject(err);
              });
          })
        );
      }
    }
    Promise.all(plotPromises).then(plots => {
      const figures = {};
      for (let plot of plots) {
        figures[`${plot.model}_${plot.dataset}`] = plot.figure;
      }
      fs.readFile('Table.mustache', 'utf8', (err, data) => {
        if (err) {
          console.log('Table template read error');
          console.log(err);
        } else {
          figures.lable = label;
          figures.caption = caption;
          figures.colors = colors;
          let table = Mustache.render(data, figures);
          table = table.replace(/&#x3D;/g, '=');
          table = table.replace(/&#x2F;/g, '/');
          console.log(table);
        }
      });
    });
  })
  .catch(err => {
    console.log('Mustache init error');
    console.log(err);
  });
