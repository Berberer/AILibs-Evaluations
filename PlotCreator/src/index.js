const resultsFetcher = require('./ResultFetcher.js');
const plotCreator = require('./PlotCreator.js');

const algorithms = [
  'SimpleRandom',
  'Systematic',
  'ClusterGMeans',
  'ClusterKMeans',
  'LLC',
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
            algorithm,
            model,
            dataset
          );
        });

        plotPromises.push(
          new Promise((resolve, reject) => {
            Promise.all(promises)
              .then(data => {
                resolve(plotCreator.createPlot(dataset, model, data));
              })
              .catch(err => {
                reject(err);
              });
          })
        );
      }
    }
    Promise.all(plotPromises).then(plots => {
      for (let plot of plots) {
        console.log(`${plot.model} on ${plot.dataset}:`);
        console.log(plot.figure);
        console.log('\n*****************************************\n');
      }
    });
  })
  .catch(err => {
    console.log('Mustache init error');
    console.log(err);
  });
