const Mustache = require('mustache');
const fs = require('fs');

let template;

function init() {
  return new Promise((resolve, reject) => {
    fs.readFile(
      'src/PlotCreators/SaturationPoint/SaturationPointFigure.mustache',
      'utf8',
      (err, data) => {
        if (err) {
          reject();
        } else {
          template = data;
          resolve();
        }
      }
    );
  });
}

function createPlot(dataset, model, data, colors) {
  const points = [];
  for (let algorithm of data) {
    if (algorithm.data.length === 1) {
      const d = Number(algorithm.data[0].relativedifference);
      if (d < 1) {
        points.push({
          color: colors[algorithm.algorithm],
          relativedifference: d,
          algorithm: algorithm.algorithm
        });
      }
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
