const mysql = require('mysql');
const fs = require('fs');

function fetchResult(login, queryData) {
  return new Promise((resolve, reject) => {
    fs.readFile(queryData.queryFile, 'utf8', (err, data) => {
      if (err) {
        resolve(err);
      } else {
        let query = data;
        query = query.replace(/ALGORITHM/g, queryData.algorithm);
        query = query.replace(/MODEL/g, queryData.model);
        query = query.replace(/DATASET/g, queryData.dataset);

        const connection = mysql.createConnection({
          host: 'isys-db.cs.upb.de',
          user: login.user,
          password: login.password,
          database: 'pgotfml_subsampling',
          ssl: {
            rejectUnauthorized: false
          }
        });

        connection.connect(err => {
          if (err) {
            console.log('Connection error');
            console.log(err);
            reject(err);
          } else {
            connection.query(query, (error, results) => {
              if (error) {
                console.log('Query error');
                console.log(error);
                reject(error);
              } else {
                let data = results;
                connection.end();

                resolve({
                  data,
                  algorithm: queryData.algorithm
                });
              }
            });
          }
        });
      }
    });
  });
}

module.exports = {
  fetchResult
};
