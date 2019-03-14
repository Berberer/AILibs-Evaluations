const Mustache = require('mustache');
const fs = require('fs');

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
let label;
let caption;
let figureColors;

switch (process.argv[4]) {
  case 'accuracy':
    plotCreator = require('./PlotCreators/Accuracy/AccuracyPlotCreator.js');
    label = 'AccuracyResults';
    caption = 'Results for the accuracy measurements in \\textit{Experiment A} ';
    figureColors = colors;
    break;
  case 'saturationPoint':
    plotCreator = require('./PlotCreators/SaturationPoint/SaturationPointPlotCreator.js');
    // 'src/PlotCreators/SaturationPoint/SaturationPointQuery.sql'
    label = 'SaturationPointResults';
    caption =
      'Overview of some learning curve extrapolation results of \\textit{Experiment B} using Class stratified sampling as an example. Displayed are: ' +
      'Anchor-Points for extrapolation(black crosses), ' +
      '\\textcolor{blue}{observed learning curve}, ' +
      'extrapolated learning curve, ' +
      '\\textcolor{lime}{extrapolated saturation point} ' +
      'and, if one was measurable, the \\textcolor{green}{observed saturation point}.';
    figureColors = false;
    break;
  case 'extrapolation':
    plotCreator = require('./PlotCreators/ExtrapolationTable/ExtrapolationTableCreator.js');
    label = 'ExtrapolationTable';
    caption =
      'Tabluar comparison of the saturation point extrapolations for anchor points sampled with the different subsampling algorithms in \\textit{Experiment B}. ' +
      'Showed are the Observed saturation point $S^*$, ' +
      'the extrapolated saturation point $\\widehat{S^*}$, ' +
      'the difference of the two in relation to  the dataset size $\\frac{|S^* - \\widehat{S^*}|}{|D|}$ ' +
      'and the absolute difference $|S^* - \\widehat{S^*}|$. ' +
      'For the missing columns it was not possible to extract anchorpoints with a suitale size ' +
      'or no true saturation point could extrapolated from the observations ';
    figureColors = colors;
    break;
  case 'timeSavings':
    plotCreator = require('./PlotCreators/TimeSavings/TimeSavingsPlotCreator.js');
    label = 'TimeSavingsResults';
    caption = 'TODO';
    figureColors = colors;
    break;
  default:
    if (process.argv[4]) {
      console.log(`Unknown plot type ${process.argv[4]}`);
    } else {
      console.log('No plot type provided');
    }
    process.exit();
}

const models = ['SVM', 'DecisionTree', 'KNN1', 'KNN5'];

const datasets = ['amazon', 'cifar10', 'eye_movements', 'har'];

plotCreator
  .init()
  .then(() => {
    const plotPromises = [];
    for (let dataset of datasets) {
      for (let model of models) {
        plotPromises.push(
          plotCreator.createPlot(
            { user: process.argv[2], password: process.argv[3] },
            model,
            dataset,
            colors
          )
        );
      }
    }

    Promise.all(plotPromises)
      .then(plots => {
        const figures = {};
        for (let plot of plots) {
          figures[`${plot.model}_${plot.dataset}`] = plot.figure;
        }
        fs.readFile('Table.mustache', 'utf8', (err, data) => {
          if (err) {
            console.log('Table template read error');
            console.log(err);
          } else {
            figures.label = label;
            figures.caption = caption;
            figures.colors = figureColors;
            let table = Mustache.render(data, figures);
            table = table.replace(/&#x3D;/g, '=');
            table = table.replace(/&#x2F;/g, '/');
            table = table.replace(/&amp;/g, '&');
            table = table.replace(/&#123;/g, '{');
            console.log(table);
          }
        });
      })
      .catch(error => {
        console.log('Plotting error');
        console.log(error);
      });
  })
  .catch(err => {
    console.log('Mustache init error');
    console.log(err);
  });
